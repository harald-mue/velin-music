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
import com.haraldmue.velin.data.Track

internal class AutoLibraryCatalog(
    private val gateway: LibraryGateway,
    private val artworkPolicy: ArtworkRequestPolicy?,
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
            AutoMediaId.Albums -> albumsCategory()
            AutoMediaId.Artists -> artistsCategory()
            is AutoMediaId.Album -> albumItem(gateway.loadAlbum(parsed.id))
            is AutoMediaId.Artist -> artistItem(gateway.loadArtist(parsed.id))
            is AutoMediaId.Track -> trackItem(gateway.loadTrack(parsed.id).toTrack())
        }
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        return when (val parsed = parseAutoMediaId(parentId) ?: error("Unknown media ID.")) {
            AutoMediaId.Root -> pagedRootCategories(page, pageSize)
            AutoMediaId.Albums -> loadLibraryWindow(page, pageSize, gateway::loadAlbumsPage).map(::albumItem)
            AutoMediaId.Artists -> loadLibraryWindow(page, pageSize, gateway::loadArtistsPage).map(::artistItem)
            is AutoMediaId.Album -> loadLibraryWindow(page, pageSize) { cursor, limit ->
                gateway.loadTracksPage(cursor = cursor, albumId = parsed.id, limit = limit)
            }.map(::trackItem)
            is AutoMediaId.Artist -> loadLibraryWindow(page, pageSize) { cursor, limit ->
                gateway.loadTracksPage(cursor = cursor, artistId = parsed.id, limit = limit)
            }.map(::trackItem)
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

    private fun albumsCategory(): MediaItem = folderItem(
        mediaId = AutoAlbumsId,
        title = "Albums",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        playable = false,
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
            .setIsPlayable(true)
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
            .setIsPlayable(true)
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

    private fun pagedRootCategories(page: Int, pageSize: Int): List<MediaItem> {
        val categories = listOf(albumsCategory(), artistsCategory())
        val size = normalizeLibraryPageSize(pageSize)
        val start = page.coerceAtLeast(0) * size
        if (start >= categories.size) return emptyList()
        return categories.drop(start).take(size)
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
        val policy = artworkPolicy ?: return null
        val id = coverId ?: return null
        return runCatching { Uri.parse(policy.urlFor(id)) }.getOrNull()
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
