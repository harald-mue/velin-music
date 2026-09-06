package com.haraldmue.velin.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MaxApiResponseBytes = 1L * 1_024L * 1_024L
private const val MaxPageItems = 200
private const val MaxModelStringLength = 4_096
private const val MaxAlbumQueueItems = 500
private const val InitialArtistPageItems = 200
private const val InitialAlbumPageItems = 200
private const val InitialTrackPageItems = 100
private val RetryableStatusCodes = setOf(408, 429, 500, 502, 503, 504)

interface LibraryGateway {
    suspend fun status(): ServerStatus
    suspend fun summary(): LibrarySummary
    suspend fun loadArtistsPage(cursor: String? = null, limit: Int = InitialArtistPageItems): Page<Artist>
    suspend fun loadAlbumsPage(cursor: String? = null, limit: Int = InitialAlbumPageItems): Page<Album>
    suspend fun loadTracksPage(
        cursor: String? = null,
        artistId: String? = null,
        albumId: String? = null,
        limit: Int = InitialTrackPageItems,
    ): Page<Track>
    suspend fun loadArtist(artistId: String): Artist
    suspend fun loadAlbum(albumId: String): Album
    suspend fun loadTrack(trackId: String): TrackDetail
    suspend fun loadAlbumTracks(albumId: String): List<Track>
    suspend fun loadArtistTracks(artistId: String): List<Track>
    suspend fun search(query: String, cursor: String? = null, limit: Int = 50): Page<Track>
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
        .addNetworkInterceptor(SafeNetworkTimingInterceptor("library"))
        .build()

    fun cancelInFlight() {
        authenticatedClient.dispatcher.cancelAll()
        authenticatedClient.connectionPool.evictAll()
    }

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

    override suspend fun summary(): LibrarySummary {
        val json = getJSON("api/v1/library/summary")
        return decodeResponse {
            LibrarySummary(
                artistCount = json.nonNegativeInt("artist_count"),
                albumCount = json.nonNegativeInt("album_count"),
                trackCount = json.nonNegativeInt("track_count"),
                revision = json.requiredString("revision"),
            )
        }
    }

    override suspend fun loadArtistsPage(cursor: String?, limit: Int): Page<Artist> =
        decodeArtistPage(getJSON("api/v1/artists", pageQuery(cursor, limit)))

    override suspend fun loadAlbumsPage(cursor: String?, limit: Int): Page<Album> =
        decodeAlbumPage(getJSON("api/v1/albums", pageQuery(cursor, limit)))

    override suspend fun loadTracksPage(
        cursor: String?,
        artistId: String?,
        albumId: String?,
        limit: Int,
    ): Page<Track> {
        val extra = buildMap {
            artistId?.trim()?.takeIf(String::isNotEmpty)?.let { put("artist_id", it) }
            albumId?.trim()?.takeIf(String::isNotEmpty)?.let { put("album_id", it) }
        }
        return decodeTrackPage(getJSON("api/v1/tracks", pageQuery(cursor, limit, extra)))
    }

    override suspend fun loadArtist(artistId: String): Artist {
        val normalized = artistId.trim()
        require(normalized.isNotEmpty() && normalized.length <= 128) { "Invalid artist ID." }
        val json = getJSON("api/v1/artists/$normalized")
        return decodeResponse {
            Artist(
                id = json.requiredString("id"),
                name = json.requiredString("name"),
                albumCount = json.nonNegativeInt("album_count"),
                trackCount = json.nonNegativeInt("track_count"),
            )
        }
    }

    override suspend fun loadAlbum(albumId: String): Album {
        val normalized = albumId.trim()
        require(normalized.isNotEmpty() && normalized.length <= 128) { "Invalid album ID." }
        val json = getJSON("api/v1/albums/$normalized")
        return decodeResponse { decodeAlbum(json) }
    }

    override suspend fun loadTrack(trackId: String): TrackDetail {
        val normalized = trackId.trim()
        require(normalized.isNotEmpty() && normalized.length <= 128) { "Invalid track ID." }
        return decodeTrackDetail(getJSON("api/v1/tracks/$normalized"))
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

    override suspend fun loadArtistTracks(artistId: String): List<Track> {
        val normalized = artistId.trim()
        require(normalized.isNotEmpty() && normalized.length <= 128) { "Invalid artist ID." }
        val tracks = ArrayList<Track>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val query = mutableMapOf("artist_id" to normalized, "limit" to "200")
            cursor?.let { query["cursor"] = it }
            val page = decodeTrackPage(getJSON("api/v1/tracks", query))
            if (tracks.size + page.items.size > MaxAlbumQueueItems) {
                throw ApiException("This artist has too many tracks for the playback queue.")
            }
            tracks += page.items
            cursor = if (page.hasMore) {
                page.nextCursor?.takeIf(seenCursors::add)
                    ?: throw ApiException("The server returned invalid artist pagination.")
            } else {
                null
            }
        } while (cursor != null)
        return tracks
    }

    override suspend fun search(query: String, cursor: String?, limit: Int): Page<Track> {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "Enter a search query." }
        require(normalized.encodeToByteArray().size <= 2_048) { "The search query is too long." }
        val queryParams = pageQuery(cursor, limit, mapOf("q" to normalized))
        return decodeTrackPage(getJSON("api/v1/search", queryParams))
    }

    private fun pageQuery(
        cursor: String?,
        limit: Int = 50,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("limit", limit.coerceIn(1, 200).toString())
        cursor?.let { put("cursor", it) }
        putAll(extra)
    }

    private suspend fun getJSON(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint(path, query))
                .get()
                .build()
            val call = authenticatedClient.newCall(request)
            val response = try {
                call.awaitCancellable()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                if (call.isCanceled() && !isActive) {
                    throw CancellationException("Velin request cancelled")
                }
                throw ApiException("Cannot reach the Velin server.", retryable = true)
            }
            try {
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
                        !it.isSuccessful -> throw ApiException(
                            "The server request failed (HTTP ${it.code}).",
                            retryable = it.code in RetryableStatusCodes,
                        )
                        else -> try {
                            JSONObject(value)
                        } catch (_: JSONException) {
                            throw ApiException("The server returned invalid JSON.")
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                if (call.isCanceled() && !isActive) {
                    throw CancellationException("Velin request cancelled")
                }
                throw ApiException("Cannot reach the Velin server.", retryable = true)
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
        decodeAlbum(item)
    }

    private fun decodeAlbum(item: JSONObject): Album = Album(
        id = item.requiredString("id"),
        title = item.requiredString("title"),
        artistName = item.optionalString("artist_name"),
        year = item.optionalInt("year"),
        coverId = item.optionalString("cover_id"),
        trackCount = item.nonNegativeInt("track_count"),
    )

    private fun decodeTrackPage(json: JSONObject): Page<Track> = decodePage(json) { item ->
        decodeTrack(item)
    }

    private fun decodeTrack(item: JSONObject): Track = Track(
        id = item.requiredString("id"),
        title = item.requiredString("title"),
        format = item.requiredString("format"),
        artistName = item.optionalString("artist_name"),
        albumTitle = item.optionalString("album_title"),
        durationMs = item.optionalLong("duration_ms"),
        trackNumber = item.optionalInt("track_number"),
        discNumber = item.optionalInt("disc_number"),
        artistId = item.optionalString("artist_id"),
        albumId = item.optionalString("album_id"),
        coverId = item.optionalString("cover_id"),
    )

    private fun decodeTrackDetail(json: JSONObject): TrackDetail = decodeResponse {
        TrackDetail(
            id = json.requiredString("id"),
            title = json.requiredString("title"),
            format = json.requiredString("format"),
            artistId = json.optionalString("artist_id"),
            artistName = json.optionalString("artist_name"),
            albumId = json.optionalString("album_id"),
            albumTitle = json.optionalString("album_title"),
            albumArtistName = json.optionalString("album_artist_name"),
            genre = json.optionalString("genre"),
            dateText = json.optionalString("date"),
            trackNumber = json.optionalInt("track_number"),
            totalTracks = json.optionalInt("total_tracks"),
            discNumber = json.optionalInt("disc_number"),
            totalDiscs = json.optionalInt("total_discs"),
            durationMs = json.optionalLong("duration_ms"),
            sampleRate = json.optionalInt("sample_rate"),
            bitsPerSample = json.optionalInt("bits_per_sample"),
            channels = json.optionalInt("channels"),
            coverId = json.optionalString("cover_id"),
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
            .retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

private suspend fun Call.awaitCancellable(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }

class ApiException(
    message: String,
    val authenticationFailed: Boolean = false,
    val retryable: Boolean = false,
) : Exception(message) {
    val isNotFound: Boolean
        get() = message?.contains("HTTP 404") == true
}

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
