package com.haraldmue.velin.playback

import androidx.media3.common.MediaItem
import com.haraldmue.velin.data.LibraryGateway
import com.haraldmue.velin.data.Track

internal data class AutoPlaybackQueue(
    val items: List<MediaItem>,
    val startIndex: Int,
)

internal class AutoPlaybackResolver(
    private val gateway: LibraryGateway,
    private val mediaItemFactory: PlaybackMediaItemFactory,
    private val cache: AutoHomeAlbums? = null,
) {
    suspend fun queueFor(mediaId: String): AutoPlaybackQueue {
        return when (val parsed = parsePlaybackMediaId(mediaId) ?: error("Unknown media ID.")) {
            AutoMediaId.Root, AutoMediaId.RecentlyAdded, AutoMediaId.Discover,
            AutoMediaId.Albums, AutoMediaId.Artists,
            -> error("This library section cannot be played.")
            is AutoMediaId.Album -> AutoPlaybackQueue(
                items = playableTracks(albumQueueTracks(parsed.id)),
                startIndex = 0,
            )
            is AutoMediaId.Artist -> AutoPlaybackQueue(
                items = playableTracks(artistQueueTracks(parsed.id)),
                startIndex = 0,
            )
            is AutoMediaId.Track -> queueForTrack(parsed.id)
        }
    }

    suspend fun playableTrack(mediaId: String): MediaItem {
        val parsed = parsePlaybackMediaId(mediaId) as? AutoMediaId.Track
            ?: error("This item cannot be added to the queue.")
        return mediaItemFactory.create(resolvedTrack(parsed.id))
    }

    private suspend fun queueForTrack(trackId: String): AutoPlaybackQueue {
        val track = resolvedTrack(trackId)
        val albumId = track.albumId
        if (!albumId.isNullOrEmpty()) {
            val albumTracks = albumQueueTracks(albumId)
            val startIndex = albumTracks.indexOfFirst { it.id == track.id }
            if (startIndex >= 0 && isValidPlaybackQueue(albumTracks.size, startIndex)) {
                return AutoPlaybackQueue(playableTracks(albumTracks), startIndex)
            }
        }
        return AutoPlaybackQueue(listOf(mediaItemFactory.create(track)), 0)
    }

    private suspend fun resolvedTrack(trackId: String): Track =
        cache?.track(trackId) ?: gateway.loadTrack(trackId).toTrack()

    private suspend fun albumQueueTracks(albumId: String): List<Track> {
        if (cache?.album(albumId) != null) {
            return cache.albumTracks(albumId)
        }
        return gateway.loadAlbumTracks(albumId)
    }

    private suspend fun artistQueueTracks(artistId: String): List<Track> {
        if (cache?.artist(artistId) != null) {
            return cache.artistTracks(artistId)
        }
        return gateway.loadArtistTracks(artistId)
    }

    private fun playableTracks(tracks: List<Track>): List<MediaItem> {
        require(tracks.isNotEmpty()) { "This album or artist has no playable tracks." }
        require(isValidPlaybackQueue(tracks.size, 0)) { "This playback queue is invalid." }
        return tracks.map(mediaItemFactory::create)
    }
}
