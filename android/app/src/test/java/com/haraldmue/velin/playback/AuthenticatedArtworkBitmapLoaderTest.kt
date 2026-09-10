package com.haraldmue.velin.playback

import android.net.Uri
import com.haraldmue.velin.data.ArtworkHttpIdleKeepAliveSeconds
import com.haraldmue.velin.data.CredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import java.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthenticatedArtworkBitmapLoaderTest {
    @Test
    fun embeddedArtworkUsesAuthenticatedBoundedVariant() {
        MockWebServer().use { server ->
            val expected = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
            server.enqueue(MockResponse().setBody(Buffer().write(expected)))
            val loader = AuthenticatedArtworkBitmapLoader(
                DeviceCredentials(
                    serverUrl = server.url("/").toString(),
                    deviceId = "device",
                    token = "secret-token",
                    serverName = "Velin",
                    serverVersion = "test",
                ),
            )
            try {
                val uri = Uri.parse(server.url("/api/v1/covers/cover-1/512").toString())

                assertArrayEquals(expected, loader.loadEmbeddedArtworkData(uri).get())
                val request = server.takeRequest()
                assertEquals("/api/v1/covers/cover-1/256", request.path)
                assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            } finally {
                loader.close()
            }
        }
    }

    @Test
    fun storeBackedLoaderReadsTheSharedCoilDiskCacheBeforeNetwork() {
        MockWebServer().use { server ->
            val expected = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
            val credentials = DeviceCredentials(
                serverUrl = server.url("/").toString(),
                deviceId = "device",
                token = "cached-token",
                serverName = "Velin",
                serverVersion = "test",
            )
            val context = RuntimeEnvironment.getApplication()
            val uri = server.url("/api/v1/covers/cover-1/256").toString()
            val cacheDirectory = context.cacheDir.resolve("artwork").apply { mkdirs() }
            val cacheFile = cacheDirectory.resolve("${artworkDiskCacheKey(uri)}.1")
            cacheFile.writeBytes(expected)
            val loader = AuthenticatedArtworkBitmapLoader(context, MutableCredentialStore(credentials))
            try {
                assertArrayEquals(expected, loader.loadEmbeddedArtworkData(Uri.parse(uri)).get())
                assertEquals(0, server.requestCount)
            } finally {
                loader.close()
                cacheFile.delete()
            }
        }
    }

    @Test
    fun storeBackedLoaderUsesTheLatestToken() {
        MockWebServer().use { server ->
            val expected = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            )
            server.enqueue(MockResponse().setBody(Buffer().write(expected)))
            server.enqueue(MockResponse().setBody(Buffer().write(expected)))
            val store = MutableCredentialStore(
                DeviceCredentials(
                    serverUrl = server.url("/").toString(),
                    deviceId = "device",
                    token = "first-token",
                    serverName = "Velin",
                    serverVersion = "test",
                ),
            )
            val loader = AuthenticatedArtworkBitmapLoader(RuntimeEnvironment.getApplication(), store)
            try {
                val uri = Uri.parse(server.url("/api/v1/covers/cover-1/256").toString())
                loader.loadEmbeddedArtworkData(uri).get()
                assertEquals("Bearer first-token", server.takeRequest().getHeader("Authorization"))

                store.save(store.load()!!.copy(token = "second-token"))
                loader.loadEmbeddedArtworkData(uri).get()
                assertEquals("Bearer second-token", server.takeRequest().getHeader("Authorization"))
            } finally {
                loader.close()
            }
        }
    }

    @Test
    fun artworkSourceLimitsRejectEmptyOrOversizedFrames() {
        assertEquals(true, artworkSourceIsWithinLimits(byteCount = 128, maxBytes = 1_024, width = 256, height = 256))
        assertEquals(false, artworkSourceIsWithinLimits(byteCount = 0, maxBytes = 1_024, width = 256, height = 256))
        assertEquals(false, artworkSourceIsWithinLimits(byteCount = 2_048, maxBytes = 1_024, width = 256, height = 256))
        assertEquals(false, artworkSourceIsWithinLimits(byteCount = 128, maxBytes = 1_024, width = 0, height = 256))
        assertEquals(false, artworkSourceIsWithinLimits(byteCount = 128, maxBytes = 1_024, width = 10_000, height = 10_000))
        assertEquals(1L, ArtworkLoaderKeepAliveSeconds)
        assertEquals(30L, ArtworkHttpIdleKeepAliveSeconds)
    }
}

private class MutableCredentialStore(
    private var credentials: DeviceCredentials?,
) : CredentialStore {
    override fun load(): DeviceCredentials? = credentials
    override fun save(credentials: DeviceCredentials) {
        this.credentials = credentials
    }
    override fun clear() {
        credentials = null
    }
}
