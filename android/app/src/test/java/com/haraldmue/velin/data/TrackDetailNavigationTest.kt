package com.haraldmue.velin.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackDetailNavigationTest {
    @Test
    fun goToAlbumIsHiddenForIndexedSingles() {
        assertEquals(
            false,
            detail(albumId = "album-1", albumTitle = "Single", albumTrackCount = 1)
                .canOpenAlbumWithMoreTracks(),
        )
    }

    @Test
    fun goToAlbumIsShownWhenTheIndexedAlbumHasMoreTracks() {
        assertEquals(
            true,
            detail(albumId = "album-1", albumTitle = "Album", albumTrackCount = 12)
                .canOpenAlbumWithMoreTracks(),
        )
    }

    @Test
    fun goToAlbumIsHiddenWhenIndexedTrackCountIsUnknown() {
        assertEquals(
            false,
            detail(albumId = "album-1", albumTitle = "Album", albumTrackCount = null)
                .canOpenAlbumWithMoreTracks(),
        )
    }

    @Test
    fun goToAlbumIsHiddenWithoutAnAlbum() {
        assertEquals(
            false,
            detail(albumId = null, albumTitle = "Album", albumTrackCount = 12).canOpenAlbumWithMoreTracks(),
        )
        assertEquals(
            false,
            detail(albumId = "album-1", albumTitle = null, albumTrackCount = 12).canOpenAlbumWithMoreTracks(),
        )
    }

    private fun detail(
        albumId: String?,
        albumTitle: String?,
        albumTrackCount: Int? = null,
    ) = TrackDetail(
        id = "track-1",
        title = "Song",
        format = "flac",
        artistId = null,
        artistName = null,
        albumId = albumId,
        albumTitle = albumTitle,
        albumArtistName = null,
        genre = null,
        dateText = null,
        trackNumber = null,
        totalTracks = null,
        discNumber = null,
        totalDiscs = null,
        durationMs = null,
        sampleRate = null,
        bitsPerSample = null,
        channels = null,
        coverId = null,
        albumTrackCount = albumTrackCount,
    )
}
