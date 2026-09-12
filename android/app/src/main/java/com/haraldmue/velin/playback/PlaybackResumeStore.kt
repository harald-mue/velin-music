package com.haraldmue.velin.playback

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun playbackResumeFile(directory: File, namespace: String): File =
    File(directory, "playback-resume-$namespace.json")

internal enum class PlaybackResumeRepeatMode(val persistedValue: String) {
    Off("off"),
    All("all"),
    One("one"),
    ;

    companion object {
        fun fromPersistedValue(value: String): PlaybackResumeRepeatMode =
            entries.firstOrNull { it.persistedValue == value }
                ?: throw IllegalArgumentException("Invalid playback resume repeat mode.")
    }
}

internal data class PlaybackResumeEntry(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val format: String,
    val coverId: String?,
)

internal data class PlaybackResumeRecord(
    val namespace: String,
    val items: List<PlaybackResumeEntry>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: PlaybackResumeRepeatMode,
    val updatedAtMs: Long,
)

/** Synchronous app-private persistence; callers must invoke file operations from an IO dispatcher. */
internal class PlaybackResumeStore private constructor(
    private val file: File,
    private val expectedNamespace: String,
    private val install: (staging: File, target: File) -> Unit,
) {
    constructor(file: File, expectedNamespace: String) : this(file, expectedNamespace, ::installAtomically)

    init {
        require(file.parentFile != null) { "Invalid playback resume path." }
        require(isValidNamespace(expectedNamespace)) { "Invalid playback resume namespace." }
    }

    fun read(): PlaybackResumeRecord? {
        if (!file.isFile) return null
        return try {
            require(file.length() in 1..MaxPlaybackResumeFileBytes) { "Invalid playback resume file size." }
            val record = decodeRecord(JSONObject(file.readText(StandardCharsets.UTF_8)))
            require(record.namespace == expectedNamespace) { "Playback resume namespace mismatch." }
            record
        } catch (_: Exception) {
            runCatching(::delete)
            null
        }
    }

    fun write(record: PlaybackResumeRecord) {
        require(record.namespace == expectedNamespace) { "Playback resume namespace mismatch." }
        if (record.items.isEmpty()) {
            delete()
            return
        }
        validateRecord(record)
        val payload = encodeRecord(record).toString().toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MaxPlaybackResumeFileBytes) { "Playback resume record is too large." }

        val directory = file.parentFile ?: error("Invalid playback resume path.")
        require(directory.isDirectory || directory.mkdirs()) { "Could not create playback resume directory." }
        val staging = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(staging).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            install(staging, file)
        } finally {
            staging.delete()
        }
    }

    fun delete() {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Could not delete playback resume state.")
        }
        File(requireNotNull(file.parentFile), "${file.name}.tmp").delete()
    }

    fun exists(): Boolean = file.isFile && file.length() in 1..MaxPlaybackResumeFileBytes

    internal companion object {
        const val CurrentVersion = 1
        const val MaxPlaybackResumeItems = 500
        const val MaxPlaybackResumeFileBytes = 1_048_576L
        const val MaxPlaybackResumePositionMs = 7L * 24 * 60 * 60 * 1_000
        const val MaxPlaybackResumeTextCodePoints = 1_024
        const val MaxPlaybackResumeIdLength = 128

        private val RootKeys = setOf(
            "version",
            "namespace",
            "items",
            "current_index",
            "position_ms",
            "shuffle_enabled",
            "repeat_mode",
            "updated_at_ms",
        )
        private val EntryKeys = setOf("id", "title", "artist", "album", "format", "cover_id")
        private val NamespacePattern = Regex("[0-9a-f]{64}")

        internal fun forTesting(
            file: File,
            expectedNamespace: String,
            install: (staging: File, target: File) -> Unit,
        ): PlaybackResumeStore = PlaybackResumeStore(file, expectedNamespace, install)

        private fun installAtomically(staging: File, target: File) {
            try {
                Files.move(
                    staging.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun encodeRecord(record: PlaybackResumeRecord): JSONObject {
            val items = JSONArray()
            record.items.forEach { entry ->
                items.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("title", entry.title)
                        .put("artist", entry.artist ?: JSONObject.NULL)
                        .put("album", entry.album ?: JSONObject.NULL)
                        .put("format", entry.format)
                        .put("cover_id", entry.coverId ?: JSONObject.NULL),
                )
            }
            return JSONObject()
                .put("version", CurrentVersion)
                .put("namespace", record.namespace)
                .put("items", items)
                .put("current_index", record.currentIndex)
                .put("position_ms", record.positionMs)
                .put("shuffle_enabled", record.shuffleEnabled)
                .put("repeat_mode", record.repeatMode.persistedValue)
                .put("updated_at_ms", record.updatedAtMs)
        }

        private fun decodeRecord(json: JSONObject): PlaybackResumeRecord {
            require(json.keys().asSequence().toSet() == RootKeys) { "Invalid playback resume fields." }
            require(json.requiredIntegralLong("version") == CurrentVersion.toLong()) {
                "Unsupported playback resume version."
            }
            val array = json.requiredArray("items")
            require(array.length() in 1..MaxPlaybackResumeItems) { "Invalid playback resume queue size." }
            val items = ArrayList<PlaybackResumeEntry>(array.length())
            for (index in 0 until array.length()) {
                items += decodeEntry(array.requiredObject(index))
            }
            val record = PlaybackResumeRecord(
                namespace = json.requiredString("namespace"),
                items = items,
                currentIndex = json.requiredIntegralLong("current_index").toIntExact("current_index"),
                positionMs = json.requiredIntegralLong("position_ms"),
                shuffleEnabled = json.requiredBoolean("shuffle_enabled"),
                repeatMode = PlaybackResumeRepeatMode.fromPersistedValue(json.requiredString("repeat_mode")),
                updatedAtMs = json.requiredIntegralLong("updated_at_ms"),
            )
            validateRecord(record)
            return record
        }

        private fun decodeEntry(json: JSONObject): PlaybackResumeEntry {
            require(json.keys().asSequence().toSet() == EntryKeys) { "Invalid playback resume entry fields." }
            val entry = PlaybackResumeEntry(
                id = json.requiredString("id"),
                title = json.requiredString("title"),
                artist = json.optionalString("artist"),
                album = json.optionalString("album"),
                format = json.requiredString("format"),
                coverId = json.optionalString("cover_id"),
            )
            validateEntry(entry)
            return entry
        }

        private fun validateRecord(record: PlaybackResumeRecord) {
            require(isValidNamespace(record.namespace)) { "Invalid playback resume namespace." }
            require(record.items.size in 1..MaxPlaybackResumeItems) { "Invalid playback resume queue size." }
            require(record.currentIndex in record.items.indices) { "Invalid playback resume queue index." }
            require(record.positionMs in 0..MaxPlaybackResumePositionMs) { "Invalid playback resume position." }
            require(record.updatedAtMs >= 0) { "Invalid playback resume update time." }
            record.items.forEach(::validateEntry)
        }

        private fun validateEntry(entry: PlaybackResumeEntry) {
            require(
                entry.id.isNotBlank() &&
                    entry.id == entry.id.trim() &&
                    entry.id.length <= MaxPlaybackResumeIdLength,
            ) {
                "Invalid playback resume track ID."
            }
            requireValidText(entry.title, "title", required = true)
            requireValidText(entry.artist, "artist", required = false)
            requireValidText(entry.album, "album", required = false)
            require(entry.format == "flac" || entry.format == "mp3") { "Invalid playback resume format." }
            entry.coverId?.let { coverId ->
                require(
                    coverId.isNotBlank() &&
                        coverId == coverId.trim() &&
                        coverId.length <= MaxPlaybackResumeIdLength,
                ) {
                    "Invalid playback resume cover ID."
                }
            }
        }

        private fun requireValidText(value: String?, field: String, required: Boolean) {
            if (value == null) {
                require(!required) { "Missing playback resume $field." }
                return
            }
            require(value.isNotBlank() && value.codePointCount() <= MaxPlaybackResumeTextCodePoints) {
                "Invalid playback resume $field."
            }
        }

        private fun isValidNamespace(value: String): Boolean = NamespacePattern.matches(value)

        private fun String.codePointCount(): Int = codePointCount(0, length)

        private fun JSONObject.requiredArray(name: String): JSONArray =
            opt(name) as? JSONArray ?: throw IllegalArgumentException("Invalid playback resume $name.")

        private fun JSONArray.requiredObject(index: Int): JSONObject =
            opt(index) as? JSONObject ?: throw IllegalArgumentException("Invalid playback resume item.")

        private fun JSONObject.requiredString(name: String): String =
            opt(name) as? String ?: throw IllegalArgumentException("Invalid playback resume $name.")

        private fun JSONObject.optionalString(name: String): String? {
            val value = opt(name)
            return when (value) {
                null, JSONObject.NULL -> null
                is String -> value
                else -> throw IllegalArgumentException("Invalid playback resume $name.")
            }
        }

        private fun JSONObject.requiredBoolean(name: String): Boolean =
            opt(name) as? Boolean ?: throw IllegalArgumentException("Invalid playback resume $name.")

        private fun JSONObject.requiredIntegralLong(name: String): Long {
            val value = opt(name)
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Invalid playback resume $name."
            }
            return (value as Number).toLong()
        }

        private fun Long.toIntExact(name: String): Int {
            require(this in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid playback resume $name." }
            return toInt()
        }
    }
}
