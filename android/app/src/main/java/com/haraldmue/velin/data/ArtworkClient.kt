package com.haraldmue.velin.data

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Authenticated artwork loader restricted to the paired Velin cover endpoint. */
class ArtworkClient(
    context: Context,
    credentials: DeviceCredentials,
    baseClient: OkHttpClient = defaultArtworkHttpClient(),
) {
    private val policy = ArtworkRequestPolicy(credentials.serverUrl)
    internal val httpClient = baseClient.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MaxConcurrentArtworkRequests
                maxRequestsPerHost = MaxConcurrentArtworkRequestsPerHost
            },
        )
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ArtworkAuthorizationInterceptor(policy, credentials.token))
        .addNetworkInterceptor(SafeNetworkTimingInterceptor("artwork"))
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context.applicationContext, 0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.applicationContext.cacheDir.resolve("artwork").toOkioPath())
                .maxSizeBytes(64L * 1024L * 1024L)
                .build()
        }
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = httpClient))
        }
        .build()

    fun urlFor(coverId: String?, size: Int = DefaultArtworkSize): String? = coverId?.let { id ->
        runCatching { policy.urlFor(id, size) }.getOrNull()
    }

    fun cancelInFlight() {
        httpClient.dispatcher.cancelAll()
    }

    fun close() {
        cancelInFlight()
        imageLoader.shutdown()
    }

    companion object {
        const val DefaultArtworkSize = 256
        private const val MaxConcurrentArtworkRequests = 4
        private const val MaxConcurrentArtworkRequestsPerHost = 2

        fun defaultArtworkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

internal class ArtworkRequestPolicy(serverAddress: String) {
    private val serverUrl = ServerAddress.normalize(serverAddress).toHttpUrl()

    fun urlFor(coverId: String, size: Int = ArtworkClient.DefaultArtworkSize): String {
        require(coverId.isNotBlank() && coverId.length <= 128) { "Invalid cover ID." }
        require(size in AllowedArtworkSizes) { "Invalid artwork size." }
        return serverUrl.newBuilder()
            .addPathSegments("api/v1/covers")
            .addPathSegment(coverId)
            .addPathSegment(size.toString())
            .build()
            .toString()
    }

    fun isAllowed(url: HttpUrl): Boolean {
        if (url.scheme != serverUrl.scheme ||
            url.host != serverUrl.host ||
            url.port != serverUrl.port ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            url.query != null ||
            url.fragment != null
        ) {
            return false
        }
        val baseSegments = serverUrl.pathSegments.filter(String::isNotEmpty)
        val requestSegments = url.pathSegments.filter(String::isNotEmpty)
        if (requestSegments.size != baseSegments.size + 4 &&
            requestSegments.size != baseSegments.size + 5
        ) return false
        if (requestSegments.take(baseSegments.size) != baseSegments) return false
        val suffix = requestSegments.drop(baseSegments.size)
        val coverRoute = suffix[0] == "api" &&
            suffix[1] == "v1" &&
            suffix[2] == "covers" &&
            suffix[3].isNotEmpty() &&
            suffix[3].length <= 128
        return coverRoute && (suffix.size == 4 || suffix[4].toIntOrNull() in AllowedArtworkSizes)
    }

    fun originalFallbackFor(url: HttpUrl): HttpUrl? {
        if (!isAllowed(url)) return null
        val baseSegments = serverUrl.pathSegments.filter(String::isNotEmpty)
        val requestSegments = url.pathSegments.filter(String::isNotEmpty)
        if (requestSegments.size != baseSegments.size + 5) return null
        return url.newBuilder().removePathSegment(url.pathSegments.lastIndex).build()
    }

    private companion object {
        val AllowedArtworkSizes = setOf(128, 256, 512)
    }
}

internal class ArtworkAuthorizationInterceptor(
    private val policy: ArtworkRequestPolicy,
    private val token: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!policy.isAllowed(chain.request().url)) {
            throw IOException("Refusing artwork request outside the paired Velin server.")
        }
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        val response = chain.proceed(request)
        val originalUrl = policy.originalFallbackFor(request.url)
        if (response.code != 404 || originalUrl == null) return response
        response.close()
        return chain.proceed(request.newBuilder().url(originalUrl).build())
    }
}
