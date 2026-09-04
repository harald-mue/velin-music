package com.haraldmue.velin.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.ui.ArtworkImage
import kotlin.math.roundToInt

@Composable
fun PlaybackQueueScreen(
    state: PlaybackUiState,
    artworkClient: ArtworkClient,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
    ) {
        item {
            if (state.queueBusy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.shuffleEnabled,
                    onClick = onToggleShuffle,
                    enabled = state.queue.size > 1 && !state.queueBusy,
                    label = { Text("Shuffle") },
                    leadingIcon = { Icon(Icons.Rounded.Shuffle, contentDescription = null) },
                )
                FilterChip(
                    selected = state.repeatMode != PlaybackRepeatMode.Off,
                    onClick = onCycleRepeat,
                    enabled = state.queue.isNotEmpty() && !state.queueBusy,
                    label = { Text("Repeat ${repeatModeLabel(state.repeatMode)}") },
                    leadingIcon = { Icon(Icons.Rounded.Repeat, contentDescription = null) },
                )
                FilterChip(
                    selected = false,
                    onClick = onSave,
                    enabled = state.canSaveQueue && !state.queueBusy,
                    label = { Text("Save") },
                    leadingIcon = { Icon(Icons.Rounded.Save, contentDescription = "Save queue") },
                )
                FilterChip(
                    selected = false,
                    onClick = onLoad,
                    enabled = state.canLoadQueue && !state.queueBusy,
                    label = { Text("Load") },
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = "Load queue") },
                )
                FilterChip(
                    selected = false,
                    onClick = onClear,
                    enabled = state.queue.isNotEmpty() && !state.queueBusy,
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Rounded.ClearAll, contentDescription = "Clear queue") },
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
                ReorderableQueueRow(
                    index = index,
                    lastIndex = state.queue.lastIndex,
                    item = item,
                    isCurrent = index == state.queueIndex,
                    canReorder = state.canReorderQueue && item.available,
                    canRemove = item.available.not() || state.canEditQueue,
                    artworkClient = artworkClient,
                    onSelect = { onSelect(index) },
                    onRemove = { onRemove(index) },
                    onMove = onMove,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReorderableQueueRow(
    index: Int,
    lastIndex: Int,
    item: PlaybackQueueItem,
    isCurrent: Boolean,
    canReorder: Boolean,
    canRemove: Boolean,
    artworkClient: ArtworkClient,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { 72.dp.toPx() }
    var dragOffsetY by remember(index) { mutableFloatStateOf(0f) }
    var dragging by remember(index) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, if (dragging) dragOffsetY.roundToInt() else 0) }
            .then(
                if (canReorder) {
                    Modifier.pointerInput(index, lastIndex) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = true
                                dragOffsetY = 0f
                            },
                            onDragEnd = {
                                val targetIndex = (index + (dragOffsetY / rowHeightPx).roundToInt())
                                    .coerceIn(0, lastIndex)
                                if (targetIndex != index) {
                                    onMove(index, targetIndex)
                                }
                                dragOffsetY = 0f
                                dragging = false
                            },
                            onDragCancel = {
                                dragOffsetY = 0f
                                dragging = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !dragging && item.available, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.DragHandle,
            contentDescription = if (canReorder && item.available) "Drag to reorder" else null,
            modifier = Modifier.padding(end = 4.dp),
            tint = if (canReorder) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.available) 1f else 0.4f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            },
        )
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
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !item.available -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    isCurrent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = when {
                    !item.available -> "Unavailable"
                    else -> item.artist.orEmpty()
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (item.available) 1f else 0.45f,
                ),
            )
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove from queue")
        }
    }
}

internal fun repeatModeLabel(mode: PlaybackRepeatMode): String = when (mode) {
    PlaybackRepeatMode.Off -> "Off"
    PlaybackRepeatMode.All -> "All"
    PlaybackRepeatMode.One -> "One"
}
