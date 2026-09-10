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
import kotlinx.coroutines.launch
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
    private val libraryLock = Any()
    private val credentialStore by lazy { AndroidKeyStoreCredentialStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                serviceScope,
                { catalog },
                { resolver },
                { artworkBitmapLoader },
                ::syncPairedLibrary,
            ),
        )
            .setSessionActivity(sessionActivity)
        artworkBitmapLoader?.let(sessionBuilder::setBitmapLoader)
        mediaSession = sessionBuilder.build()
        artworkBitmapLoader?.let { loader ->
            artworkMetadataEnricher = CurrentArtworkMetadataEnricher(player, loader)
        }
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
        serviceScope.cancel()
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
