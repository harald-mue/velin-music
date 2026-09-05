package com.haraldmue.velin.ui.library

import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySnapshot
import com.haraldmue.velin.data.LibrarySummary
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun startupLoadsOnlySummaryAndHomeAlbumsUntilASectionIsOpened() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = LibraryViewModel(gateway)
            advanceUntilIdle()

            assertEquals(16, gateway.albumPageLimits.single())
            assertEquals(0, gateway.artistPageRequests)
            assertEquals(0, gateway.trackPageRequests)
            assertEquals(1, viewModel.state.value.summary?.trackCount)

            viewModel.ensureSectionLoaded(LibrarySection.Artists)
            advanceUntilIdle()
            assertEquals(1, gateway.artistPageRequests)
            assertEquals(listOf(200), gateway.artistPageLimits)
            assertTrue(LibrarySection.Artists in viewModel.state.value.loadedSections)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changedRevisionInvalidatesAndReloadsPreviouslyOpenedSections() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = LibraryViewModel(gateway)
            advanceUntilIdle()
            viewModel.ensureSectionLoaded(LibrarySection.Artists)
            advanceUntilIdle()
            assertEquals(1, gateway.artistPageRequests)

            gateway.revision = "revision-2"
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("revision-2", viewModel.state.value.summary?.revision)
            assertEquals(2, gateway.artistPageRequests)
            assertTrue(LibrarySection.Artists in viewModel.state.value.loadedSections)
            assertEquals(0, gateway.trackPageRequests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun detailResultsAreCachedAndArtistRequestsRunConcurrently() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = LibraryViewModel(gateway)
            advanceUntilIdle()
            val album = snapshot("Album").albums.items.single()

            viewModel.openAlbum(album)
            advanceUntilIdle()
            viewModel.closeAlbum()
            viewModel.openAlbum(album)
            advanceUntilIdle()
            assertEquals(1, gateway.albumTrackRequests)

            gateway.artistDelayMs = 1_000
            val startedAt = testScheduler.currentTime
            viewModel.openArtist(Artist("artist-1", "Artist", 1, 1))
            advanceUntilIdle()
            assertEquals(1_000, testScheduler.currentTime - startedAt)
            assertTrue(viewModel.state.value.artistTracks.isNotEmpty())
            assertEquals(1, gateway.artistDetailRequests)
            assertEquals(1, gateway.artistTrackRequests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun laterRefreshReplacesEarlierInFlightResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = LibraryViewModel(gateway)
            advanceUntilIdle()
            assertEquals("first", viewModel.state.value.homeAlbums.first().title)
            assertFalse(viewModel.state.value.loading)

            gateway.albumTitle = "stale"
            gateway.loadDelayMs = 1_000
            viewModel.refresh()
            gateway.albumTitle = "fresh"
            gateway.loadDelayMs = 0
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("fresh", viewModel.state.value.homeAlbums.first().title)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class ControllableLibraryGateway : LibraryGateway {
    var albumTitle: String = "first"
    var revision: String = "revision-1"
    var loadDelayMs: Long = 0
    var artistDelayMs: Long = 0
    var albumTrackRequests: Int = 0
    var artistDetailRequests: Int = 0
    var artistTrackRequests: Int = 0
    var artistPageRequests: Int = 0
    var trackPageRequests: Int = 0
    val artistPageLimits = mutableListOf<Int>()
    val albumPageLimits = mutableListOf<Int>()

    override suspend fun status(): ServerStatus =
        ServerStatus(name = "Velin", status = "ok", version = "test")

    override suspend fun summary(): LibrarySummary {
        if (loadDelayMs > 0) delay(loadDelayMs)
        return LibrarySummary(artistCount = 1, albumCount = 1, trackCount = 1, revision = revision)
    }

    override suspend fun loadArtistsPage(cursor: String?, limit: Int): Page<Artist> {
        artistPageRequests++
        artistPageLimits += limit
        return Page(emptyList(), null, false)
    }

    override suspend fun loadAlbumsPage(cursor: String?, limit: Int): Page<Album> {
        albumPageLimits += limit
        if (loadDelayMs > 0) delay(loadDelayMs)
        return Page(snapshot(albumTitle).albums.items, null, false)
    }

    override suspend fun loadTracksPage(
        cursor: String?,
        artistId: String?,
        albumId: String?,
        limit: Int,
    ): Page<Track> {
        trackPageRequests++
        return Page(emptyList(), null, false)
    }

    override suspend fun loadArtist(artistId: String): Artist {
        artistDetailRequests++
        if (artistDelayMs > 0) delay(artistDelayMs)
        return Artist(id = artistId, name = "Artist", albumCount = 1, trackCount = 1)
    }

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

    override suspend fun loadAlbumTracks(albumId: String): List<Track> {
        albumTrackRequests++
        return emptyList()
    }

    override suspend fun loadArtistTracks(artistId: String): List<Track> {
        artistTrackRequests++
        if (artistDelayMs > 0) delay(artistDelayMs)
        return listOf(testTrack(artistId))
    }

    override suspend fun search(query: String, cursor: String?, limit: Int): Page<Track> =
        Page(emptyList(), null, false)
}

private fun testTrack(artistId: String) = Track(
    id = "track-1",
    title = "Track",
    format = "flac",
    artistName = "Artist",
    albumTitle = "Album",
    durationMs = 1_000,
    artistId = artistId,
)

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
