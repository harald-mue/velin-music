package com.haraldmue.velin.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingStateTest {
    @Test
    fun progressFractionIsBounded() {
        assertEquals(0f, progressFraction(10, null))
        assertEquals(0f, progressFraction(-1, 100))
        assertEquals(0.5f, progressFraction(50, 100))
        assertEquals(1f, progressFraction(150, 100))
    }

    @Test
    fun queueSelectionRequiresBoundedSizeAndValidStartIndex() {
        assertEquals(false, isValidPlaybackQueue(0, 0))
        assertEquals(true, isValidPlaybackQueue(1, 0))
        assertEquals(true, isValidPlaybackQueue(500, 499))
        assertEquals(false, isValidPlaybackQueue(500, 500))
        assertEquals(false, isValidPlaybackQueue(501, 0))
    }

    @Test
    fun enqueueRespectsQueueCapacity() {
        assertEquals(true, canEnqueue(1, 1))
        assertEquals(true, canEnqueue(499, 1))
        assertEquals(false, canEnqueue(500, 1))
        assertEquals(true, canEnqueue(0, 32))
        assertEquals(false, canEnqueue(480, 21))
        assertEquals(false, canEnqueue(10, 0))
    }

    @Test
    fun reorderRequiresEditableNonShuffledQueue() {
        assertEquals(false, canReorderQueue(2, shuffleEnabled = true, canEditQueue = true))
        assertEquals(false, canReorderQueue(1, shuffleEnabled = false, canEditQueue = true))
        assertEquals(true, canReorderQueue(2, shuffleEnabled = false, canEditQueue = true))
    }

    @Test
    fun networkLossIgnoresStartupFlaps() {
        val network = "wifi"
        assertEquals(false, shouldCancelForLostNetwork(network, trackedNetwork = null, elapsedSinceStartMs = 5_000))
        assertEquals(false, shouldCancelForLostNetwork(network, trackedNetwork = network, elapsedSinceStartMs = 100))
        assertEquals(false, shouldCancelForLostNetwork("other", trackedNetwork = network, elapsedSinceStartMs = 5_000))
        assertEquals(true, shouldCancelForLostNetwork(network, trackedNetwork = network, elapsedSinceStartMs = 5_000))
        assertEquals(
            true,
            isLikelyEmulator(
                fingerprint = "generic/sdk_gphone64_x86_64/emu64xa:16/AE3A.240806.005/12281002:userdebug/dev-keys",
                model = "sdk_gphone64_x86_64",
                hardware = "ranchu",
                product = "sdk_gphone64_x86_64",
                manufacturer = "Google",
            ),
        )
        assertEquals(
            false,
            isLikelyEmulator(
                fingerprint = "google/shiba/shiba:16/BP2A.250605.031.A3/123:user/release-keys",
                model = "Pixel 8",
                hardware = "shiba",
                product = "shiba",
                manufacturer = "Google",
            ),
        )
    }

    @Test
    fun queueMoveRejectsInvalidIndices() {
        assertEquals(false, isValidQueueMove(0, 0, 3))
        assertEquals(false, isValidQueueMove(-1, 1, 3))
        assertEquals(false, isValidQueueMove(0, 3, 3))
        assertEquals(true, isValidQueueMove(0, 2, 3))
    }

    @Test
    fun repeatModesHaveStableLabels() {
        assertEquals("Off", repeatModeLabel(PlaybackRepeatMode.Off))
        assertEquals("All", repeatModeLabel(PlaybackRepeatMode.All))
        assertEquals("One", repeatModeLabel(PlaybackRepeatMode.One))
    }

    @Test
    fun playbackTimeSupportsHoursAndClampsNegativeValues() {
        assertEquals("0:00", formatPlaybackTime(-1))
        assertEquals("2:03", formatPlaybackTime(123_000))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
    }
}
