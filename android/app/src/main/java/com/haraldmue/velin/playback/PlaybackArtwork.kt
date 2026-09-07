package com.haraldmue.velin.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Android Auto's compact dashboard loads `artworkUri` itself and cannot
 * authenticate Velin's token-free cover URLs. Publish a bitmap and keep the
 * URL only in extras for Compose.
 */
internal object PlaybackArtwork {
    const val EXTRA_URI = "com.haraldmue.velin.ARTWORK_URI"
    const val EXTRA_EMBEDDED = "com.haraldmue.velin.EMBEDDED_CURRENT_ARTWORK"

    fun MediaMetadata.playbackArtworkUrl(): String? =
        artworkUri?.toString() ?: extras?.getString(EXTRA_URI)

    fun MediaItem.withEmbeddedArtwork(data: ByteArray): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle())
        mediaMetadata.artworkUri?.toString()?.let { extras.putString(EXTRA_URI, it) }
        extras.putBoolean(EXTRA_EMBEDDED, true)
        val metadata = mediaMetadata.buildUpon()
            .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setArtworkUri(null)
            .setExtras(extras)
            .build()
        return buildUpon().setMediaMetadata(metadata).build()
    }

    fun MediaItem.withoutPublicArtworkUri(): MediaItem {
        if (mediaMetadata.artworkUri == null) return this
        val extras = Bundle(mediaMetadata.extras ?: Bundle())
        mediaMetadata.artworkUri?.toString()?.let { extras.putString(EXTRA_URI, it) }
        extras.putBoolean(EXTRA_EMBEDDED, true)
        val metadata = mediaMetadata.buildUpon()
            .setArtworkUri(null)
            .setExtras(extras)
            .build()
        return buildUpon().setMediaMetadata(metadata).build()
    }

    fun MediaItem.withoutEmbeddedArtwork(): MediaItem {
        if (mediaMetadata.extras?.getBoolean(EXTRA_EMBEDDED) != true) return this
        val extras = Bundle(mediaMetadata.extras).apply { remove(EXTRA_EMBEDDED) }
        val restored = extras.getString(EXTRA_URI)?.let(Uri::parse)
        val metadata = mediaMetadata.buildUpon()
            .setArtworkData(null, null)
            .setArtworkUri(restored)
            .setExtras(extras)
            .build()
        return buildUpon().setMediaMetadata(metadata).build()
    }
}
