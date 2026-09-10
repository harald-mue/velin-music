package com.haraldmue.velin.playback

import android.net.Uri
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.DeviceCredentials
import java.io.File

internal const val AutoArtworkAuthority = "com.haraldmue.velin.artwork"

internal data class AutoArtworkRef(
    val coverId: String,
    val size: Int,
)

internal object AutoArtworkUris {
    fun uri(coverId: String, size: Int = ArtworkClient.DefaultArtworkSize): Uri? {
        val id = coverId.trim()
        if (!isSafeCoverId(id) || size !in AutoArtworkSizes) return null
        return Uri.Builder()
            .scheme("content")
            .authority(AutoArtworkAuthority)
            .appendPath("covers")
            .appendPath(id)
            .appendPath(size.toString())
            .build()
    }

    fun parse(uri: Uri): AutoArtworkRef? {
        if (uri.scheme != "content" || uri.authority != AutoArtworkAuthority) return null
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "covers") return null
        val size = segments[2].toIntOrNull() ?: return null
        if (!isSafeCoverId(segments[1]) || size !in AutoArtworkSizes) return null
        return AutoArtworkRef(coverId = segments[1], size = size)
    }
}

internal fun isSafeCoverId(coverId: String): Boolean =
    coverId.isNotEmpty() &&
        coverId.length <= 128 &&
        coverId.all { character -> character.isLetterOrDigit() || character == '-' }

internal fun coilArtworkCacheFile(
    cacheDirectory: File,
    credentials: DeviceCredentials,
    coverId: String,
    size: Int,
): File? {
    val url = runCatching {
        ArtworkRequestPolicy(credentials.serverUrl).urlFor(coverId, size)
    }.getOrNull() ?: return null
    val file = cacheDirectory.resolve("${artworkDiskCacheKey(url)}.1")
    return file.takeIf { it.isFile && it.length() in 1..MaxServedArtworkBytes }
}

internal fun writeCoilArtworkCache(cacheDirectory: File, url: String, bytes: ByteArray): File {
    require(bytes.isNotEmpty() && bytes.size <= MaxServedArtworkBytes) { "Invalid artwork payload." }
    cacheDirectory.mkdirs()
    val dest = cacheDirectory.resolve("${artworkDiskCacheKey(url)}.1")
    val tmp = File.createTempFile("velin-artwork-", ".part", cacheDirectory)
    try {
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
        }
        check(dest.isFile && dest.length() == bytes.size.toLong()) { "Artwork cache write failed." }
        return dest
    } catch (error: Exception) {
        tmp.delete()
        throw error
    }
}

private val AutoArtworkSizes = setOf(128, 256, 512)
internal const val MaxServedArtworkBytes = 1 * 1_024 * 1_024
