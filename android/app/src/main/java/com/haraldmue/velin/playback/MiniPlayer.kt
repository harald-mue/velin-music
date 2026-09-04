package com.haraldmue.velin.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.ui.ArtworkImage

@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    artworkClient: ArtworkClient,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    if (state.mediaId == null) return

    Surface(
        modifier = Modifier.clickable(onClick = onOpen),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkImage(
                artworkClient = artworkClient,
                artworkUrl = state.artworkUrl,
                modifier = Modifier.size(48.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title ?: "Unknown track",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                val detail = state.error ?: state.artist.orEmpty()
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            if (state.isBuffering) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            TextButton(onClick = onPrevious, enabled = state.hasPrevious) {
                Text("Prev")
            }
            TextButton(onClick = onTogglePlayPause, enabled = !state.isBuffering) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
            TextButton(onClick = onNext, enabled = state.hasNext) {
                Text("Next")
            }
        }
    }
}
