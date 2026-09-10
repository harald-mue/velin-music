package com.haraldmue.velin.data

data class ServerStatus(
    val name: String,
    val status: String,
    val version: String,
)

data class LibrarySummary(
    val artistCount: Int,
    val albumCount: Int,
    val trackCount: Int,
    val revision: String,
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
    val addedAtMs: Long? = null,
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
    val artistId: String? = null,
    val albumId: String? = null,
    val coverId: String? = null,
)

data class TrackDetail(
    val id: String,
    val title: String,
    val format: String,
    val artistId: String?,
    val artistName: String?,
    val albumId: String?,
    val albumTitle: String?,
    val albumArtistName: String?,
    val genre: String?,
    val dateText: String?,
    val trackNumber: Int?,
    val totalTracks: Int?,
    val discNumber: Int?,
    val totalDiscs: Int?,
    val durationMs: Long?,
    val sampleRate: Int?,
    val bitsPerSample: Int?,
    val channels: Int?,
    val coverId: String?,
    val albumTrackCount: Int? = null,
) {
    fun toTrack(): Track = Track(
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

    fun canOpenAlbumWithMoreTracks(): Boolean =
        !albumId.isNullOrBlank() && !albumTitle.isNullOrBlank() && (albumTrackCount ?: 0) > 1
}

data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class AccumulatedPage<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
) {
    fun append(page: Page<T>): AccumulatedPage<T> = copy(
        items = items + page.items,
        nextCursor = page.nextCursor,
        hasMore = page.hasMore,
        loadingMore = false,
        loadMoreError = null,
    )

    companion object {
        fun <T> from(page: Page<T>): AccumulatedPage<T> = AccumulatedPage(
            items = page.items,
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
    }
}

data class LibrarySnapshot(
    val artists: AccumulatedPage<Artist> = AccumulatedPage(),
    val albums: AccumulatedPage<Album> = AccumulatedPage(),
    val tracks: AccumulatedPage<Track> = AccumulatedPage(),
)
