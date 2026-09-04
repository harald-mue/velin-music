package com.haraldmue.velin.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val MaxSavedQueueItems = 500
private const val MaxSavedIdLength = 128
private const val MaxSavedTitleLength = 1_024
private const val MaxSavedArtistLength = 1_024

data class SavedQueueEntry(
    val id: String,
    val title: String,
    val artist: String?,
)

data class SavedQueueRecord(
    val items: List<SavedQueueEntry>,
) {
    val ids: List<String> get() = items.map(SavedQueueEntry::id)
}

class SavedQueueStore(
    private val file: File,
) {
    fun read(): SavedQueueRecord? {
        if (!file.isFile) return null
        val text = file.readText()
        if (text.isBlank()) return null
        val json = JSONObject(text)
        val array = json.getJSONArray("items")
        require(array.length() <= MaxSavedQueueItems) { "The saved queue is too large." }
        val items = ArrayList<SavedQueueEntry>(array.length())
        for (index in 0 until array.length()) {
            items += decodeEntry(array.getJSONObject(index))
        }
        return SavedQueueRecord(items)
    }

    fun write(record: SavedQueueRecord) {
        require(record.items.size <= MaxSavedQueueItems) { "The saved queue is too large." }
        val array = JSONArray()
        record.items.forEach { entry ->
            val id = entry.id.trim()
            val title = entry.title.trim()
            require(id.isNotEmpty() && id.length <= MaxSavedIdLength) { "Invalid saved track ID." }
            require(title.isNotEmpty() && title.length <= MaxSavedTitleLength) { "Invalid saved track title." }
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("title", title)
                    .put("artist", entry.artist?.trim()?.takeIf(String::isNotEmpty)?.take(MaxSavedArtistLength) ?: JSONObject.NULL),
            )
        }
        val payload = JSONObject().put("items", array).toString()
        val directory = file.parentFile ?: error("Invalid saved-queue path.")
        directory.mkdirs()
        val staging = File(directory, "${file.name}.tmp")
        staging.writeText(payload)
        if (!staging.renameTo(file)) {
            file.writeText(payload)
            staging.delete()
        }
    }

    fun exists(): Boolean = file.isFile && file.length() > 0
}

internal fun canSaveSavedQueue(currentIds: List<String>, persistedIds: List<String>?): Boolean =
    currentIds.isNotEmpty() && currentIds != persistedIds

private fun decodeEntry(json: JSONObject): SavedQueueEntry {
    val id = json.getString("id").trim()
    require(id.isNotEmpty() && id.length <= MaxSavedIdLength) { "Invalid saved track ID." }
    val title = json.getString("title").trim()
    require(title.isNotEmpty() && title.length <= MaxSavedTitleLength) { "Invalid saved track title." }
    val artist = if (json.isNull("artist")) {
        null
    } else {
        json.getString("artist").trim().takeIf(String::isNotEmpty)?.take(MaxSavedArtistLength)
    }
    return SavedQueueEntry(id = id, title = title, artist = artist)
}
