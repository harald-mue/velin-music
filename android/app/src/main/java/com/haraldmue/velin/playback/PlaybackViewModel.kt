package com.haraldmue.velin.playback

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackQueueItem(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
)

enum class PlaybackRepeatMode {
    Off,
    All,
    One,
}

/** UI-safe projection of the Media3 controller state. */
data class PlaybackUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val format: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val queue: List<PlaybackQueueItem> = emptyList(),
    val canEditQueue: Boolean = false,
    val canReorderQueue: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    val error: String? = null,
)

class PlaybackViewModel(
    context: Context,
    credentials: DeviceCredentials,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val mediaItemFactory = PlaybackMediaItemFactory(credentials)
    private val mutableState = androidx.compose.runtime.mutableStateOf(PlaybackUiState())
    val state: androidx.compose.runtime.State<PlaybackUiState> = mutableState
    private val controllerFuture = MediaController.Builder(
        applicationContext,
        SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var pendingQueue: PendingQueue? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateState(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                isBuffering = false,
                error = "Playback failed. Check the server connection and device access.",
            )
        }
    }

    init {
        controllerFuture.addListener(
            {
                try {
                    val connectedController = controllerFuture.get()
                    controller = connectedController
                    connectedController.addListener(listener)
                    updateState(connectedController)
                    pendingQueue?.let { queue ->
                        pendingQueue = null
                        startQueue(connectedController, queue.items, queue.startIndex)
                    }
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(
                        error = "Could not connect to the playback service.",
                    )
                }
            },
            ContextCompat.getMainExecutor(applicationContext),
        )
        viewModelScope.launch {
            while (isActive) {
                controller?.let(::updateProgress)
                delay(500)
            }
        }
    }

    fun play(track: Track) {
        playQueue(listOf(track), 0)
    }

    fun enqueueTrack(track: Track, playNext: Boolean) {
        val item = try {
            mediaItemFactory.create(track)
        } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = "This track cannot be played.")
            return
        }
        val currentController = controller
        if (currentController == null) {
            pendingQueue = PendingQueue(listOf(item), 0)
            return
        }
        enqueueItems(currentController, listOf(item), playNext)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        pendingQueue = null
        if (!isValidPlaybackQueue(tracks.size, startIndex)) {
            mutableState.value = mutableState.value.copy(error = "This playback queue is invalid.")
            return
        }
        val items = try {
            tracks.map(mediaItemFactory::create)
        } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = "This playback queue contains an invalid track.")
            return
        }
        val currentController = controller
        if (currentController == null) {
            pendingQueue = PendingQueue(items, startIndex)
            return
        }
        startQueue(currentController, items, startIndex)
    }

    fun togglePlayPause() {
        controller?.let { currentController ->
            if (currentController.isPlaying) {
                currentController.pause()
            } else {
                if (currentController.playbackState == Player.STATE_ENDED) {
                    currentController.seekTo(0)
                }
                currentController.play()
            }
        }
    }

    fun skipToPrevious() {
        controller?.let { currentController ->
            if (currentController.hasPreviousMediaItem()) {
                currentController.seekToPreviousMediaItem()
                currentController.play()
            }
        }
    }

    fun skipToNext() {
        controller?.let { currentController ->
            if (currentController.hasNextMediaItem()) {
                currentController.seekToNextMediaItem()
                currentController.play()
            }
        }
    }

    fun selectQueueItem(index: Int) {
        controller?.let { currentController ->
            if (index !in 0 until currentController.mediaItemCount ||
                !currentController.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            ) {
                return
            }
            currentController.seekToDefaultPosition(index)
            currentController.play()
        }
    }

    fun removeQueueItem(index: Int) {
        controller?.let { currentController ->
            if (currentController.mediaItemCount <= 1 ||
                index !in 0 until currentController.mediaItemCount ||
                !currentController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            ) {
                return
            }
            currentController.removeMediaItem(index)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        controller?.let { currentController ->
            if (currentController.shuffleModeEnabled ||
                !currentController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                !isValidQueueMove(fromIndex, toIndex, currentController.mediaItemCount)
            ) {
                return
            }
            currentController.moveMediaItem(fromIndex, toIndex)
        }
    }

    fun toggleShuffle() {
        controller?.let { currentController ->
            if (currentController.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
                currentController.shuffleModeEnabled = !currentController.shuffleModeEnabled
            }
        }
    }

    fun cycleRepeatMode() {
        controller?.let { currentController ->
            if (!currentController.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) return
            currentController.repeatMode = when (currentController.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.let { currentController ->
            val duration = playableDuration(currentController) ?: return
            if (!currentController.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
            currentController.seekTo(positionMs.coerceIn(0, duration))
            updateProgress(currentController)
        }
    }

    fun stopAndClear() {
        pendingQueue = null
        controller?.run {
            stop()
            clearMediaItems()
        }
        mutableState.value = PlaybackUiState(connected = controller != null)
    }

    private fun startQueue(controller: MediaController, items: List<MediaItem>, startIndex: Int) {
        mutableState.value = mutableState.value.copy(error = null)
        controller.setMediaItems(items, startIndex, 0)
        controller.prepare()
        controller.play()
        updateState(controller)
    }

    private fun enqueueItems(controller: MediaController, items: List<MediaItem>, playNext: Boolean) {
        if (items.isEmpty()) return
        if (!controller.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        mutableState.value = mutableState.value.copy(error = null)
        if (controller.mediaItemCount == 0) {
            startQueue(controller, items, 0)
            return
        }
        if (!canEnqueue(controller.mediaItemCount, items.size)) {
            mutableState.value = mutableState.value.copy(error = "The playback queue is full.")
            return
        }
        val index = if (playNext) {
            controller.currentMediaItemIndex + 1
        } else {
            controller.mediaItemCount
        }
        controller.addMediaItems(index, items)
        updateState(controller)
    }

    private fun updateState(player: Player) {
        val item = player.currentMediaItem
        val metadata = item?.mediaMetadata
        val duration = playableDuration(player)
        val position = boundedPosition(player.currentPosition, duration)
        val bufferedPosition = boundedPosition(player.bufferedPosition, duration)
        val queue = if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
            (0 until player.mediaItemCount).map { index ->
                val queueItem = player.getMediaItemAt(index)
                PlaybackQueueItem(
                    mediaId = queueItem.mediaId,
                    title = queueItem.mediaMetadata.title?.toString() ?: "Unknown track",
                    artist = queueItem.mediaMetadata.artist?.toString(),
                    artworkUrl = queueItem.mediaMetadata.artworkUri?.toString(),
                )
            }
        } else {
            emptyList()
        }
        mutableState.value = PlaybackUiState(
            connected = true,
            mediaId = item?.mediaId,
            title = metadata?.title?.toString(),
            artist = metadata?.artist?.toString(),
            album = metadata?.albumTitle?.toString(),
            artworkUrl = metadata?.artworkUri?.toString(),
            format = when (item?.localConfiguration?.mimeType) {
                "audio/flac" -> "FLAC"
                "audio/mpeg" -> "MP3"
                else -> null
            },
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isEnded = player.playbackState == Player.STATE_ENDED,
            positionMs = position,
            bufferedPositionMs = bufferedPosition,
            durationMs = duration,
            canSeek = duration != null && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            queueIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            queueSize = player.mediaItemCount,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            queue = queue,
            canEditQueue = queue.size > 1 && player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS),
            canReorderQueue = canReorderQueue(
                queueSize = queue.size,
                shuffleEnabled = player.shuffleModeEnabled,
                canEditQueue = queue.size > 1 && player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS),
            ),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.All
                Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.One
                else -> PlaybackRepeatMode.Off
            },
            error = mutableState.value.error.takeIf { player.playerError != null },
        )
    }

    private fun updateProgress(player: Player) {
        if (player.currentMediaItem == null) return
        val duration = playableDuration(player)
        mutableState.value = mutableState.value.copy(
            positionMs = boundedPosition(player.currentPosition, duration),
            bufferedPositionMs = boundedPosition(player.bufferedPosition, duration),
            durationMs = duration,
            canSeek = duration != null && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isEnded = player.playbackState == Player.STATE_ENDED,
        )
    }

    private fun playableDuration(player: Player): Long? =
        player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    private fun boundedPosition(position: Long, duration: Long?): Long =
        if (duration == null) position.coerceAtLeast(0) else position.coerceIn(0, duration)

    override fun onCleared() {
        controller?.removeListener(listener)
        controller = null
        pendingQueue = null
        MediaController.releaseFuture(controllerFuture)
    }

    private data class PendingQueue(val items: List<MediaItem>, val startIndex: Int)
}

internal fun isValidPlaybackQueue(queueSize: Int, startIndex: Int): Boolean =
    queueSize in 1..500 && startIndex in 0 until queueSize

internal fun canEnqueue(currentSize: Int, addCount: Int): Boolean =
    addCount > 0 && currentSize + addCount <= 500

internal fun canReorderQueue(
    queueSize: Int,
    shuffleEnabled: Boolean,
    canEditQueue: Boolean,
): Boolean = canEditQueue && queueSize > 1 && !shuffleEnabled

internal fun isValidQueueMove(fromIndex: Int, toIndex: Int, queueSize: Int): Boolean {
    if (queueSize <= 1) return false
    if (fromIndex !in 0 until queueSize) return false
    if (toIndex !in 0 until queueSize) return false
    return fromIndex != toIndex
}

class PlaybackViewModelFactory(
    private val context: Context,
    private val credentials: DeviceCredentials,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlaybackViewModel::class.java))
        return PlaybackViewModel(context, credentials) as T
    }
}
