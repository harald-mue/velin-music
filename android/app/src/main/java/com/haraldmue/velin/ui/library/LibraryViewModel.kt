package com.haraldmue.velin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.haraldmue.velin.data.AccumulatedPage
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import com.haraldmue.velin.data.cache.LibraryCacheRepository
import com.haraldmue.velin.data.cache.SnapshotSyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val status: ServerStatus? = null,
    val statusError: Boolean = false,
    val summary: LibrarySummary? = null,
    val hasActiveSnapshot: Boolean = false,
    val recentlyAddedAlbums: List<Album> = emptyList(),
    val discoveryAlbums: List<Album> = emptyList(),
    val error: String? = null,
    val authenticationFailed: Boolean = false,
    val searchQuery: String = "",
    val searchLoading: Boolean = false,
    val searchResults: com.haraldmue.velin.data.AccumulatedPage<Track> =
        com.haraldmue.velin.data.AccumulatedPage(),
    val searchError: String? = null,
    val selectedAlbum: Album? = null,
    val albumTracks: List<Track> = emptyList(),
    val albumLoading: Boolean = false,
    val albumError: String? = null,
    val selectedArtist: Artist? = null,
    val artistTracks: List<Track> = emptyList(),
    val artistLoading: Boolean = false,
    val artistError: String? = null,
    val selectedTrackId: String? = null,
    val trackDetail: TrackDetail? = null,
    val trackLoading: Boolean = false,
    val trackError: String? = null,
)

class LibraryViewModel(
    private val gateway: LibraryGateway,
    private val repository: LibraryCacheRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    val albums: Flow<PagingData<Album>> = repository.albums.cachedIn(viewModelScope)
    val artists: Flow<PagingData<Artist>> = repository.artists.cachedIn(viewModelScope)
    val tracks: Flow<PagingData<Track>> = repository.tracks.cachedIn(viewModelScope)

    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private var albumJob: Job? = null
    private var artistJob: Job? = null
    private var trackJob: Job? = null
    private val albumTrackCache = LinkedHashMap<String, List<Track>>(16, 0.75f, true)
    private val artistCache = LinkedHashMap<String, ArtistCacheEntry>(8, 0.75f, true)
    private val trackDetailCache = object : LinkedHashMap<String, TrackDetail>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackDetail>?): Boolean =
            size > MaxCachedTrackDetails
    }

    init {
        viewModelScope.launch {
            repository.activeSnapshot.collectLatest { snapshot ->
                mutableState.update {
                    it.copy(
                        summary = snapshot?.summary,
                        hasActiveSnapshot = snapshot != null,
                        loading = it.loading && snapshot == null,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.recentlyAddedAlbums(HomeAlbumItems).collectLatest { albums ->
                mutableState.update { it.copy(recentlyAddedAlbums = albums) }
            }
        }
        viewModelScope.launch {
            repository.discoveryAlbums(HomeDiscoveryItems).collectLatest { albums ->
                mutableState.update { it.copy(discoveryAlbums = albums) }
            }
        }
        viewModelScope.launch {
            val initialSnapshot = repository.activeSnapshot.first()
            if (initialSnapshot != null) {
                mutableState.update {
                    it.copy(
                        summary = initialSnapshot.summary,
                        hasActiveSnapshot = true,
                        loading = false,
                    )
                }
            }
            refreshInternal(bootstrapWhenEmpty = initialSnapshot == null)
        }
    }

    fun refresh() {
        refreshInternal(bootstrapWhenEmpty = mutableState.value.summary == null)
    }

    private fun refreshInternal(bootstrapWhenEmpty: Boolean) {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch {
            val previousRevision = mutableState.value.summary?.revision
            mutableState.update {
                it.copy(
                    loading = true,
                    status = null,
                    statusError = false,
                    error = null,
                    authenticationFailed = false,
                )
            }
            try {
                val (statusResult, syncResult) = supervisorScope {
                    val statusRequest = async { gateway.status() }
                    val bootstrapRequest = if (bootstrapWhenEmpty) {
                        launch {
                            runCatching {
                                coroutineScope {
                                    val summaryRequest = async { gateway.summary() }
                                    val albumsRequest = async { gateway.loadAlbumsPage(limit = HomeAlbumItems) }
                                    summaryRequest.await() to albumsRequest.await().items
                                }
                            }.onSuccess { (summary, albums) ->
                                if (generation == refreshGeneration) {
                                    mutableState.update {
                                        it.copy(
                                            summary = summary,
                                            discoveryAlbums = albums,
                                            loading = false,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    }
                    val status = runCatching { statusRequest.await() }
                    status.onSuccess { value ->
                        if (generation == refreshGeneration) {
                            mutableState.update { it.copy(status = value, statusError = false) }
                        }
                    }.onFailure {
                        if (generation == refreshGeneration) {
                            mutableState.update { it.copy(statusError = true) }
                        }
                    }
                    bootstrapRequest?.join()
                    status to repository.sync()
                }
                when (syncResult) {
                    is SnapshotSyncResult.Activated -> {
                        if (previousRevision != syncResult.snapshot.summary.revision) {
                            searchJob?.cancel()
                            mutableState.update {
                                it.copy(
                                    searchQuery = "",
                                    searchResults = AccumulatedPage(),
                                    searchError = null,
                                )
                            }
                        }
                        clearDetailCaches()
                        mutableState.update {
                            it.copy(
                                summary = syncResult.snapshot.summary,
                                hasActiveSnapshot = true,
                                error = statusResult.exceptionOrNull()?.displayMessage(),
                            )
                        }
                    }
                    is SnapshotSyncResult.KeptPrevious -> mutableState.update {
                        it.copy(error = syncResult.reason)
                    }
                    is SnapshotSyncResult.Failed -> mutableState.update {
                        val apiError = syncResult.cause as? ApiException
                        it.copy(
                            error = syncResult.cause.displayMessage(),
                            authenticationFailed = apiError?.authenticationFailed == true,
                        )
                    }
                }
                statusResult.exceptionOrNull()?.let { statusError ->
                    val apiError = statusError as? ApiException
                    if (apiError?.authenticationFailed == true) {
                        mutableState.update {
                            it.copy(authenticationFailed = true)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (generation == refreshGeneration) {
                    mutableState.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun cancelForCredentialChange() {
        refreshJob?.cancel()
        searchJob?.cancel()
        albumJob?.cancel()
        artistJob?.cancel()
        trackJob?.cancel()
    }

    private fun Throwable.displayMessage(): String = when (this) {
        is ApiException -> message ?: "Could not refresh the library."
        else -> "Could not refresh the library."
    }

    fun openAlbum(album: Album) {
        closeArtist()
        closeTrack()
        albumJob?.cancel()
        val cachedTracks = albumTrackCache[album.id]
        mutableState.value = mutableState.value.copy(
            selectedAlbum = album,
            albumTracks = cachedTracks.orEmpty(),
            albumLoading = cachedTracks == null,
            albumError = null,
        )
        if (cachedTracks != null) return
        albumJob = viewModelScope.launch {
            try {
                val roomTracks = try {
                    if (repository.album(album.id) != null) {
                        repository.albumTracks(album.id).first()
                    } else {
                        null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                val tracks = roomTracks ?: gateway.loadAlbumTracks(album.id)
                putTrackList(albumTrackCache, album.id, tracks, MaxCachedAlbums)
                mutableState.update {
                    if (it.selectedAlbum?.id != album.id) it else it.copy(
                        albumTracks = tracks,
                        albumLoading = false,
                    )
                }
            } catch (error: IllegalArgumentException) {
                mutableState.update {
                    if (it.selectedAlbum?.id != album.id) it else it.copy(
                        albumLoading = false,
                        albumError = error.message ?: "Invalid album.",
                    )
                }
            } catch (error: ApiException) {
                mutableState.update {
                    if (it.selectedAlbum?.id != album.id) it else it.copy(
                        albumLoading = false,
                        albumError = error.message ?: "Could not load the album.",
                        authenticationFailed = error.authenticationFailed,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    if (it.selectedAlbum?.id != album.id) it else it.copy(
                        albumLoading = false,
                        albumError = "Could not load the album.",
                    )
                }
            }
        }
    }

    fun closeAlbum() {
        albumJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedAlbum = null,
            albumTracks = emptyList(),
            albumLoading = false,
            albumError = null,
        )
    }

    fun retryAlbum() {
        mutableState.value.selectedAlbum?.let(::openAlbum)
    }

    fun openArtist(artist: Artist) {
        closeAlbum()
        closeTrack()
        artistJob?.cancel()
        val cached = artistCache[artist.id]
        mutableState.value = mutableState.value.copy(
            selectedArtist = cached?.artist ?: artist,
            artistTracks = cached?.tracks.orEmpty(),
            artistLoading = cached == null,
            artistError = null,
        )
        if (cached != null) return
        artistJob = viewModelScope.launch {
            try {
                val (detail, tracks) = coroutineScope {
                    val detailRequest = async { gateway.loadArtist(artist.id) }
                    val tracksRequest = async { gateway.loadArtistTracks(artist.id) }
                    detailRequest.await() to tracksRequest.await()
                }
                putArtistCache(artist.id, ArtistCacheEntry(detail, tracks))
                mutableState.update {
                    if (it.selectedArtist?.id != artist.id) it else it.copy(
                        selectedArtist = detail,
                        artistTracks = tracks,
                        artistLoading = false,
                    )
                }
            } catch (error: IllegalArgumentException) {
                mutableState.update {
                    if (it.selectedArtist?.id != artist.id) it else it.copy(
                        artistLoading = false,
                        artistError = error.message ?: "Invalid artist.",
                    )
                }
            } catch (error: ApiException) {
                mutableState.update {
                    if (it.selectedArtist?.id != artist.id) it else it.copy(
                        artistLoading = false,
                        artistError = error.message ?: "Could not load the artist.",
                        authenticationFailed = error.authenticationFailed,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    if (it.selectedArtist?.id != artist.id) it else it.copy(
                        artistLoading = false,
                        artistError = "Could not load the artist.",
                    )
                }
            }
        }
    }

    fun closeArtist() {
        artistJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedArtist = null,
            artistTracks = emptyList(),
            artistLoading = false,
            artistError = null,
        )
    }

    fun retryArtist() {
        mutableState.value.selectedArtist?.let(::openArtist)
    }

    fun openTrack(trackId: String) {
        closeAlbum()
        closeArtist()
        trackJob?.cancel()
        val cached = trackDetailCache[trackId]
        mutableState.value = mutableState.value.copy(
            selectedTrackId = trackId,
            trackDetail = cached,
            trackLoading = cached == null,
            trackError = null,
        )
        if (cached != null) return
        trackJob = viewModelScope.launch {
            try {
                val detail = gateway.loadTrack(trackId)
                trackDetailCache[trackId] = detail
                mutableState.update {
                    if (it.selectedTrackId != trackId) it else it.copy(
                        trackDetail = detail,
                        trackLoading = false,
                    )
                }
            } catch (error: IllegalArgumentException) {
                mutableState.update {
                    if (it.selectedTrackId != trackId) it else it.copy(
                        trackLoading = false,
                        trackError = error.message ?: "Invalid track.",
                    )
                }
            } catch (error: ApiException) {
                mutableState.update {
                    if (it.selectedTrackId != trackId) it else it.copy(
                        trackLoading = false,
                        trackError = error.message ?: "Could not load the track.",
                        authenticationFailed = error.authenticationFailed,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    if (it.selectedTrackId != trackId) it else it.copy(
                        trackLoading = false,
                        trackError = "Could not load the track.",
                    )
                }
            }
        }
    }

    fun closeTrack() {
        trackJob?.cancel()
        mutableState.value = mutableState.value.copy(
            selectedTrackId = null,
            trackDetail = null,
            trackLoading = false,
            trackError = null,
        )
    }

    fun retryTrack() {
        mutableState.value.selectedTrackId?.let(::openTrack)
    }

    fun search(query: String) {
        searchJob?.cancel()
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            mutableState.value = mutableState.value.copy(
                searchQuery = "",
                searchLoading = false,
                searchResults = com.haraldmue.velin.data.AccumulatedPage(),
                searchError = null,
            )
            return
        }
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                searchQuery = normalized,
                searchLoading = true,
                searchError = null,
            )
            try {
                val results = gateway.search(normalized)
                mutableState.value = mutableState.value.copy(
                    searchLoading = false,
                    searchResults = com.haraldmue.velin.data.AccumulatedPage.from(results),
                )
            } catch (error: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(
                    searchLoading = false,
                    searchError = error.message ?: "Invalid search query.",
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    searchLoading = false,
                    searchError = error.message ?: "Search failed.",
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    searchLoading = false,
                    searchError = "Search failed.",
                )
            }
        }
    }

    fun loadMoreSearch() {
        val query = mutableState.value.searchQuery
        val results = mutableState.value.searchResults
        if (query.isBlank() || !results.hasMore || results.loadingMore) return
        val cursor = results.nextCursor ?: return
        mutableState.update {
            if (it.searchQuery != query || it.searchResults.nextCursor != cursor) it else it.copy(
                searchResults = it.searchResults.copy(loadingMore = true, loadMoreError = null),
            )
        }
        viewModelScope.launch {
            try {
                val page = gateway.search(query, cursor)
                mutableState.update {
                    if (it.searchQuery != query || it.searchResults.nextCursor != cursor) it else it.copy(
                        searchResults = it.searchResults.append(page),
                    )
                }
            } catch (error: ApiException) {
                mutableState.update {
                    if (it.searchQuery != query || it.searchResults.nextCursor != cursor) it else it.copy(
                        searchResults = it.searchResults.copy(
                            loadingMore = false,
                            loadMoreError = error.message ?: "Search failed.",
                        ),
                        authenticationFailed = error.authenticationFailed,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update {
                    if (it.searchQuery != query || it.searchResults.nextCursor != cursor) it else it.copy(
                        searchResults = it.searchResults.copy(
                            loadingMore = false,
                            loadMoreError = "Search failed.",
                        ),
                    )
                }
            }
        }
    }

    private fun clearDetailCaches() {
        albumTrackCache.clear()
        artistCache.clear()
        trackDetailCache.clear()
    }

    private fun putTrackList(
        cache: LinkedHashMap<String, List<Track>>,
        key: String,
        tracks: List<Track>,
        maxEntries: Int,
    ) {
        cache[key] = tracks
        while (cache.size > maxEntries || cache.values.sumOf { it.size } > MaxCachedDetailTracks) {
            val eldest = cache.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    private fun putArtistCache(key: String, entry: ArtistCacheEntry) {
        artistCache[key] = entry
        while (artistCache.size > MaxCachedArtists ||
            artistCache.values.sumOf { it.tracks.size } > MaxCachedDetailTracks
        ) {
            val eldest = artistCache.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    private data class ArtistCacheEntry(
        val artist: Artist,
        val tracks: List<Track>,
    )

    private companion object {
        const val HomeAlbumItems = 16
        const val HomeDiscoveryItems = 32
        const val MaxCachedAlbums = 32
        const val MaxCachedArtists = 12
        const val MaxCachedTrackDetails = 64
        const val MaxCachedDetailTracks = 2_000
    }
}

class LibraryViewModelFactory(
    private val gateway: LibraryGateway,
    private val repository: LibraryCacheRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        return LibraryViewModel(gateway, repository) as T
    }
}
