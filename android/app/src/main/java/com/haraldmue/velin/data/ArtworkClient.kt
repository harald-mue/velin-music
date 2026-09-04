package com.haraldmue.velin.data

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ArtworkAuthorizationInterceptor(policy, credentials.token))
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = httpClient))
        }
        .build()

    fun urlFor(coverId: String?): String? = coverId?.let { id ->
        runCatching { policy.urlFor(id) }.getOrNull()
    }

    fun cancelInFlight() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }

    fun close() {
        cancelInFlight()
        imageLoader.shutdown()
    }

    private companion object {
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

    fun urlFor(coverId: String): String {
        require(coverId.isNotBlank() && coverId.length <= 128) { "Invalid cover ID." }
        return serverUrl.newBuilder()
            .addPathSegments("api/v1/covers")
            .addPathSegment(coverId)
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
        if (requestSegments.size != baseSegments.size + 4) return false
        if (requestSegments.take(baseSegments.size) != baseSegments) return false
        val suffix = requestSegments.drop(baseSegments.size)
        return suffix[0] == "api" &&
            suffix[1] == "v1" &&
            suffix[2] == "covers" &&
            suffix[3].isNotEmpty() &&
            suffix[3].length <= 128
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
        return chain.proceed(request)
    }
}
