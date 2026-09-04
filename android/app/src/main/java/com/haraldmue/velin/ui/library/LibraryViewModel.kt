package com.haraldmue.velin.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySnapshot
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val EmptyTrackPage = Page<Track>(emptyList(), null, false)

data class LibraryUiState(
    val loading: Boolean = true,
    val status: ServerStatus? = null,
    val library: LibrarySnapshot? = null,
    val error: String? = null,
    val authenticationFailed: Boolean = false,
    val searchLoading: Boolean = false,
    val searchResults: Page<Track> = EmptyTrackPage,
    val searchError: String? = null,
    val selectedAlbum: Album? = null,
    val albumTracks: List<Track> = emptyList(),
    val albumLoading: Boolean = false,
    val albumError: String? = null,
)

class LibraryViewModel(
    private val gateway: LibraryGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var albumJob: Job? = null

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

    fun openAlbum(album: Album) {
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

    fun search(query: String) {
        searchJob?.cancel()
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            mutableState.value = mutableState.value.copy(
                searchLoading = false,
                searchResults = EmptyTrackPage,
                searchError = null,
            )
            return
        }
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(searchLoading = true, searchError = null)
            try {
                val results = gateway.search(normalized)
                mutableState.value = mutableState.value.copy(
                    searchLoading = false,
                    searchResults = results,
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
