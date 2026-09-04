package com.haraldmue.velin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.PairingClient
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.VelinApiClient
import com.haraldmue.velin.playback.MiniPlayer
import com.haraldmue.velin.playback.NetworkLossCanceller
import com.haraldmue.velin.playback.isLikelyEmulator
import com.haraldmue.velin.playback.NowPlayingScreen
import com.haraldmue.velin.playback.PlaybackQueueScreen
import com.haraldmue.velin.playback.PlaybackViewModel
import com.haraldmue.velin.playback.PlaybackViewModelFactory
import com.haraldmue.velin.ui.library.AlbumDetailScreen
import com.haraldmue.velin.ui.library.ArtistDetailScreen
import com.haraldmue.velin.ui.library.HomeScreen
import com.haraldmue.velin.ui.library.LibraryScreen
import com.haraldmue.velin.ui.library.LibrarySection
import com.haraldmue.velin.ui.library.LibraryViewModel
import com.haraldmue.velin.ui.library.LibraryViewModelFactory
import com.haraldmue.velin.ui.library.SearchScreen
import com.haraldmue.velin.ui.library.TrackDetailScreen
import com.haraldmue.velin.ui.pairing.PairingLoadingScreen
import com.haraldmue.velin.ui.pairing.PairingScreen
import com.haraldmue.velin.ui.pairing.PairingUiState
import com.haraldmue.velin.ui.pairing.PairingViewModel
import com.haraldmue.velin.ui.pairing.PairingViewModelFactory

private data class PlaybackRequest(val tracks: List<Track>, val startIndex: Int)

internal enum class Destination(val label: String) {
    Home("Home"),
    Search("Search"),
    Library("Library"),
}

@Composable
fun VelinApp() {
    val applicationContext = LocalContext.current.applicationContext
    val pairingFactory = remember(applicationContext) {
        PairingViewModelFactory(
            pairingGateway = PairingClient(),
            credentialStore = AndroidKeyStoreCredentialStore(applicationContext),
        )
    }
    val pairingViewModel: PairingViewModel = viewModel(factory = pairingFactory)
    val pairingState by pairingViewModel.state.collectAsState()

    when (val state = pairingState) {
        PairingUiState.Loading -> PairingLoadingScreen()
        PairingUiState.Unpaired,
        is PairingUiState.Pairing,
        is PairingUiState.Error,
        -> PairingScreen(
            state = state,
            onPair = pairingViewModel::pair,
            onDismissError = pairingViewModel::dismissError,
        )
        is PairingUiState.Paired -> ConnectedApp(
            credentials = state.credentials,
            onDisconnect = pairingViewModel::disconnect,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedApp(
    credentials: DeviceCredentials,
    onDisconnect: () -> Unit,
) {
    val applicationContext = LocalContext.current.applicationContext
    var destination by remember { mutableStateOf(Destination.Home) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showPlaybackQueue by rememberSaveable { mutableStateOf(false) }
    val artworkClient = remember(credentials) { ArtworkClient(applicationContext, credentials) }
    val apiClient = remember(credentials) { VelinApiClient(credentials) }
    DisposableEffect(artworkClient) {
        onDispose(artworkClient::close)
    }
    DisposableEffect(apiClient, artworkClient) {
        if (isLikelyEmulator()) {
            onDispose { }
        } else {
            val canceller = NetworkLossCanceller(applicationContext) {
                apiClient.cancelInFlight()
                artworkClient.cancelInFlight()
            }
            canceller.start()
            onDispose(canceller::stop)
        }
    }
    val libraryFactory = remember(apiClient) {
        LibraryViewModelFactory(apiClient)
    }
    val libraryViewModel: LibraryViewModel = viewModel(
        key = "library-${credentials.deviceId}",
        factory = libraryFactory,
    )
    val libraryState by libraryViewModel.state.collectAsState()
    val playbackFactory = remember(credentials) {
        PlaybackViewModelFactory(applicationContext, credentials)
    }
    val playbackViewModel: PlaybackViewModel = viewModel(
        key = "playback-${credentials.deviceId}",
        factory = playbackFactory,
    )
    val playbackState by playbackViewModel.state
    var pendingPlaybackRequest by remember { mutableStateOf<PlaybackRequest?>(null) }
    var notificationPermissionHandled by rememberSaveable { mutableStateOf(false) }
    var showAppMenu by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionHandled = true
        pendingPlaybackRequest?.let { request ->
            playbackViewModel.playQueue(request.tracks, request.startIndex)
        }
        pendingPlaybackRequest = null
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionHandled
        ) {
            notificationPermissionHandled = true
            pendingPlaybackRequest = PlaybackRequest(tracks, startIndex)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            playbackViewModel.playQueue(tracks, startIndex)
        }
    }

    fun disconnect() {
        showNowPlaying = false
        showPlaybackQueue = false
        libraryViewModel.closeAlbum()
        libraryViewModel.closeArtist()
        libraryViewModel.closeTrack()
        playbackViewModel.stopAndClear()
        onDisconnect()
    }

    BackHandler(enabled = showPlaybackQueue) {
        showPlaybackQueue = false
    }
    BackHandler(enabled = showNowPlaying && !showPlaybackQueue) {
        showNowPlaying = false
    }
    BackHandler(enabled = !showNowPlaying && libraryState.selectedTrackId != null) {
        libraryViewModel.closeTrack()
    }
    BackHandler(enabled = !showNowPlaying && libraryState.selectedTrackId == null && libraryState.selectedArtist != null) {
        libraryViewModel.closeArtist()
    }
    BackHandler(enabled = !showNowPlaying && libraryState.selectedTrackId == null && libraryState.selectedArtist == null && libraryState.selectedAlbum != null) {
        libraryViewModel.closeAlbum()
    }

    val backAction: (() -> Unit)? = when {
        showPlaybackQueue -> ({ showPlaybackQueue = false })
        showNowPlaying -> ({ showNowPlaying = false })
        libraryState.selectedTrackId != null -> libraryViewModel::closeTrack
        libraryState.selectedArtist != null -> libraryViewModel::closeArtist
        libraryState.selectedAlbum != null -> libraryViewModel::closeAlbum
        else -> null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            showPlaybackQueue -> "Queue"
                            showNowPlaying -> "Now playing"
                            libraryState.selectedTrackId != null -> "Track"
                            libraryState.selectedArtist != null -> "Artist"
                            libraryState.selectedAlbum != null -> "Album"
                            else -> "Velin"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    backAction?.let { action ->
                        IconButton(onClick = action) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (backAction == null) {
                        IconButton(
                            onClick = libraryViewModel::refresh,
                            enabled = !libraryState.loading,
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library")
                        }
                        Box {
                            IconButton(onClick = { showAppMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showAppMenu,
                                onDismissRequest = { showAppMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Disconnect from server") },
                                    onClick = {
                                        showAppMenu = false
                                        disconnect()
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (!showNowPlaying) {
                Column {
                    MiniPlayer(
                        state = playbackState,
                        artworkClient = artworkClient,
                        onOpen = { showNowPlaying = true },
                        onPrevious = playbackViewModel::skipToPrevious,
                        onTogglePlayPause = playbackViewModel::togglePlayPause,
                        onNext = playbackViewModel::skipToNext,
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = {
                                    libraryViewModel.closeAlbum()
                                    libraryViewModel.closeArtist()
                                    libraryViewModel.closeTrack()
                                    destination = item
                                },
                                icon = {
                                    Icon(
                                        imageVector = when (item) {
                                            Destination.Home -> Icons.Rounded.Home
                                            Destination.Search -> Icons.Rounded.Search
                                            Destination.Library -> Icons.Rounded.LibraryMusic
                                        },
                                        contentDescription = item.label,
                                    )
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (showPlaybackQueue) {
                PlaybackQueueScreen(
                    state = playbackState,
                    artworkClient = artworkClient,
                    onSelect = playbackViewModel::selectQueueItem,
                    onRemove = playbackViewModel::removeQueueItem,
                    onMove = playbackViewModel::moveQueueItem,
                    onToggleShuffle = playbackViewModel::toggleShuffle,
                    onCycleRepeat = playbackViewModel::cycleRepeatMode,
                )
                return@Column
            }
            if (showNowPlaying) {
                NowPlayingScreen(
                    state = playbackState,
                    artworkClient = artworkClient,
                    onPrevious = playbackViewModel::skipToPrevious,
                    onTogglePlayPause = playbackViewModel::togglePlayPause,
                    onNext = playbackViewModel::skipToNext,
                    onSeek = playbackViewModel::seekTo,
                    onOpenQueue = { showPlaybackQueue = true },
                    onToggleShuffle = playbackViewModel::toggleShuffle,
                    onCycleRepeat = playbackViewModel::cycleRepeatMode,
                )
                return@Column
            }
            libraryState.selectedTrackId?.let {
                TrackDetailScreen(
                    state = libraryState,
                    artworkClient = artworkClient,
                    currentTrackId = playbackState.mediaId,
                    onPlay = { track -> playQueue(listOf(track), 0) },
                    onPlayNext = { track -> playbackViewModel.enqueueTrack(track, playNext = true) },
                    onAddToQueue = { track -> playbackViewModel.enqueueTrack(track, playNext = false) },
                    onOpenAlbum = { album ->
                        libraryViewModel.closeTrack()
                        libraryViewModel.openAlbum(album)
                    },
                    onOpenArtist = { artist ->
                        libraryViewModel.closeTrack()
                        libraryViewModel.openArtist(artist)
                    },
                    onRetry = libraryViewModel::retryTrack,
                    onPairAgain = ::disconnect,
                )
                return@Column
            }
            libraryState.selectedArtist?.let { artist ->
                ArtistDetailScreen(
                    artist = artist,
                    state = libraryState,
                    artworkClient = artworkClient,
                    currentTrackId = playbackState.mediaId,
                    onPlayQueue = ::playQueue,
                    onRetry = libraryViewModel::retryArtist,
                    onPairAgain = ::disconnect,
                )
                return@Column
            }
            libraryState.selectedAlbum?.let { album ->
                AlbumDetailScreen(
                    album = album,
                    state = libraryState,
                    artworkClient = artworkClient,
                    currentTrackId = playbackState.mediaId,
                    onPlayQueue = ::playQueue,
                    onRetry = libraryViewModel::retryAlbum,
                    onPairAgain = ::disconnect,
                )
                return@Column
            }
            when (destination) {
                Destination.Home -> HomeScreen(
                    credentials = credentials,
                    state = libraryState,
                    artworkClient = artworkClient,
                    onAlbumClick = libraryViewModel::openAlbum,
                    onRetry = libraryViewModel::refresh,
                    onPairAgain = ::disconnect,
                )
                Destination.Search -> SearchScreen(
                    state = libraryState,
                    artworkClient = artworkClient,
                    currentTrackId = playbackState.mediaId,
                    onSearch = libraryViewModel::search,
                    onTrackClick = ::playQueue,
                    onTrackDetail = libraryViewModel::openTrack,
                    onLoadMoreSearch = libraryViewModel::loadMoreSearch,
                    onPairAgain = ::disconnect,
                )
                Destination.Library -> LibraryScreen(
                    state = libraryState,
                    artworkClient = artworkClient,
                    currentTrackId = playbackState.mediaId,
                    onAlbumClick = libraryViewModel::openAlbum,
                    onArtistClick = libraryViewModel::openArtist,
                    onTrackClick = ::playQueue,
                    onTrackDetail = libraryViewModel::openTrack,
                    onLoadMore = libraryViewModel::loadMore,
                    onRetry = libraryViewModel::refresh,
                    onPairAgain = ::disconnect,
                )
            }
        }
    }
}
