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
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.SavedQueueEntry
import com.haraldmue.velin.data.SavedQueueRecord
import com.haraldmue.velin.data.SavedQueueStore
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.canSaveSavedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackQueueItem(
    val mediaId: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val available: Boolean = true,
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
    val canSaveQueue: Boolean = false,
    val canLoadQueue: Boolean = false,
    val queueBusy: Boolean = false,
    val error: String? = null,
)

class PlaybackViewModel(
    context: Context,
    credentials: DeviceCredentials,
    private val libraryGateway: LibraryGateway,
    private val savedQueueStore: SavedQueueStore,
) : ViewModel() {
    private val applicationContext = context.applicationContext
    private val mediaItemFactory = PlaybackMediaItemFactory(credentials)
    private val mutableState = androidx.compose.runtime.mutableStateOf(PlaybackUiState())
    val state: androidx.compose.runtime.State<PlaybackUiState> = mutableState
    private var displayQueue: MutableList<PlaybackQueueItem>? = null
    private val tracksById = linkedMapOf<String, Track>()
    private var persistedIds: List<String>? = null
    private var queueBusy = false
    private val controllerFuture = MediaController.Builder(
        applicationContext,
        SessionToken(applicationContext, ComponentName(applicationContext, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private var pendingQueue: PendingQueue? = null
    private var controllerReleased = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateState(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = mutableState.value.copy(
                isPlaying = false,
                isBuffering = false,
                error = playbackErrorMessage(error),
            )
        }
    }

    init {
        viewModelScope.launch {
            persistedIds = withContext(Dispatchers.IO) { readPersistedIds() }
            controller?.let(::updateState) ?: run {
                mutableState.value = mutableState.value.copy(canLoadQueue = canLoadSavedQueue())
            }
        }
        controllerFuture.addListener(
            {
                try {
                    val connectedController = controllerFuture.get()
                    if (controllerReleased) {
                        MediaController.releaseFuture(controllerFuture)
                        return@addListener
                    }
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
        enqueueTracks(listOf(track), playNext)
    }

    fun enqueueTracks(tracks: List<Track>, playNext: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = try {
            tracks.map(mediaItemFactory::create)
        } catch (_: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = "This playback queue contains an invalid track.")
            return
        }
        val overlay = displayQueue
        val currentController = controller
        val currentSize = when {
            overlay != null -> overlay.size
            currentController != null && currentController.mediaItemCount > 0 -> currentController.mediaItemCount
            else -> pendingQueue?.items?.size ?: 0
        }
        if (!canEnqueue(currentSize, tracks.size)) {
            mutableState.value = mutableState.value.copy(error = "The playback queue is full.")
            return
        }
        if (overlay != null) {
            val currentId = currentController?.currentMediaItem?.mediaId
            val currentIndex = overlay.indexOfFirst { it.mediaId == currentId }
            var insertAt = if (playNext && currentIndex >= 0) currentIndex + 1 else overlay.size
            tracks.zip(items).forEach { (track, item) ->
                tracksById[track.id] = track
                overlay.add(insertAt, queueItemFor(track, item))
                insertAt += 1
            }
        }
        if (currentController == null) {
            pendingQueue = pendingQueueFromOverlayOr(items)
            return
        }
        enqueueItems(currentController, items, playNext)
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        pendingQueue = null
        forgetOverlay()
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
        val overlay = displayQueue
        val playerIndex = if (overlay != null) {
            val item = overlay.getOrNull(index) ?: return
            if (!item.available) return
            controller?.let { playerIndexOf(it, item.mediaId) } ?: return
        } else {
            index
        }
        controller?.let { currentController ->
            if (playerIndex !in 0 until currentController.mediaItemCount ||
                !currentController.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            ) {
                return
            }
            currentController.seekToDefaultPosition(playerIndex)
            currentController.play()
        }
    }

    fun removeQueueItem(index: Int) {
        val overlay = displayQueue
        if (overlay != null) {
            if (index !in overlay.indices) return
            val removed = overlay.removeAt(index)
            if (removed.available) {
                tracksById.remove(removed.mediaId)
                controller?.let { currentController ->
                    val playerIndex = playerIndexOf(currentController, removed.mediaId)
                    if (playerIndex >= 0 && currentController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                        if (currentController.mediaItemCount <= 1) {
                            currentController.stop()
                            currentController.clearMediaItems()
                        } else {
                            currentController.removeMediaItem(playerIndex)
                        }
                    }
                }
            }
            if (overlay.isEmpty()) {
                displayQueue = null
            }
            controller?.let(::updateState)
            return
        }
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
        val overlay = displayQueue
        if (overlay != null) {
            if (!isValidQueueMove(fromIndex, toIndex, overlay.size) || mutableState.value.shuffleEnabled) {
                return
            }
            val item = overlay.removeAt(fromIndex)
            overlay.add(toIndex, item)
            rebuildPlayerFromOverlay()
            controller?.let(::updateState)
            return
        }
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
        forgetOverlay()
        controller?.run {
            stop()
            clearMediaItems()
        }
        mutableState.value = PlaybackUiState(
            connected = controller != null,
            canLoadQueue = canLoadSavedQueue(),
        )
    }

    fun clearQueue() {
        stopAndClear()
    }

    fun releaseForCredentialChange() {
        if (controllerReleased) return
        controllerReleased = true
        forgetOverlay()
        controller?.removeListener(listener)
        controller = null
        pendingQueue = null
        MediaController.releaseFuture(controllerFuture)
    }

    fun saveQueue() {
        if (queueBusy) return
        val items = itemsForSave()
        if (!canSaveSavedQueue(items.map { it.id }, persistedIds)) return
        queueBusy = true
        mutableState.value = mutableState.value.copy(queueBusy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    savedQueueStore.write(SavedQueueRecord(items))
                }
                persistedIds = items.map { it.id }
                displayQueue?.removeAll { !it.available }
                if (displayQueue?.isEmpty() == true) {
                    displayQueue = null
                    tracksById.clear()
                }
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Could not save the queue.")
            } finally {
                queueBusy = false
                controller?.let(::updateState) ?: publishIdleOverlayState()
            }
        }
    }

    fun loadQueue() {
        if (queueBusy) return
        queueBusy = true
        mutableState.value = mutableState.value.copy(queueBusy = true, error = null)
        viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { savedQueueStore.read() }
                if (record == null || record.items.isEmpty()) {
                    persistedIds = null
                    return@launch
                }
                persistedIds = record.ids
                val overlay = ArrayList<PlaybackQueueItem>(record.items.size)
                val playable = ArrayList<Track>()
                for (entry in record.items) {
                    val resolved = resolveSavedEntry(entry)
                    overlay += resolved.item
                    resolved.track?.let(playable::add)
                }
                displayQueue = overlay.toMutableList()
                tracksById.clear()
                playable.forEach { tracksById[it.id] = it }
                val mediaItems = playable.map(mediaItemFactory::create)
                val currentController = controller
                if (playable.isEmpty()) {
                    currentController?.run {
                        stop()
                        clearMediaItems()
                    }
                    publishIdleOverlayState()
                } else if (currentController == null) {
                    pendingQueue = PendingQueue(mediaItems, 0)
                    publishIdleOverlayState()
                } else {
                    startQueue(currentController, mediaItems, 0)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                forgetOverlay()
                mutableState.value = mutableState.value.copy(error = "Could not load the saved queue.")
            } finally {
                queueBusy = false
                controller?.let(::updateState) ?: publishIdleOverlayState()
            }
        }
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
        val queue = displayQueue?.toList() ?: if (player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
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
        val currentId = item?.mediaId
        val queueIndex = if (displayQueue != null) {
            queue.indexOfFirst { it.available && it.mediaId == currentId }.let { match ->
                if (match >= 0) match else player.currentMediaItemIndex.coerceAtLeast(0)
            }
        } else {
            player.currentMediaItemIndex.coerceAtLeast(0)
        }
        val canChangeItems = player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        val canEdit = queue.isNotEmpty() && (displayQueue != null || (queue.size > 1 && canChangeItems))
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
            queueIndex = queueIndex,
            queueSize = queue.size,
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            queue = queue,
            canEditQueue = canEdit,
            canReorderQueue = canReorderQueue(
                queueSize = queue.size,
                shuffleEnabled = player.shuffleModeEnabled,
                canEditQueue = canEdit,
            ),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.All
                Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.One
                else -> PlaybackRepeatMode.Off
            },
            canSaveQueue = canSaveSavedQueue(availableIds(), persistedIds),
            canLoadQueue = canLoadSavedQueue(),
            queueBusy = queueBusy,
            error = mutableState.value.error,
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
        releaseForCredentialChange()
    }

    private fun forgetOverlay() {
        displayQueue = null
        tracksById.clear()
    }

    private fun readPersistedIds(): List<String>? =
        try {
            savedQueueStore.read()?.ids
        } catch (_: Exception) {
            null
        }

    private fun canLoadSavedQueue(): Boolean =
        !queueBusy && persistedIds != null

    private fun pendingQueueFromOverlayOr(items: List<MediaItem>): PendingQueue {
        val overlay = displayQueue ?: return PendingQueue(items, 0)
        val overlayItems = overlay.mapNotNull { queued ->
            if (queued.available) tracksById[queued.mediaId]?.let(mediaItemFactory::create) else null
        }
        return PendingQueue(overlayItems.ifEmpty { items }, 0)
    }

    private fun availableIds(): List<String> {
        displayQueue?.let { overlay ->
            return overlay.filter { it.available }.map { it.mediaId }
        }
        val player = controller ?: return emptyList()
        if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
        return (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    }

    private fun itemsForSave(): List<SavedQueueEntry> {
        val overlay = displayQueue
        if (overlay != null) {
            return overlay.filter { it.available }.map {
                SavedQueueEntry(id = it.mediaId, title = it.title, artist = it.artist)
            }
        }
        return availableIds().map { id ->
            val player = controller
            val item = (0 until (player?.mediaItemCount ?: 0))
                .mapNotNull { index -> player?.getMediaItemAt(index)?.takeIf { it.mediaId == id } }
                .firstOrNull()
            SavedQueueEntry(
                id = id,
                title = item?.mediaMetadata?.title?.toString() ?: "Unknown track",
                artist = item?.mediaMetadata?.artist?.toString(),
            )
        }
    }

    private fun queueItemFor(track: Track, mediaItem: MediaItem): PlaybackQueueItem =
        PlaybackQueueItem(
            mediaId = track.id,
            title = track.title,
            artist = track.artistName,
            artworkUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
            available = true,
        )

    private fun playerIndexOf(player: MediaController, mediaId: String): Int =
        (0 until player.mediaItemCount).indexOfFirst { player.getMediaItemAt(it).mediaId == mediaId }

    private fun rebuildPlayerFromOverlay() {
        val overlay = displayQueue ?: return
        val currentController = controller ?: return
        if (!currentController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        val tracks = overlay.mapNotNull { item ->
            if (item.available) tracksById[item.mediaId] else null
        }
        if (tracks.isEmpty()) {
            currentController.stop()
            currentController.clearMediaItems()
            return
        }
        val items = tracks.map(mediaItemFactory::create)
        val currentId = currentController.currentMediaItem?.mediaId
        val startIndex = items.indexOfFirst { it.mediaId == currentId }.coerceAtLeast(0)
        val position = currentController.currentPosition.coerceAtLeast(0)
        currentController.setMediaItems(items, startIndex, position)
        currentController.prepare()
    }

    private suspend fun resolveSavedEntry(entry: SavedQueueEntry): ResolvedSavedItem {
        return try {
            val track = libraryGateway.loadTrack(entry.id).toTrack()
            val mediaItem = mediaItemFactory.create(track)
            ResolvedSavedItem(item = queueItemFor(track, mediaItem), track = track)
        } catch (error: ApiException) {
            if (error.isNotFound) {
                ResolvedSavedItem(
                    item = PlaybackQueueItem(
                        mediaId = entry.id,
                        title = entry.title,
                        artist = entry.artist,
                        artworkUrl = null,
                        available = false,
                    ),
                    track = null,
                )
            } else {
                throw error
            }
        } catch (_: IllegalArgumentException) {
            ResolvedSavedItem(
                item = PlaybackQueueItem(
                    mediaId = entry.id,
                    title = entry.title,
                    artist = entry.artist,
                    artworkUrl = null,
                    available = false,
                ),
                track = null,
            )
        }
    }

    private fun publishIdleOverlayState() {
        val queue = displayQueue?.toList().orEmpty()
        mutableState.value = PlaybackUiState(
            connected = controller != null,
            queue = queue,
            queueSize = queue.size,
            canEditQueue = queue.isNotEmpty(),
            canReorderQueue = queue.size > 1,
            canSaveQueue = canSaveSavedQueue(availableIds(), persistedIds),
            canLoadQueue = canLoadSavedQueue(),
            queueBusy = queueBusy,
            error = mutableState.value.error,
        )
    }

    private data class ResolvedSavedItem(
        val item: PlaybackQueueItem,
        val track: Track?,
    )

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

internal fun playbackErrorMessage(error: PlaybackException): String =
    playbackErrorMessage(error.errorCodeName)

internal fun playbackErrorMessage(errorCodeName: String): String {
    val code = errorCodeName.removePrefix("ERROR_CODE_")
    return "Playback failed ($code). Check the server and proxy stream logs."
}

class PlaybackViewModelFactory(
    private val context: Context,
    private val credentials: DeviceCredentials,
    private val libraryGateway: LibraryGateway,
    private val savedQueueStore: SavedQueueStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PlaybackViewModel::class.java))
        return PlaybackViewModel(context, credentials, libraryGateway, savedQueueStore) as T
    }
}
