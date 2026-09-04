package com.haraldmue.velin.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.haraldmue.velin.data.AccumulatedPage
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
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Text("Home", style = MaterialTheme.typography.headlineLarge)
                    Surface(
                        modifier = Modifier.padding(top = 14.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(7.dp),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primary,
                            ) {}
                            Text(
                                text = "Connected · ${credentials.serverName} ${state.status?.version ?: credentials.serverVersion}",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        text = credentials.serverUrl,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text("Your library", style = MaterialTheme.typography.titleLarge)
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LibraryStat(
                            value = "${library.artists.items.size}${moreSuffix(library.artists.hasMore)}",
                            label = "Artists",
                            modifier = Modifier.weight(1f),
                        )
                        LibraryStat(
                            value = "${library.albums.items.size}${moreSuffix(library.albums.hasMore)}",
                            label = "Albums",
                            modifier = Modifier.weight(1f),
                        )
                        LibraryStat(
                            value = "${library.tracks.items.size}${moreSuffix(library.tracks.hasMore)}",
                            label = "Tracks",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (library.albums.items.isNotEmpty()) {
                    item {
                        Text(
                            text = "Albums",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    items(library.albums.items.take(8), key = { it.id }) { album ->
                        AlbumRow(album, artworkClient, onClick = { onAlbumClick(album) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(
                text = label,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class LibraryTab(val label: String) {
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
    onArtistClick: (Artist) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onTrackDetail: (String) -> Unit,
    onLoadMore: (LibrarySection) -> Unit,
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
            var section by remember { mutableStateOf(LibraryTab.Albums) }
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
                        LibraryTab.entries.forEach { item ->
                            FilterChip(
                                selected = section == item,
                                onClick = { section = item },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                when (section) {
                    LibraryTab.Albums -> {
                        items(library.albums.items, key = { it.id }) { album ->
                            AlbumRow(album, artworkClient, onClick = { onAlbumClick(album) })
                        }
                        loadMoreItem(
                            page = library.albums,
                            onLoadMore = { onLoadMore(LibrarySection.Albums) },
                        )
                    }
                    LibraryTab.Artists -> {
                        items(library.artists.items, key = { it.id }) { artist ->
                            ArtistRow(artist, onClick = { onArtistClick(artist) })
                        }
                        loadMoreItem(
                            page = library.artists,
                            onLoadMore = { onLoadMore(LibrarySection.Artists) },
                        )
                    }
                    LibraryTab.Tracks -> {
                        itemsIndexed(
                            items = library.tracks.items,
                            key = { _, track -> track.id },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                artworkClient = artworkClient,
                                isCurrent = track.id == currentTrackId,
                                onClick = { onTrackClick(library.tracks.items, index) },
                                onDetailClick = { onTrackDetail(track.id) },
                            )
                        }
                        loadMoreItem(
                            page = library.tracks,
                            onLoadMore = { onLoadMore(LibrarySection.Tracks) },
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
    onTrackDetail: (String) -> Unit,
    onLoadMoreSearch: () -> Unit,
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
            placeholder = { Text("Tracks, artists, or albums") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null)
            },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            trailingIcon = {
                IconButton(
                    onClick = { onSearch(query) },
                    enabled = query.isNotBlank() && !state.searchLoading,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Search")
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
                        onDetailClick = { onTrackDetail(track.id) },
                    )
                }
                loadMoreItem(
                    page = state.searchResults,
                    onLoadMore = onLoadMoreSearch,
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    artist: Artist,
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onPlayQueue(state.artistTracks, 0) },
                    modifier = Modifier.padding(vertical = 20.dp),
                    enabled = state.artistTracks.isNotEmpty() && !state.artistLoading,
                ) {
                    Text("Play artist")
                }
            }
        }
        when {
            state.artistLoading -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.artistError != null -> item {
                ErrorContent(
                    message = state.artistError,
                    actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
                    onAction = if (state.authenticationFailed) onPairAgain else onRetry,
                )
            }
            state.artistTracks.isEmpty() -> item {
                Text(
                    text = "This artist has no indexed tracks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> itemsIndexed(
                items = state.artistTracks,
                key = { _, track -> track.id },
            ) { index, track ->
                TrackRow(
                    track = track,
                    artworkClient = artworkClient,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onPlayQueue(state.artistTracks, index) },
                )
            }
        }
    }
}

@Composable
fun TrackDetailScreen(
    state: LibraryUiState,
    artworkClient: ArtworkClient,
    currentTrackId: String?,
    onPlay: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    when {
        state.trackLoading -> LoadingScreen()
        state.trackError != null -> ErrorScreen(
            message = state.trackError,
            actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
            onAction = if (state.authenticationFailed) onPairAgain else onRetry,
        )
        else -> {
            val track = state.trackDetail ?: return
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        ArtworkImage(
                            artworkClient = artworkClient,
                            artworkUrl = artworkClient.urlFor(track.coverId),
                            modifier = Modifier.size(180.dp),
                        )
                        Text(
                            text = track.title,
                            modifier = Modifier.padding(top = 20.dp),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        track.artistName?.let { artistName ->
                            Text(
                                text = artistName,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable(enabled = track.artistId != null) {
                                        track.artistId?.let { artistId ->
                                            onOpenArtist(
                                                Artist(
                                                    id = artistId,
                                                    name = artistName,
                                                    albumCount = 0,
                                                    trackCount = 0,
                                                ),
                                            )
                                        }
                                    },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        track.albumTitle?.let { albumTitle ->
                            Text(
                                text = albumTitle,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable(enabled = track.albumId != null) {
                                        track.albumId?.let { albumId ->
                                            onOpenAlbum(
                                                Album(
                                                    id = albumId,
                                                    title = albumTitle,
                                                    artistName = track.albumArtistName ?: track.artistName,
                                                    year = null,
                                                    coverId = track.coverId,
                                                    trackCount = track.totalTracks ?: 0,
                                                ),
                                            )
                                        }
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Button(onClick = { onPlay(track.toTrack()) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                                Text(if (track.id == currentTrackId) "Again" else "Play")
                            }
                            TextButton(onClick = { onPlayNext(track.toTrack()) }) {
                                Text("Play next")
                            }
                            IconButton(onClick = { onAddToQueue(track.toTrack()) }) {
                                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Add to queue")
                            }
                        }
                    }
                }
                item {
                    DetailSection("Format", track.format.uppercase())
                    track.durationMs?.let { DetailSection("Duration", formatDuration(it)) }
                    track.genre?.takeIf(String::isNotEmpty)?.let { DetailSection("Genre", it) }
                    track.dateText?.takeIf(String::isNotEmpty)?.let { DetailSection("Date", it) }
                    val position = listOfNotNull(
                        track.discNumber?.let { "Disc $it" },
                        track.trackNumber?.let { number ->
                            track.totalTracks?.let { total -> "Track $number of $total" } ?: "Track $number"
                        },
                    ).joinToString(" · ")
                    if (position.isNotEmpty()) DetailSection("Position", position)
                    val technical = listOfNotNull(
                        track.sampleRate?.let { "$it Hz" },
                        track.bitsPerSample?.let { "$it-bit" },
                        track.channels?.let { channels ->
                            when (channels) {
                                1 -> "Mono"
                                2 -> "Stereo"
                                else -> "$channels channels"
                            }
                        },
                    ).joinToString(" · ")
                    if (technical.isNotEmpty()) DetailSection("Technical", technical)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.loadMoreItem(
    page: AccumulatedPage<*>,
    onLoadMore: () -> Unit,
) {
    if (page.hasMore || page.loadingMore) {
        item(key = "load-more") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (page.loadingMore) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = onLoadMore) {
                        Text("Load more")
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
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
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
private fun ArtistRow(artist: Artist, onClick: (() -> Unit)? = null) {
    ItemRow(
        title = artist.name,
        subtitle = "${artist.albumCount} albums",
        detail = "${artist.trackCount} tracks",
        leading = {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = artist.name.firstOrNull()?.uppercase() ?: "V",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun TrackRow(
    track: Track,
    artworkClient: ArtworkClient,
    isCurrent: Boolean = false,
    showPosition: Boolean = false,
    onClick: (() -> Unit)? = null,
    onDetailClick: (() -> Unit)? = null,
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
        trailing = onDetailClick?.let { detailClick ->
            {
                IconButton(onClick = detailClick) {
                    Icon(Icons.Rounded.Info, contentDescription = "Track information")
                }
            }
        },
    )
}

@Composable
private fun ItemRow(
    title: String,
    subtitle: String,
    detail: String,
    artworkClient: ArtworkClient? = null,
    artworkUrl: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .then(interactionModifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        artworkClient?.let {
            ArtworkImage(
                artworkClient = it,
                artworkUrl = artworkUrl,
                modifier = Modifier.size(58.dp),
            )
        }
        val hasLeading = leading != null || artworkClient != null
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (hasLeading) 13.dp else 0.dp),
        ) {
            Text(
                text = title,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing.invoke()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = if (artworkClient != null || leading != null) 71.dp else 0.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun moreSuffix(hasMore: Boolean): String = if (hasMore) "+" else ""
