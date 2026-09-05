package com.haraldmue.velin.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.haraldmue.velin.data.CredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.Track
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackDataSourceFactoryTest {
    @Test
    fun mediaItemContainsSafeStreamUrlAndMetadata() {
        val credentials = credentials("https://velin.example/music")
        val track = testTrack()

        val item = PlaybackMediaItemFactory(credentials).create(track)

        assertEquals("track-1", item.mediaId)
        assertEquals("https://velin.example/music/api/v1/tracks/track-1/stream", item.localConfiguration?.uri.toString())
        assertFalse(item.localConfiguration?.uri.toString().contains(credentials.token))
        assertEquals("Track", item.mediaMetadata.title.toString())
        assertEquals("Artist", item.mediaMetadata.artist.toString())
        assertEquals(
            "https://velin.example/music/api/v1/covers/cover-1",
            item.mediaMetadata.artworkUri.toString(),
        )
        assertFalse(item.mediaMetadata.artworkUri.toString().contains(credentials.token))
    }

    @Test
    fun dataSourceAddsBearerHeaderAndReadsOnlyServerStream() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("audio-bytes"))
            val credentials = credentials(server.url("/music").toString().trimEnd('/'))
            val item = PlaybackMediaItemFactory(credentials).create(testTrack())
            val dataSource = PlaybackDataSourceFactory(credentials).createDataSource()

            val output = ByteArrayOutputStream()
            try {
                dataSource.open(DataSpec.Builder().setUri(item.localConfiguration!!.uri).build())
                val buffer = ByteArray(16)
                while (true) {
                    val count = dataSource.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    output.write(buffer, 0, count)
                }
            } finally {
                dataSource.close()
            }

            assertEquals("audio-bytes", output.toString(Charsets.UTF_8.name()))
            val request = server.takeRequest()
            assertEquals("/music/api/v1/tracks/track-1/stream", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun reloadingFactoryUsesCredentialsSavedAfterTheServiceStarted() {
        MockWebServer().use { oldServer ->
            MockWebServer().use { newServer ->
                val oldCredentials = credentials(oldServer.url("/").toString().trimEnd('/'))
                val newCredentials = credentials(
                    newServer.url("/velin").toString().trimEnd('/'),
                    token = "new-token",
                )
                val store = MutableCredentialStore(oldCredentials)
                val factory = PlaybackDataSourceFactory.reloading(store)

                store.credentials = newCredentials
                newServer.enqueue(MockResponse().setBody("new-audio"))
                val item = PlaybackMediaItemFactory(newCredentials).create(testTrack())
                val dataSource = factory.createDataSource()
                try {
                    dataSource.open(DataSpec.Builder().setUri(item.localConfiguration!!.uri).build())
                } finally {
                    dataSource.close()
                }

                val request = newServer.takeRequest()
                assertEquals("/velin/api/v1/tracks/track-1/stream", request.path)
                assertEquals("Bearer new-token", request.getHeader("Authorization"))
                assertEquals(0, oldServer.requestCount)
            }
        }
    }

    @Test
    fun dataSourceDoesNotFollowRedirectsThatCouldLeakAuthorization() {
        MockWebServer().use { server ->
            MockWebServer().use { otherServer ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", otherServer.url("/capture")),
                )
                val credentials = credentials(server.url("/").toString().trimEnd('/'))
                val item = PlaybackMediaItemFactory(credentials).create(testTrack())
                val dataSource = PlaybackDataSourceFactory(credentials).createDataSource()

                assertThrows(IOException::class.java) {
                    dataSource.open(DataSpec.Builder().setUri(item.localConfiguration!!.uri).build())
                }
                dataSource.close()
                assertEquals(1, server.requestCount)
                assertEquals(0, otherServer.requestCount)
            }
        }
    }

    @Test
    fun dataSourceRejectsForeignHostBeforeNetworkAccess() {
        MockWebServer().use { server ->
            val credentials = credentials(server.url("/").toString().trimEnd('/'))
            val dataSource = PlaybackDataSourceFactory(credentials).createDataSource()

            assertThrows(IOException::class.java) {
                dataSource.open(DataSpec.Builder().setUri(Uri.parse("https://attacker.example/api/v1/tracks/x/stream")).build())
            }
            assertEquals(0, server.requestCount)
        }
    }

    private fun credentials(serverUrl: String, token: String = "test-token") = DeviceCredentials(
        serverUrl = serverUrl,
        deviceId = "device-1",
        token = token,
        serverName = "Velin",
        serverVersion = "test",
    )

    private class MutableCredentialStore(
        var credentials: DeviceCredentials?,
    ) : CredentialStore {
        override fun load(): DeviceCredentials? = credentials

        override fun save(credentials: DeviceCredentials) {
            this.credentials = credentials
        }

        override fun clear() {
            credentials = null
        }
    }

    private fun testTrack() = Track(
        id = "track-1",
        title = "Track",
        format = "flac",
        artistName = "Artist",
        albumTitle = "Album",
        durationMs = 123_000,
        coverId = "cover-1",
    )
}
