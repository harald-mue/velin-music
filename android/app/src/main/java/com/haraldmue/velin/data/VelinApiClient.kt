package com.haraldmue.velin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MaxApiResponseBytes = 1L * 1_024L * 1_024L
private const val MaxPageItems = 200
private const val MaxModelStringLength = 4_096
private const val MaxAlbumQueueItems = 500

interface LibraryGateway {
    suspend fun status(): ServerStatus
    suspend fun loadLibrary(): LibrarySnapshot
    suspend fun loadAlbumTracks(albumId: String): List<Track>
    suspend fun search(query: String): Page<Track>
}

class VelinApiClient(
    private val credentials: DeviceCredentials,
    httpClient: OkHttpClient = defaultHttpClient(),
) : LibraryGateway {
    private val baseUrl = ServerAddress.normalize(credentials.serverUrl).toHttpUrl()
    private val authenticatedClient = httpClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer ${credentials.token}")
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun status(): ServerStatus {
        val json = getJSON("api/v1/status")
        return decodeResponse {
            ServerStatus(
                name = json.requiredString("name"),
                status = json.requiredString("status"),
                version = json.requiredString("version"),
            )
        }
    }

    override suspend fun loadLibrary(): LibrarySnapshot = coroutineScope {
        val artists = async { loadArtists() }
        val albums = async { loadAlbums() }
        val tracks = async { loadTracks() }
        LibrarySnapshot(
            artists = artists.await(),
            albums = albums.await(),
            tracks = tracks.await(),
        )
    }

    override suspend fun loadAlbumTracks(albumId: String): List<Track> {
        val normalized = albumId.trim()
        require(normalized.isNotEmpty() && normalized.length <= 128) { "Invalid album ID." }
        val tracks = ArrayList<Track>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val query = mutableMapOf("album_id" to normalized, "limit" to "200")
            cursor?.let { query["cursor"] = it }
            val page = decodeTrackPage(getJSON("api/v1/tracks", query))
            if (tracks.size + page.items.size > MaxAlbumQueueItems) {
                throw ApiException("This album is too large for the playback queue.")
            }
            tracks += page.items
            cursor = if (page.hasMore) {
                page.nextCursor?.takeIf(seenCursors::add)
                    ?: throw ApiException("The server returned invalid album pagination.")
            } else {
                null
            }
        } while (cursor != null)
        return tracks
    }

    override suspend fun search(query: String): Page<Track> {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "Enter a search query." }
        require(normalized.encodeToByteArray().size <= 2_048) { "The search query is too long." }
        val json = getJSON("api/v1/search", mapOf("q" to normalized, "limit" to "50"))
        return decodeTrackPage(json)
    }

    private suspend fun loadArtists(): Page<Artist> =
        decodeArtistPage(getJSON("api/v1/artists", mapOf("limit" to "50")))

    private suspend fun loadAlbums(): Page<Album> =
        decodeAlbumPage(getJSON("api/v1/albums", mapOf("limit" to "50")))

    private suspend fun loadTracks(): Page<Track> =
        decodeTrackPage(getJSON("api/v1/tracks", mapOf("limit" to "50")))

    private suspend fun getJSON(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(path, query))
                .get()
                .build()
            val response = try {
                authenticatedClient.newCall(request).execute()
            } catch (_: IOException) {
                throw ApiException("Cannot reach the Velin server.")
            }
            response.use {
                val body = it.body ?: throw ApiException("The server returned an empty response.")
                val source = body.source()
                source.request(MaxApiResponseBytes + 1)
                if (source.buffer.size > MaxApiResponseBytes) {
                    throw ApiException("The server response is too large.")
                }
                val value = source.readUtf8()
                when {
                    it.code == 401 || it.code == 403 -> throw ApiException(
                        "Device access was revoked. Pair this device again.",
                        authenticationFailed = true,
                    )
                    !it.isSuccessful -> throw ApiException("The server request failed (HTTP ${it.code}).")
                    else -> try {
                        JSONObject(value)
                    } catch (_: JSONException) {
                        throw ApiException("The server returned invalid JSON.")
                    }
                }
            }
        }

    private fun endpoint(path: String, query: Map<String, String>): HttpUrl {
        val builder = baseUrl.newBuilder()
        path.split('/').filter(String::isNotEmpty).forEach(builder::addPathSegment)
        query.forEach(builder::addQueryParameter)
        return builder.build()
    }

    private fun decodeArtistPage(json: JSONObject): Page<Artist> = decodePage(json) { item ->
        Artist(
            id = item.requiredString("id"),
            name = item.requiredString("name"),
            albumCount = item.nonNegativeInt("album_count"),
            trackCount = item.nonNegativeInt("track_count"),
        )
    }

    private fun decodeAlbumPage(json: JSONObject): Page<Album> = decodePage(json) { item ->
        Album(
            id = item.requiredString("id"),
            title = item.requiredString("title"),
            artistName = item.optionalString("artist_name"),
            year = item.optionalInt("year"),
            coverId = item.optionalString("cover_id"),
            trackCount = item.nonNegativeInt("track_count"),
        )
    }

    private fun decodeTrackPage(json: JSONObject): Page<Track> = decodePage(json) { item ->
        Track(
            id = item.requiredString("id"),
            title = item.requiredString("title"),
            format = item.requiredString("format"),
            artistName = item.optionalString("artist_name"),
            albumTitle = item.optionalString("album_title"),
            durationMs = item.optionalLong("duration_ms"),
            trackNumber = item.optionalInt("track_number"),
            discNumber = item.optionalInt("disc_number"),
            coverId = item.optionalString("cover_id"),
        )
    }

    private fun <T> decodePage(json: JSONObject, itemDecoder: (JSONObject) -> T): Page<T> = decodeResponse {
        val itemsJSON = json.getJSONArray("items")
        require(itemsJSON.length() <= MaxPageItems) { "Too many items in server response." }
        val items = ArrayList<T>(itemsJSON.length())
        for (index in 0 until itemsJSON.length()) {
            items += itemDecoder(itemsJSON.getJSONObject(index))
        }
        Page(
            items = items,
            nextCursor = json.optionalString("next_cursor"),
            hasMore = json.getBoolean("has_more"),
        )
    }

    private fun <T> decodeResponse(block: () -> T): T = try {
        block()
    } catch (_: JSONException) {
        throw ApiException("The server returned an invalid response.")
    } catch (error: IllegalArgumentException) {
        throw ApiException(error.message ?: "The server returned an invalid response.")
    }

    companion object {
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

class ApiException(
    message: String,
    val authenticationFailed: Boolean = false,
) : Exception(message)

private fun JSONObject.requiredString(name: String): String =
    getString(name).trim().also {
        require(it.isNotEmpty() && it.length <= MaxModelStringLength) { "Invalid $name in server response." }
    }

private fun JSONObject.optionalString(name: String): String? =
    if (!has(name) || isNull(name)) null else requiredString(name)

private fun JSONObject.nonNegativeInt(name: String): Int =
    getInt(name).also { require(it >= 0) { "Invalid $name in server response." } }

private fun JSONObject.optionalInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

private fun JSONObject.optionalLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else getLong(name).also {
        require(it >= 0) { "Invalid $name in server response." }
    }
