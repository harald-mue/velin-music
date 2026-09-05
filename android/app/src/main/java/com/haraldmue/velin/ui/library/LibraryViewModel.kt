package com.haraldmue.velin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haraldmue.velin.data.AccumulatedPage
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySnapshot
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibrarySection {
    Albums,
    Artists,
    Tracks,
}

data class LibraryUiState(
    val loading: Boolean = true,
    val status: ServerStatus? = null,
    val summary: LibrarySummary? = null,
    val homeAlbums: List<Album> = emptyList(),
    val library: LibrarySnapshot? = null,
    val loadedSections: Set<LibrarySection> = emptySet(),
    val loadingSections: Set<LibrarySection> = emptySet(),
    val sectionErrors: Map<LibrarySection, String> = emptyMap(),
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshGeneration = 0
    private val sectionJobs = mutableMapOf<LibrarySection, Job>()
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
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        val interruptedSections = mutableState.value.loadingSections
        val previouslyRequestedSections = mutableState.value.loadedSections + interruptedSections
        sectionJobs.values.forEach(Job::cancel)
        sectionJobs.clear()
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = true,
                    library = it.library?.withoutLoadingMarkers(),
                    loadingSections = emptySet(),
                    error = null,
                    authenticationFailed = false,
                )
            }
            try {
                val result = coroutineScope {
                    val statusRequest = async { gateway.status() }
                    val summaryRequest = async { gateway.summary() }
                    val albumsRequest = async { gateway.loadAlbumsPage(limit = HomeAlbumItems) }
                    RefreshResult(statusRequest.await(), summaryRequest.await(), albumsRequest.await().items)
                }
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val revisionChanged = mutableState.value.summary?.revision != result.summary.revision
                if (revisionChanged) {
                    searchJob?.cancel()
                    sectionJobs.values.forEach(Job::cancel)
                    sectionJobs.clear()
                    clearDetailCaches()
                }
                mutableState.update { state ->
                    state.copy(
                        loading = false,
                        status = result.status,
                        summary = result.summary,
                        homeAlbums = result.homeAlbums,
                        library = if (revisionChanged) LibrarySnapshot() else state.library ?: LibrarySnapshot(),
                        loadedSections = if (revisionChanged) emptySet() else state.loadedSections,
                        loadingSections = if (revisionChanged) emptySet() else state.loadingSections,
                        sectionErrors = if (revisionChanged) emptyMap() else state.sectionErrors,
                        searchQuery = if (revisionChanged) "" else state.searchQuery,
                        searchResults = if (revisionChanged) AccumulatedPage() else state.searchResults,
                        searchError = if (revisionChanged) null else state.searchError,
                    )
                }
                val sectionsToReload = if (revisionChanged) previouslyRequestedSections else interruptedSections
                sectionsToReload.forEach(::ensureSectionLoaded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (generation != refreshGeneration) return@launch
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Could not load the library.",
                        authenticationFailed = error.authenticationFailed,
                    )
                }
            } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                mutableState.update {
                    it.copy(
                        loading = false,
                        error = "Could not load the library.",
                    )
                }
            }
        }
    }

    fun ensureSectionLoaded(section: LibrarySection) {
        val state = mutableState.value
        if (state.library == null || section in state.loadedSections || section in state.loadingSections) return
        when (section) {
            LibrarySection.Artists -> loadInitialSection(
                section = section,
                fetch = { gateway.loadArtistsPage(limit = 200) },
                replace = { library, page -> library.copy(artists = page) },
            )
            LibrarySection.Albums -> loadInitialSection(
                section = section,
                fetch = { gateway.loadAlbumsPage(limit = 200) },
                replace = { library, page -> library.copy(albums = page) },
            )
            LibrarySection.Tracks -> loadInitialSection(
                section = section,
                fetch = { gateway.loadTracksPage(limit = 100) },
                replace = { library, page -> library.copy(tracks = page) },
            )
        }
    }

    private fun <T> loadInitialSection(
        section: LibrarySection,
        fetch: suspend () -> com.haraldmue.velin.data.Page<T>,
        replace: (LibrarySnapshot, AccumulatedPage<T>) -> LibrarySnapshot,
    ) {
        val generation = refreshGeneration
        sectionJobs[section]?.cancel()
        mutableState.update {
            it.copy(
                loadingSections = it.loadingSections + section,
                sectionErrors = it.sectionErrors - section,
            )
        }
        sectionJobs[section] = viewModelScope.launch {
            try {
                val page = fetch()
                if (generation != refreshGeneration) return@launch
                mutableState.update { current ->
                    val library = current.library ?: return@update current
                    current.copy(
                        library = replace(library, AccumulatedPage.from(page)),
                        loadedSections = current.loadedSections + section,
                        loadingSections = current.loadingSections - section,
                        sectionErrors = current.sectionErrors - section,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (generation != refreshGeneration) return@launch
                finishSectionError(section, error.message ?: "Could not load this section.", error.authenticationFailed)
            } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                finishSectionError(section, "Could not load this section.", false)
            }
        }
    }

    private fun finishSectionError(section: LibrarySection, message: String, authenticationFailed: Boolean) {
        mutableState.update {
            it.copy(
                loadingSections = it.loadingSections - section,
                sectionErrors = it.sectionErrors + (section to message),
                authenticationFailed = authenticationFailed,
            )
        }
    }

    fun loadMore(section: LibrarySection) {
        when (section) {
            LibrarySection.Artists -> loadMorePage(
                select = LibrarySnapshot::artists,
                replace = { library, page -> library.copy(artists = page) },
                fetch = { cursor -> gateway.loadArtistsPage(cursor = cursor, limit = 200) },
            )
            LibrarySection.Albums -> loadMorePage(
                select = LibrarySnapshot::albums,
                replace = { library, page -> library.copy(albums = page) },
                fetch = { cursor -> gateway.loadAlbumsPage(cursor = cursor, limit = 200) },
            )
            LibrarySection.Tracks -> loadMorePage(
                select = LibrarySnapshot::tracks,
                replace = { library, page -> library.copy(tracks = page) },
                fetch = { cursor -> gateway.loadTracksPage(cursor = cursor, limit = 100) },
            )
        }
    }

    private fun <T> loadMorePage(
        select: (LibrarySnapshot) -> AccumulatedPage<T>,
        replace: (LibrarySnapshot, AccumulatedPage<T>) -> LibrarySnapshot,
        fetch: suspend (String) -> com.haraldmue.velin.data.Page<T>,
    ) {
        val library = mutableState.value.library ?: return
        val page = select(library)
        if (!page.hasMore || page.loadingMore) return
        val cursor = page.nextCursor ?: return
        val generation = refreshGeneration
        mutableState.update { state ->
            val currentLibrary = state.library ?: return@update state
            val currentPage = select(currentLibrary)
            if (currentPage.nextCursor != cursor || currentPage.loadingMore) return@update state
            state.copy(
                library = replace(
                    currentLibrary,
                    currentPage.copy(loadingMore = true, loadMoreError = null),
                ),
            )
        }
        viewModelScope.launch {
            try {
                val nextPage = fetch(cursor)
                if (generation != refreshGeneration) return@launch
                mutableState.update { state ->
                    val currentLibrary = state.library ?: return@update state
                    val currentPage = select(currentLibrary)
                    if (currentPage.nextCursor != cursor || !currentPage.loadingMore) return@update state
                    state.copy(library = replace(currentLibrary, currentPage.append(nextPage)))
                }
            } catch (error: ApiException) {
                if (generation != refreshGeneration) return@launch
                mutableState.update { state ->
                    val currentLibrary = state.library ?: return@update state
                    val currentPage = select(currentLibrary)
                    if (currentPage.nextCursor != cursor) return@update state
                    state.copy(
                        library = replace(
                            currentLibrary,
                            currentPage.copy(
                                loadingMore = false,
                                loadMoreError = error.message ?: "Could not load more items.",
                            ),
                        ),
                        authenticationFailed = error.authenticationFailed,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation != refreshGeneration) return@launch
                mutableState.update { state ->
                    val currentLibrary = state.library ?: return@update state
                    val currentPage = select(currentLibrary)
                    if (currentPage.nextCursor != cursor) return@update state
                    state.copy(
                        library = replace(
                            currentLibrary,
                            currentPage.copy(
                                loadingMore = false,
                                loadMoreError = "Could not load more items.",
                            ),
                        ),
                    )
                }
            }
        }
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
                val tracks = gateway.loadAlbumTracks(album.id)
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

    private fun LibrarySnapshot.withoutLoadingMarkers(): LibrarySnapshot = copy(
        artists = artists.copy(loadingMore = false),
        albums = albums.copy(loadingMore = false),
        tracks = tracks.copy(loadingMore = false),
    )

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

    private data class RefreshResult(
        val status: ServerStatus,
        val summary: LibrarySummary,
        val homeAlbums: List<Album>,
    )

    private companion object {
        const val HomeAlbumItems = 16
        const val MaxCachedAlbums = 32
        const val MaxCachedArtists = 12
        const val MaxCachedTrackDetails = 64
        const val MaxCachedDetailTracks = 2_000
    }
}

class LibraryViewModelFactory(
    private val gateway: LibraryGateway,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        return LibraryViewModel(gateway) as T
    }
}
