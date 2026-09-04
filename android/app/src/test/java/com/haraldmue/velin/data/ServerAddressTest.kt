package com.haraldmue.velin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun normalizesSupportedServerAddresses() {
        assertEquals("http://192.168.1.20:8080", ServerAddress.normalize(" HTTP://192.168.1.20:8080/ "))
        assertEquals("https://music.example.test/velin", ServerAddress.normalize("https://MUSIC.example.test/velin/"))
        assertEquals(
            "https://music.example.test/api/v1/pair",
            ServerAddress.pairingEndpoint("https://music.example.test"),
        )
    }

    @Test
    fun identifiesCleartextAddresses() {
        assertTrue(ServerAddress.isCleartext("http://velin.local:8080"))
        assertFalse(ServerAddress.isCleartext("https://velin.example"))
    }

    @Test
    fun rejectsUnsafeOrIncompleteAddresses() {
        listOf(
            "",
            "velin.local:8080",
            "ftp://velin.local",
            "https://user:secret@velin.local",
            "https://velin.local/?token=secret",
            "https://velin.local/#fragment",
            "https:///missing-host",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                ServerAddress.normalize(value)
            }
        }
    }
}
