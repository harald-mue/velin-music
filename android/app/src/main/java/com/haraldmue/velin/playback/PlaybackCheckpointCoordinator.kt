package com.haraldmue.velin.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import com.haraldmue.velin.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal data class LoadedPlaybackResume(
    val record: PlaybackResumeRecord,
    val mediaItems: List<MediaItem>,
)

internal class PlaybackResumeLoader(
    private val store: PlaybackResumeStore,
    private val mediaItemFactory: PlaybackMediaItemFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(): LoadedPlaybackResume? = withContext(dispatcher) {
        val record = store.read() ?: return@withContext null
        try {
            LoadedPlaybackResume(record, record.toMediaItems(mediaItemFactory))
        } catch (error: IllegalArgumentException) {
            store.delete()
            null
        }
    }
}

internal class PlaybackRestoreGuard {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun cancel() {
        generation++
    }

    fun isCurrent(token: Long): Boolean = token == generation
}

internal object PlaybackResumeMetadata {
    const val ExtraFormat = "com.haraldmue.velin.RESUME_FORMAT"
    const val ExtraCoverId = "com.haraldmue.velin.RESUME_COVER_ID"
}

/** Serializes and conflates checkpoint writes on the supplied IO dispatcher. */
internal class PlaybackCheckpointCoordinator private constructor(
    private val writeRecord: suspend (PlaybackResumeRecord) -> Unit,
    private val deleteRecord: suspend () -> Unit,
    dispatcher: CoroutineDispatcher,
    private val onError: (Exception) -> Unit,
) {
    private val commands = Channel<Command>(Channel.CONFLATED)
    private val persistenceMutex = Mutex()
    private val generation = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = scope.launch {
        for (command in commands) {
            try {
                persistenceMutex.withLock {
                    if (command.generation != generation.get()) return@withLock
                    when (command) {
                        is Command.Write -> writeRecord(command.record)
                        is Command.Delete -> deleteRecord()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    constructor(
        store: PlaybackResumeStore,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        onError: (Exception) -> Unit = {},
    ) : this(
        writeRecord = { record -> store.write(record) },
        deleteRecord = store::delete,
        dispatcher = dispatcher,
        onError = onError,
    )

    fun checkpoint(record: PlaybackResumeRecord) {
        commands.trySend(Command.Write(generation.get(), record))
    }

    fun delete() {
        val deleteGeneration = generation.incrementAndGet()
        commands.trySend(Command.Delete(deleteGeneration))
    }

    suspend fun deleteAndAwait() {
        val deleteGeneration = generation.incrementAndGet()
        try {
            persistenceMutex.withLock {
                if (deleteGeneration == generation.get()) {
                    deleteRecord()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error)
            throw error
        }
    }

    /** Processes the last buffered command and then terminates the writer. */
    fun close() {
        commands.close()
    }

    internal suspend fun awaitClosed() {
        worker.join()
    }

    internal companion object {
        fun forTesting(
            dispatcher: CoroutineDispatcher,
            writeRecord: suspend (PlaybackResumeRecord) -> Unit,
            deleteRecord: suspend () -> Unit,
            onError: (Exception) -> Unit = {},
        ): PlaybackCheckpointCoordinator = PlaybackCheckpointCoordinator(
            writeRecord = writeRecord,
            deleteRecord = deleteRecord,
            dispatcher = dispatcher,
            onError = onError,
        )
    }

    private sealed interface Command {
        val generation: Long

        data class Write(
            override val generation: Long,
            val record: PlaybackResumeRecord,
        ) : Command

        data class Delete(override val generation: Long) : Command
    }
}

internal fun capturePlaybackResumeRecord(
    player: Player,
    namespace: String,
    updatedAtMs: Long,
): PlaybackResumeRecord? {
    if (player.mediaItemCount == 0) return null
    val mediaItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    return createPlaybackResumeRecord(
        mediaItems = mediaItems,
        namespace = namespace,
        currentIndex = player.currentMediaItemIndex,
        currentPositionMs = player.currentPosition,
        playbackEnded = player.playbackState == Player.STATE_ENDED,
        shuffleEnabled = player.shuffleModeEnabled,
        repeatMode = player.repeatMode,
        updatedAtMs = updatedAtMs,
    )
}

internal fun createPlaybackResumeRecord(
    mediaItems: List<MediaItem>,
    namespace: String,
    currentIndex: Int,
    currentPositionMs: Long,
    playbackEnded: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    updatedAtMs: Long,
): PlaybackResumeRecord? {
    if (mediaItems.isEmpty()) return null
    require(mediaItems.size <= PlaybackResumeStore.MaxPlaybackResumeItems) {
        "Playback queue is too large to resume."
    }
    val items = mediaItems.map(MediaItem::toPlaybackResumeEntry)
    return PlaybackResumeRecord(
        namespace = namespace,
        items = items,
        currentIndex = currentIndex.takeIf { it in items.indices } ?: 0,
        positionMs = if (playbackEnded) {
            0
        } else {
            currentPositionMs.coerceIn(0, PlaybackResumeStore.MaxPlaybackResumePositionMs)
        },
        shuffleEnabled = shuffleEnabled,
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_ALL -> PlaybackResumeRepeatMode.All
            Player.REPEAT_MODE_ONE -> PlaybackResumeRepeatMode.One
            else -> PlaybackResumeRepeatMode.Off
        },
        updatedAtMs = updatedAtMs.coerceAtLeast(0),
    )
}

internal fun PlaybackResumeRecord.toMediaItems(factory: PlaybackMediaItemFactory): List<MediaItem> =
    items.map { entry ->
        factory.create(
            Track(
                id = entry.id,
                title = entry.title,
                format = entry.format,
                artistName = entry.artist,
                albumTitle = entry.album,
                durationMs = null,
                coverId = entry.coverId,
            ),
        )
    }

private fun MediaItem.toPlaybackResumeEntry(): PlaybackResumeEntry {
    val metadata = mediaMetadata
    val format = metadata.extras?.getString(PlaybackResumeMetadata.ExtraFormat)
        ?: when (localConfiguration?.mimeType) {
            MimeTypes.AUDIO_FLAC -> "flac"
            MimeTypes.AUDIO_MPEG -> "mp3"
            else -> throw IllegalArgumentException("Playback item has no resumable format.")
        }
    return PlaybackResumeEntry(
        id = mediaId,
        title = metadata.title?.toString() ?: "Unknown track",
        artist = metadata.artist?.toString(),
        album = metadata.albumTitle?.toString(),
        format = format,
        coverId = metadata.extras?.getString(PlaybackResumeMetadata.ExtraCoverId),
    )
}
