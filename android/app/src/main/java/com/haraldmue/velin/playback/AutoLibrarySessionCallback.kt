package com.haraldmue.velin.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.playback.PlaybackArtwork.withEmbeddedArtwork
import com.haraldmue.velin.playback.PlaybackArtwork.withoutPublicArtworkUri
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AutoLibrarySessionCallback(
    private val scope: CoroutineScope,
    private val catalog: () -> AutoLibraryCatalog?,
    private val resolver: () -> AutoPlaybackResolver?,
    private val artworkLoader: () -> AuthenticatedArtworkBitmapLoader?,
    private val playbackResumption: suspend () -> LoadedPlaybackResume? = { null },
    private val beforeLibraryAccess: () -> Unit = {},
    private val beforeQueueMutation: () -> Unit = {},
    private val afterQueueMutation: () -> Unit = {},
    private val beforePlaybackResumption: () -> Unit = {},
    private val trustedControllerPackage: String? = null,
    private val clearPlaybackResume: suspend () -> Unit = {},
) : MediaLibrarySession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ConnectionResult {
        beforeLibraryAccess()
        val isExternalMediaSurface =
            session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller) ||
                session.isMediaNotificationController(controller) ||
                isAndroidAutoBrowserPackage(controller.packageName)
        val isTrustedController = isTrustedPlaybackResumeController(
            controller.packageName,
            trustedControllerPackage,
        )
        if (!isExternalMediaSurface && !isTrustedController) {
            return super.onConnect(session, controller)
        }
        val defaultCommands = if (isExternalMediaSurface) {
            ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        } else {
            ConnectionResult.DEFAULT_SESSION_COMMANDS
        }
        val commands = if (isTrustedController) {
            defaultCommands.buildUpon().add(PlaybackResumeCommands.ClearCheckpoint).build()
        } else {
            defaultCommands
        }
        return ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commands)
            .build()
    }

    override fun onPlayerInteractionFinished(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        playerCommands: Player.Commands,
    ) {
        if (playerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
            afterQueueMutation()
        }
        val player = session.player
        if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE) &&
            shouldPrepareForExternalPlay(
                playbackState = player.playbackState,
                mediaItemCount = player.mediaItemCount,
                playWhenReady = player.playWhenReady,
            )
        ) {
            player.prepare()
        }
        super.onPlayerInteractionFinished(session, controllerInfo, playerCommands)
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        // Default Callback returns NOT_SUPPORTED. Android Auto subscribes to the root
        // and waits; without success it never leaves the loading spinner.
        beforeLibraryAccess()
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        beforeLibraryAccess()
        return immediateLibraryRoot(catalog(), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
        withTimeout(LibraryBrowseTimeoutMs) {
            LibraryResult.ofItem(withBrowseArtwork(requireCatalog().item(mediaId)), null)
        }
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
        withTimeout(LibraryBrowseTimeoutMs) {
            LibraryResult.ofItemList(
                withBrowseArtwork(requireCatalog().children(parentId, page, pageSize)),
                params,
            )
        }
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = libraryFuture {
        withTimeout(LibraryBrowseTimeoutMs) {
            val items = requireCatalog().search(query, 0, 50)
            val count = if (items.size >= 50) Int.MAX_VALUE else items.size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
        withTimeout(LibraryBrowseTimeoutMs) {
            LibraryResult.ofItemList(
                withBrowseArtwork(requireCatalog().search(query, page, pageSize)),
                params,
            )
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand != PlaybackResumeCommands.ClearCheckpoint) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        if (!isTrustedPlaybackResumeController(controller.packageName, trustedControllerPackage)) {
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        }
        return valueFuture {
            clearPlaybackResume()
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        beforePlaybackResumption()
        return valueFuture {
            playbackResumptionResult(playbackResumption())
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        beforeQueueMutation()
        return valueFuture {
            withTimeout(LibraryPlayTimeoutMs) {
                mediaItems.map { item ->
                    if (item.localConfiguration?.uri != null) {
                        item
                    } else {
                        requireResolver().playableTrack(item.mediaId)
                    }
                }
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> {
        beforeQueueMutation()
        return valueFuture {
            withTimeout(LibraryPlayTimeoutMs) {
                if (mediaItems.isEmpty()) {
                    return@withTimeout MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, startPositionMs)
                }
                if (mediaItems.all { it.localConfiguration?.uri != null }) {
                    val enriched = embedCurrentArtwork(mediaItems, startIndex)
                    return@withTimeout MediaItemsWithStartPosition(enriched, startIndex, startPositionMs)
                }
                if (mediaItems.size == 1) {
                    val queue = requireResolver().queueFor(mediaItems.single().mediaId)
                    val enriched = embedCurrentArtwork(queue.items, queue.startIndex)
                    return@withTimeout MediaItemsWithStartPosition(enriched, queue.startIndex, startPositionMs)
                }
                val resolved = mediaItems.map { item ->
                    if (item.localConfiguration?.uri != null) {
                        item
                    } else {
                        requireResolver().playableTrack(item.mediaId)
                    }
                }
                val enriched = embedCurrentArtwork(resolved, startIndex)
                MediaItemsWithStartPosition(enriched, startIndex, startPositionMs)
            }
        }
    }

    private suspend fun withBrowseArtwork(items: List<MediaItem>): List<MediaItem> {
        val loader = artworkLoader() ?: return items
        return embedBrowseArtwork(items) { uri ->
            await(loader.loadEmbeddedArtworkData(uri, MaxEmbeddedBrowseArtworkBytes))
        }
    }

    private suspend fun withBrowseArtwork(item: MediaItem): MediaItem =
        withBrowseArtwork(listOf(item)).single()

    private suspend fun embedCurrentArtwork(
        items: List<MediaItem>,
        currentIndex: Int,
    ): List<MediaItem> {
        val loader = artworkLoader() ?: return items
        if (currentIndex !in items.indices) return items
        val item = items[currentIndex]
        if (item.mediaMetadata.artworkData != null) {
            return if (item.mediaMetadata.artworkUri == null) {
                items
            } else {
                items.toMutableList().apply {
                    this[currentIndex] = item.withoutPublicArtworkUri()
                }
            }
        }
        val artworkUri = item.mediaMetadata.artworkUri ?: return items
        if (artworkUri.scheme == "content") return items
        val data = try {
            await(loader.loadEmbeddedArtworkData(artworkUri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return items
        }
        return items.toMutableList().apply {
            this[currentIndex] = item.withEmbeddedArtwork(data)
        }
    }

    private fun requireCatalog(): AutoLibraryCatalog =
        catalog() ?: throw UnpairedLibraryException()

    private fun requireResolver(): AutoPlaybackResolver =
        resolver() ?: throw UnpairedLibraryException()

    private suspend fun <T> await(future: ListenableFuture<T>): T =
        suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { value -> continuation.resume(value) }
                        .onFailure { error -> continuation.resumeWithException(error) }
                },
                { it.run() },
            )
            continuation.invokeOnCancellation { future.cancel(true) }
        }

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        val job = scope.launch {
            val result = try {
                beforeLibraryAccess()
                block()
            } catch (error: TimeoutCancellationException) {
                libraryError<T>(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                libraryError<T>(error)
            }
            future.set(result)
        }
        cancelOnFutureCancel(future, job)
        return future
    }

    private fun <T> valueFuture(block: suspend () -> T): ListenableFuture<T> =
        cancellableValueFuture(scope, beforeLibraryAccess, block)

    private fun cancelOnFutureCancel(future: ListenableFuture<*>, job: Job) {
        future.addListener(
            {
                if (future.isCancelled) {
                    job.cancel()
                }
            },
            { it.run() },
        )
    }
}

internal fun isTrustedPlaybackResumeController(
    controllerPackage: String,
    trustedControllerPackage: String?,
): Boolean = trustedControllerPackage != null && controllerPackage == trustedControllerPackage

internal object PlaybackResumeCommands {
    val ClearCheckpoint = SessionCommand(
        "com.haraldmue.velin.command.CLEAR_PLAYBACK_RESUME",
        Bundle.EMPTY,
    )
}

internal fun playbackResumptionResult(loaded: LoadedPlaybackResume?): MediaItemsWithStartPosition {
    loaded ?: throw UnsupportedOperationException("No playback state is available for resumption.")
    return MediaItemsWithStartPosition(
        loaded.mediaItems,
        loaded.record.currentIndex,
        loaded.record.positionMs,
    )
}

internal fun <T> cancellableValueFuture(
    scope: CoroutineScope,
    beforeAccess: () -> Unit,
    block: suspend () -> T,
): ListenableFuture<T> {
    val future = SettableFuture.create<T>()
    val job = scope.launch {
        try {
            beforeAccess()
            future.set(block())
        } catch (error: CancellationException) {
            future.cancel(false)
            throw error
        } catch (error: Throwable) {
            future.setException(error)
        }
    }
    future.addListener(
        { if (future.isCancelled) job.cancel() },
        { it.run() },
    )
    return future
}

internal fun shouldPrepareForExternalPlay(
    playbackState: Int,
    mediaItemCount: Int,
    playWhenReady: Boolean,
): Boolean = playWhenReady && shouldPrepareBeforePlay(playbackState, mediaItemCount)

/**
 * The platform MediaBrowser compatibility adapter blocks the main thread in onGetRoot until
 * this future completes. The static root must therefore never be dispatched to a coroutine.
 */
internal fun immediateLibraryRoot(
    catalog: AutoLibraryCatalog?,
    params: MediaLibraryService.LibraryParams?,
): ListenableFuture<LibraryResult<MediaItem>> {
    val result = try {
        LibraryResult.ofItem(catalog?.root() ?: throw UnpairedLibraryException(), params)
    } catch (error: Throwable) {
        libraryError<MediaItem>(error)
    }
    return Futures.immediateFuture(result)
}

private class UnpairedLibraryException : Exception("Pair this device before browsing the library.")

private fun <T : Any> libraryError(error: Throwable): LibraryResult<T> = when (error) {
    is UnpairedLibraryException -> LibraryResult.ofError<T>(SessionError.ERROR_SESSION_SETUP_REQUIRED)
    is ApiException -> when {
        error.authenticationFailed ->
            LibraryResult.ofError<T>(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED)
        error.isNotFound -> LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)
        else -> LibraryResult.ofError<T>(SessionError.ERROR_IO)
    }
    is IllegalArgumentException, is IllegalStateException ->
        LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)
    else -> LibraryResult.ofError<T>(SessionError.ERROR_IO)
}

private const val LibraryBrowseTimeoutMs = 10_000L
private const val LibraryPlayTimeoutMs = 15_000L
