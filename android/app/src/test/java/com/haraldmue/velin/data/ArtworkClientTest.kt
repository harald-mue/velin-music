package com.haraldmue.velin.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ArtworkClientTest {
    @Test
    fun policyBuildsOnlyBoundedCoverUrls() {
        val policy = ArtworkRequestPolicy("https://velin.example/music")

        assertEquals(
            "https://velin.example/music/api/v1/covers/cover-1/256",
            policy.urlFor("cover-1"),
        )
        assertThrows(IllegalArgumentException::class.java) { policy.urlFor("") }
        assertThrows(IllegalArgumentException::class.java) { policy.urlFor("x".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { policy.urlFor("cover-1", 1024) }
    }

    @Test
    fun interceptorAddsBearerHeaderOnlyToExactCoverRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("image"))
            val policy = ArtworkRequestPolicy(server.url("/music").toString().trimEnd('/'))
            val client = client(policy)

            client.newCall(Request.Builder().url(policy.urlFor("cover-1")).build()).execute().use {
                assertEquals(200, it.code)
            }

            val request = server.takeRequest()
            assertEquals("/music/api/v1/covers/cover-1/256", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/music/api/v1/status")).build()).execute()
            }
        }
    }

    @Test
    fun missingDerivativeFallsBackToAuthenticatedOriginalForStagedUpgrades() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("original-image"))
            val policy = ArtworkRequestPolicy(server.url("/music").toString().trimEnd('/'))
            val client = client(policy)

            client.newCall(Request.Builder().url(policy.urlFor("cover-1")).build()).execute().use {
                assertEquals(200, it.code)
            }

            assertEquals("/music/api/v1/covers/cover-1/256", server.takeRequest().path)
            val fallback = server.takeRequest()
            assertEquals("/music/api/v1/covers/cover-1", fallback.path)
            assertEquals("Bearer test-token", fallback.getHeader("Authorization"))
        }
    }

    @Test
    fun redirectsAreNotFollowedAndCannotLeakAuthorization() {
        MockWebServer().use { server ->
            MockWebServer().use { otherServer ->
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", otherServer.url("/capture")),
                )
                val policy = ArtworkRequestPolicy(server.url("/").toString().trimEnd('/'))
                val client = client(policy)

                client.newCall(Request.Builder().url(policy.urlFor("cover-1")).build()).execute().use {
                    assertEquals(302, it.code)
                }

                assertEquals(1, server.requestCount)
                assertEquals(0, otherServer.requestCount)
                assertFalse(policy.isAllowed(otherServer.url("/api/v1/covers/cover-1")))
            }
        }
    }

    @Test
    fun idleHttpKeepAliveIsShorterThanOkHttpDefault() {
        assertEquals(2, ArtworkHttpIdleConnections)
        assertEquals(30L, ArtworkHttpIdleKeepAliveSeconds)
    }

    private fun client(policy: ArtworkRequestPolicy): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ArtworkAuthorizationInterceptor(policy, "test-token"))
        .build()
}
