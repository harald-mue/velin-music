package com.haraldmue.velin.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.haraldmue.velin.data.AndroidKeyStoreCredentialStore

/** Owns background audio playback and exposes it through an Android media session. */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var artworkBitmapLoader: AuthenticatedArtworkBitmapLoader? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val playerBuilder = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(createPlaybackLoadControl())
        val credentials = AndroidKeyStoreCredentialStore(this).load()
        credentials?.let {
            playerBuilder.setMediaSourceFactory(
                DefaultMediaSourceFactory(PlaybackDataSourceFactory(it)),
            )
            artworkBitmapLoader = AuthenticatedArtworkBitmapLoader(it)
        }
        val player = playerBuilder.build()
            .apply {
                setHandleAudioBecomingNoisy(true)
            }
        val sessionBuilder = MediaSession.Builder(this, player)
        artworkBitmapLoader?.let(sessionBuilder::setBitmapLoader)
        mediaSession = sessionBuilder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        artworkBitmapLoader?.close()
        artworkBitmapLoader = null
        super.onDestroy()
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
