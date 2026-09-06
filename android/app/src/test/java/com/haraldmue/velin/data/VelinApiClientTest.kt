package com.haraldmue.velin.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    fun loadsAuthenticatedLibrarySummary() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"artist_count":12,"album_count":34,"track_count":2096,"revision":"revision-1"}""",
                ),
            )
            val client = VelinApiClient(credentials(server))

            val summary = client.summary()

            assertEquals(12, summary.artistCount)
            assertEquals(34, summary.albumCount)
            assertEquals(2096, summary.trackCount)
            assertEquals("revision-1", summary.revision)
            val request = server.takeRequest()
            assertEquals("/api/v1/library/summary", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
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
    fun artistsPageUsesCursor() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"items":[{"id":"artist-1","name":"One","album_count":1,"track_count":1}],"has_more":true,"next_cursor":"page-2"}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"items":[{"id":"artist-2","name":"Two","album_count":0,"track_count":2}],"has_more":false}""",
                ),
            )
            val client = VelinApiClient(credentials(server))

            val first = client.loadArtistsPage()
            val second = client.loadArtistsPage(first.nextCursor)

            assertEquals("One", first.items.single().name)
            assertTrue(first.hasMore)
            assertEquals("Two", second.items.single().name)
            assertFalse(second.hasMore)
            assertEquals(null, server.takeRequest().requestUrl?.queryParameter("cursor"))
            assertEquals("page-2", server.takeRequest().requestUrl?.queryParameter("cursor"))
        }
    }

    @Test
    fun loadTrackParsesDetailMetadata() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{
                        "id":"track-1",
                        "title":"Song",
                        "format":"flac",
                        "artist_id":"artist-1",
                        "artist_name":"Artist",
                        "album_id":"album-1",
                        "album_title":"Album",
                        "genre":"Rock",
                        "duration_ms":180000,
                        "sample_rate":44100,
                        "bits_per_sample":16,
                        "channels":2
                    }""".trimIndent(),
                ),
            )
            val client = VelinApiClient(credentials(server))

            val track = client.loadTrack("track-1")

            assertEquals("Song", track.title)
            assertEquals("flac", track.format)
            assertEquals("artist-1", track.artistId)
            assertEquals("album-1", track.albumId)
            assertEquals("Rock", track.genre)
            assertEquals(44100, track.sampleRate)
            assertEquals("/api/v1/tracks/track-1", server.takeRequest().path)
        }
    }

    @Test
    fun loadAlbumParsesDetailMetadata() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"id":"album-1","title":"Album","artist_name":"Artist","year":2026,"cover_id":"cover-1","track_count":8,"added_at":"2026-01-02T03:04:05Z"}""",
                ),
            )
            val client = VelinApiClient(credentials(server))

            val album = client.loadAlbum("album-1")

            assertEquals("Album", album.title)
            assertEquals("Artist", album.artistName)
            assertEquals("cover-1", album.coverId)
            assertEquals(8, album.trackCount)
            assertEquals(1_767_323_045_000L, album.addedAtMs)
            assertEquals("/api/v1/albums/album-1", server.takeRequest().path)
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
