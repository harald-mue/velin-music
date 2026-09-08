package com.haraldmue.velin.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.haraldmue.velin.data.CredentialStore
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.ServerAddress
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val AuthorizationHeader = "Authorization"

/** Creates Media3 data sources that can access only the currently paired Velin server's streams. */
class PlaybackDataSourceFactory private constructor(
    private val credentialsProvider: () -> DeviceCredentials?,
    private val httpClient: OkHttpClient,
) : DataSource.Factory {
    constructor(
        credentials: DeviceCredentials,
        httpClient: OkHttpClient = playbackHttpClient(),
    ) : this(credentialsProvider = { credentials }, httpClient = httpClient)

    override fun createDataSource(): DataSource {
        val credentials = credentialsProvider()
            ?: throw IllegalStateException("Playback credentials are unavailable.")
        val serverUrl = ServerAddress.normalize(credentials.serverUrl).toHttpUrl()
        val delegate = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(
                mapOf(AuthorizationHeader to "Bearer ${credentials.token}"),
            )
            .createDataSource()
        return ServerBoundDataSource(serverUrl = serverUrl, delegate = delegate)
    }

    internal companion object {
        fun reloading(
            credentialStore: CredentialStore,
            httpClient: OkHttpClient = playbackHttpClient(),
        ): PlaybackDataSourceFactory = PlaybackDataSourceFactory(
            credentialsProvider = credentialStore::load,
            httpClient = httpClient,
        )

        fun playbackHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            // FLAC range reads can idle while ExoPlayer plays from its buffer; a 12s
            // read timeout aborts healthy emulator/LAN streams. Network loss still
            // cancels in-flight calls from PlaybackService.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .connectionPool(
                ConnectionPool(PlaybackHttpIdleConnections, PlaybackHttpIdleKeepAliveSeconds, TimeUnit.SECONDS),
            )
            .build()
    }
}

internal const val PlaybackHttpIdleConnections = 2
internal const val PlaybackHttpIdleKeepAliveSeconds = 60L

internal class ServerBoundDataSource(
    private val serverUrl: HttpUrl,
    private val delegate: DataSource,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!isAllowedStream(dataSpec.uri)) {
            throw IOException("Refusing media request outside the paired Velin server.")
        }
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        delegate.close()
    }

    private fun isAllowedStream(uri: Uri): Boolean {
        val requestUrl = uri.toString().toHttpUrlOrNull() ?: return false
        if (requestUrl.scheme != serverUrl.scheme ||
            requestUrl.host != serverUrl.host ||
            requestUrl.port != serverUrl.port ||
            requestUrl.username.isNotEmpty() ||
            requestUrl.password.isNotEmpty() ||
            requestUrl.query != null ||
            requestUrl.fragment != null
        ) {
            return false
        }
        val baseSegments = serverUrl.pathSegments.filter(String::isNotEmpty)
        val requestSegments = requestUrl.pathSegments.filter(String::isNotEmpty)
        if (requestSegments.size != baseSegments.size + 5) return false
        if (requestSegments.take(baseSegments.size) != baseSegments) return false
        val suffix = requestSegments.drop(baseSegments.size)
        return suffix[0] == "api" &&
            suffix[1] == "v1" &&
            suffix[2] == "tracks" &&
            suffix[3].isNotEmpty() &&
            suffix[3].length <= 128 &&
            suffix[4] == "stream"
    }
}
