package com.haraldmue.velin.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.haraldmue.velin.playback.PlaybackArtwork.withoutEmbeddedArtwork
import com.haraldmue.velin.playback.PlaybackArtwork.withoutPublicArtworkUri
import com.haraldmue.velin.playback.PlaybackArtwork.withEmbeddedArtwork
import java.io.Closeable

/** Embeds current-item artwork and hides the auth-gated URI from Auto. */
internal class CurrentArtworkMetadataEnricher(
    private val player: Player,
    private val loader: AuthenticatedArtworkBitmapLoader,
) : Player.Listener, Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var pending: ListenableFuture<ByteArray>? = null
    private var closed = false

    init {
        player.addListener(this)
        request(player.currentMediaItem)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        request(mediaItem)
    }

    override fun close() {
        closed = true
        generation++
        pending?.cancel(true)
        pending = null
        player.removeListener(this)
    }

    private fun request(item: MediaItem?) {
        val requestGeneration = ++generation
        pending?.cancel(true)
        pending = null
        if (closed || item == null) {
            removeMarkedArtwork(C.INDEX_UNSET)
            return
        }
        val metadata = item.mediaMetadata
        if (metadata.artworkData != null) {
            if (metadata.artworkUri != null) {
                replaceCurrent(item.withoutPublicArtworkUri())
            }
            removeMarkedArtwork(player.currentMediaItemIndex)
            return
        }
        val uri = metadata.artworkUri ?: run {
            removeMarkedArtwork(player.currentMediaItemIndex)
            return
        }

        val mediaId = item.mediaId
        val future = loader.loadEmbeddedArtworkData(uri)
        pending = future
        future.addListener(
            {
                val data = runCatching { future.get() }.getOrNull() ?: return@addListener
                mainHandler.post {
                    val current = player.currentMediaItem ?: return@post
                    if (closed || requestGeneration != generation || current.mediaId != mediaId) {
                        return@post
                    }
                    val index = player.currentMediaItemIndex
                    removeMarkedArtwork(index)
                    replaceCurrent(current.withEmbeddedArtwork(data))
                    pending = null
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun replaceCurrent(item: MediaItem) {
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) return
        player.replaceMediaItem(index, item)
    }

    private fun removeMarkedArtwork(retainedIndex: Int) {
        for (index in 0 until player.mediaItemCount) {
            if (index == retainedIndex) continue
            val item = player.getMediaItemAt(index)
            if (item.mediaMetadata.extras?.getBoolean(PlaybackArtwork.EXTRA_EMBEDDED) != true) continue
            player.replaceMediaItem(index, item.withoutEmbeddedArtwork())
        }
    }
}
