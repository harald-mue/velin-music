package com.haraldmue.velin.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.Page
import com.haraldmue.velin.data.Track

internal class AutoLibraryCatalog(
    private val gateway: LibraryGateway,
    private val artworkPolicy: ArtworkRequestPolicy?,
    private val homeAlbums: AutoHomeAlbums? = null,
) {
    fun root(): MediaItem = folderItem(
        mediaId = AutoRootId,
        title = "Velin",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        playable = false,
        extras = autoBrowseStyleExtras(),
    )

    suspend fun item(mediaId: String): MediaItem {
        return when (val parsed = parseAutoMediaId(mediaId) ?: error("Unknown media ID.")) {
            AutoMediaId.Root -> root()
            AutoMediaId.RecentlyAdded -> recentlyAddedCategory()
            AutoMediaId.Discover -> discoverCategory()
            AutoMediaId.Albums -> albumsCategory()
            AutoMediaId.Artists -> artistsCategory()
            is AutoMediaId.Album -> albumItem(cachedAlbum(parsed.id) ?: gateway.loadAlbum(parsed.id))
            is AutoMediaId.Artist -> artistItem(cachedArtist(parsed.id) ?: gateway.loadArtist(parsed.id))
            is AutoMediaId.Track -> trackItem(cachedTrack(parsed.id) ?: gateway.loadTrack(parsed.id).toTrack())
        }
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        return when (val parsed = parseAutoMediaId(parentId) ?: error("Unknown media ID.")) {
            AutoMediaId.Root -> pagedRootCategories(page, pageSize)
            AutoMediaId.RecentlyAdded -> pageOf(homeShelves().first.map(::albumItem), page, pageSize)
            AutoMediaId.Discover -> pageOf(homeShelves().second.map(::albumItem), page, pageSize)
            AutoMediaId.Albums -> cachedOrNetwork(
                page,
                pageSize,
                cachedPage = { limit, offset -> homeAlbums?.albums(limit, offset).orEmpty() },
                network = gateway::loadAlbumsPage,
            ).map(::albumItem)
            AutoMediaId.Artists -> cachedOrNetwork(
                page,
                pageSize,
                cachedPage = { limit, offset -> homeAlbums?.artists(limit, offset).orEmpty() },
                network = gateway::loadArtistsPage,
            ).map(::artistItem)
            is AutoMediaId.Album -> albumChildren(parsed.id, page, pageSize)
            is AutoMediaId.Artist -> artistChildren(parsed.id, page, pageSize)
            is AutoMediaId.Track -> emptyList()
        }
    }

    suspend fun search(query: String, page: Int, pageSize: Int): List<MediaItem> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        return loadLibraryWindow(page, pageSize) { cursor, limit ->
            gateway.search(normalized, cursor, limit)
        }.map(::trackItem)
    }

    private suspend fun albumChildren(albumId: String, page: Int, pageSize: Int): List<MediaItem> {
        if (cachedAlbum(albumId) != null) {
            return pageOf(homeAlbums?.albumTracks(albumId).orEmpty().map(::trackItem), page, pageSize)
        }
        return loadLibraryWindow(page, pageSize) { cursor, limit ->
            gateway.loadTracksPage(cursor = cursor, albumId = albumId, limit = limit)
        }.map(::trackItem)
    }

    private suspend fun artistChildren(artistId: String, page: Int, pageSize: Int): List<MediaItem> {
        if (cachedArtist(artistId) != null) {
            return pageOf(homeAlbums?.artistTracks(artistId).orEmpty().map(::trackItem), page, pageSize)
        }
        return loadLibraryWindow(page, pageSize) { cursor, limit ->
            gateway.loadTracksPage(cursor = cursor, artistId = artistId, limit = limit)
        }.map(::trackItem)
    }

    private suspend fun cachedAlbum(albumId: String): Album? = homeAlbums?.album(albumId)

    private suspend fun cachedArtist(artistId: String): Artist? = homeAlbums?.artist(artistId)

    private suspend fun cachedTrack(trackId: String): Track? = homeAlbums?.track(trackId)

    private fun recentlyAddedCategory(): MediaItem = folderItem(
        mediaId = AutoRecentlyAddedId,
        title = "Recent",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        playable = false,
        extras = autoGridBrowseStyleExtras(),
    )

    private fun discoverCategory(): MediaItem = folderItem(
        mediaId = AutoDiscoverId,
        title = "Discover",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        playable = false,
        extras = autoGridBrowseStyleExtras(),
    )

    private fun albumsCategory(): MediaItem = folderItem(
        mediaId = AutoAlbumsId,
        title = "Albums",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        playable = false,
        extras = autoBrowseStyleExtras(),
    )

    private fun artistsCategory(): MediaItem = folderItem(
        mediaId = AutoArtistsId,
        title = "Artists",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
        playable = false,
    )

    private fun albumItem(album: Album): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(album.title)
            .setArtist(album.artistName)
            .setAlbumTitle(album.title)
            .setAlbumArtist(album.artistName)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .apply {
                album.year?.let(::setReleaseYear)
                artworkUri(album.coverId)?.let(::setArtworkUri)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(albumMediaId(album.id))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun artistItem(artist: Artist): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(artist.name)
            .setArtist(artist.name)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .build()
        return MediaItem.Builder()
            .setMediaId(artistMediaId(artist.id))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun trackItem(track: Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                track.durationMs?.let(::setDurationMs)
                track.trackNumber?.let(::setTrackNumber)
                track.discNumber?.let(::setDiscNumber)
                artworkUri(track.coverId)?.let(::setArtworkUri)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(trackMediaId(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun pagedRootCategories(page: Int, pageSize: Int): List<MediaItem> {
        val (recent, discover) = homeShelves()
        val categories = buildList {
            if (recent.isNotEmpty()) add(recentlyAddedCategory())
            if (discover.isNotEmpty()) add(discoverCategory())
            add(albumsCategory())
            add(artistsCategory())
        }
        return pageOf(categories, page, pageSize)
    }

    private suspend fun homeShelves(): Pair<List<Album>, List<Album>> {
        val source = homeAlbums ?: return emptyList<Album>() to emptyList()
        return runCatching {
            autoHomeShelves(
                source.recentlyAdded(AutoHomeRecentlyAddedLimit),
                source.discovery(AutoHomeDiscoveryLimit),
            )
        }.getOrDefault(emptyList<Album>() to emptyList())
    }

    private suspend fun <T> cachedOrNetwork(
        page: Int,
        pageSize: Int,
        cachedPage: suspend (limit: Int, offset: Int) -> List<T>,
        network: suspend (cursor: String?, limit: Int) -> Page<T>,
    ): List<T> {
        val size = normalizeLibraryPageSize(pageSize)
        val offset = page.coerceAtLeast(0) * size
        val cached = cachedPage(size, offset)
        if (cached.isNotEmpty()) return cached
        if (page > 0 && cachedPage(1, 0).isNotEmpty()) return emptyList()
        return loadLibraryWindow(page, pageSize, network)
    }

    private fun pageOf(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        val size = normalizeLibraryPageSize(pageSize)
        val start = page.coerceAtLeast(0) * size
        if (start >= items.size) return emptyList()
        return items.drop(start).take(size)
    }

    private fun folderItem(
        mediaId: String,
        title: String,
        mediaType: Int,
        playable: Boolean,
        extras: Bundle? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(playable)
            .setMediaType(mediaType)
            .apply { extras?.let(::setExtras) }
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun artworkUri(coverId: String?): Uri? {
        artworkPolicy ?: return null
        val id = coverId ?: return null
        return AutoArtworkUris.uri(id)
    }
}

private fun autoBrowseStyleExtras(): Bundle = Bundle().apply {
    putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
    )
    putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
    )
}

private fun autoGridBrowseStyleExtras(): Bundle = Bundle().apply {
    putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
    )
    putInt(
        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
    )
}
