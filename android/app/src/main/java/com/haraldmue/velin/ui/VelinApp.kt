package com.haraldmue.velin.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.PairingClient
import com.haraldmue.velin.data.SavedQueueStore
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.VelinApiClient
import com.haraldmue.velin.playback.MiniPlayer
import com.haraldmue.velin.playback.NetworkLossCanceller
import com.haraldmue.velin.playback.isLikelyEmulator
import com.haraldmue.velin.playback.NowPlayingScreen
import com.haraldmue.velin.playback.PlaybackQueueScreen
import com.haraldmue.velin.playback.PlaybackService
import com.haraldmue.velin.playback.PlaybackViewModel
import com.haraldmue.velin.playback.PlaybackViewModelFactory
import com.haraldmue.velin.ui.library.AlbumDetailScreen
import com.haraldmue.velin.ui.library.ArtistDetailScreen
import com.haraldmue.velin.ui.library.HomeScreen
import com.haraldmue.velin.ui.library.LibraryScreen
import com.haraldmue.velin.ui.library.LibrarySection
import com.haraldmue.velin.ui.library.LibraryTab
import com.haraldmue.velin.ui.library.LibraryUiState
import com.haraldmue.velin.ui.library.LibraryViewModel
import com.haraldmue.velin.ui.library.LibraryViewModelFactory
import com.haraldmue.velin.ui.library.TrackDetailScreen
import com.haraldmue.velin.ui.layout.isLandscape
import com.haraldmue.velin.ui.layout.usesNavigationRail
import com.haraldmue.velin.ui.layout.velinWidthClass
import com.haraldmue.velin.ui.pairing.PairingLoadingScreen
import com.haraldmue.velin.ui.pairing.PairingScreen
import com.haraldmue.velin.ui.pairing.PairingUiState
import com.haraldmue.velin.ui.pairing.PairingViewModel
import com.haraldmue.velin.ui.pairing.PairingViewModelFactory

private data class PlaybackRequest(val tracks: List<Track>, val startIndex: Int)

internal enum class Destination(val label: String) {
    Home("Home"),
    Queue("Queue"),
    Library("Library"),
}

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Home -> Icons.Rounded.Home
        Destination.Queue -> Icons.AutoMirrored.Rounded.QueueMusic
        Destination.Library -> Icons.Rounded.LibraryMusic
    }

internal enum class ServerReachability {
    Connected,
    Unreachable,
    Unauthorized,
    Loading,
}

@Composable
fun VelinApp() {
    val applicationContext = LocalContext.current.applicationContext
    val pairingFactory = remember(applicationContext) {
        PairingViewModelFactory(
            pairingGateway = PairingClient(),
            credentialStore = AndroidKeyStoreCredentialStore(applicationContext),
            onCredentialsChanged = {
                applicationContext.stopService(Intent(applicationContext, PlaybackService::class.java))
            },
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
    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.Albums) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showServerInfo by remember { mutableStateOf(false) }
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
    val playbackFactory = remember(credentials, apiClient) {
        PlaybackViewModelFactory(
            applicationContext,
            credentials,
            apiClient,
            SavedQueueStore(File(applicationContext.filesDir, "saved-queue-${credentials.deviceId}.json")),
        )
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
        showServerInfo = false
        libraryViewModel.closeAlbum()
        libraryViewModel.closeArtist()
        libraryViewModel.closeTrack()
        playbackViewModel.stopAndClear()
        playbackViewModel.releaseForCredentialChange()
        onDisconnect()
    }

    fun selectDestination(item: Destination) {
        libraryViewModel.closeAlbum()
        libraryViewModel.closeArtist()
        libraryViewModel.closeTrack()
        showNowPlaying = false
        destination = item
    }

    BackHandler(enabled = showNowPlaying) {
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
        showNowPlaying -> ({ showNowPlaying = false })
        libraryState.selectedTrackId != null -> libraryViewModel::closeTrack
        libraryState.selectedArtist != null -> libraryViewModel::closeArtist
        libraryState.selectedAlbum != null -> libraryViewModel::closeAlbum
        else -> null
    }
    val reachability = serverReachability(libraryState)
    val useRail = usesNavigationRail(velinWidthClass(), isLandscape())

    Row(modifier = Modifier.fillMaxSize()) {
        if (useRail) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                Destination.entries.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item && backAction == null && !showNowPlaying,
                        onClick = { selectDestination(item) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when {
                                showNowPlaying -> "Now playing"
                                libraryState.selectedTrackId != null -> "Track"
                                libraryState.selectedArtist != null -> "Artist"
                                libraryState.selectedAlbum != null -> "Album"
                                destination == Destination.Queue -> "Queue"
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
                            ConnectionStatusDot(reachability)
                            IconButton(onClick = libraryViewModel::refresh) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library")
                            }
                            IconButton(onClick = { showServerInfo = true }) {
                                Icon(Icons.Rounded.Info, contentDescription = "Server information")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                Column {
                    if (!showNowPlaying) {
                        MiniPlayer(
                            state = playbackState,
                            artworkClient = artworkClient,
                            onOpen = { showNowPlaying = true },
                            onPrevious = playbackViewModel::skipToPrevious,
                            onTogglePlayPause = playbackViewModel::togglePlayPause,
                            onNext = playbackViewModel::skipToNext,
                        )
                    }
                    if (!useRail && !showNowPlaying) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background,
                            tonalElevation = 0.dp,
                        ) {
                            Destination.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = destination == item && backAction == null,
                                    onClick = { selectDestination(item) },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (libraryState.loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                if (showNowPlaying) {
                    NowPlayingScreen(
                        state = playbackState,
                        artworkClient = artworkClient,
                        onPrevious = playbackViewModel::skipToPrevious,
                        onTogglePlayPause = playbackViewModel::togglePlayPause,
                        onNext = playbackViewModel::skipToNext,
                        onSeek = playbackViewModel::seekTo,
                        onOpenQueue = { selectDestination(Destination.Queue) },
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
                        onEnqueueQueue = { tracks -> playbackViewModel.enqueueTracks(tracks) },
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
                        onEnqueueQueue = { tracks -> playbackViewModel.enqueueTracks(tracks) },
                        onRetry = libraryViewModel::retryAlbum,
                        onPairAgain = ::disconnect,
                    )
                    return@Column
                }
                when (destination) {
                    Destination.Queue -> Box(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxSize(),
                    ) {
                        PlaybackQueueScreen(
                            state = playbackState,
                            artworkClient = artworkClient,
                            onSelect = playbackViewModel::selectQueueItem,
                            onRemove = playbackViewModel::removeQueueItem,
                            onMove = playbackViewModel::moveQueueItem,
                            onToggleShuffle = playbackViewModel::toggleShuffle,
                            onCycleRepeat = playbackViewModel::cycleRepeatMode,
                            onClear = playbackViewModel::clearQueue,
                            onSave = playbackViewModel::saveQueue,
                            onLoad = playbackViewModel::loadQueue,
                        )
                    }
                    Destination.Home -> HomeScreen(
                        state = libraryState,
                        artworkClient = artworkClient,
                        onAlbumClick = libraryViewModel::openAlbum,
                        onOpenLibrarySection = { tab ->
                            libraryTab = tab
                            destination = Destination.Library
                        },
                        onRetry = libraryViewModel::refresh,
                        onPairAgain = ::disconnect,
                    )
                    Destination.Library -> LibraryScreen(
                        state = libraryState,
                        artworkClient = artworkClient,
                        currentTrackId = playbackState.mediaId,
                        section = libraryTab,
                        onSectionChange = { libraryTab = it },
                        onAlbumClick = libraryViewModel::openAlbum,
                        onArtistClick = libraryViewModel::openArtist,
                        onTrackClick = ::playQueue,
                        onTrackDetail = libraryViewModel::openTrack,
                        onSearch = libraryViewModel::search,
                        onLoadSection = libraryViewModel::ensureSectionLoaded,
                        onLoadMore = libraryViewModel::loadMore,
                        onLoadMoreSearch = libraryViewModel::loadMoreSearch,
                        onRetry = libraryViewModel::refresh,
                        onPairAgain = ::disconnect,
                    )
                }
            }
        }
    }

    if (showServerInfo) {
        ServerInfoDialog(
            credentials = credentials,
            state = libraryState,
            reachability = reachability,
            onRefresh = libraryViewModel::refresh,
            onDisconnect = ::disconnect,
            onDismiss = { showServerInfo = false },
        )
    }
}

@Composable
private fun ConnectionStatusDot(reachability: ServerReachability) {
    val color = when (reachability) {
        ServerReachability.Connected -> MaterialTheme.colorScheme.primary
        ServerReachability.Unauthorized, ServerReachability.Unreachable -> MaterialTheme.colorScheme.error
        ServerReachability.Loading -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val description = when (reachability) {
        ServerReachability.Connected -> "Connected to server"
        ServerReachability.Unauthorized -> "Device access expired"
        ServerReachability.Unreachable -> "Server unreachable"
        ServerReachability.Loading -> "Connecting to server"
    }
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .size(40.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun ServerInfoDialog(
    credentials: DeviceCredentials,
    state: LibraryUiState,
    reachability: ServerReachability,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status = when (reachability) {
        ServerReachability.Connected -> "Connected"
        ServerReachability.Unauthorized -> "Access expired"
        ServerReachability.Unreachable -> "Unreachable"
        ServerReachability.Loading -> "Connecting"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server") },
        text = {
            Column {
                Text("${credentials.serverName} ${state.status?.version ?: credentials.serverVersion}")
                Text(
                    text = credentials.serverUrl,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = status,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (reachability == ServerReachability.Connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (credentials.serverUrl.startsWith("http://", ignoreCase = true)) {
                    Text(
                        text = "HTTP is not encrypted. Use it only on a trusted local network.",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                    onRefresh()
                    onDismiss()
                }) {
                Text("Refresh")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDisconnect()
                },
            ) {
                Text("Disconnect")
            }
        },
    )
}

internal fun serverReachability(state: LibraryUiState): ServerReachability = when {
    state.authenticationFailed -> ServerReachability.Unauthorized
    state.loading && state.library == null -> ServerReachability.Loading
    state.error != null && state.library == null -> ServerReachability.Unreachable
    state.library != null -> ServerReachability.Connected
    else -> ServerReachability.Unreachable
}
