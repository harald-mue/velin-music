package com.haraldmue.velin.data.cache

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.Track
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CachedLibrarySnapshot(
    val summary: LibrarySummary,
    val activatedAtMs: Long,
)

enum class LibrarySyncSection {
    ARTISTS,
    ALBUMS,
    TRACKS,
    VERIFYING,
}

sealed interface LibraryCacheSyncState {
    data object Idle : LibraryCacheSyncState

    data class Syncing(
        val section: LibrarySyncSection,
        val downloadedItems: Int,
    ) : LibraryCacheSyncState

    data class Ready(val snapshot: CachedLibrarySnapshot) : LibraryCacheSyncState

    data class KeptPrevious(val reason: String) : LibraryCacheSyncState

    data class Failed(val message: String) : LibraryCacheSyncState
}

sealed interface SnapshotSyncResult {
    data class Activated(val snapshot: CachedLibrarySnapshot) : SnapshotSyncResult
    data class KeptPrevious(val reason: String) : SnapshotSyncResult
    data class Failed(val cause: Throwable) : SnapshotSyncResult
}

class LibraryCacheRepository(
    private val database: LibraryCacheDatabase,
    private val gateway: LibraryGateway,
    credentials: DeviceCredentials,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val generationId: () -> String = { UUID.randomUUID().toString() },
) {
    val namespace: String = CacheNamespace.from(credentials)

    private val dao = database.libraryCacheDao()
    private val syncMutex = Mutex()
    private val mutableSyncState = MutableStateFlow<LibraryCacheSyncState>(LibraryCacheSyncState.Idle)

    val syncState: StateFlow<LibraryCacheSyncState> = mutableSyncState.asStateFlow()

    val activeSnapshot: Flow<CachedLibrarySnapshot?> = dao.observeState(namespace).map { state ->
        val summary = state?.summaryOrNull() ?: return@map null
        CachedLibrarySnapshot(summary, requireNotNull(state.activatedAtMs))
    }

    val artists: Flow<PagingData<Artist>> = Pager(PagingConfig(pageSize = DatabasePageSize)) {
        dao.artistsPagingSource(namespace)
    }.flow.map { pagingData -> pagingData.map(CachedArtistEntity::toModel) }

    val albums: Flow<PagingData<Album>> = Pager(PagingConfig(pageSize = DatabasePageSize)) {
        dao.albumsPagingSource(namespace)
    }.flow.map { pagingData -> pagingData.map(CachedAlbumEntity::toModel) }

    val tracks: Flow<PagingData<Track>> = Pager(PagingConfig(pageSize = DatabasePageSize)) {
        dao.tracksPagingSource(namespace)
    }.flow.map { pagingData -> pagingData.map(CachedTrackEntity::toModel) }

    fun homeAlbums(limit: Int = DefaultHomeAlbumCount): Flow<List<Album>> =
        dao.observeHomeAlbums(namespace, limit.coerceAtLeast(1))
            .map { rows -> rows.map(CachedAlbumEntity::toModel) }

    fun albumTracks(albumId: String): Flow<List<Track>> =
        dao.observeAlbumTracks(namespace, albumId)
            .map { rows -> rows.map(CachedTrackEntity::toModel) }

    fun artistTracks(artistId: String): Flow<List<Track>> =
        dao.observeArtistTracks(namespace, artistId)
            .map { rows -> rows.map(CachedTrackEntity::toModel) }

    suspend fun album(albumId: String): Album? =
        dao.activeAlbum(namespace, albumId)?.toModel()

    suspend fun artist(artistId: String): Artist? =
        dao.activeArtist(namespace, artistId)?.toModel()

    suspend fun clear() {
        syncMutex.withLock {
            database.withTransaction {
                dao.deleteAllArtists(namespace)
                dao.deleteAllAlbums(namespace)
                dao.deleteAllTracks(namespace)
                dao.deleteState(namespace)
            }
            mutableSyncState.value = LibraryCacheSyncState.Idle
        }
    }

    suspend fun sync(): SnapshotSyncResult = syncMutex.withLock {
        val generation = generationId()
        try {
            val before = retryTransientRequest { gateway.summary() }
            val current = dao.state(namespace)
            if (
                current?.activeGeneration != null &&
                current.activeRevision == before.revision &&
                current.artistCount == before.artistCount &&
                current.albumCount == before.albumCount &&
                current.trackCount == before.trackCount &&
                current.activatedAtMs != null
            ) {
                cleanupOldGenerations()
                val snapshot = CachedLibrarySnapshot(before, current.activatedAtMs)
                mutableSyncState.value = LibraryCacheSyncState.Ready(snapshot)
                return@withLock SnapshotSyncResult.Activated(snapshot)
            }
            cleanupOldGenerations()

            val artistsDownloaded = downloadAll(
                section = LibrarySyncSection.ARTISTS,
                load = { cursor -> gateway.loadArtistsPage(cursor, NetworkPageSize) },
                insert = { items, start ->
                    dao.insertArtists(items.mapIndexed { index, item ->
                        item.toCacheEntity(namespace, generation, start + index)
                    })
                },
            )
            val albumsDownloaded = downloadAll(
                section = LibrarySyncSection.ALBUMS,
                load = { cursor -> gateway.loadAlbumsPage(cursor, NetworkPageSize) },
                insert = { items, start ->
                    dao.insertAlbums(items.mapIndexed { index, item ->
                        item.toCacheEntity(namespace, generation, start + index)
                    })
                },
            )
            val tracksDownloaded = downloadAll(
                section = LibrarySyncSection.TRACKS,
                load = { cursor -> gateway.loadTracksPage(cursor = cursor, limit = NetworkPageSize) },
                insert = { items, start ->
                    dao.insertTracks(items.mapIndexed { index, item ->
                        item.toCacheEntity(namespace, generation, start + index)
                    })
                },
            )

            val downloaded = artistsDownloaded + albumsDownloaded + tracksDownloaded
            mutableSyncState.value = LibraryCacheSyncState.Syncing(
                LibrarySyncSection.VERIFYING,
                downloaded,
            )
            val after = retryTransientRequest { gateway.summary() }
            if (before.revision != after.revision) {
                deleteGeneration(generation)
                return@withLock keptPrevious("The library changed while it was being downloaded.")
            }
            if (
                artistsDownloaded != before.artistCount ||
                albumsDownloaded != before.albumCount ||
                tracksDownloaded != before.trackCount
            ) {
                deleteGeneration(generation)
                return@withLock keptPrevious(
                    "The downloaded library did not match the server summary.",
                )
            }

            val activatedAtMs = clockMs()
            database.withTransaction {
                dao.upsertState(
                    CacheStateEntity(
                        namespace = namespace,
                        activeGeneration = generation,
                        activeRevision = after.revision,
                        artistCount = after.artistCount,
                        albumCount = after.albumCount,
                        trackCount = after.trackCount,
                        activatedAtMs = activatedAtMs,
                    ),
                )
                deleteOtherGenerations(generation)
            }
            val snapshot = CachedLibrarySnapshot(after, activatedAtMs)
            mutableSyncState.value = LibraryCacheSyncState.Ready(snapshot)
            SnapshotSyncResult.Activated(snapshot)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { deleteGeneration(generation) }
            throw cancelled
        } catch (error: Throwable) {
            withContext(NonCancellable) { deleteGeneration(generation) }
            mutableSyncState.value = LibraryCacheSyncState.Failed(
                error.message ?: "Library sync failed.",
            )
            SnapshotSyncResult.Failed(error)
        }
    }

    private suspend fun <T> downloadAll(
        section: LibrarySyncSection,
        load: suspend (String?) -> Page<T>,
        insert: suspend (List<T>, Int) -> Unit,
    ): Int {
        var cursor: String? = null
        var downloaded = 0
        val seenCursors = mutableSetOf<String>()
        do {
            mutableSyncState.value = LibraryCacheSyncState.Syncing(section, downloaded)
            val page = retryTransientRequest { load(cursor) }
            if (page.items.isNotEmpty()) {
                insert(page.items, downloaded)
            }
            downloaded += page.items.size
            cursor = if (page.hasMore) {
                page.nextCursor
                    ?.takeIf(String::isNotBlank)
                    ?.takeIf(seenCursors::add)
                    ?: error("The server returned invalid library pagination.")
            } else {
                null
            }
        } while (cursor != null)
        return downloaded
    }

    private suspend fun <T> retryTransientRequest(block: suspend () -> T): T {
        var retryDelayMs = InitialRetryDelayMs
        repeat(MaxRequestAttempts - 1) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (!error.retryable) throw error
            }
            delay(retryDelayMs)
            retryDelayMs *= 2
        }
        return block()
    }

    private suspend fun cleanupOldGenerations() {
        val active = dao.state(namespace)?.activeGeneration
        database.withTransaction {
            if (active == null) {
                dao.deleteAllArtists(namespace)
                dao.deleteAllAlbums(namespace)
                dao.deleteAllTracks(namespace)
            } else {
                deleteOtherGenerations(active)
            }
        }
    }

    private suspend fun deleteGeneration(generation: String) {
        database.withTransaction {
            dao.deleteArtistGeneration(namespace, generation)
            dao.deleteAlbumGeneration(namespace, generation)
            dao.deleteTrackGeneration(namespace, generation)
        }
    }

    private suspend fun deleteOtherGenerations(generation: String) {
        dao.deleteOtherArtistGenerations(namespace, generation)
        dao.deleteOtherAlbumGenerations(namespace, generation)
        dao.deleteOtherTrackGenerations(namespace, generation)
    }

    private fun keptPrevious(reason: String): SnapshotSyncResult.KeptPrevious {
        mutableSyncState.value = LibraryCacheSyncState.KeptPrevious(reason)
        return SnapshotSyncResult.KeptPrevious(reason)
    }

    private companion object {
        const val NetworkPageSize = 200
        const val DatabasePageSize = 50
        const val DefaultHomeAlbumCount = 20
        const val MaxRequestAttempts = 3
        const val InitialRetryDelayMs = 250L
    }
}
