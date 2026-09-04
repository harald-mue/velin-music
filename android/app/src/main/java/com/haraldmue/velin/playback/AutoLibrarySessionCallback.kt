package com.haraldmue.velin.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.haraldmue.velin.data.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

internal class AutoLibrarySessionCallback(
    private val scope: CoroutineScope,
    private val catalog: AutoLibraryCatalog?,
    private val resolver: AutoPlaybackResolver?,
) : MediaLibrarySession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ConnectionResult {
        val isExternalMediaSurface =
            session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller) ||
                session.isMediaNotificationController(controller) ||
                isAndroidAutoBrowserPackage(controller.packageName)
        if (!isExternalMediaSurface) {
            return super.onConnect(session, controller)
        }
        return ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS)
            .build()
    }

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        // Default Callback returns NOT_SUPPORTED. Android Auto subscribes to the root
        // and waits; without success it never leaves the loading spinner.
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = immediateLibraryRoot(catalog, params)

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
        withTimeout(LibraryBrowseTimeoutMs) {
            LibraryResult.ofItem(requireCatalog().item(mediaId), null)
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
            LibraryResult.ofItemList(requireCatalog().children(parentId, page, pageSize), params)
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
            LibraryResult.ofItemList(requireCatalog().search(query, page, pageSize), params)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = valueFuture {
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

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = valueFuture {
        withTimeout(LibraryPlayTimeoutMs) {
            if (mediaItems.isEmpty()) {
                return@withTimeout MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, startPositionMs)
            }
            if (mediaItems.all { it.localConfiguration?.uri != null }) {
                return@withTimeout MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
            if (mediaItems.size == 1) {
                val queue = requireResolver().queueFor(mediaItems.single().mediaId)
                return@withTimeout MediaItemsWithStartPosition(queue.items, queue.startIndex, startPositionMs)
            }
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration?.uri != null) {
                    item
                } else {
                    requireResolver().playableTrack(item.mediaId)
                }
            }
            MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
        }
    }

    private fun requireCatalog(): AutoLibraryCatalog =
        catalog ?: throw UnpairedLibraryException()

    private fun requireResolver(): AutoPlaybackResolver =
        resolver ?: throw UnpairedLibraryException()

    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        val job = scope.launch {
            val result = try {
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

    private fun <T> valueFuture(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                future.set(block())
            } catch (error: TimeoutCancellationException) {
                future.setException(error)
            } catch (error: CancellationException) {
                future.cancel(false)
                throw error
            } catch (error: Throwable) {
                future.setException(error)
            }
        }
        cancelOnFutureCancel(future, job)
        return future
    }

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
