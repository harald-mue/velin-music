package com.haraldmue.velin.data

data class ServerStatus(
    val name: String,
    val status: String,
    val version: String,
)

data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)

data class Album(
    val id: String,
    val title: String,
    val artistName: String?,
    val year: Int?,
    val coverId: String?,
    val trackCount: Int,
)

data class Track(
    val id: String,
    val title: String,
    val format: String,
    val artistName: String?,
    val albumTitle: String?,
    val durationMs: Long?,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val coverId: String? = null,
)

data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class LibrarySnapshot(
    val artists: Page<Artist>,
    val albums: Page<Album>,
    val tracks: Page<Track>,
)
