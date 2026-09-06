package com.haraldmue.velin.data.cache

import androidx.room.Room
import androidx.paging.PagingSource
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCacheRepositoryTest {
    private lateinit var database: LibraryCacheDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ordersHomeSectionsAndAlbumPlaybackDeterministically() = runTest {
        val gateway = FakeLibraryGateway(
            artists = listOf(
                Artist("artist-2", "Second", 1, 1),
                Artist("artist-1", "First", 1, 1),
            ),
            albums = listOf(
                album("album-3", addedAtMs = 100),
                album("album-1", addedAtMs = 300),
                album("album-2", addedAtMs = 200),
            ),
            tracks = listOf(
                track("track-3", "album-1", trackNumber = 2),
                track("other", "album-2"),
                track("track-1", "album-1", trackNumber = 1),
            ),
        )
        val repository = repository(
            gateway,
            "https://one.example",
            "device",
            "generation-1",
            discoveryStartID = "album-2",
        )

        assertTrue(repository.sync() is SnapshotSyncResult.Activated)
        assertEquals(
            listOf("album-1", "album-2", "album-3"),
            repository.recentlyAddedAlbums().first().map(Album::id),
        )
        assertEquals(
            listOf("album-2", "album-3", "album-1"),
            repository.discoveryAlbums().first().map(Album::id),
        )
        assertEquals(
            listOf("track-1", "track-3"),
            repository.albumTracks("album-1").first().map(Track::id),
        )
        val artistPage = database.libraryCacheDao().artistsPagingSource(repository.namespace).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        )
        assertEquals(
            listOf("artist-2", "artist-1"),
            (artistPage as PagingSource.LoadResult.Page<Int, CachedArtistEntity>)
                .data.map(CachedArtistEntity::id),
        )
    }

    @Test
    fun matchingRevisionDoesNotDownloadSnapshotAgain() = runTest {
        val gateway = FakeLibraryGateway(albums = listOf(album("album")))
        val repository = repository(
            gateway,
            "https://one.example",
            "device",
            "generation-1",
        )

        assertTrue(repository.sync() is SnapshotSyncResult.Activated)
        val pageLoadsAfterFirstSync = gateway.pageLoads
        assertTrue(repository.sync() is SnapshotSyncResult.Activated)

        assertEquals(pageLoadsAfterFirstSync, gateway.pageLoads)
    }

    @Test
    fun transientPageFailureIsRetried() = runTest {
        val gateway = FakeLibraryGateway(albums = listOf(album("album")))
        gateway.transientAlbumFailures = 1
        val repository = repository(
            gateway,
            "https://one.example",
            "device",
            "generation-1",
        )

        assertTrue(repository.sync() is SnapshotSyncResult.Activated)
        assertEquals("album", repository.album("album")?.id)
    }

    @Test
    fun transientSummaryFailureIsRetried() = runTest {
        val gateway = FakeLibraryGateway(albums = listOf(album("album")))
        gateway.transientSummaryFailures = 1
        val repository = repository(
            gateway,
            "https://one.example",
            "device",
            "generation-1",
        )

        assertTrue(repository.sync() is SnapshotSyncResult.Activated)
        assertEquals("album", repository.album("album")?.id)
    }

    @Test
    fun isolatesNamespacesInOneDatabase() = runTest {
        val first = repository(
            FakeLibraryGateway(albums = listOf(album("shared", "First"))),
            "https://one.example",
            "device",
            "generation-a",
        )
        val second = repository(
            FakeLibraryGateway(albums = listOf(album("shared", "Second"))),
            "https://two.example",
            "device",
            "generation-b",
        )

        first.sync()
        second.sync()

        assertEquals("First", first.album("shared")?.title)
        assertEquals("Second", second.album("shared")?.title)
    }

    @Test
    fun clearDeletesOnlyCurrentNamespace() = runTest {
        val first = repository(
            FakeLibraryGateway(albums = listOf(album("first"))),
            "https://one.example",
            "device",
            "generation-a",
        )
        val second = repository(
            FakeLibraryGateway(albums = listOf(album("second"))),
            "https://two.example",
            "device",
            "generation-b",
        )
        first.sync()
        second.sync()

        first.clear()

        assertNull(first.activeSnapshot.first())
        assertNull(first.album("first"))
        assertEquals("second", second.album("second")?.id)
    }

    @Test
    fun revisionChurnKeepsPreviouslyActiveGeneration() = runTest {
        val gateway = FakeLibraryGateway(albums = listOf(album("old", "Old")))
        val generations = ArrayDeque(listOf("generation-1", "generation-2"))
        val repository = LibraryCacheRepository(
            database,
            gateway,
            credentials("https://one.example", "device"),
            clockMs = { 123L },
            generationId = { generations.removeFirst() },
        )
        assertTrue(repository.sync() is SnapshotSyncResult.Activated)

        gateway.albums = listOf(album("new", "New"))
        gateway.summaryRevisions = ArrayDeque(listOf("revision-2", "revision-3"))
        assertTrue(repository.sync() is SnapshotSyncResult.KeptPrevious)

        assertEquals("Old", repository.album("old")?.title)
        assertNull(repository.album("new"))
        assertEquals("revision-1", repository.activeSnapshot.first()?.summary?.revision)
    }

    @Test
    fun downloadFailureKeepsPreviouslyActiveGeneration() = runTest {
        val gateway = FakeLibraryGateway(albums = listOf(album("old", "Old")))
        val generations = ArrayDeque(listOf("generation-1", "generation-2"))
        val repository = LibraryCacheRepository(
            database,
            gateway,
            credentials("https://one.example", "device"),
            generationId = { generations.removeFirst() },
        )
        repository.sync()

        gateway.albums = listOf(album("new", "New"))
        gateway.summaryRevisions = ArrayDeque(listOf("revision-2"))
        gateway.failTracks = true
        assertTrue(repository.sync() is SnapshotSyncResult.Failed)

        assertEquals("Old", repository.album("old")?.title)
        assertNull(repository.album("new"))
    }

    private fun repository(
        gateway: FakeLibraryGateway,
        server: String,
        device: String,
        generation: String,
        discoveryStartID: String = "",
    ) = LibraryCacheRepository(
        database,
        gateway,
        credentials(server, device),
        generationId = { generation },
        discoveryStartID = discoveryStartID,
    )
}

private class FakeLibraryGateway(
    var albums: List<Album> = emptyList(),
    private val artists: List<Artist> = emptyList(),
    private val tracks: List<Track> = emptyList(),
) : LibraryGateway {
    var summaryRevisions = ArrayDeque(listOf("revision-1"))
    var failTracks = false
    var transientAlbumFailures = 0
    var transientSummaryFailures = 0
    var pageLoads = 0

    override suspend fun status() = ServerStatus("Test", "ok", "1")

    override suspend fun summary(): LibrarySummary {
        if (transientSummaryFailures > 0) {
            transientSummaryFailures--
            throw ApiException("temporary", retryable = true)
        }
        val revision = if (summaryRevisions.size > 1) {
            summaryRevisions.removeFirst()
        } else {
            summaryRevisions.first()
        }
        return LibrarySummary(artists.size, albums.size, tracks.size, revision)
    }

    override suspend fun loadArtistsPage(cursor: String?, limit: Int): Page<Artist> {
        pageLoads++
        return page(artists, cursor)
    }

    override suspend fun loadAlbumsPage(cursor: String?, limit: Int): Page<Album> {
        pageLoads++
        if (transientAlbumFailures > 0) {
            transientAlbumFailures--
            throw ApiException("temporary", retryable = true)
        }
        return page(albums, cursor)
    }

    override suspend fun loadTracksPage(
        cursor: String?,
        artistId: String?,
        albumId: String?,
        limit: Int,
    ): Page<Track> {
        pageLoads++
        if (failTracks) error("track download failed")
        return page(tracks, cursor)
    }

    override suspend fun loadArtist(artistId: String): Artist = error("unused")
    override suspend fun loadAlbum(albumId: String): Album = error("unused")
    override suspend fun loadTrack(trackId: String): TrackDetail = error("unused")
    override suspend fun loadAlbumTracks(albumId: String): List<Track> = error("unused")
    override suspend fun loadArtistTracks(artistId: String): List<Track> = error("unused")
    override suspend fun search(query: String, cursor: String?, limit: Int): Page<Track> =
        error("unused")

    private fun <T> page(items: List<T>, cursor: String?): Page<T> {
        val start = cursor?.toInt() ?: 0
        val end = (start + 2).coerceAtMost(items.size)
        return Page(
            items = items.subList(start, end),
            nextCursor = end.takeIf { it < items.size }?.toString(),
            hasMore = end < items.size,
        )
    }
}

private fun credentials(server: String, device: String) = DeviceCredentials(
    serverUrl = server,
    deviceId = device,
    token = "secret",
    serverName = "Test",
    serverVersion = "1",
)

private fun album(id: String, title: String = id, addedAtMs: Long? = null) =
    Album(id, title, "Artist", 2026, null, 1, addedAtMs)

private fun track(id: String, albumId: String, trackNumber: Int? = null) = Track(
    id = id,
    title = id,
    format = "flac",
    artistName = "Artist",
    albumTitle = albumId,
    durationMs = 1_000,
    trackNumber = trackNumber,
    albumId = albumId,
)
