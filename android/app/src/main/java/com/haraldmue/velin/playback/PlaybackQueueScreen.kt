package com.haraldmue.velin.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
fun PlaybackQueueScreen(
    state: PlaybackUiState,
    artworkClient: ArtworkClient,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
    ) {
        item {
            Text("Playback queue", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "${state.queue.size} tracks",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterChip(
                    selected = state.shuffleEnabled,
                    onClick = onToggleShuffle,
                    enabled = state.queue.size > 1,
                    label = { Text("Shuffle") },
                )
                FilterChip(
                    selected = state.repeatMode != PlaybackRepeatMode.Off,
                    onClick = onCycleRepeat,
                    enabled = state.queue.isNotEmpty(),
                    label = { Text("Repeat: ${repeatModeLabel(state.repeatMode)}") },
                )
            }
        }
        if (state.queue.isEmpty()) {
            item {
                Text(
                    text = "The playback queue is empty.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(
                items = state.queue,
                key = { index, item -> "$index-${item.mediaId}" },
            ) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ArtworkImage(
                        artworkClient = artworkClient,
                        artworkUrl = item.artworkUrl,
                        modifier = Modifier.size(48.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (index == state.queueIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == state.queueIndex) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        item.artist?.takeIf(String::isNotEmpty)?.let { artist ->
                            Text(
                                text = artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(
                        onClick = { onRemove(index) },
                        enabled = state.canEditQueue,
                    ) {
                        Text("Remove")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

internal fun repeatModeLabel(mode: PlaybackRepeatMode): String = when (mode) {
    PlaybackRepeatMode.Off -> "Off"
    PlaybackRepeatMode.All -> "All"
    PlaybackRepeatMode.One -> "One"
}
