package com.haraldmue.velin.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.haraldmue.velin.data.ArtworkAuthorizationInterceptor
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.DeviceCredentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Loads notification/lock-screen artwork without placing credentials in media metadata. */
internal class AuthenticatedArtworkBitmapLoader(
    credentials: DeviceCredentials,
) : BitmapLoader, Closeable {
    private val policy = ArtworkRequestPolicy(credentials.serverUrl)
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(ArtworkAuthorizationInterceptor(policy, credentials.token))
        .build()
    private val executor: ListeningExecutorService = MoreExecutors.listeningDecorator(
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "velin-artwork").apply { isDaemon = true }
        },
    )

    override fun supportsMimeType(mimeType: String): Boolean = mimeType in SUPPORTED_MIME_TYPES

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit<Bitmap> { decodeBounded(data) }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = executor.submit<Bitmap> {
        decodeBounded(readArtworkData(uri))
    }

    fun loadEmbeddedArtworkData(uri: Uri): ListenableFuture<ByteArray> = executor.submit<ByteArray> {
        val url = uri.toString().toHttpUrlOrNull()
            ?: throw IOException("Invalid artwork URL.")
        if (!policy.isAllowed(url)) throw IOException("Artwork URL is outside the paired server.")
        val pathSegments = url.pathSegments
        val embeddedUrl = if (pathSegments.lastOrNull() == "512") {
            url.newBuilder().setPathSegment(pathSegments.lastIndex, "256").build()
        } else {
            url
        }
        val data = readArtworkData(Uri.parse(embeddedUrl.toString()))
        if (data.size > MAX_EMBEDDED_ARTWORK_BYTES) {
            throw IOException("Artwork is too large for embedded media metadata.")
        }
        decodeBounded(data).recycle()
        data
    }

    private fun readArtworkData(uri: Uri): ByteArray {
        val url = uri.toString().toHttpUrlOrNull()
            ?: throw IOException("Invalid artwork URL.")
        if (!policy.isAllowed(url)) throw IOException("Artwork URL is outside the paired server.")
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

    private fun decodeBounded(data: ByteArray): Bitmap {
        if (data.isEmpty() || data.size > MAX_ARTWORK_BYTES) throw IOException("Invalid artwork data.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported artwork data.")
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels > MAX_SOURCE_PIXELS) throw IOException("Artwork dimensions are too large.")
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
        const val MAX_SOURCE_PIXELS = 50_000_000L
        const val MAX_BITMAP_DIMENSION = 1_024
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}
