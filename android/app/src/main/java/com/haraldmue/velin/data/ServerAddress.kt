package com.haraldmue.velin.data

import java.net.URI
import java.net.URISyntaxException

private const val MaxServerUrlLength = 2_048

object ServerAddress {
    fun normalize(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "Enter a server URL." }
        require(trimmed.length <= MaxServerUrlLength) { "The server URL is too long." }

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Enter a valid server URL.")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Use an http:// or https:// server URL." }
        require(uri.host != null) { "The server URL must include a host." }
        require(uri.rawUserInfo == null) { "The server URL must not include credentials." }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "The server URL must not include a query or fragment."
        }

        val normalizedPath = (uri.path ?: "")
            .split('/')
            .fold(mutableListOf<String>()) { segments, segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> require(segments.isNotEmpty()) { "The server URL path is invalid." }.also {
                        segments.removeAt(segments.lastIndex)
                    }
                    else -> segments += segment
                }
                segments
            }
            .joinToString(separator = "/", prefix = "/")
            .takeUnless { it == "/" }
            .orEmpty()

        return URI(
            scheme,
            null,
            uri.host.lowercase(),
            uri.port,
            normalizedPath,
            null,
            null,
        ).toASCIIString()
    }

    fun pairingEndpoint(serverUrl: String): String =
        "${normalize(serverUrl)}/api/v1/pair"

    fun isCleartext(serverUrl: String): Boolean =
        normalize(serverUrl).startsWith("http://")
}
