package com.haraldmue.velin.playback

import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.data.cache.LibraryCacheRepository
import kotlinx.coroutines.flow.first

internal const val AutoHomeRecentlyAddedLimit = 16
internal const val AutoHomeDiscoveryLimit = 32

internal interface AutoHomeAlbums {
    suspend fun recentlyAdded(limit: Int): List<Album>
    suspend fun discovery(limit: Int): List<Album>
    suspend fun albums(limit: Int, offset: Int): List<Album> = emptyList()
    suspend fun artists(limit: Int, offset: Int): List<Artist> = emptyList()
    suspend fun album(albumId: String): Album? = null
    suspend fun artist(artistId: String): Artist? = null
    suspend fun albumTracks(albumId: String): List<Track> = emptyList()
    suspend fun artistTracks(artistId: String): List<Track> = emptyList()
    suspend fun track(trackId: String): Track? = null
}

internal class CachedAutoHomeAlbums(
    private val cache: LibraryCacheRepository,
) : AutoHomeAlbums {
    override suspend fun recentlyAdded(limit: Int): List<Album> =
        cache.recentlyAddedAlbums(limit).first()

    override suspend fun discovery(limit: Int): List<Album> =
        cache.discoveryAlbums(limit).first()

    override suspend fun albums(limit: Int, offset: Int): List<Album> =
        cache.albumsWindow(limit, offset)

    override suspend fun artists(limit: Int, offset: Int): List<Artist> =
        cache.artistsWindow(limit, offset)

    override suspend fun album(albumId: String): Album? = cache.album(albumId)

    override suspend fun artist(artistId: String): Artist? = cache.artist(artistId)

    override suspend fun albumTracks(albumId: String): List<Track> =
        cache.albumTracks(albumId).first()

    override suspend fun artistTracks(artistId: String): List<Track> =
        cache.artistTracks(artistId).first()

    override suspend fun track(trackId: String): Track? = cache.track(trackId)
}

internal fun autoHomeShelves(
    recentlyAdded: List<Album>,
    discovery: List<Album>,
): Pair<List<Album>, List<Album>> {
    // Auto shows these as separate tabs, so Discover keeps its own order
    // even when albums also appear under Recent. Phone Home still
    // de-duplicates on one scrolling canvas.
    val recent = recentlyAdded.take(AutoHomeRecentlyAddedLimit)
    val discover = discovery.take(AutoHomeRecentlyAddedLimit)
    return recent to discover
}
