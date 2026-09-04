package com.haraldmue.velin.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val bufferedFraction = progressFraction(state.bufferedPositionMs, duration)

    LaunchedEffect(positionFraction, dragging) {
        if (!dragging) sliderFraction = positionFraction
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArtworkImage(
            artworkClient = artworkClient,
            artworkUrl = state.artworkUrl,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f),
        )
        Text(
            text = state.title ?: "Nothing playing",
            modifier = Modifier.padding(top = 28.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val subtitle = listOfNotNull(state.artist, state.album).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val playbackDetail = listOfNotNull(
            state.format,
            if (state.queueSize > 1) "${state.queueIndex + 1} of ${state.queueSize}" else null,
        ).joinToString(" · ")
        if (playbackDetail.isNotEmpty()) {
            Text(
                text = playbackDetail,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onOpenQueue, enabled = state.queue.isNotEmpty()) {
                Text("Queue (${state.queue.size})")
            }
            TextButton(onClick = onToggleShuffle, enabled = state.queue.size > 1) {
                Text(if (state.shuffleEnabled) "Shuffle on" else "Shuffle off")
            }
            TextButton(onClick = onCycleRepeat, enabled = state.queue.isNotEmpty()) {
                Text("Repeat ${repeatModeLabel(state.repeatMode)}")
            }
        }
        LinearProgressIndicator(
            progress = { bufferedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
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
            Text(formatPlaybackTime(if (dragging && duration != null) (sliderFraction * duration).toLong() else state.positionMs))
            Text(duration?.let(::formatPlaybackTime) ?: "--:--")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious, enabled = state.hasPrevious) {
                Text("Previous")
            }
            Button(
                onClick = onTogglePlayPause,
                enabled = state.mediaId != null && !state.isBuffering,
            ) {
                Text(if (state.isPlaying) "Pause" else if (state.isEnded) "Replay" else "Play")
            }
            TextButton(onClick = onNext, enabled = state.hasNext) {
                Text("Next")
            }
        }
        if (state.isBuffering) {
            Text(
                text = "Buffering…",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(top = 16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
