package com.haraldmue.velin.ui.library

import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySnapshot
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import com.haraldmue.velin.data.AccumulatedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun laterRefreshReplacesEarlierInFlightResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = LibraryViewModel(gateway)
            advanceUntilIdle()
            assertEquals("first", viewModel.state.value.library?.albums?.items?.first()?.title)
            assertFalse(viewModel.state.value.loading)

            gateway.albumTitle = "stale"
            gateway.loadDelayMs = 1_000
            viewModel.refresh()
            gateway.albumTitle = "fresh"
            gateway.loadDelayMs = 0
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("fresh", viewModel.state.value.library?.albums?.items?.first()?.title)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class ControllableLibraryGateway : LibraryGateway {
    var albumTitle: String = "first"
    var loadDelayMs: Long = 0

    override suspend fun status(): ServerStatus =
        ServerStatus(name = "Velin", status = "ok", version = "test")

    override suspend fun loadLibrary(): LibrarySnapshot {
        if (loadDelayMs > 0) delay(loadDelayMs)
        return snapshot(albumTitle)
    }

    override suspend fun loadArtistsPage(cursor: String?, limit: Int): Page<Artist> =
        Page(emptyList(), null, false)

    override suspend fun loadAlbumsPage(cursor: String?, limit: Int): Page<Album> =
        Page(emptyList(), null, false)

    override suspend fun loadTracksPage(
        cursor: String?,
        artistId: String?,
        albumId: String?,
        limit: Int,
    ): Page<Track> = Page(emptyList(), null, false)

    override suspend fun loadArtist(artistId: String): Artist =
        Artist(id = artistId, name = "Artist", albumCount = 0, trackCount = 0)

    override suspend fun loadAlbum(albumId: String): Album =
        Album(id = albumId, title = albumTitle, artistName = null, year = null, coverId = null, trackCount = 0)

    override suspend fun loadTrack(trackId: String): TrackDetail =
        TrackDetail(
            id = trackId,
            title = "Track",
            format = "flac",
            artistId = null,
            artistName = null,
            albumId = null,
            albumTitle = null,
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
        )

    override suspend fun loadAlbumTracks(albumId: String): List<Track> = emptyList()

    override suspend fun loadArtistTracks(artistId: String): List<Track> = emptyList()

    override suspend fun search(query: String, cursor: String?, limit: Int): Page<Track> =
        Page(emptyList(), null, false)
}

private fun snapshot(title: String) = LibrarySnapshot(
    artists = AccumulatedPage(),
    albums = AccumulatedPage(
        items = listOf(
            Album(
                id = "album-1",
                title = title,
                artistName = null,
                year = null,
                coverId = null,
                trackCount = 1,
            ),
        ),
    ),
    tracks = AccumulatedPage(),
)
