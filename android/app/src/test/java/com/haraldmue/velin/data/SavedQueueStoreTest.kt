package com.haraldmue.velin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SavedQueueStoreTest {
    @Test
    fun roundTripPreservesOpaqueIdsAndMetadata() {
        val file = Files.createTempFile("velin-saved-queue", ".json").toFile()
        try {
            val store = SavedQueueStore(file)
            val record = SavedQueueRecord(
                listOf(
                    SavedQueueEntry(id = "track-1", title = "Going Under", artist = "Evanescence"),
                    SavedQueueEntry(id = "track-2", title = "Pocahontas", artist = null),
                ),
            )
            store.write(record)
            assertEquals(record, store.read())
            assertTrue(store.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun saveIsEnabledOnlyWhenAvailableIdsChange() {
        assertFalse(canSaveSavedQueue(emptyList(), listOf("a")))
        assertFalse(canSaveSavedQueue(listOf("a", "b"), listOf("a", "b")))
        assertTrue(canSaveSavedQueue(listOf("a", "b"), null))
        assertTrue(canSaveSavedQueue(listOf("a"), listOf("a", "b")))
        assertTrue(canSaveSavedQueue(listOf("a", "c"), listOf("a", "b")))
    }
}
