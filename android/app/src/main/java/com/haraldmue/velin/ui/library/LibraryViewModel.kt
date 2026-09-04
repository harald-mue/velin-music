package com.haraldmue.velin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySnapshot
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LibrarySection {
    Albums,
    Artists,
    Tracks,
}

data class LibraryUiState(
    val loading: Boolean = true,
    val status: ServerStatus? = null,
    val library: LibrarySnapshot? = null,
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
    private var albumJob: Job? = null
    private var artistJob: Job? = null
    private var trackJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                loading = true,
                error = null,
                authenticationFailed = false,
            )
            try {
                val (status, library) = coroutineScope {
                    val statusRequest = async { gateway.status() }
                    val libraryRequest = async { gateway.loadLibrary() }
                    statusRequest.await() to libraryRequest.await()
                }
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    status = status,
                    library = library,
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = error.message ?: "Could not load the library.",
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = "Could not load the library.",
                )
            }
        }
    }

    fun loadMore(section: LibrarySection) {
        val library = mutableState.value.library ?: return
        when (section) {
            LibrarySection.Artists -> loadMorePage(
                page = library.artists,
                fetch = gateway::loadArtistsPage,
            ) { updated ->
                library.copy(artists = updated)
            }
            LibrarySection.Albums -> loadMorePage(
                page = library.albums,
                fetch = gateway::loadAlbumsPage,
            ) { updated ->
                library.copy(albums = updated)
            }
            LibrarySection.Tracks -> loadMorePage(
                page = library.tracks,
                fetch = gateway::loadTracksPage,
            ) { updated ->
                library.copy(tracks = updated)
            }
        }
    }

    private fun <T> loadMorePage(
        page: com.haraldmue.velin.data.AccumulatedPage<T>,
        fetch: suspend (String?) -> com.haraldmue.velin.data.Page<T>,
        merge: (com.haraldmue.velin.data.AccumulatedPage<T>) -> LibrarySnapshot,
    ) {
        if (!page.hasMore || page.loadingMore) return
        val cursor = page.nextCursor ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                library = merge(page.copy(loadingMore = true)),
            )
            try {
                val nextPage = fetch(cursor)
                mutableState.value = mutableState.value.copy(
                    library = merge(page.append(nextPage)),
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    library = merge(page.copy(loadingMore = false)),
                    error = error.message,
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    library = merge(page.copy(loadingMore = false)),
                    error = "Could not load more items.",
                )
            }
        }
    }

    fun openAlbum(album: Album) {
        closeArtist()
        closeTrack()
        albumJob?.cancel()
        albumJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedAlbum = album,
                albumTracks = emptyList(),
                albumLoading = true,
                albumError = null,
            )
            try {
                val tracks = gateway.loadAlbumTracks(album.id)
                mutableState.value = mutableState.value.copy(
                    albumTracks = tracks,
                    albumLoading = false,
                )
            } catch (error: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(
                    albumLoading = false,
                    albumError = error.message ?: "Invalid album.",
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    albumLoading = false,
                    albumError = error.message ?: "Could not load the album.",
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    albumLoading = false,
                    albumError = "Could not load the album.",
                )
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
        artistJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedArtist = artist,
                artistTracks = emptyList(),
                artistLoading = true,
                artistError = null,
            )
            try {
                val detail = gateway.loadArtist(artist.id)
                val tracks = gateway.loadArtistTracks(artist.id)
                mutableState.value = mutableState.value.copy(
                    selectedArtist = detail,
                    artistTracks = tracks,
                    artistLoading = false,
                )
            } catch (error: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(
                    artistLoading = false,
                    artistError = error.message ?: "Invalid artist.",
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    artistLoading = false,
                    artistError = error.message ?: "Could not load the artist.",
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    artistLoading = false,
                    artistError = "Could not load the artist.",
                )
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
        trackJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedTrackId = trackId,
                trackDetail = null,
                trackLoading = true,
                trackError = null,
            )
            try {
                val detail = gateway.loadTrack(trackId)
                mutableState.value = mutableState.value.copy(
                    trackDetail = detail,
                    trackLoading = false,
                )
            } catch (error: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(
                    trackLoading = false,
                    trackError = error.message ?: "Invalid track.",
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    trackLoading = false,
                    trackError = error.message ?: "Could not load the track.",
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    trackLoading = false,
                    trackError = "Could not load the track.",
                )
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
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                searchResults = results.copy(loadingMore = true),
            )
            try {
                val page = gateway.search(query, cursor)
                mutableState.value = mutableState.value.copy(
                    searchResults = results.append(page),
                )
            } catch (error: ApiException) {
                mutableState.value = mutableState.value.copy(
                    searchResults = results.copy(loadingMore = false),
                    searchError = error.message ?: "Search failed.",
                    authenticationFailed = error.authenticationFailed,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    searchResults = results.copy(loadingMore = false),
                    searchError = "Search failed.",
                )
            }
        }
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
