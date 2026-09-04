package com.haraldmue.velin.data

import org.json.JSONException
import org.json.JSONObject

private const val MaxPairingPayloadBytes = 4_096
private const val MaxPairingPayloadCodeLength = 512

/** Data encoded by the Velin administration pairing QR code. */
data class PairingPayload(
    val serverUrl: String,
    val code: String,
)

object PairingPayloadParser {
    private val ExpectedKeys = setOf("server_url", "code")

    fun parse(value: String): PairingPayload {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "The QR code is empty." }
        require(trimmed.encodeToByteArray().size <= MaxPairingPayloadBytes) {
            "The QR code payload is too large."
        }

        val json = try {
            JSONObject(trimmed)
        } catch (_: JSONException) {
            throw IllegalArgumentException("This is not a Velin pairing QR code.")
        }
        val keys = json.keys().asSequence().toSet()
        require(keys == ExpectedKeys) { "This is not a Velin pairing QR code." }

        val serverUrl = try {
            ServerAddress.normalize(json.getString("server_url"))
        } catch (_: JSONException) {
            throw IllegalArgumentException("This is not a Velin pairing QR code.")
        }
        val code = try {
            json.getString("code").trim()
        } catch (_: JSONException) {
            throw IllegalArgumentException("This is not a Velin pairing QR code.")
        }
        require(code.isNotEmpty() && code.length <= MaxPairingPayloadCodeLength) {
            "The QR code contains an invalid pairing code."
        }
        return PairingPayload(serverUrl = serverUrl, code = code)
    }
}
