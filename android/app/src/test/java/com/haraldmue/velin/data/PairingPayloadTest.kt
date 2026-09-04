package com.haraldmue.velin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun parsesExactVelinPairingPayload() {
        val payload = PairingPayloadParser.parse(
            """{"server_url":"HTTP://192.168.1.20:8080/","code":" one-time-code "}""",
        )

        assertEquals("http://192.168.1.20:8080", payload.serverUrl)
        assertEquals("one-time-code", payload.code)
    }

    @Test
    fun rejectsMalformedOrExpandedPayloads() {
        listOf(
            "",
            "not-json",
            "{}",
            """{"server_url":"https://velin.example"}""",
            """{"server_url":"https://velin.example","code":""}""",
            """{"server_url":"https://velin.example","code":"code","token":"must-not-be-here"}""",
        ).forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) {
                PairingPayloadParser.parse(value)
            }
        }
    }
}
