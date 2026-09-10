package com.haraldmue.velin.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBrowseArtworkTest {
    @Test
    fun contentUrisAreLeftForAndroidAutoToFetch() = runTest {
        val item = albumItem("content://com.haraldmue.velin.artwork/covers/cover-1/256")

        val unchanged = embedBrowseArtworkItem(item, 0) { error("should not load") }

        assertSame(item, unchanged)
        assertEquals(
            "content://com.haraldmue.velin.artwork/covers/cover-1/256",
            unchanged.mediaMetadata.artworkUri.toString(),
        )
        assertNull(unchanged.mediaMetadata.artworkData)
    }

    @Test
    fun embedsAuthenticatedBitmapAndClearsPublicUri() = runTest {
        val item = albumItem("https://velin.example/api/v1/covers/cover-1/256")
        val data = byteArrayOf(9, 8, 7)

        val embedded = embedBrowseArtworkItem(item, 0) { data }

        assertNull(embedded.mediaMetadata.artworkUri)
        assertArrayEquals(data, embedded.mediaMetadata.artworkData)
    }

    @Test
    fun skipsItemsBeyondTheBrowseCap() = runTest {
        val item = albumItem("https://velin.example/api/v1/covers/cover-1/256")

        val skipped = embedBrowseArtworkItem(item, MaxEmbeddedBrowseArtworkItems) { error("should not load") }

        assertSame(item, skipped)
        assertEquals(
            "https://velin.example/api/v1/covers/cover-1/256",
            skipped.mediaMetadata.artworkUri.toString(),
        )
        assertNull(skipped.mediaMetadata.artworkData)
    }

    @Test
    fun loadFailuresLeaveTheOriginalItem() = runTest {
        val item = albumItem("https://velin.example/api/v1/covers/cover-1/256")

        val unchanged = embedBrowseArtworkItem(item, 0) { error("offline") }

        assertSame(item, unchanged)
    }

    @Test
    fun oversizedArtworkLeavesTheOriginalItem() = runTest {
        val item = albumItem("https://velin.example/api/v1/covers/cover-1/256")

        val unchanged = embedBrowseArtworkItem(item, 0) {
            ByteArray(MaxEmbeddedBrowseArtworkBytes + 1)
        }

        assertSame(item, unchanged)
    }

    @Test
    fun slowLoadsLeaveTheOriginalItem() = runTest {
        val item = albumItem("https://velin.example/api/v1/covers/cover-1/256")

        val result = embedBrowseArtwork(listOf(item)) {
            delay(BrowseArtworkLoadTimeoutMs + 1)
            byteArrayOf(1)
        }

        assertSame(item, result.single())
        assertNull(result.single().mediaMetadata.artworkData)
    }

    @Test
    fun embedsABoundedPrefixInParallel() = runTest {
        val items = List(3) { index ->
            albumItem("https://velin.example/api/v1/covers/cover-$index/256")
        }

        val embedded = embedBrowseArtwork(items) { uri ->
            byteArrayOf(uri.lastPathSegment!!.first().code.toByte())
        }

        assertEquals(3, embedded.size)
        embedded.forEach { item ->
            assertNull(item.mediaMetadata.artworkUri)
            assertEquals(1, item.mediaMetadata.artworkData?.size)
        }
    }

    private fun albumItem(artwork: String): MediaItem = MediaItem.Builder()
        .setMediaId("album-1")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Album")
                .setArtworkUri(Uri.parse(artwork))
                .build(),
        )
        .build()
}
