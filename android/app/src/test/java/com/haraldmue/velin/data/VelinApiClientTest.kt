package com.haraldmue.velin.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class VelinApiClientTest {
    @Test
    fun statusUsesBearerHeaderAndPreservesBasePath() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"name":"Velin","status":"ok","version":"test"}"""),
            )
            val client = VelinApiClient(credentials(server, path = "/music"))

            val status = client.status()

            assertEquals(ServerStatus("Velin", "ok", "test"), status)
            val request = server.takeRequest()
            assertEquals("/music/api/v1/status", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
        }
    }

    @Test
    fun searchEncodesQueryAndParsesTrackPage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{
                        "items":[{
                            "id":"track-1",
                            "title":"Needle Song",
                            "format":"flac",
                            "artist_name":"Artist",
                            "album_title":"Album",
                            "duration_ms":123000
                        }],
                        "has_more":false
                    }""".trimIndent(),
                ),
            )
            val client = VelinApiClient(credentials(server))

            val page = client.search("needle song")

            assertFalse(page.hasMore)
            assertEquals(1, page.items.size)
            assertEquals("Needle Song", page.items.single().title)
            assertEquals("Artist", page.items.single().artistName)
            assertEquals("flac", page.items.single().format)
            val request = server.takeRequest()
            assertEquals("needle song", request.requestUrl?.queryParameter("q"))
            assertEquals("50", request.requestUrl?.queryParameter("limit"))
        }
    }

    @Test
    fun loadsAuthenticatedLibrarySnapshot() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/api/v1/artists") -> MockResponse().setBody(
                        """{"items":[{"id":"artist-1","name":"Artist","album_count":1,"track_count":1}],"has_more":false}""",
                    )
                    request.path.orEmpty().startsWith("/api/v1/albums") -> MockResponse().setBody(
                        """{"items":[{"id":"album-1","title":"Album","artist_name":"Artist","year":2026,"track_count":1}],"has_more":false}""",
                    )
                    request.path.orEmpty().startsWith("/api/v1/tracks") -> MockResponse().setBody(
                        """{"items":[{"id":"track-1","title":"Track","format":"mp3","artist_name":"Artist","album_title":"Album"}],"has_more":false}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val client = VelinApiClient(credentials(server))

            val library = client.loadLibrary()

            assertEquals("Artist", library.artists.items.single().name)
            assertEquals("Album", library.albums.items.single().title)
            assertEquals("Track", library.tracks.items.single().title)
            repeat(3) {
                assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
            }
        }
    }

    @Test
    fun albumTracksFollowBoundedCursorPages() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"items":[{"id":"track-1","title":"One","format":"flac","disc_number":1,"track_number":2}],"has_more":true,"next_cursor":"next-page"}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"items":[{"id":"track-2","title":"Two","format":"mp3"}],"has_more":false}""",
                ),
            )
            val client = VelinApiClient(credentials(server))

            val tracks = client.loadAlbumTracks("album-1")

            assertEquals(listOf("One", "Two"), tracks.map(Track::title))
            assertEquals(1, tracks.first().discNumber)
            assertEquals(2, tracks.first().trackNumber)
            val first = server.takeRequest().requestUrl!!
            assertEquals("album-1", first.queryParameter("album_id"))
            assertEquals("200", first.queryParameter("limit"))
            val second = server.takeRequest().requestUrl!!
            assertEquals("next-page", second.queryParameter("cursor"))
        }
    }

    @Test
    fun unauthorizedResponseIsMarkedAsAuthenticationFailure() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val client = VelinApiClient(credentials(server))

            val error = try {
                client.status()
                fail("status() accepted an unauthorized response")
                error("unreachable")
            } catch (error: ApiException) {
                error
            }

            assertTrue(error.authenticationFailed)
            assertFalse(error.message.orEmpty().contains("test-token"))
        }
    }

    private fun credentials(server: MockWebServer, path: String = ""): DeviceCredentials = DeviceCredentials(
        serverUrl = server.url(path.ifEmpty { "/" }).toString().trimEnd('/'),
        deviceId = "device-1",
        token = "test-token",
        serverName = "Velin",
        serverVersion = "test",
    )
}
