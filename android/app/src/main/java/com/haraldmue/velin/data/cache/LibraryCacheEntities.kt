package com.haraldmue.velin.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.Track

@Entity(tableName = "cache_state", primaryKeys = ["namespace"])
data class CacheStateEntity(
    val namespace: String,
    @ColumnInfo(name = "active_generation") val activeGeneration: String?,
    @ColumnInfo(name = "active_revision") val activeRevision: String?,
    @ColumnInfo(name = "artist_count") val artistCount: Int,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "activated_at_ms") val activatedAtMs: Long?,
) {
    fun summaryOrNull(): LibrarySummary? = activeRevision?.let { revision ->
        LibrarySummary(artistCount, albumCount, trackCount, revision)
    }
}

@Entity(
    tableName = "cached_artists",
    primaryKeys = ["namespace", "generation", "id"],
    indices = [Index(value = ["namespace", "generation", "server_order"], unique = true)],
)
data class CachedArtistEntity(
    val namespace: String,
    val generation: String,
    val id: String,
    @ColumnInfo(name = "server_order") val serverOrder: Int,
    val name: String,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "track_count") val trackCount: Int,
) {
    fun toModel() = Artist(id, name, albumCount, trackCount)
}

@Entity(
    tableName = "cached_albums",
    primaryKeys = ["namespace", "generation", "id"],
    indices = [
        Index(value = ["namespace", "generation", "server_order"], unique = true),
        Index(value = ["namespace", "generation", "added_at_ms"]),
    ],
)
data class CachedAlbumEntity(
    val namespace: String,
    val generation: String,
    val id: String,
    @ColumnInfo(name = "server_order") val serverOrder: Int,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String?,
    val year: Int?,
    @ColumnInfo(name = "cover_id") val coverId: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "added_at_ms") val addedAtMs: Long?,
) {
    fun toModel() = Album(id, title, artistName, year, coverId, trackCount, addedAtMs)
}

@Entity(
    tableName = "cached_tracks",
    primaryKeys = ["namespace", "generation", "id"],
    indices = [
        Index(value = ["namespace", "generation", "server_order"], unique = true),
        Index(value = ["namespace", "generation", "album_id", "server_order"]),
        Index(value = ["namespace", "generation", "artist_id", "server_order"]),
    ],
)
data class CachedTrackEntity(
    val namespace: String,
    val generation: String,
    val id: String,
    @ColumnInfo(name = "server_order") val serverOrder: Int,
    val title: String,
    val format: String,
    @ColumnInfo(name = "artist_name") val artistName: String?,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "disc_number") val discNumber: Int?,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "album_id") val albumId: String?,
    @ColumnInfo(name = "cover_id") val coverId: String?,
) {
    fun toModel() = Track(
        id = id,
        title = title,
        format = format,
        artistName = artistName,
        albumTitle = albumTitle,
        durationMs = durationMs,
        trackNumber = trackNumber,
        discNumber = discNumber,
        artistId = artistId,
        albumId = albumId,
        coverId = coverId,
    )
}

internal fun Artist.toCacheEntity(namespace: String, generation: String, order: Int) =
    CachedArtistEntity(namespace, generation, id, order, name, albumCount, trackCount)

internal fun Album.toCacheEntity(namespace: String, generation: String, order: Int) =
    CachedAlbumEntity(namespace, generation, id, order, title, artistName, year, coverId, trackCount, addedAtMs)

internal fun Track.toCacheEntity(namespace: String, generation: String, order: Int) =
    CachedTrackEntity(
        namespace,
        generation,
        id,
        order,
        title,
        format,
        artistName,
        albumTitle,
        durationMs,
        trackNumber,
        discNumber,
        artistId,
        albumId,
        coverId,
    )
