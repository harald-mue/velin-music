package com.haraldmue.velin.data.cache

import com.haraldmue.velin.data.DeviceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CacheNamespaceTest {
    @Test
    fun normalizesServerAndDoesNotIncludeToken() {
        val first = credentials(" HTTPS://MUSIC.example.test/ ", "device-1", "token-a")
        val second = credentials("https://music.example.test", "device-1", "token-b")

        assertEquals(CacheNamespace.from(first), CacheNamespace.from(second))
    }

    @Test
    fun separatesServersAndDevices() {
        val base = CacheNamespace.from(credentials("https://one.example", "device-1", "token"))

        assertNotEquals(
            base,
            CacheNamespace.from(credentials("https://two.example", "device-1", "token")),
        )
        assertNotEquals(
            base,
            CacheNamespace.from(credentials("https://one.example", "device-2", "token")),
        )
    }

    private fun credentials(server: String, device: String, token: String) = DeviceCredentials(
        serverUrl = server,
        deviceId = device,
        token = token,
        serverName = "Test",
        serverVersion = "1",
    )
}
