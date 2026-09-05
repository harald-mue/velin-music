package com.haraldmue.velin.data

import android.util.Log
import com.haraldmue.velin.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Debug-only, credential-free request timing grouped by endpoint category. */
internal class SafeNetworkTimingInterceptor(
    private val component: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!BuildConfig.DEBUG) return chain.proceed(chain.request())
        val request = chain.request()
        val category = safeCategory(request.url.encodedPath, request.url.queryParameterNames)
        val startedAt = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            logInfo(
                "component=$component category=$category status=${response.code} " +
                    "duration_ms=${elapsedMilliseconds(startedAt)} " +
                    "bytes=${response.body?.contentLength() ?: -1}",
            )
            response
        } catch (error: IOException) {
            val message =
                "component=$component category=$category failure=${error.javaClass.simpleName} " +
                    "duration_ms=${elapsedMilliseconds(startedAt)}"
            if (chain.call().isCanceled()) {
                logInfo("$message canceled=true")
            } else {
                logWarning(message)
            }
            throw error
        }
    }

    private fun safeCategory(path: String, queryNames: Set<String>): String = when {
        path.endsWith("/api/v1/status") -> "status"
        path.endsWith("/api/v1/library/summary") -> "summary"
        path.endsWith("/api/v1/artists") -> "artists-page"
        path.endsWith("/api/v1/albums") -> "albums-page"
        path.endsWith("/api/v1/tracks") && "album_id" in queryNames -> "album-tracks"
        path.endsWith("/api/v1/tracks") && "artist_id" in queryNames -> "artist-tracks"
        path.endsWith("/api/v1/tracks") -> "tracks-page"
        path.endsWith("/api/v1/search") -> "search"
        "/api/v1/covers/" in path -> "cover"
        "/api/v1/artists/" in path -> "artist-detail"
        "/api/v1/albums/" in path -> "album-detail"
        "/api/v1/tracks/" in path -> "track-detail"
        else -> "other"
    }

    private companion object {
        const val Tag = "VelinNetwork"

        fun elapsedMilliseconds(startedAt: Long): Long =
            (System.nanoTime() - startedAt) / 1_000_000

        fun logInfo(message: String) {
            runCatching { Log.i(Tag, message) }
        }

        fun logWarning(message: String) {
            runCatching { Log.w(Tag, message) }
        }
    }
}
