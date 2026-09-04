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
