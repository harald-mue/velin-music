package com.haraldmue.velin.playback

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoArtworkUrisTest {
    @Test
    fun browseCoverUrisAreOpaqueContentUris() {
        val uri = requireNotNull(AutoArtworkUris.uri("cover-1"))
        assertEquals(
            "content://com.haraldmue.velin.artwork/covers/cover-1/256",
            uri.toString(),
        )
        assertEquals(AutoArtworkRef("cover-1", 256), AutoArtworkUris.parse(uri))
        val hashed = requireNotNull(
            AutoArtworkUris.uri("1d36c45072692d1bf596054a11dd5e9760a4cc2661ae3a9faa00f519efc1b417"),
        )
        assertEquals(
            AutoArtworkRef("1d36c45072692d1bf596054a11dd5e9760a4cc2661ae3a9faa00f519efc1b417", 256),
            AutoArtworkUris.parse(hashed),
        )
    }

    @Test
    fun parseRejectsHttpAndUnsafeIds() {
        assertNull(AutoArtworkUris.parse(Uri.parse("https://velin.example/api/v1/covers/cover-1/256")))
        assertNull(AutoArtworkUris.parse(Uri.parse("content://com.haraldmue.velin.artwork/covers/../256")))
        assertNull(AutoArtworkUris.uri("../cover"))
        assertNull(AutoArtworkUris.uri(""))
    }

    @Test
    fun coilCacheFileUsesTheSha256OfThePairedCoverUrl() {
        val directory = kotlin.io.path.createTempDirectory("velin-art").toFile()
        val credentials = com.haraldmue.velin.data.DeviceCredentials(
            serverUrl = "http://localhost:8080",
            deviceId = "device",
            token = "secret",
            serverName = "Velin",
            serverVersion = "test",
        )
        val url = com.haraldmue.velin.data.ArtworkRequestPolicy(credentials.serverUrl)
            .urlFor("cover1", 256)
        val expected = directory.resolve("${artworkDiskCacheKey(url)}.1")
        expected.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(expected, coilArtworkCacheFile(directory, credentials, "cover1", 256))
        expected.delete()
        directory.delete()
    }

    @Test
    fun fetchedArtworkIsWrittenIntoTheCoilCacheFile() {
        val directory = kotlin.io.path.createTempDirectory("velin-art-write").toFile()
        val credentials = com.haraldmue.velin.data.DeviceCredentials(
            serverUrl = "http://localhost:8080",
            deviceId = "device",
            token = "secret",
            serverName = "Velin",
            serverVersion = "test",
        )
        val url = com.haraldmue.velin.data.ArtworkRequestPolicy(credentials.serverUrl)
            .urlFor("cover1", 256)
        val bytes = byteArrayOf(9, 8, 7, 6)
        val written = writeCoilArtworkCache(directory, url, bytes)
        assertEquals(directory.resolve("${artworkDiskCacheKey(url)}.1"), written)
        assertEquals(bytes.toList(), written.readBytes().toList())
        assertEquals(written, coilArtworkCacheFile(directory, credentials, "cover1", 256))
        directory.deleteRecursively()
    }
}
