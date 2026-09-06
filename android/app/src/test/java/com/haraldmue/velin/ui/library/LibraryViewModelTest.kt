package com.haraldmue.velin.ui.library

import android.os.Looper
import androidx.room.Room
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import com.haraldmue.velin.data.cache.LibraryCacheDatabase
import com.haraldmue.velin.data.cache.LibraryCacheRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {
    private lateinit var database: LibraryCacheDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LibraryCacheDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun startupSyncsRoomSnapshotAndHomeAlbums() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = viewModel(gateway)
            settle()

            assertEquals(listOf(16, 200), gateway.albumPageLimits)
            assertEquals(1, gateway.artistPageRequests)
            assertEquals(1, gateway.trackPageRequests)
            assertEquals(1, viewModel.state.value.summary?.trackCount)
            assertEquals("first", viewModel.state.value.recentlyAddedAlbums.single().title)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changedRevisionActivatesNewRoomSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val viewModel = viewModel(gateway)
            settle()
            assertEquals(1, gateway.artistPageRequests)

            gateway.revision = "revision-2"
            gateway.albumTitle = "second"
            viewModel.refresh()
            settle()

            assertEquals("revision-2", viewModel.state.value.summary?.revision)
            assertEquals(2, gateway.artistPageRequests)
            assertEquals("second", viewModel.state.value.recentlyAddedAlbums.single().title)
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
            val viewModel = viewModel(gateway)
            settle()
            val album = snapshot("Album").albums.items.single()

            viewModel.openAlbum(album)
            settle()
            viewModel.closeAlbum()
            viewModel.openAlbum(album)
            settle()
            assertEquals(0, gateway.albumTrackRequests)

            gateway.artistDelayMs = 1_000
            val startedAt = testScheduler.currentTime
            viewModel.openArtist(Artist("artist-1", "Artist", 1, 1))
            settle()
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
            val viewModel = viewModel(gateway)
            settle()
            assertEquals("first", viewModel.state.value.recentlyAddedAlbums.first().title)
            assertFalse(viewModel.state.value.loading)

            gateway.albumTitle = "stale"
            gateway.revision = "revision-2"
            gateway.loadDelayMs = 1_000
            viewModel.refresh()
            gateway.albumTitle = "fresh"
            gateway.revision = "revision-3"
            gateway.loadDelayMs = 0
            viewModel.refresh()
            settle()

            assertEquals("fresh", viewModel.state.value.recentlyAddedAlbums.first().title)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedRefreshKeepsPreviouslyActivatedSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway()
            val repository = repository(gateway)
            repository.sync()
            gateway.albumTitle = "new"
            gateway.revision = "revision-2"
            gateway.failTracks = true

            val viewModel = LibraryViewModel(gateway, repository)
            settle()

            assertEquals("revision-1", viewModel.state.value.summary?.revision)
            assertEquals("first", viewModel.state.value.recentlyAddedAlbums.single().title)
            assertEquals("Could not refresh the library.", viewModel.state.value.error)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedInitialSyncDoesNotActivateBootstrapData() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gateway = ControllableLibraryGateway().apply { failTracks = true }
            val viewModel = viewModel(gateway)
            settle()

            assertFalse(viewModel.state.value.hasActiveSnapshot)
            assertEquals("Could not refresh the library.", viewModel.state.value.error)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(gateway: ControllableLibraryGateway): LibraryViewModel {
        return LibraryViewModel(gateway, repository(gateway))
    }

    private fun repository(gateway: ControllableLibraryGateway): LibraryCacheRepository {
        val credentials = DeviceCredentials(
            serverUrl = "https://example.test",
            deviceId = "device",
            token = "token",
            serverName = "Test",
            serverVersion = "1",
        )
        return LibraryCacheRepository(database, gateway, credentials)
    }

    private fun TestScope.settle() {
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
    }
}

private class ControllableLibraryGateway : LibraryGateway {
    var albumTitle: String = "first"
    var revision: String = "revision-1"
    var loadDelayMs: Long = 0
    var artistDelayMs: Long = 0
    var failTracks: Boolean = false
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
        return Page(listOf(Artist("artist-1", "Artist", 1, 1)), null, false)
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
        if (failTracks) error("failed")
        return Page(listOf(testTrack("artist-1")), null, false)
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
    albumId = "album-1",
)

private fun snapshot(title: String) = object {
    val albums = object {
        val items = listOf(
            Album(
                id = "album-1",
                title = title,
                artistName = null,
                year = null,
                coverId = null,
                trackCount = 1,
                addedAtMs = 1_000,
            ),
        )
    }
}
