package com.haraldmue.velin.playback

import androidx.media3.common.Player
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCheckpointCoordinatorTest {
    @Test
    fun conflatesPendingWritesToNewestRecord() = runTest {
        val writes = mutableListOf<PlaybackResumeRecord>()
        val coordinator = PlaybackCheckpointCoordinator.forTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            writeRecord = writes::add,
            deleteRecord = {},
        )

        coordinator.checkpoint(record(positionMs = 1))
        coordinator.checkpoint(record(positionMs = 2))
        coordinator.checkpoint(record(positionMs = 3))
        coordinator.close()
        advanceUntilIdle()
        coordinator.awaitClosed()

        assertEquals(listOf(3L), writes.map(PlaybackResumeRecord::positionMs))
    }

    @Test
    fun deleteRunsAfterAnAlreadyStartedWriteAndCannotBeOvertaken() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var persisted: PlaybackResumeRecord? = null
        val coordinator = PlaybackCheckpointCoordinator.forTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            writeRecord = { value ->
                writeStarted.complete(Unit)
                releaseWrite.await()
                persisted = value
            },
            deleteRecord = { persisted = null },
        )

        coordinator.checkpoint(record(positionMs = 10))
        runCurrent()
        assertTrue(writeStarted.isCompleted)
        coordinator.delete()
        releaseWrite.complete(Unit)
        coordinator.close()
        advanceUntilIdle()
        coordinator.awaitClosed()

        assertNull(persisted)
    }

    @Test
    fun awaitedDeleteInvalidatesOlderWritesButAllowsNewState() = runTest {
        var persisted: PlaybackResumeRecord? = null
        val coordinator = PlaybackCheckpointCoordinator.forTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            writeRecord = { persisted = it },
            deleteRecord = { persisted = null },
        )
        coordinator.checkpoint(record(positionMs = 1))

        val deletion = async { coordinator.deleteAndAwait() }
        advanceUntilIdle()
        deletion.await()
        assertNull(persisted)

        coordinator.checkpoint(record(positionMs = 2))
        coordinator.close()
        advanceUntilIdle()
        coordinator.awaitClosed()
        assertEquals(2L, persisted?.positionMs)
    }

    @Test
    fun persistenceFailureIsReportedAndLaterCommandsContinue() = runTest {
        val errors = mutableListOf<Exception>()
        var attempts = 0
        var persisted: PlaybackResumeRecord? = null
        val coordinator = PlaybackCheckpointCoordinator.forTesting(
            dispatcher = StandardTestDispatcher(testScheduler),
            writeRecord = { value ->
                attempts++
                if (attempts == 1) error("disk failure")
                persisted = value
            },
            deleteRecord = { persisted = null },
            onError = errors::add,
        )

        coordinator.checkpoint(record(positionMs = 1))
        runCurrent()
        coordinator.checkpoint(record(positionMs = 2))
        coordinator.close()
        advanceUntilIdle()
        coordinator.awaitClosed()

        assertEquals(1, errors.size)
        assertEquals(2L, persisted?.positionMs)
    }

    @Test
    fun mediaItemsProjectToBoundedSecretFreeResumeRecord() {
        val credentials = DeviceCredentials(
            serverUrl = "https://music.example/velin",
            deviceId = "device",
            token = "secret-token",
            serverName = "Velin",
            serverVersion = "1",
        )
        val mediaItems = listOf(
            PlaybackMediaItemFactory(credentials).create(
                Track(
                    id = "track-1",
                    title = "Song",
                    format = "flac",
                    artistName = "Artist",
                    albumTitle = "Album",
                    durationMs = 60_000,
                    coverId = "cover-1",
                ),
            ),
        )

        val record = createPlaybackResumeRecord(
            mediaItems = mediaItems,
            namespace = Namespace,
            currentIndex = 9,
            currentPositionMs = Long.MAX_VALUE,
            playbackEnded = false,
            shuffleEnabled = true,
            repeatMode = Player.REPEAT_MODE_ONE,
            updatedAtMs = -1,
        )

        requireNotNull(record)
        assertEquals(0, record.currentIndex)
        assertEquals(PlaybackResumeStore.MaxPlaybackResumePositionMs, record.positionMs)
        assertEquals(0, record.updatedAtMs)
        assertEquals(PlaybackResumeRepeatMode.One, record.repeatMode)
        assertEquals("flac", record.items.single().format)
        assertEquals("cover-1", record.items.single().coverId)
        assertEquals("Song", record.items.single().title)
    }

    @Test
    fun restoreGuardRejectsDelayedWorkAfterUserMutation() {
        val guard = PlaybackRestoreGuard()
        val first = guard.begin()
        assertTrue(guard.isCurrent(first))

        guard.cancel()

        assertFalse(guard.isCurrent(first))
        val second = guard.begin()
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun resumeRecordRebuildsMediaItemsWithCurrentCredentialsWithoutTokens() {
        val record = record(positionMs = 123).copy(
            items = listOf(
                PlaybackResumeEntry("flac-id", "FLAC", "Artist", "Album", "flac", "cover"),
                PlaybackResumeEntry("mp3-id", "MP3", null, null, "mp3", null),
            ),
            currentIndex = 1,
        )
        val items = record.toMediaItems(PlaybackMediaItemFactory(testCredentials()))

        assertEquals(listOf("flac-id", "mp3-id"), items.map { it.mediaId })
        assertTrue(items[0].localConfiguration?.uri.toString().endsWith("/api/v1/tracks/flac-id/stream"))
        assertEquals("audio/flac", items[0].localConfiguration?.mimeType)
        assertEquals("audio/mpeg", items[1].localConfiguration?.mimeType)
        assertEquals("cover", items[0].mediaMetadata.extras?.getString(PlaybackResumeMetadata.ExtraCoverId))
        assertFalse(items.any { "token" in it.localConfiguration?.uri.toString() })
    }

    @Test
    fun checkpointClearCommandTrustsOnlyVelinPackage() {
        assertTrue(isTrustedPlaybackResumeController("com.haraldmue.velin", "com.haraldmue.velin"))
        assertFalse(isTrustedPlaybackResumeController("com.android.systemui", "com.haraldmue.velin"))
        assertFalse(isTrustedPlaybackResumeController("com.haraldmue.velin", null))
        assertFalse("token" in PlaybackResumeCommands.ClearCheckpoint.customAction.lowercase())
    }

    @Test
    fun media3ResumptionReturnsQueueIndexAndPosition() {
        val record = record(positionMs = 321)
        val mediaItems = record.toMediaItems(PlaybackMediaItemFactory(testCredentials()))

        val result = playbackResumptionResult(LoadedPlaybackResume(record, mediaItems))

        assertEquals(mediaItems.map { it.mediaId }, result.mediaItems.map { it.mediaId })
        assertEquals(record.currentIndex, result.startIndex)
        assertEquals(321L, result.startPositionMs)
        assertThrows(UnsupportedOperationException::class.java) {
            playbackResumptionResult(null)
        }
    }

    @Test
    fun cancellingAValueFutureCancelsItsCoroutine() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val future = cancellableValueFuture(this, beforeAccess = {}) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        runCurrent()
        assertTrue(started.isCompleted)

        future.cancel(true)
        runCurrent()

        assertTrue(future.isCancelled)
        assertTrue(cancelled)
    }

    @Test
    fun externalPlayPreparesOnlyAnIdleNonEmptyQueueThatWantsPlayback() {
        assertTrue(shouldPrepareForExternalPlay(Player.STATE_IDLE, mediaItemCount = 1, playWhenReady = true))
        assertFalse(shouldPrepareForExternalPlay(Player.STATE_IDLE, mediaItemCount = 1, playWhenReady = false))
        assertFalse(shouldPrepareForExternalPlay(Player.STATE_IDLE, mediaItemCount = 0, playWhenReady = true))
        assertFalse(shouldPrepareForExternalPlay(Player.STATE_READY, mediaItemCount = 1, playWhenReady = true))
    }

    @Test
    fun phonePlayPreparesOnlyAnIdleNonEmptyQueue() {
        assertTrue(shouldPrepareBeforePlay(Player.STATE_IDLE, mediaItemCount = 1))
        assertFalse(shouldPrepareBeforePlay(Player.STATE_IDLE, mediaItemCount = 0))
        assertFalse(shouldPrepareBeforePlay(Player.STATE_READY, mediaItemCount = 1))
        assertFalse(shouldPrepareBeforePlay(Player.STATE_ENDED, mediaItemCount = 1))
    }

    @Test
    fun positionCheckpointPollingRunsOnlyWhilePlaying() {
        assertTrue(shouldPollPlaybackCheckpoint(isPlaying = true))
        assertFalse(shouldPollPlaybackCheckpoint(isPlaying = false))
    }

    @Test
    fun endedPlaybackRestartsCurrentItemAtZero() {
        val item = PlaybackMediaItemFactory(testCredentials()).create(
            Track("track", "Track", "mp3", null, null, null),
        )

        val record = createPlaybackResumeRecord(
            mediaItems = listOf(item),
            namespace = Namespace,
            currentIndex = 0,
            currentPositionMs = 123_456,
            playbackEnded = true,
            shuffleEnabled = false,
            repeatMode = Player.REPEAT_MODE_OFF,
            updatedAtMs = 10,
        )

        assertEquals(0L, record?.positionMs)
        assertEquals(PlaybackResumeRepeatMode.Off, record?.repeatMode)
    }

    private fun record(positionMs: Long) = PlaybackResumeRecord(
        namespace = Namespace,
        items = listOf(PlaybackResumeEntry("track", "Track", null, null, "flac", null)),
        currentIndex = 0,
        positionMs = positionMs,
        shuffleEnabled = false,
        repeatMode = PlaybackResumeRepeatMode.Off,
        updatedAtMs = 1,
    )

    private fun testCredentials() = DeviceCredentials(
        serverUrl = "https://music.example",
        deviceId = "device",
        token = "token",
        serverName = "Velin",
        serverVersion = "1",
    )

    private companion object {
        val Namespace = "a".repeat(64)
    }
}
