package com.haraldmue.velin.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.haraldmue.velin.playback.PlaybackArtwork.playbackArtworkUrl
import com.haraldmue.velin.playback.PlaybackArtwork.withEmbeddedArtwork
import com.haraldmue.velin.playback.PlaybackArtwork.withoutEmbeddedArtwork
import com.haraldmue.velin.playback.PlaybackArtwork.withoutPublicArtworkUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackArtworkTest {
    @Test
    fun embeddedArtworkClearsPublicUriAndKeepsItInExtras() {
        val original = "https://velin.example/api/v1/covers/cover-1/512"
        val item = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Song")
                    .setArtworkUri(Uri.parse(original))
                    .build(),
            )
            .build()
        val data = byteArrayOf(1, 2, 3)

        val embedded = item.withEmbeddedArtwork(data)

        assertNull(embedded.mediaMetadata.artworkUri)
        assertArrayEquals(data, embedded.mediaMetadata.artworkData)
        assertEquals(original, embedded.mediaMetadata.playbackArtworkUrl())
        assertEquals(true, embedded.mediaMetadata.extras?.getBoolean(PlaybackArtwork.EXTRA_EMBEDDED))
    }

    @Test
    fun withoutPublicArtworkUriKeepsExistingBitmapAndExtrasUrl() {
        val original = "https://velin.example/api/v1/covers/cover-1/512"
        val data = byteArrayOf(1, 2, 3)
        val item = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Song")
                    .setArtworkUri(Uri.parse(original))
                    .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build(),
            )
            .build()

        val stripped = item.withoutPublicArtworkUri()

        assertNull(stripped.mediaMetadata.artworkUri)
        assertArrayEquals(data, stripped.mediaMetadata.artworkData)
        assertEquals(original, stripped.mediaMetadata.playbackArtworkUrl())
        assertEquals(true, stripped.mediaMetadata.extras?.getBoolean(PlaybackArtwork.EXTRA_EMBEDDED))
    }

    @Test
    fun strippingEmbeddedArtworkRestoresPublicUri() {
        val original = "https://velin.example/api/v1/covers/cover-1/512"
        val item = MediaItem.Builder()
            .setMediaId("track-1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Song")
                    .setArtworkUri(Uri.parse(original))
                    .build(),
            )
            .build()

        val restored = item.withEmbeddedArtwork(byteArrayOf(1, 2, 3)).withoutEmbeddedArtwork()

        assertEquals(original, restored.mediaMetadata.artworkUri.toString())
        assertNull(restored.mediaMetadata.artworkData)
        assertEquals(original, restored.mediaMetadata.playbackArtworkUrl())
        assertEquals(false, restored.mediaMetadata.extras?.getBoolean(PlaybackArtwork.EXTRA_EMBEDDED))
    }
}
