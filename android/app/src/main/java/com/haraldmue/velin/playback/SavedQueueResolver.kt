package com.haraldmue.velin.playback

import com.haraldmue.velin.data.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val MaxConcurrentSavedQueueLoads = 8

/** Resolves each unique cache miss once, with bounded network concurrency, while preserving queue order. */
internal suspend fun resolveSavedQueueTracks(
    trackIds: List<String>,
    cachedTracks: Map<String, Track>,
    maxConcurrentLoads: Int = MaxConcurrentSavedQueueLoads,
    loadRemote: suspend (String) -> Track?,
): List<Track?> = coroutineScope {
    require(maxConcurrentLoads > 0) { "Saved-queue load concurrency must be positive." }
    val missingIds = trackIds.distinct().filterNot(cachedTracks::containsKey)
    val permits = Semaphore(maxConcurrentLoads)
    val remoteTracks = missingIds.map { id ->
        async {
            id to permits.withPermit { loadRemote(id) }
        }
    }.awaitAll().toMap()

    trackIds.map { id -> cachedTracks[id] ?: remoteTracks[id] }
}
