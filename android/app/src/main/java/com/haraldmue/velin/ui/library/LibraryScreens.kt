package com.haraldmue.velin.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.DeviceCredentials
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.ui.ArtworkImage

@Composable
fun HomeScreen(
    credentials: DeviceCredentials,
    state: LibraryUiState,
    artworkClient: ArtworkClient,
    onAlbumClick: (Album) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    when {
        state.loading && state.library == null -> LoadingScreen()
        state.error != null && state.library == null -> ErrorScreen(
            message = state.error,
            actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
            onAction = if (state.authenticationFailed) onPairAgain else onRetry,
        )
        else -> {
            val library = state.library ?: return
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Text("Home", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = "${credentials.serverName} ${state.status?.version ?: credentials.serverVersion}",
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = credentials.serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text("Library overview", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${library.artists.items.size}${moreSuffix(library.artists.hasMore)} artists",
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text("${library.albums.items.size}${moreSuffix(library.albums.hasMore)} albums")
                    Text("${library.tracks.items.size}${moreSuffix(library.tracks.hasMore)} tracks")
                }
                if (library.albums.items.isNotEmpty()) {
                    item { Text("Albums", style = MaterialTheme.typography.titleLarge) }
                    items(library.albums.items.take(8), key = { it.id }) { album ->
                        AlbumRow(album, artworkClient, onClick = { onAlbumClick(album) })
                    }
                }
            }
        }
    }
}

private enum class LibrarySection(val label: String) {
    Albums("Albums"),
    Artists("Artists"),
    Tracks("Tracks"),
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    artworkClient: ArtworkClient,
    currentTrackId: String?,
    onAlbumClick: (Album) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    when {
        state.loading && state.library == null -> LoadingScreen()
        state.error != null && state.library == null -> ErrorScreen(
            message = state.error,
            actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
            onAction = if (state.authenticationFailed) onPairAgain else onRetry,
        )
        else -> {
            val library = state.library ?: return
            var section by remember { mutableStateOf(LibrarySection.Albums) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            ) {
                item {
                    Text("Library", style = MaterialTheme.typography.headlineLarge)
                    Row(
                        modifier = Modifier.padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LibrarySection.entries.forEach { item ->
                            FilterChip(
                                selected = section == item,
                                onClick = { section = item },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                when (section) {
                    LibrarySection.Albums -> items(library.albums.items, key = { it.id }) { album ->
                        AlbumRow(album, artworkClient, onClick = { onAlbumClick(album) })
                    }
                    LibrarySection.Artists -> items(library.artists.items, key = { it.id }) { ArtistRow(it) }
                    LibrarySection.Tracks -> itemsIndexed(
                        items = library.tracks.items,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        TrackRow(
                            track = track,
                            artworkClient = artworkClient,
                            isCurrent = track.id == currentTrackId,
                            onClick = { onTrackClick(library.tracks.items, index) },
                        )
                    }
                }
                val hasMore = when (section) {
                    LibrarySection.Albums -> library.albums.hasMore
                    LibrarySection.Artists -> library.artists.hasMore
                    LibrarySection.Tracks -> library.tracks.hasMore
                }
                if (hasMore) {
                    item {
                        Text(
                            "Showing the first 50 items. Pagination will be added next.",
                            modifier = Modifier.padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    state: LibraryUiState,
    artworkClient: ArtworkClient,
    currentTrackId: String?,
    onSearch: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onPairAgain: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = "Search",
            modifier = Modifier.padding(top = 24.dp, bottom = 16.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) onSearch("")
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Tracks, artists, or albums") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            trailingIcon = {
                Button(onClick = { onSearch(query) }, enabled = query.isNotBlank() && !state.searchLoading) {
                    Text("Search")
                }
            },
        )
        if (state.authenticationFailed) {
            ErrorContent(
                message = "Device access was revoked. Pair this device again.",
                actionLabel = "Pair again",
                onAction = onPairAgain,
            )
        } else if (state.searchLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        } else if (state.searchError != null) {
            Text(
                text = state.searchError,
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state.searchResults.items.isEmpty()) {
            Text(
                text = if (query.isBlank()) "Enter a query to search the indexed library." else "No matching tracks.",
                modifier = Modifier.padding(top = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                itemsIndexed(
                    items = state.searchResults.items,
                    key = { _, track -> track.id },
                ) { index, track ->
                    TrackRow(
                        track = track,
                        artworkClient = artworkClient,
                        isCurrent = track.id == currentTrackId,
                        onClick = { onTrackClick(state.searchResults.items, index) },
                    )
                }
                if (state.searchResults.hasMore) {
                    item {
                        Text(
                            "More matches are available.",
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(
    album: Album,
    state: LibraryUiState,
    artworkClient: ArtworkClient,
    currentTrackId: String?,
    onPlayQueue: (List<Track>, Int) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArtworkImage(
                    artworkClient = artworkClient,
                    artworkUrl = artworkClient.urlFor(album.coverId),
                    modifier = Modifier.size(180.dp),
                )
                Text(
                    text = album.title,
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val detail = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onPlayQueue(state.albumTracks, 0) },
                    modifier = Modifier.padding(vertical = 20.dp),
                    enabled = state.albumTracks.isNotEmpty() && !state.albumLoading,
                ) {
                    Text("Play album")
                }
            }
        }
        when {
            state.albumLoading -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.albumError != null -> item {
                ErrorContent(
                    message = state.albumError,
                    actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
                    onAction = if (state.authenticationFailed) onPairAgain else onRetry,
                )
            }
            state.albumTracks.isEmpty() -> item {
                Text(
                    text = "This album has no indexed tracks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> itemsIndexed(
                items = state.albumTracks,
                key = { _, track -> track.id },
            ) { index, track ->
                TrackRow(
                    track = track,
                    artworkClient = artworkClient,
                    isCurrent = track.id == currentTrackId,
                    showPosition = true,
                    onClick = { onPlayQueue(state.albumTracks, index) },
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text("Loading library…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun ErrorScreen(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ErrorContent(message, actionLabel, onAction)
    }
}

@Composable
private fun ErrorContent(message: String, actionLabel: String, onAction: () -> Unit) {
    Text("Could not load Velin", style = MaterialTheme.typography.titleLarge)
    Text(
        text = message,
        modifier = Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) {
        Text(actionLabel)
    }
}

@Composable
private fun AlbumRow(
    album: Album,
    artworkClient: ArtworkClient,
    onClick: (() -> Unit)? = null,
) {
    ItemRow(
        title = album.title,
        artworkClient = artworkClient,
        artworkUrl = artworkClient.urlFor(album.coverId),
        subtitle = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
        detail = "${album.trackCount} tracks",
        onClick = onClick,
    )
}

@Composable
private fun ArtistRow(artist: Artist) {
    ItemRow(
        title = artist.name,
        subtitle = "${artist.albumCount} albums",
        detail = "${artist.trackCount} tracks",
    )
}

@Composable
private fun TrackRow(
    track: Track,
    artworkClient: ArtworkClient,
    isCurrent: Boolean = false,
    showPosition: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val technicalDetail = listOfNotNull(
        track.format.uppercase(),
        track.durationMs?.let(::formatDuration),
    ).joinToString(" · ")
    val position = if (showPosition && track.trackNumber != null) {
        if ((track.discNumber ?: 1) > 1) "${track.discNumber}.${track.trackNumber}. " else "${track.trackNumber}. "
    } else {
        ""
    }
    ItemRow(
        title = position + track.title,
        artworkClient = artworkClient,
        artworkUrl = artworkClient.urlFor(track.coverId),
        subtitle = listOfNotNull(track.artistName, track.albumTitle).joinToString(" · "),
        detail = if (isCurrent) "Now playing · $technicalDetail" else technicalDetail,
        onClick = onClick,
    )
}

@Composable
private fun ItemRow(
    title: String,
    subtitle: String,
    detail: String,
    artworkClient: ArtworkClient? = null,
    artworkUrl: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interactionModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artworkClient?.let {
            ArtworkImage(
                artworkClient = it,
                artworkUrl = artworkUrl,
                modifier = Modifier.size(56.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (artworkClient == null) 0.dp else 12.dp),
        ) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun moreSuffix(hasMore: Boolean): String = if (hasMore) "+" else ""
