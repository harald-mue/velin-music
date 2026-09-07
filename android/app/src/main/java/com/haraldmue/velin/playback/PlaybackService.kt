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
import com.haraldmue.velin.data.VelinApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/** Owns background audio playback and the Android Auto media library. */
class PlaybackService : MediaLibraryService() {
    private var mediaSession: MediaLibrarySession? = null
    private var artworkBitmapLoader: AuthenticatedArtworkBitmapLoader? = null
    private var artworkMetadataEnricher: CurrentArtworkMetadataEnricher? = null
    private var libraryClient: VelinApiClient? = null
    private var playbackHttpClient: OkHttpClient? = null
    private var networkLossCanceller: NetworkLossCanceller? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val playerBuilder = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(createPlaybackLoadControl())
        val credentialStore = AndroidKeyStoreCredentialStore(this)
        val credentials = credentialStore.load()
        var catalog: AutoLibraryCatalog? = null
        var resolver: AutoPlaybackResolver? = null
        if (credentials != null) {
            val streamClient = PlaybackDataSourceFactory.playbackHttpClient()
            playbackHttpClient = streamClient
            playerBuilder.setMediaSourceFactory(
                DefaultMediaSourceFactory(PlaybackDataSourceFactory.reloading(credentialStore, streamClient)),
            )
            artworkBitmapLoader = AuthenticatedArtworkBitmapLoader(credentials)
            val gateway = VelinApiClient(credentials)
            libraryClient = gateway
            catalog = AutoLibraryCatalog(gateway, ArtworkRequestPolicy(credentials.serverUrl))
            resolver = AutoPlaybackResolver(gateway, PlaybackMediaItemFactory(credentials))
        }
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
            AutoLibrarySessionCallback(serviceScope, catalog, resolver, artworkBitmapLoader),
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
        super.onDestroy()
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
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build()

        // ExoPlayer defaults are 50 s min/max; longer windows help self-hosted FLAC over LAN.
        private const val MIN_BUFFER_MS = 120_000
        private const val MAX_BUFFER_MS = 300_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
    }
}
