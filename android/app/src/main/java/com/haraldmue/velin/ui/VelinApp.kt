package com.haraldmue.velin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.PairingClient
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.VelinApiClient
import com.haraldmue.velin.playback.MiniPlayer
import com.haraldmue.velin.playback.NowPlayingScreen
import com.haraldmue.velin.playback.PlaybackQueueScreen
import com.haraldmue.velin.playback.PlaybackViewModel
import com.haraldmue.velin.playback.PlaybackViewModelFactory
import com.haraldmue.velin.ui.library.AlbumDetailScreen
import com.haraldmue.velin.ui.library.HomeScreen
import com.haraldmue.velin.ui.library.LibraryScreen
import com.haraldmue.velin.ui.library.LibraryViewModel
import com.haraldmue.velin.ui.library.LibraryViewModelFactory
import com.haraldmue.velin.ui.library.SearchScreen
import com.haraldmue.velin.ui.pairing.PairingLoadingScreen
import com.haraldmue.velin.ui.pairing.PairingScreen
import com.haraldmue.velin.ui.pairing.PairingUiState
import com.haraldmue.velin.ui.pairing.PairingViewModel
import com.haraldmue.velin.ui.pairing.PairingViewModelFactory

private data class PlaybackRequest(val tracks: List<Track>, val startIndex: Int)

internal enum class Destination(val label: String, val symbol: String) {
    Home("Home", "H"),
    Search("Search", "S"),
    Library("Library", "L"),
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
    DisposableEffect(artworkClient) {
        onDispose(artworkClient::close)
    }
    val libraryFactory = remember(credentials) {
        LibraryViewModelFactory(VelinApiClient(credentials))
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
        playbackViewModel.stopAndClear()
        onDisconnect()
    }

    BackHandler(enabled = showPlaybackQueue) {
        showPlaybackQueue = false
    }
    BackHandler(enabled = showNowPlaying && !showPlaybackQueue) {
        showNowPlaying = false
    }
    BackHandler(enabled = !showNowPlaying && libraryState.selectedAlbum != null) {
        libraryViewModel.closeAlbum()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            showPlaybackQueue -> "Queue"
                            showNowPlaying -> "Now playing"
                            libraryState.selectedAlbum != null -> "Album"
                            else -> "Velin"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    when {
                        showPlaybackQueue -> TextButton(onClick = { showPlaybackQueue = false }) {
                            Text("Back")
                        }
                        showNowPlaying -> TextButton(onClick = { showNowPlaying = false }) {
                            Text("Back")
                        }
                        libraryState.selectedAlbum != null -> TextButton(onClick = libraryViewModel::closeAlbum) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (!showNowPlaying && libraryState.selectedAlbum == null) {
                        TextButton(onClick = libraryViewModel::refresh, enabled = !libraryState.loading) {
                            Text("Refresh")
                        }
                        TextButton(onClick = ::disconnect) {
                            Text("Disconnect")
                        }
                    }
                },
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
                    HorizontalDivider()
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = {
                                    libraryViewModel.closeAlbum()
                                    destination = item
                                },
                                icon = { Text(item.symbol, fontWeight = FontWeight.Bold) },
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
                    onPairAgain = ::disconnect,
                )
                Destination.Library -> LibraryScreen(
                    state = libraryState,
                    artworkClient = artworkClient,
                    currentTrackId = playbackState.mediaId,
                    onAlbumClick = libraryViewModel::openAlbum,
                    onTrackClick = ::playQueue,
                    onRetry = libraryViewModel::refresh,
                    onPairAgain = ::disconnect,
                )
            }
        }
    }
}
