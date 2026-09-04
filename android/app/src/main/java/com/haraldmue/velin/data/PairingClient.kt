package com.haraldmue.velin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MaxPairingResponseBytes = 64L * 1_024L
private const val MaxPairingCodeLength = 512

fun interface PairingGateway {
    suspend fun pair(serverUrl: String, code: String): DeviceCredentials
}

class PairingClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : PairingGateway {
    override suspend fun pair(serverUrl: String, code: String): DeviceCredentials = withContext(Dispatchers.IO) {
        val normalizedUrl = ServerAddress.normalize(serverUrl)
        val normalizedCode = code.trim()
        require(normalizedCode.isNotEmpty()) { "Enter a pairing code." }
        require(normalizedCode.length <= MaxPairingCodeLength) { "The pairing code is too long." }

        val body = JSONObject()
            .put("code", normalizedCode)
            .toString()
            .toRequestBody(JsonMediaType)
        val request = Request.Builder()
            .url(ServerAddress.pairingEndpoint(normalizedUrl))
            .post(body)
            .header("Accept", "application/json")
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (_: IOException) {
            throw PairingException("Cannot reach the Velin server.")
        }
        response.use {
            val responseBody = it.body ?: throw PairingException("The server returned an empty response.")
            val source = responseBody.source()
            source.request(MaxPairingResponseBytes + 1)
            if (source.buffer.size > MaxPairingResponseBytes) {
                throw PairingException("The server response is too large.")
            }
            val jsonText = source.readUtf8()
            if (!it.isSuccessful) {
                throw PairingException(errorMessage(it.code, jsonText))
            }
            parseSuccess(normalizedUrl, jsonText)
        }
    }

    private fun parseSuccess(serverUrl: String, value: String): DeviceCredentials = try {
        val json = JSONObject(value)
        val server = json.getJSONObject("server")
        DeviceCredentials(
            serverUrl = serverUrl,
            deviceId = json.getString("device_id").requiredField("device_id"),
            token = json.getString("token").requiredField("token"),
            serverName = server.getString("name").requiredField("server.name"),
            serverVersion = server.getString("version").requiredField("server.version"),
        )
    } catch (_: JSONException) {
        throw PairingException("The server returned an invalid pairing response.")
    }

    private fun errorMessage(statusCode: Int, body: String): String {
        val errorCode = try {
            JSONObject(body).optJSONObject("error")?.optString("code")
        } catch (_: JSONException) {
            null
        }
        return when {
            statusCode == 401 && errorCode == "invalid_pairing_code" ->
                "The pairing code is invalid, expired, or already used."
            statusCode == 429 -> "Too many pairing attempts. Try again later."
            statusCode in 500..599 -> "The Velin server could not complete pairing."
            else -> "Pairing failed (HTTP $statusCode)."
        }
    }

    private fun String.requiredField(name: String): String =
        trim().takeIf { it.isNotEmpty() }
            ?: throw PairingException("The server response is missing $name.")

    companion object {
        private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

class PairingException(message: String) : Exception(message)
