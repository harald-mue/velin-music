package com.haraldmue.velin.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.haraldmue.velin.data.ArtworkAuthorizationInterceptor
import com.haraldmue.velin.data.ArtworkHttpIdleConnections
import com.haraldmue.velin.data.ArtworkHttpIdleKeepAliveSeconds
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.CredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Loads notification/lock-screen artwork without placing credentials in media metadata. */
internal class AuthenticatedArtworkBitmapLoader private constructor(
    private val credentialsProvider: () -> DeviceCredentials?,
    private val artworkCacheDirectory: File?,
) : BitmapLoader, Closeable {
    constructor(credentials: DeviceCredentials) : this({ credentials }, null)
    constructor(context: Context, credentialStore: CredentialStore) : this(
        credentialStore::load,
        context.applicationContext.cacheDir.resolve("artwork"),
    )

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .connectionPool(
            ConnectionPool(ArtworkHttpIdleConnections, ArtworkHttpIdleKeepAliveSeconds, TimeUnit.SECONDS),
        )
        .addInterceptor(
            ArtworkAuthorizationInterceptor(
                { credentialsProvider()?.let { ArtworkRequestPolicy(it.serverUrl) } },
                { credentialsProvider()?.token },
            ),
        )
        .build()
    private val executorPool = ThreadPoolExecutor(
        2,
        2,
        ArtworkLoaderKeepAliveSeconds,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread(runnable, "velin-artwork").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(executorPool)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType in SUPPORTED_MIME_TYPES

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> { decodeBounded(data) }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit<Bitmap> {
        decodeBounded(readArtworkData(uri))
    }

    fun loadEmbeddedArtworkData(
        uri: Uri,
        maxBytes: Int = MAX_EMBEDDED_ARTWORK_BYTES,
    ): ListenableFuture<ByteArray> = executor.submit<ByteArray> {
        require(maxBytes in 1..MAX_EMBEDDED_ARTWORK_BYTES) { "Invalid embedded artwork limit." }
        val credentials = credentialsProvider()
            ?: throw IOException("Artwork credentials are unavailable.")
        val policy = ArtworkRequestPolicy(credentials.serverUrl)
        val url = uri.toString().toHttpUrlOrNull()
            ?: throw IOException("Invalid artwork URL.")
        if (!policy.isAllowed(url)) throw IOException("Artwork URL is outside the paired server.")
        val pathSegments = url.pathSegments
        val embeddedUrl = if (pathSegments.lastOrNull() == "512") {
            url.newBuilder().setPathSegment(pathSegments.lastIndex, "256").build()
        } else {
            url
        }
        val embeddedUri = Uri.parse(embeddedUrl.toString())
        val data = readCachedArtworkData(embeddedUri, maxBytes)
            ?: readArtworkData(embeddedUri)
        if (data.size > maxBytes) {
            throw IOException("Artwork is too large for embedded media metadata.")
        }
        validateArtworkBounds(data, maxBytes)
        data
    }

    private fun readCachedArtworkData(uri: Uri, maxBytes: Int): ByteArray? {
        val directory = artworkCacheDirectory ?: return null
        val file = directory.resolve("${artworkDiskCacheKey(uri.toString())}.1")
        val size = file.length()
        if (!file.isFile || size <= 0 || size > maxBytes) return null
        return runCatching { file.readBytes().takeIf { it.size in 1..maxBytes } }.getOrNull()
    }

    private fun readArtworkData(uri: Uri): ByteArray {
        val url = uri.toString().toHttpUrlOrNull()
            ?: throw IOException("Invalid artwork URL.")
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", SUPPORTED_MIME_TYPES.joinToString(", "))
                .get()
                .build(),
        ).execute()
        return response.use {
            if (!it.isSuccessful) throw IOException("Artwork request failed (HTTP ${it.code}).")
            val body = it.body ?: throw IOException("Artwork response is empty.")
            if (body.contentLength() > MAX_ARTWORK_BYTES) throw IOException("Artwork response is too large.")
            val source = body.source()
            source.request(MAX_ARTWORK_BYTES + 1L)
            if (source.buffer.size > MAX_ARTWORK_BYTES) throw IOException("Artwork response is too large.")
            source.readByteArray()
        }
    }

    fun cancelInFlight() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    override fun close() {
        cancelInFlight()
        executor.shutdownNow()
    }

    private fun validateArtworkBounds(data: ByteArray, maxBytes: Int) {
        if (data.isEmpty() || data.size > maxBytes) throw IOException("Invalid artwork data.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (!artworkSourceIsWithinLimits(
                byteCount = data.size,
                maxBytes = maxBytes,
                width = bounds.outWidth,
                height = bounds.outHeight,
            )
        ) {
            throw IOException("Unsupported artwork data.")
        }
    }

    private fun decodeBounded(data: ByteArray): Bitmap {
        validateArtworkBounds(data, MAX_ARTWORK_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_BITMAP_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_BITMAP_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
            ?: throw IOException("Could not decode artwork.")
    }

    private companion object {
        const val MAX_ARTWORK_BYTES = 8 * 1_024 * 1_024
        const val MAX_EMBEDDED_ARTWORK_BYTES = 1 * 1_024 * 1_024
        const val MAX_BITMAP_DIMENSION = 1_024
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}

internal const val ArtworkLoaderKeepAliveSeconds = 1L

internal fun artworkDiskCacheKey(url: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun artworkSourceIsWithinLimits(
    byteCount: Int,
    maxBytes: Int,
    width: Int,
    height: Int,
    maxPixels: Long = 50_000_000L,
): Boolean {
    if (byteCount <= 0 || byteCount > maxBytes) return false
    if (width <= 0 || height <= 0) return false
    return width.toLong() * height.toLong() <= maxPixels
}
