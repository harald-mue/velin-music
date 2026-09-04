package com.haraldmue.velin.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackSeekBar(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long?,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onScrubChange: (Long?) -> Unit = {},
    modifier: Modifier = Modifier,
    activeColor: Color,
    bufferedColor: Color,
    trackColor: Color,
) {
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(progressFraction(positionMs, durationMs)) }
    val liveFraction = progressFraction(positionMs, durationMs)
    val bufferFraction = progressFraction(bufferedPositionMs, durationMs)

    LaunchedEffect(liveFraction, dragging) {
        if (!dragging) fraction = liveFraction
    }

    fun seekTo(rawFraction: Float) {
        val duration = durationMs ?: return
        val coerced = rawFraction.coerceIn(0f, 1f)
        fraction = coerced
        onSeek((coerced * duration).toLong())
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (!enabled) disabled()
                setProgress {
                    if (!enabled || durationMs == null) {
                        false
                    } else {
                        seekTo(it)
                        true
                    }
                }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs == null) return@pointerInput
                detectTapGestures { offset ->
                    seekTo(offset.x / size.width.toFloat())
                }
            }
            .pointerInput(enabled, durationMs, onScrubChange) {
                if (!enabled || durationMs == null) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        durationMs?.let { onScrubChange((fraction * it).toLong()) }
                    },
                    onDragEnd = {
                        seekTo(fraction)
                        onScrubChange(null)
                        dragging = false
                    },
                    onDragCancel = {
                        onScrubChange(null)
                        dragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        durationMs?.let { onScrubChange((fraction * it).toLong()) }
                    },
                )
            },
    ) {
        val trackHeight = 4.dp.toPx()
        val trackTop = (size.height - trackHeight) / 2f
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )
        if (bufferFraction > 0f) {
            drawRoundRect(
                color = bufferedColor,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width * bufferFraction, trackHeight),
                cornerRadius = radius,
            )
        }
        if (fraction > 0f) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(0f, trackTop),
                size = Size(size.width * fraction, trackHeight),
                cornerRadius = radius,
            )
        }
        val thumbRadius = if (dragging) 7.dp.toPx() else 5.dp.toPx()
        val thumbX = (size.width * fraction).coerceIn(thumbRadius, size.width - thumbRadius)
        drawCircle(
            color = activeColor,
            radius = thumbRadius,
            center = Offset(thumbX, size.height / 2f),
        )
    }
}
