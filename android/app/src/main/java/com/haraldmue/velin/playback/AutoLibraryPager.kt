package com.haraldmue.velin.playback

import com.haraldmue.velin.data.Page

internal fun normalizeLibraryPageSize(pageSize: Int): Int = when {
    pageSize <= 0 -> 50
    pageSize > 200 -> 200
    else -> pageSize
}

private const val MaxLibraryWindowFetches = 16

internal suspend fun <T> loadLibraryWindow(
    page: Int,
    pageSize: Int,
    load: suspend (cursor: String?, limit: Int) -> Page<T>,
): List<T> {
    val size = normalizeLibraryPageSize(pageSize)
    val skip = page.coerceAtLeast(0) * size
    var skipped = 0
    var cursor: String? = null
    val seen = mutableSetOf<String>()
    var fetches = 0
    while (true) {
        val remainingToSkip = (skip - skipped).coerceAtLeast(0)
        val limit = if (remainingToSkip > 0) {
            minOf(200, maxOf(remainingToSkip, size))
        } else {
            size
        }
        val loaded = load(cursor, limit)
        fetches += 1
        if (fetches > MaxLibraryWindowFetches) return emptyList()
        if (loaded.items.isEmpty()) return emptyList()
        if (remainingToSkip >= loaded.items.size) {
            skipped += loaded.items.size
            val next = loaded.nextCursor
            if (!loaded.hasMore || next.isNullOrEmpty() || !seen.add(next)) {
                return emptyList()
            }
            cursor = next
            continue
        }
        return loaded.items.drop(remainingToSkip).take(size)
    }
}
