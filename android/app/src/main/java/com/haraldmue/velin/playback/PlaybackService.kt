package com.haraldmue.velin.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.haraldmue.velin.MainActivity
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.VelinApiClient
import com.haraldmue.velin.data.cache.CacheNamespace
import com.haraldmue.velin.data.cache.LibraryCacheDatabase
import com.haraldmue.velin.data.cache.LibraryCacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Owns background audio playback and the Android Auto media library. */
class PlaybackService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var artworkBitmapLoader: AuthenticatedArtworkBitmapLoader? = null
    private var artworkMetadataEnricher: CurrentArtworkMetadataEnricher? = null
    private var libraryClient: VelinApiClient? = null
    private var playbackHttpClient: OkHttpClient? = null
    private var networkLossCanceller: NetworkLossCanceller? = null
    private var catalog: AutoLibraryCatalog? = null
    private var resolver: AutoPlaybackResolver? = null
    private var libraryFingerprint: String? = null
    private var snapshotWatchJob: Job? = null
    private var checkpointCoordinator: PlaybackCheckpointCoordinator? = null
    @Volatile
    private var checkpointNamespace: String? = null
    private var checkpointListener: Player.Listener? = null
    private var checkpointProgressJob: Job? = null
    private var playbackRestoreJob: Job? = null
    @Volatile
    private var playbackResumeLoader: PlaybackResumeLoader? = null
    private val playbackRestoreGuard = PlaybackRestoreGuard()
    private val libraryLock = Any()
    private val credentialStore by lazy { AndroidKeyStoreCredentialStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkpointMainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val streamClient = PlaybackDataSourceFactory.playbackHttpClient()
        playbackHttpClient = streamClient
        val playerBuilder = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(createPlaybackLoadControl())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(PlaybackDataSourceFactory.reloading(credentialStore, streamClient)),
            )
        artworkBitmapLoader = AuthenticatedArtworkBitmapLoader(this, credentialStore)
        syncPairedLibrary()
        val player = playerBuilder.build()
            .apply {
                setHandleAudioBecomingNoisy(true)
            }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sessionBuilder = MediaLibrarySession.Builder(
            this,
            player,
            AutoLibrarySessionCallback(
                scope = serviceScope,
                catalog = { catalog },
                resolver = { resolver },
                artworkLoader = { artworkBitmapLoader },
                playbackResumption = ::loadPlaybackResumption,
                beforeLibraryAccess = ::syncPairedLibrary,
                beforeQueueMutation = ::cancelPendingPlaybackRestore,
                afterQueueMutation = ::reconcilePlaybackCheckpoint,
                beforePlaybackResumption = ::cancelPendingPlaybackRestore,
                trustedControllerPackage = packageName,
                clearPlaybackResume = ::clearPlaybackResumeCheckpoint,
            ),
        )
            .setSessionActivity(sessionActivity)
        artworkBitmapLoader?.let(sessionBuilder::setBitmapLoader)
        mediaSession = sessionBuilder.build()
        artworkBitmapLoader?.let { loader ->
            artworkMetadataEnricher = CurrentArtworkMetadataEnricher(player, loader)
        }
        syncPlaybackCredentialScope(credentialStore.load())
        if (!isLikelyEmulator()) {
            networkLossCanceller = NetworkLossCanceller(this, ::dropStaleNetwork).also { it.start() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onDestroy() {
        networkLossCanceller?.stop()
        networkLossCanceller = null
        snapshotWatchJob?.cancel()
        snapshotWatchJob = null
        val preserveEmptyCheckpoint = playbackRestoreJob?.isActive == true
        cancelPendingPlaybackRestore()
        stopPlaybackCheckpointing(mediaSession?.player, preserveEmptyCheckpoint)
        checkpointMainScope.cancel()
        serviceScope.cancel()
        playbackResumeLoader = null
        artworkMetadataEnricher?.close()
        artworkMetadataEnricher = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        artworkBitmapLoader?.close()
        artworkBitmapLoader = null
        libraryClient = null
        playbackHttpClient = null
        catalog = null
        resolver = null
        libraryFingerprint = null
        super.onDestroy()
    }

    private fun syncPairedLibrary() {
        val credentials = credentialStore.load()
        val fingerprint = credentials?.libraryFingerprint()
        val notify: Boolean
        synchronized(libraryLock) {
            if (fingerprint == libraryFingerprint) {
                notify = false
                return@synchronized
            }
            libraryFingerprint = fingerprint
            snapshotWatchJob?.cancel()
            snapshotWatchJob = null
            libraryClient?.cancelInFlight()
            libraryClient = null
            if (credentials == null) {
                catalog = null
                resolver = null
                artworkBitmapLoader?.cancelInFlight()
                notify = true
                return@synchronized
            }
            val gateway = VelinApiClient(credentials)
            libraryClient = gateway
            val cache = LibraryCacheRepository(
                database = LibraryCacheDatabase.get(this),
                gateway = gateway,
                credentials = credentials,
                discoveryStartID = CacheNamespace.from(credentials),
            )
            val homeAlbums = CachedAutoHomeAlbums(cache)
            catalog = AutoLibraryCatalog(
                gateway,
                ArtworkRequestPolicy(credentials.serverUrl),
                homeAlbums,
            )
            resolver = AutoPlaybackResolver(gateway, PlaybackMediaItemFactory(credentials), homeAlbums)
            snapshotWatchJob = serviceScope.launch {
                cache.activeSnapshot.collect {
                    notifyBrowseTreeChanged()
                }
            }
            notify = true
        }
        mainHandler.post { syncPlaybackCredentialScope(credentials) }
        if (notify) {
            notifyBrowseTreeChanged()
        }
    }

    private fun notifyBrowseTreeChanged() {
        mainHandler.post {
            val session = mediaSession ?: return@post
            AutoBrowsableCategoryIds.forEach { mediaId ->
                session.notifyChildrenChanged(mediaId, Int.MAX_VALUE, null)
            }
        }
    }

    private fun syncPlaybackCredentialScope(credentials: DeviceCredentials?) {
        val player = mediaSession?.player ?: return
        val namespace = credentials?.let(CacheNamespace::from)
        if (namespace == checkpointNamespace) return

        cancelPendingPlaybackRestore()
        checkpointProgressJob?.cancel()
        checkpointProgressJob = null
        checkpointListener?.let(player::removeListener)
        checkpointListener = null
        checkpointCoordinator?.let { coordinator ->
            coordinator.delete()
            coordinator.close()
        }
        checkpointCoordinator = null
        checkpointNamespace = null
        playbackResumeLoader = null
        player.stop()
        player.clearMediaItems()

        if (credentials == null || namespace == null) return
        val store = PlaybackResumeStore(
            file = playbackResumeFile(filesDir, namespace),
            expectedNamespace = namespace,
        )
        val loader = PlaybackResumeLoader(store, PlaybackMediaItemFactory(credentials))
        playbackResumeLoader = loader
        startPlaybackCheckpointing(player, namespace, store)
        startPlaybackRestoration(player, namespace, loader)
    }

    private fun startPlaybackCheckpointing(
        player: Player,
        namespace: String,
        store: PlaybackResumeStore,
    ) {
        val coordinator = PlaybackCheckpointCoordinator(store)
        val listener = object : Player.Listener {
            override fun onEvents(currentPlayer: Player, events: Player.Events) {
                if (events.containsAny(PlaybackCheckpointEvents)) {
                    checkpointPlayback(currentPlayer, namespace, coordinator)
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) {
                    syncCheckpointProgress(currentPlayer, namespace, coordinator)
                }
            }
        }
        checkpointCoordinator = coordinator
        checkpointNamespace = namespace
        checkpointListener = listener
        player.addListener(listener)
    }

    private fun startPlaybackRestoration(
        player: Player,
        namespace: String,
        loader: PlaybackResumeLoader,
    ) {
        val restoreToken = playbackRestoreGuard.begin()
        playbackRestoreJob = checkpointMainScope.launch {
            val loaded = loader.load() ?: return@launch
            val record = loaded.record
            val mediaItems = loaded.mediaItems
            if (!playbackRestoreGuard.isCurrent(restoreToken) ||
                checkpointNamespace != namespace ||
                mediaSession?.player !== player ||
                player.mediaItemCount != 0
            ) {
                return@launch
            }
            player.setMediaItems(mediaItems, record.currentIndex, record.positionMs)
            player.shuffleModeEnabled = record.shuffleEnabled
            player.repeatMode = when (record.repeatMode) {
                PlaybackResumeRepeatMode.Off -> Player.REPEAT_MODE_OFF
                PlaybackResumeRepeatMode.All -> Player.REPEAT_MODE_ALL
                PlaybackResumeRepeatMode.One -> Player.REPEAT_MODE_ONE
            }
            player.pause()
        }
    }

    private fun reconcilePlaybackCheckpoint() {
        cancelPendingPlaybackRestore()
        val player = mediaSession?.player ?: return
        val namespace = checkpointNamespace ?: return
        val coordinator = checkpointCoordinator ?: return
        checkpointPlayback(player, namespace, coordinator)
    }

    private suspend fun clearPlaybackResumeCheckpoint() {
        val coordinator = withContext(Dispatchers.Main.immediate) {
            cancelPendingPlaybackRestore()
            checkpointCoordinator
        }
        coordinator?.deleteAndAwait()
    }

    private suspend fun loadPlaybackResumption(): LoadedPlaybackResume? {
        val credentials = credentialStore.load() ?: return null
        if (CacheNamespace.from(credentials) != checkpointNamespace) return null
        return playbackResumeLoader?.load()
    }

    private fun cancelPendingPlaybackRestore() {
        playbackRestoreGuard.cancel()
        playbackRestoreJob?.cancel()
        playbackRestoreJob = null
    }

    private fun stopPlaybackCheckpointing(player: Player?, preserveEmptyCheckpoint: Boolean) {
        checkpointProgressJob?.cancel()
        checkpointProgressJob = null
        checkpointListener?.let { listener -> player?.removeListener(listener) }
        checkpointListener = null
        val coordinator = checkpointCoordinator ?: return
        val namespace = checkpointNamespace
        checkpointCoordinator = null
        checkpointNamespace = null
        if ((player == null || player.mediaItemCount == 0) && !preserveEmptyCheckpoint) {
            coordinator.delete()
        } else if (player != null && namespace != null) {
            checkpointPlayback(player, namespace, coordinator)
        }
        // Listener removal prevents player.release() from replacing the final state with an empty queue.
        coordinator.close()
    }

    private fun checkpointPlayback(
        player: Player,
        namespace: String,
        coordinator: PlaybackCheckpointCoordinator,
    ) {
        if (player.mediaItemCount == 0) {
            coordinator.delete()
            return
        }
        runCatching { capturePlaybackResumeRecord(player, namespace, System.currentTimeMillis()) }
            .getOrNull()
            ?.let(coordinator::checkpoint)
    }

    private fun syncCheckpointProgress(
        player: Player,
        namespace: String,
        coordinator: PlaybackCheckpointCoordinator,
    ) {
        if (!shouldPollPlaybackCheckpoint(player.isPlaying)) {
            checkpointProgressJob?.cancel()
            checkpointProgressJob = null
            return
        }
        if (checkpointProgressJob?.isActive == true) return
        checkpointProgressJob = checkpointMainScope.launch {
            while (isActive) {
                delay(PlaybackCheckpointPositionIntervalMs)
                val currentPlayer = mediaSession?.player ?: break
                if (!shouldPollPlaybackCheckpoint(currentPlayer.isPlaying)) break
                checkpointPlayback(currentPlayer, namespace, coordinator)
            }
            checkpointProgressJob = null
        }
    }

    private fun Player.Events.containsAny(eventIds: IntArray): Boolean =
        eventIds.any(::contains)

    private fun dropStaleNetwork() {
        libraryClient?.cancelInFlight()
        playbackHttpClient?.let { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
        artworkBitmapLoader?.cancelInFlight()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        if (connectivity.activeNetwork != null) {
            return
        }
        mainHandler.post {
            val player = mediaSession?.player ?: return@post
            if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
                player.stop()
            }
        }
    }

    private companion object {
        private fun createPlaybackLoadControl(): DefaultLoadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    PlaybackMinBufferMs,
                    PlaybackMaxBufferMs,
                    PlaybackBufferForPlaybackMs,
                    PlaybackBufferForPlaybackAfterRebufferMs,
                )
                .build()
    }
}

internal fun DeviceCredentials.libraryFingerprint(): String =
    "$deviceId\u0000$serverUrl\u0000$token"

// ExoPlayer defaults are 50 s min/max. One extra minute of headroom still helps
// self-hosted FLAC over LAN without keeping radio/RAM on a five-minute fill.
internal const val PlaybackMinBufferMs = 60_000
internal const val PlaybackMaxBufferMs = 120_000
internal const val PlaybackBufferForPlaybackMs = 2_500
internal const val PlaybackBufferForPlaybackAfterRebufferMs = 5_000
internal const val PlaybackCheckpointPositionIntervalMs = 5_000L

internal fun shouldPollPlaybackCheckpoint(isPlaying: Boolean): Boolean = isPlaying

private val PlaybackCheckpointEvents = intArrayOf(
    Player.EVENT_TIMELINE_CHANGED,
    Player.EVENT_MEDIA_ITEM_TRANSITION,
    Player.EVENT_POSITION_DISCONTINUITY,
    Player.EVENT_PLAYBACK_STATE_CHANGED,
    Player.EVENT_IS_PLAYING_CHANGED,
    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
    Player.EVENT_REPEAT_MODE_CHANGED,
)
