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
) {
    suspend fun queueFor(mediaId: String): AutoPlaybackQueue {
        return when (val parsed = parsePlaybackMediaId(mediaId) ?: error("Unknown media ID.")) {
            AutoMediaId.Root, AutoMediaId.Albums, AutoMediaId.Artists ->
                error("This library section cannot be played.")
            is AutoMediaId.Album -> AutoPlaybackQueue(
                items = playableTracks(gateway.loadAlbumTracks(parsed.id)),
                startIndex = 0,
            )
            is AutoMediaId.Artist -> AutoPlaybackQueue(
                items = playableTracks(gateway.loadArtistTracks(parsed.id)),
                startIndex = 0,
            )
            is AutoMediaId.Track -> queueForTrack(parsed.id)
        }
    }

    suspend fun playableTrack(mediaId: String): MediaItem {
        val parsed = parsePlaybackMediaId(mediaId) as? AutoMediaId.Track
            ?: error("This item cannot be added to the queue.")
        return mediaItemFactory.create(gateway.loadTrack(parsed.id).toTrack())
    }

    private suspend fun queueForTrack(trackId: String): AutoPlaybackQueue {
        val track = gateway.loadTrack(trackId).toTrack()
        val albumId = track.albumId
        if (!albumId.isNullOrEmpty()) {
            val albumTracks = gateway.loadAlbumTracks(albumId)
            val startIndex = albumTracks.indexOfFirst { it.id == track.id }
            if (startIndex >= 0 && isValidPlaybackQueue(albumTracks.size, startIndex)) {
                return AutoPlaybackQueue(playableTracks(albumTracks), startIndex)
            }
        }
        return AutoPlaybackQueue(listOf(mediaItemFactory.create(track)), 0)
    }

    private fun playableTracks(tracks: List<Track>): List<MediaItem> {
        require(tracks.isNotEmpty()) { "This album or artist has no playable tracks." }
        require(isValidPlaybackQueue(tracks.size, 0)) { "This playback queue is invalid." }
        return tracks.map(mediaItemFactory::create)
    }
}
