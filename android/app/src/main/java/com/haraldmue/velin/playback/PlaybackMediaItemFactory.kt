package com.haraldmue.velin.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.haraldmue.velin.data.ArtworkRequestPolicy
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.ServerAddress
import com.haraldmue.velin.data.Track
import okhttp3.HttpUrl.Companion.toHttpUrl

class PlaybackMediaItemFactory(
    credentials: DeviceCredentials,
) {
    private val serverUrl = ServerAddress.normalize(credentials.serverUrl).toHttpUrl()
    private val artworkPolicy = ArtworkRequestPolicy(credentials.serverUrl)

    fun create(track: Track): MediaItem {
        require(track.id.isNotBlank() && track.id.length <= 128) { "Invalid track ID." }
        val streamUrl = serverUrl.newBuilder()
            .addPathSegments("api/v1/tracks")
            .addPathSegment(track.id)
            .addPathSegment("stream")
            .build()
        val metadataExtras = Bundle().apply {
            putString(PlaybackResumeMetadata.ExtraFormat, track.format.lowercase())
            track.coverId?.let { putString(PlaybackResumeMetadata.ExtraCoverId, it) }
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setExtras(metadataExtras)
        track.coverId?.let { coverId ->
            metadataBuilder.setArtworkUri(Uri.parse(artworkPolicy.urlFor(coverId, size = 512)))
        }
        val metadata = metadataBuilder.build()
        val builder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl.toString())
            .setMediaMetadata(metadata)
        when (track.format.lowercase()) {
            "flac" -> builder.setMimeType(MimeTypes.AUDIO_FLAC)
            "mp3" -> builder.setMimeType(MimeTypes.AUDIO_MPEG)
        }
        return builder.build()
    }
}
