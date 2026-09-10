package com.haraldmue.velin.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import com.haraldmue.velin.playback.PlaybackArtwork.withEmbeddedArtwork
import com.haraldmue.velin.playback.PlaybackArtwork.withoutPublicArtworkUri
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

internal const val MaxEmbeddedBrowseArtworkItems = 8
internal const val MaxEmbeddedBrowseArtworkBytes = 64 * 1_024
internal const val BrowseArtworkLoadTimeoutMs = 2_000L

internal suspend fun embedBrowseArtwork(
    items: List<MediaItem>,
    loadEmbedded: suspend (Uri) -> ByteArray,
): List<MediaItem> = coroutineScope {
    items.mapIndexed { index, item ->
        async {
            withTimeoutOrNull(BrowseArtworkLoadTimeoutMs) {
                embedBrowseArtworkItem(item, index, loadEmbedded)
            } ?: item
        }
    }.awaitAll()
}

internal suspend fun embedBrowseArtworkItem(
    item: MediaItem,
    index: Int,
    loadEmbedded: suspend (Uri) -> ByteArray,
): MediaItem {
    if (index >= MaxEmbeddedBrowseArtworkItems) return item
    if (item.mediaMetadata.artworkData != null) {
        val existing = item.mediaMetadata.artworkUri
        return if (existing == null || existing.scheme == "content") {
            item
        } else {
            item.withoutPublicArtworkUri()
        }
    }
    val uri = item.mediaMetadata.artworkUri ?: return item
    if (uri.scheme == "content") return item
    return try {
        val data = loadEmbedded(uri)
        if (data.size > MaxEmbeddedBrowseArtworkBytes) item else item.withEmbeddedArtwork(data)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        item
    }
}
