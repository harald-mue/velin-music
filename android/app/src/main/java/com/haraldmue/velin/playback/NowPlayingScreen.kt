package com.haraldmue.velin.playback

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.ui.ArtworkImage

@Composable
fun NowPlayingScreen(
    state: PlaybackUiState,
    artworkClient: ArtworkClient,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val duration = state.durationMs
    var dragging by remember(state.mediaId) { mutableStateOf(false) }
    var sliderFraction by remember(state.mediaId) { mutableFloatStateOf(0f) }
    val positionFraction = progressFraction(state.positionMs, duration)

    LaunchedEffect(positionFraction, dragging) {
        if (!dragging) sliderFraction = positionFraction
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtworkImage(
            artworkClient = artworkClient,
            artworkUrl = state.artworkUrl,
            modifier = Modifier
                .fillMaxWidth(0.64f)
                .widthIn(max = 360.dp)
                .aspectRatio(1f)
                .shadow(18.dp, MaterialTheme.shapes.large),
        )
        Text(
            text = state.title ?: "Nothing playing",
            modifier = Modifier.padding(top = 28.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
        val subtitle = listOfNotNull(state.artist, state.album).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val playbackDetail = listOfNotNull(
            state.format?.uppercase(),
            if (state.queueSize > 1) "${state.queueIndex + 1} of ${state.queueSize}" else null,
        ).joinToString(" · ")
        if (playbackDetail.isNotEmpty()) {
            Surface(
                modifier = Modifier.padding(top = 12.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
            ) {
                Text(
                    text = playbackDetail,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Slider(
            value = sliderFraction,
            onValueChange = {
                dragging = true
                sliderFraction = it
            },
            onValueChangeFinished = {
                duration?.let { onSeek((sliderFraction * it).toLong()) }
                dragging = false
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSeek,
            valueRange = 0f..1f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatPlaybackTime(
                    if (dragging && duration != null) (sliderFraction * duration).toLong() else state.positionMs,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                duration?.let(::formatPlaybackTime) ?: "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = state.hasPrevious, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = onTogglePlayPause,
                enabled = state.mediaId != null && !state.isBuffering,
                modifier = Modifier.size(72.dp),
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = when {
                            state.isPlaying -> Icons.Rounded.Pause
                            state.isEnded -> Icons.Rounded.Replay
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = when {
                            state.isPlaying -> "Pause"
                            state.isEnded -> "Replay"
                            else -> "Play"
                        },
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
            IconButton(onClick = onNext, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", modifier = Modifier.size(34.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PlaybackOption(
                icon = Icons.Rounded.Shuffle,
                label = if (state.shuffleEnabled) "Shuffle on" else "Shuffle",
                selected = state.shuffleEnabled,
                enabled = state.queue.size > 1,
                onClick = onToggleShuffle,
            )
            PlaybackOption(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Queue · ${state.queue.size}",
                enabled = state.queue.isNotEmpty(),
                onClick = onOpenQueue,
            )
            PlaybackOption(
                icon = if (state.repeatMode == PlaybackRepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                label = "Repeat ${repeatModeLabel(state.repeatMode)}",
                selected = state.repeatMode != PlaybackRepeatMode.Off,
                enabled = state.queue.isNotEmpty(),
                onClick = onCycleRepeat,
            )
        }

        state.error?.let { error ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(14.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PlaybackOption(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun progressFraction(positionMs: Long, durationMs: Long?): Float {
    if (durationMs == null || durationMs <= 0) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
