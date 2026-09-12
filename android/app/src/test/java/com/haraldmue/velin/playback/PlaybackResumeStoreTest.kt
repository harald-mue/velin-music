package com.haraldmue.velin.playback

import com.haraldmue.velin.data.SavedQueueEntry
import com.haraldmue.velin.data.SavedQueueRecord
import com.haraldmue.velin.data.SavedQueueStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaybackResumeStoreTest {
    @Test
    fun roundTripPreservesDuplicatesModesAndMetadata() = withStore { store, _, namespace ->
        PlaybackResumeRepeatMode.entries.forEachIndexed { index, repeatMode ->
            val record = record(
                namespace = namespace,
                repeatMode = repeatMode,
                shuffleEnabled = index % 2 == 0,
            )

            store.write(record)

            assertEquals(record, store.read())
            assertTrue(store.exists())
        }
    }

    @Test
    fun emptyQueueDeletesExistingCheckpoint() = withStore { store, file, namespace ->
        store.write(record(namespace))

        store.write(
            record(namespace).copy(
                items = emptyList(),
                currentIndex = -1,
            ),
        )

        assertFalse(file.exists())
        assertFalse(store.exists())
        assertNull(store.read())
    }

    @Test
    fun failedAtomicInstallKeepsPreviousRecord() = withStore { store, file, namespace ->
        val previous = record(namespace)
        store.write(previous)
        val failingStore = PlaybackResumeStore.forTesting(file, namespace) { _, _ ->
            throw IllegalStateException("simulated install failure")
        }

        assertThrows(IllegalStateException::class.java) {
            failingStore.write(previous.copy(positionMs = 9_999))
        }

        assertEquals(previous, store.read())
        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
    }

    @Test
    fun malformedOrUnsupportedRecordsAreIgnoredAndDeleted() = withStore { store, file, namespace ->
        val valid = validJson(namespace)
        val malformedRecords = listOf(
            "not-json",
            JSONObject(valid.toString()).put("version", 2).toString(),
            JSONObject(valid.toString()).put("namespace", "b".repeat(64)).toString(),
            JSONObject(valid.toString()).put("current_index", 20).toString(),
            JSONObject(valid.toString()).put("position_ms", -1).toString(),
            JSONObject(valid.toString()).put("position_ms", 1.5).toString(),
            JSONObject(valid.toString()).put("repeat_mode", "random").toString(),
            JSONObject(valid.toString()).put("unexpected", true).toString(),
            JSONObject(valid.toString()).apply {
                getJSONArray("items").getJSONObject(0).put("format", "wav")
            }.toString(),
            JSONObject(valid.toString()).apply {
                getJSONArray("items").getJSONObject(0)
                    .put("title", "x".repeat(PlaybackResumeStore.MaxPlaybackResumeTextCodePoints + 1))
            }.toString(),
            JSONObject(valid.toString()).apply {
                val item = getJSONArray("items").getJSONObject(0)
                put(
                    "items",
                    JSONArray().apply {
                        repeat(PlaybackResumeStore.MaxPlaybackResumeItems + 1) { put(JSONObject(item.toString())) }
                    },
                )
            }.toString(),
        )

        malformedRecords.forEach { payload ->
            file.writeText(payload)
            assertNull(payload.take(80), store.read())
            assertFalse(file.exists())
        }
    }

    @Test
    fun writeRejectsInvalidRecordsBeforeReplacingPreviousState() = withStore { store, _, namespace ->
        val previous = record(namespace)
        store.write(previous)

        val invalidRecords = listOf(
            previous.copy(currentIndex = -1),
            previous.copy(positionMs = PlaybackResumeStore.MaxPlaybackResumePositionMs + 1),
            previous.copy(updatedAtMs = -1),
            previous.copy(items = previous.items.map { it.copy(id = "") }),
            previous.copy(items = previous.items.map { it.copy(format = "ogg") }),
            previous.copy(items = previous.items.map { it.copy(coverId = " ") }),
        )
        invalidRecords.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.write(invalid) }
            assertEquals(previous, store.read())
        }
    }

    @Test
    fun deletingOneCredentialNamespaceDoesNotChangeAnother() {
        val directory = Files.createTempDirectory("velin-resume-namespaces").toFile()
        try {
            val firstNamespace = "a".repeat(64)
            val secondNamespace = "b".repeat(64)
            val first = PlaybackResumeStore(playbackResumeFile(directory, firstNamespace), firstNamespace)
            val second = PlaybackResumeStore(playbackResumeFile(directory, secondNamespace), secondNamespace)
            first.write(record(firstNamespace).copy(positionMs = 1))
            second.write(record(secondNamespace).copy(positionMs = 2))

            first.delete()

            assertNull(first.read())
            assertEquals(2L, second.read()?.positionMs)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun automaticCheckpointDeletionDoesNotChangeManualSavedQueue() {
        val directory = Files.createTempDirectory("velin-independent-queues").toFile()
        try {
            val namespace = "a".repeat(64)
            val manualStore = SavedQueueStore(File(directory, "saved-queue-device.json"))
            val automaticStore = PlaybackResumeStore(playbackResumeFile(directory, namespace), namespace)
            val manualRecord = SavedQueueRecord(
                listOf(SavedQueueEntry("track", "Track", "Artist")),
            )
            manualStore.write(manualRecord)
            automaticStore.write(record(namespace))

            automaticStore.delete()

            assertEquals(manualRecord, manualStore.read())
            assertTrue(manualStore.exists())
            assertFalse(automaticStore.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun serializedSchemaContainsNoSecretUrlOrArtworkBytes() = withStore { store, file, namespace ->
        store.write(record(namespace))

        val json = JSONObject(file.readText())
        assertEquals(
            setOf(
                "version",
                "namespace",
                "items",
                "current_index",
                "position_ms",
                "shuffle_enabled",
                "repeat_mode",
                "updated_at_ms",
            ),
            json.keys().asSequence().toSet(),
        )
        assertEquals(
            setOf("id", "title", "artist", "album", "format", "cover_id"),
            json.getJSONArray("items").getJSONObject(0).keys().asSequence().toSet(),
        )
        val serialized = file.readText().lowercase()
        assertFalse("token" in serialized)
        assertFalse("authorization" in serialized)
        assertFalse("http://" in serialized || "https://" in serialized)
        assertFalse("artwork_data" in serialized)
    }

    private fun validJson(namespace: String): JSONObject {
        val directory = Files.createTempDirectory("velin-resume-json").toFile()
        return try {
            val file = File(directory, "resume.json")
            PlaybackResumeStore(file, namespace).write(record(namespace))
            JSONObject(file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun record(
        namespace: String,
        repeatMode: PlaybackResumeRepeatMode = PlaybackResumeRepeatMode.All,
        shuffleEnabled: Boolean = true,
    ) = PlaybackResumeRecord(
        namespace = namespace,
        items = listOf(
            PlaybackResumeEntry(
                id = "track-1",
                title = "Going Under",
                artist = "Evanescence",
                album = "Fallen",
                format = "flac",
                coverId = "cover-1",
            ),
            PlaybackResumeEntry(
                id = "track-1",
                title = "Going Under",
                artist = null,
                album = null,
                format = "mp3",
                coverId = null,
            ),
        ),
        currentIndex = 1,
        positionMs = 12_345,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        updatedAtMs = 1_789_000_000_000,
    )

    private fun withStore(block: (PlaybackResumeStore, File, String) -> Unit) {
        val directory = Files.createTempDirectory("velin-playback-resume").toFile()
        try {
            val namespace = "a".repeat(64)
            val file = File(directory, "resume.json")
            block(PlaybackResumeStore(file, namespace), file, namespace)
        } finally {
            directory.deleteRecursively()
        }
    }
}
