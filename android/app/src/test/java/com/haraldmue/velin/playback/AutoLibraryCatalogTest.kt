package com.haraldmue.velin.playback

import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ApiException
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.LibrarySummary
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.ServerStatus
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.TrackDetail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoLibraryCatalogTest {
    @Test
    fun mediaIdsAreStableAndPrefixed() {
        assertEquals(AutoMediaId.Root, parseAutoMediaId(AutoRootId))
        assertEquals(AutoMediaId.Albums, parseAutoMediaId(AutoAlbumsId))
        assertEquals(AutoMediaId.Artists, parseAutoMediaId(AutoArtistsId))
        assertEquals(AutoMediaId.Album("album-1"), parseAutoMediaId(albumMediaId("album-1")))
        assertEquals(AutoMediaId.Artist("artist-1"), parseAutoMediaId(artistMediaId("artist-1")))
        assertEquals(AutoMediaId.Track("track-1"), parseAutoMediaId(trackMediaId("track-1")))
        assertNull(parseAutoMediaId("track-1"))
        assertEquals(AutoMediaId.Track("track-1"), parsePlaybackMediaId("track-1"))
        assertEquals(true, isAndroidAutoBrowserPackage("com.google.android.projection.gearhead"))
        assertEquals(false, isAndroidAutoBrowserPackage("com.haraldmue.velin"))
    }

    @Test
    fun rootExposesAlbumsAndArtistsOnly() = runTest {
        val catalog = AutoLibraryCatalog(FakeLibraryGateway(), artworkPolicy())

        val root = catalog.root()
        val rootFuture = immediateLibraryRoot(catalog, null)
        val children = catalog.children(AutoRootId, 0, 50)

        assertTrue(rootFuture.isDone)
        assertEquals(AutoRootId, rootFuture.get().value?.mediaId)
        assertEquals(AutoRootId, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
        assertEquals(listOf(AutoAlbumsId, AutoArtistsId), children.map { it.mediaId })
        assertEquals("Albums", children[0].mediaMetadata.title.toString())
        assertEquals(false, children[0].mediaMetadata.isPlayable)
        assertEquals(true, children[0].mediaMetadata.isBrowsable)
        assertEquals("Artists", children[1].mediaMetadata.title.toString())
        assertTrue(children.none { it.mediaId.contains("recent", ignoreCase = true) })
        assertEquals(
            androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            root.mediaMetadata.extras?.getInt(
                androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            ),
        )
    }

    @Test
    fun albumsAndArtistsAreBrowsableWithMetadata() = runTest {
        val catalog = AutoLibraryCatalog(FakeLibraryGateway(), artworkPolicy())

        val albums = catalog.children(AutoAlbumsId, 0, 50)
        val artists = catalog.children(AutoArtistsId, 0, 50)
        val album = catalog.item(albumMediaId("album-1"))
        val artist = catalog.item(artistMediaId("artist-1"))

        assertEquals(listOf(albumMediaId("album-1")), albums.map { it.mediaId })
        assertEquals("Mezzanine", albums.single().mediaMetadata.title.toString())
        assertEquals("Massive Attack", albums.single().mediaMetadata.artist.toString())
        assertEquals(true, albums.single().mediaMetadata.isBrowsable)
        assertEquals(true, albums.single().mediaMetadata.isPlayable)
        assertEquals(
            "https://velin.example/api/v1/covers/cover-1/256",
            albums.single().mediaMetadata.artworkUri.toString(),
        )
        assertEquals(listOf(artistMediaId("artist-1")), artists.map { it.mediaId })
        assertEquals("Massive Attack", artists.single().mediaMetadata.title.toString())
        assertEquals(true, artist.mediaMetadata.isBrowsable)
        assertEquals(true, album.mediaMetadata.isPlayable)
        assertNull(album.localConfiguration)
    }

    @Test
    fun albumChildrenArePlayableTracksInDiscTrackOrder() = runTest {
        val catalog = AutoLibraryCatalog(FakeLibraryGateway(), artworkPolicy())

        val tracks = catalog.children(albumMediaId("album-1"), 0, 50)

        assertEquals(
            listOf(trackMediaId("t-angel"), trackMediaId("t-teardrop"), trackMediaId("t-inertia")),
            tracks.map { it.mediaId },
        )
        assertEquals("Teardrop", tracks[1].mediaMetadata.title.toString())
        assertEquals("Massive Attack", tracks[1].mediaMetadata.artist.toString())
        assertEquals("Mezzanine", tracks[1].mediaMetadata.albumTitle.toString())
        assertEquals(3, tracks[1].mediaMetadata.trackNumber)
        assertEquals(1, tracks[1].mediaMetadata.discNumber)
        tracks.forEach { track ->
            assertEquals(false, track.mediaMetadata.isBrowsable)
            assertEquals(true, track.mediaMetadata.isPlayable)
            assertNull(track.localConfiguration)
            assertFalse(track.mediaId.contains("secret-token"))
            assertFalse(track.mediaMetadata.toString().contains("secret-token"))
        }
    }

    @Test
    fun artistChildrenArePlayableTracks() = runTest {
        val catalog = AutoLibraryCatalog(FakeLibraryGateway(), artworkPolicy())

        val tracks = catalog.children(artistMediaId("artist-1"), 0, 50)

        assertEquals(3, tracks.size)
        assertEquals(trackMediaId("t-angel"), tracks.first().mediaId)
    }

    @Test
    fun paginationDoesNotLoadTheEntireCollection() = runTest {
        val gateway = FakeLibraryGateway(albumCount = 5)
        val catalog = AutoLibraryCatalog(gateway, artworkPolicy())

        val first = catalog.children(AutoAlbumsId, 0, 2)
        val second = catalog.children(AutoAlbumsId, 1, 2)

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertEquals(albumMediaId("album-1"), first.first().mediaId)
        assertEquals(albumMediaId("album-3"), second.first().mediaId)
        assertTrue(gateway.albumPageLimits.all { it <= 2 })
    }

    @Test
    fun searchReturnsPlayableTracks() = runTest {
        val catalog = AutoLibraryCatalog(FakeLibraryGateway(), artworkPolicy())

        val results = catalog.search("tear", 0, 50)

        assertEquals(listOf(trackMediaId("t-teardrop")), results.map { it.mediaId })
        assertEquals(true, results.single().mediaMetadata.isPlayable)
    }

    @Test
    fun missingItemsSurfaceAsNotFound() = runTest {
        val catalog = AutoLibraryCatalog(FakeLibraryGateway(), artworkPolicy())
        val error = runCatching { catalog.item(albumMediaId("missing")) }.exceptionOrNull()
        assertTrue(error is ApiException && error.isNotFound)
    }

    @Test
    fun pagerSkipsToRequestedWindow() = runTest {
        val items = loadLibraryWindow(1, 2) { cursor, limit ->
            val start = cursor?.toInt() ?: 0
            val values = (start until start + limit).filter { it < 7 }
            val next = start + values.size
            Page(values, nextCursor = next.toString(), hasMore = next < 7)
        }
        assertEquals(listOf(2, 3), items)
    }

    @Test
    fun pageSizeIsCappedForLargeLibraryRequests() {
        assertEquals(50, normalizeLibraryPageSize(0))
        assertEquals(200, normalizeLibraryPageSize(Int.MAX_VALUE))
        assertEquals(25, normalizeLibraryPageSize(25))
    }

    @Test
    fun pagerStopsAfterBoundedFetches() = runTest {
        var fetches = 0
        val items = loadLibraryWindow(50, 2) { _, _ ->
            fetches += 1
            Page(listOf(fetches), nextCursor = fetches.toString(), hasMore = true)
        }
        assertEquals(emptyList<Int>(), items)
        assertEquals(17, fetches)
    }

    private fun artworkPolicy() = ArtworkRequestPolicy("https://velin.example")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoPlaybackResolverTest {
    @Test
    fun selectedAlbumTrackBuildsCompleteOrderedQueue() = runTest {
        val credentials = testCredentials()
        val resolver = AutoPlaybackResolver(FakeLibraryGateway(), PlaybackMediaItemFactory(credentials))

        val queue = resolver.queueFor(trackMediaId("t-teardrop"))

        assertEquals(1, queue.startIndex)
        assertEquals(listOf("t-angel", "t-teardrop", "t-inertia"), queue.items.map { it.mediaId })
        queue.items.forEach { item ->
            assertTrue(item.localConfiguration?.uri.toString().endsWith("/stream"))
            assertFalse(item.localConfiguration?.uri.toString().contains(credentials.token))
            assertFalse(item.mediaMetadata.artworkUri.toString().contains(credentials.token))
        }
        assertEquals(
            "https://velin.example/api/v1/tracks/t-teardrop/stream",
            queue.items[1].localConfiguration?.uri.toString(),
        )
    }

    @Test
    fun playingAnAlbumStartsAtTheFirstTrack() = runTest {
        val resolver = AutoPlaybackResolver(FakeLibraryGateway(), PlaybackMediaItemFactory(testCredentials()))

        val queue = resolver.queueFor(albumMediaId("album-1"))

        assertEquals(0, queue.startIndex)
        assertEquals(3, queue.items.size)
        assertEquals("t-angel", queue.items.first().mediaId)
    }

    @Test
    fun playingAnArtistUsesTheArtistTrackList() = runTest {
        val resolver = AutoPlaybackResolver(FakeLibraryGateway(), PlaybackMediaItemFactory(testCredentials()))

        val queue = resolver.queueFor(artistMediaId("artist-1"))

        assertEquals(0, queue.startIndex)
        assertEquals(3, queue.items.size)
    }

    @Test
    fun unavailableServerFailsCleanly() = runTest {
        val resolver = AutoPlaybackResolver(
            FakeLibraryGateway(unavailable = true),
            PlaybackMediaItemFactory(testCredentials()),
        )
        val error = runCatching { resolver.queueFor(trackMediaId("t-teardrop")) }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertFalse(error!!.message.orEmpty().contains("secret-token"))
    }

    @Test
    fun unauthorizedAccessIsMarkedAsAuthenticationFailure() = runTest {
        val resolver = AutoPlaybackResolver(
            FakeLibraryGateway(unauthorized = true),
            PlaybackMediaItemFactory(testCredentials()),
        )
        val error = runCatching { resolver.playableTrack(trackMediaId("t-teardrop")) }.exceptionOrNull()
        assertTrue(error is ApiException && error.authenticationFailed)
    }

    private fun testCredentials() = DeviceCredentials(
        serverUrl = "https://velin.example",
        deviceId = "device-1",
        token = "secret-token",
        serverName = "Velin",
        serverVersion = "test",
    )
}

private class FakeLibraryGateway(
    private val albumCount: Int = 1,
    private val unavailable: Boolean = false,
    private val unauthorized: Boolean = false,
) : LibraryGateway {
    val albumPageLimits = mutableListOf<Int>()

    private val albumTracks = listOf(
        track("t-angel", "Angel", disc = 1, number = 1),
        track("t-teardrop", "Teardrop", disc = 1, number = 3),
        track("t-inertia", "Inertia Creeps", disc = 2, number = 1),
    )

    override suspend fun status(): ServerStatus = ServerStatus("Velin", "ok", "test")

    override suspend fun summary(): LibrarySummary = error("unused")

    override suspend fun loadArtistsPage(cursor: String?, limit: Int): Page<Artist> {
        checkAvailable()
        return Page(
            items = listOf(Artist("artist-1", "Massive Attack", albumCount = 1, trackCount = 3)),
            nextCursor = null,
            hasMore = false,
        )
    }

    override suspend fun loadAlbumsPage(cursor: String?, limit: Int): Page<Album> {
        checkAvailable()
        albumPageLimits += limit
        val all = (1..albumCount).map { index ->
            Album(
                id = "album-$index",
                title = if (index == 1) "Mezzanine" else "Album $index",
                artistName = "Massive Attack",
                year = 1998,
                coverId = "cover-1",
                trackCount = 3,
            )
        }
        val start = cursor?.toIntOrNull() ?: 0
        val slice = all.drop(start).take(limit)
        val nextIndex = start + slice.size
        return Page(
            items = slice,
            nextCursor = if (nextIndex < all.size) nextIndex.toString() else null,
            hasMore = nextIndex < all.size,
        )
    }

    override suspend fun loadTracksPage(
        cursor: String?,
        artistId: String?,
        albumId: String?,
        limit: Int,
    ): Page<Track> {
        checkAvailable()
        val all = when {
            albumId == "album-1" || artistId == "artist-1" -> albumTracks
            else -> emptyList()
        }
        val start = cursor?.toIntOrNull() ?: 0
        val slice = all.drop(start).take(limit)
        val next = start + slice.size
        return Page(slice, nextCursor = if (next < all.size) next.toString() else null, hasMore = next < all.size)
    }

    override suspend fun loadArtist(artistId: String): Artist {
        checkAvailable()
        if (artistId != "artist-1") throw notFound()
        return Artist("artist-1", "Massive Attack", 1, 3)
    }

    override suspend fun loadAlbum(albumId: String): Album {
        checkAvailable()
        if (albumId != "album-1") throw notFound()
        return Album("album-1", "Mezzanine", "Massive Attack", 1998, "cover-1", 3)
    }

    override suspend fun loadTrack(trackId: String): TrackDetail {
        checkAvailable()
        val track = albumTracks.firstOrNull { it.id == trackId } ?: throw notFound()
        return TrackDetail(
            id = track.id,
            title = track.title,
            format = track.format,
            artistId = track.artistId,
            artistName = track.artistName,
            albumId = track.albumId,
            albumTitle = track.albumTitle,
            albumArtistName = track.artistName,
            genre = null,
            dateText = null,
            trackNumber = track.trackNumber,
            totalTracks = 3,
            discNumber = track.discNumber,
            totalDiscs = 2,
            durationMs = track.durationMs,
            sampleRate = null,
            bitsPerSample = null,
            channels = null,
            coverId = track.coverId,
        )
    }

    override suspend fun loadAlbumTracks(albumId: String): List<Track> {
        checkAvailable()
        if (albumId != "album-1") throw notFound()
        return albumTracks
    }

    override suspend fun loadArtistTracks(artistId: String): List<Track> {
        checkAvailable()
        if (artistId != "artist-1") throw notFound()
        return albumTracks
    }

    override suspend fun search(query: String, cursor: String?, limit: Int): Page<Track> {
        checkAvailable()
        val matches = albumTracks.filter { it.title.contains(query, ignoreCase = true) }
        return Page(matches.take(limit), nextCursor = null, hasMore = false)
    }

    private fun checkAvailable() {
        if (unauthorized) {
            throw ApiException("Device access was revoked. Pair this device again.", authenticationFailed = true)
        }
        if (unavailable) {
            throw ApiException("Cannot reach the Velin server.")
        }
    }

    private fun notFound() = ApiException("The server request failed (HTTP 404).")

    private fun track(id: String, title: String, disc: Int, number: Int) = Track(
        id = id,
        title = title,
        format = "flac",
        artistName = "Massive Attack",
        albumTitle = "Mezzanine",
        durationMs = 180_000,
        trackNumber = number,
        discNumber = disc,
        artistId = "artist-1",
        albumId = "album-1",
        coverId = "cover-1",
    )
}
