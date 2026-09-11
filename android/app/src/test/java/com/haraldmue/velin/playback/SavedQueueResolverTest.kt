package com.haraldmue.velin.playback

import com.haraldmue.velin.data.Track
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedQueueResolverTest {
    @Test
    fun usesCacheAndResolvesUniqueMissesWithBoundedConcurrencyInQueueOrder() = runTest {
        val activeLoads = AtomicInteger()
        val maximumLoads = AtomicInteger()
        val requestedIds = mutableListOf<String>()
        val cached = track("cached")

        val resolved = resolveSavedQueueTracks(
            trackIds = listOf("remote-2", "cached", "remote-1", "remote-2", "missing"),
            cachedTracks = mapOf(cached.id to cached),
            maxConcurrentLoads = 2,
        ) { id ->
            requestedIds += id
            val active = activeLoads.incrementAndGet()
            maximumLoads.updateAndGet { previous -> maxOf(previous, active) }
            try {
                delay(10)
                if (id == "missing") null else track(id)
            } finally {
                activeLoads.decrementAndGet()
            }
        }

        assertEquals(listOf("remote-2", "remote-1", "missing"), requestedIds)
        assertEquals(2, maximumLoads.get())
        assertEquals(
            listOf("remote-2", "cached", "remote-1", "remote-2", null),
            resolved.map { it?.id },
        )
    }

    private fun track(id: String) = Track(
        id = id,
        title = id,
        format = "flac",
        artistName = null,
        albumTitle = null,
        durationMs = null,
    )
}
