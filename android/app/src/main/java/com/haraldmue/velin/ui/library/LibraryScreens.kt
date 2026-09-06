package com.haraldmue.velin.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.haraldmue.velin.data.AccumulatedPage
import com.haraldmue.velin.data.Album
import com.haraldmue.velin.data.ArtworkClient
import com.haraldmue.velin.data.Artist
import com.haraldmue.velin.data.Track
import com.haraldmue.velin.ui.ArtworkImage
import com.haraldmue.velin.ui.layout.albumGridColumns
import com.haraldmue.velin.ui.layout.isLandscape
import com.haraldmue.velin.ui.layout.usesSplitDetail
import com.haraldmue.velin.ui.layout.velinWidthClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) {
    Albums("Albums"),
    Artists("Artists"),
    Tracks("Tracks"),
    Search("Search"),
}

@Stable
class LibraryScrollStates internal constructor(
    val albums: LazyListState,
    val artists: LazyListState,
    val tracks: LazyListState,
    val search: LazyListState,
)

@Composable
fun rememberLibraryScrollStates(): LibraryScrollStates {
    val albums = rememberLazyListState()
    val artists = rememberLazyListState()
    val tracks = rememberLazyListState()
    val search = rememberLazyListState()
    return remember(albums, artists, tracks, search) {
        LibraryScrollStates(albums, artists, tracks, search)
    }
}

@Composable
fun HomeScreen(
    state: LibraryUiState,
    artworkClient: ArtworkClient,
    onAlbumClick: (Album) -> Unit,
    onOpenLibrarySection: (LibraryTab) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    when {
        state.loading && state.summary == null -> LoadingScreen()
        state.error != null && !state.hasActiveSnapshot -> ErrorScreen(
            message = state.error,
            actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
            onAction = if (state.authenticationFailed) onPairAgain else onRetry,
        )
        else -> {
            val summary = state.summary ?: return
            val preferredColumns = albumGridColumns(velinWidthClass())
            val recentlyAdded = state.recentlyAddedAlbums.take(preferredColumns * 2)
            val recentIDs = recentlyAdded.mapTo(mutableSetOf(), Album::id)
            val discovery = state.discoveryAlbums
                .asSequence()
                .filterNot { it.id in recentIDs }
                .take(preferredColumns * 2)
                .toList()
            val visibleAlbumCount = maxOf(recentlyAdded.size, discovery.size)
            val columns = minOf(preferredColumns, maxOf(1, visibleAlbumCount))
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LibraryOverview(
                        artists = summary.artistCount.toString(),
                        albums = summary.albumCount.toString(),
                        tracks = summary.trackCount.toString(),
                        onArtists = { onOpenLibrarySection(LibraryTab.Artists) },
                        onAlbums = { onOpenLibrarySection(LibraryTab.Albums) },
                        onTracks = { onOpenLibrarySection(LibraryTab.Tracks) },
                    )
                }
                if (recentlyAdded.isNotEmpty()) {
                    item(key = "recent-header", span = { GridItemSpan(maxLineSpan) }) {
                        HomeAlbumSectionHeader(
                            title = "Recently added",
                            onViewAll = { onOpenLibrarySection(LibraryTab.Albums) },
                        )
                    }
                    items(recentlyAdded, key = { "recent-${it.id}" }) { album ->
                        AlbumGridItem(album, artworkClient, onClick = { onAlbumClick(album) })
                    }
                }
                if (discovery.isNotEmpty()) {
                    item(key = "discover-header", span = { GridItemSpan(maxLineSpan) }) {
                        HomeAlbumSectionHeader(
                            title = "Discover",
                            onViewAll = { onOpenLibrarySection(LibraryTab.Albums) },
                        )
                    }
                    items(discovery, key = { "discover-${it.id}" }) { album ->
                        AlbumGridItem(album, artworkClient, onClick = { onAlbumClick(album) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeAlbumSectionHeader(title: String, onViewAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onViewAll) {
            Text("All albums")
        }
    }
}

@Composable
private fun LibraryOverview(
    artists: String,
    albums: String,
    tracks: String,
    onArtists: () -> Unit,
    onAlbums: () -> Unit,
    onTracks: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryMetric(value = artists, label = "Artists", modifier = Modifier.weight(1f), onClick = onArtists)
        LibraryMetricDivider()
        LibraryMetric(value = albums, label = "Albums", modifier = Modifier.weight(1f), onClick = onAlbums)
        LibraryMetricDivider()
        LibraryMetric(value = tracks, label = "Tracks", modifier = Modifier.weight(1f), onClick = onTracks)
    }
}

@Composable
private fun LibraryMetricDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(36.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun LibraryMetric(
    value: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlbumGridItem(album: Album, artworkClient: ArtworkClient, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        ArtworkImage(
            artworkClient = artworkClient,
            artworkUrl = artworkClient.urlFor(album.coverId),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Text(
            text = album.title,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    albums: Flow<PagingData<Album>>,
    artists: Flow<PagingData<Artist>>,
    tracks: Flow<PagingData<Track>>,
    scrollStates: LibraryScrollStates,
    artworkClient: ArtworkClient,
    currentTrackId: String?,
    section: LibraryTab,
    onSectionChange: (LibraryTab) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onTrackDetail: (String) -> Unit,
    onSearch: (String) -> Unit,
    onLoadMoreSearch: () -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    val albumItems = albums.collectAsLazyPagingItems()
    val artistItems = artists.collectAsLazyPagingItems()
    val trackItems = tracks.collectAsLazyPagingItems()
    when {
        state.loading && state.summary == null -> LoadingScreen()
        state.error != null && !state.hasActiveSnapshot -> ErrorScreen(
            message = state.error,
            actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
            onAction = if (state.authenticationFailed) onPairAgain else onRetry,
        )
        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LibraryTab.entries.forEach { item ->
                        FilterChip(
                            selected = section == item,
                            onClick = { onSectionChange(item) },
                            label = { Text(item.label) },
                        )
                    }
                }
                when (section) {
                    LibraryTab.Albums -> PagingCatalog(
                        pagingItems = albumItems,
                        listState = scrollStates.albums,
                        emptyMessage = "No indexed albums.",
                        key = Album::id,
                    ) { _, album ->
                        AlbumRow(album, artworkClient, onClick = { onAlbumClick(album) })
                    }
                    LibraryTab.Artists -> PagingCatalog(
                        pagingItems = artistItems,
                        listState = scrollStates.artists,
                        emptyMessage = "No indexed artists.",
                        key = Artist::id,
                    ) { _, artist ->
                        ArtistRow(artist, onClick = { onArtistClick(artist) })
                    }
                    LibraryTab.Tracks -> PagingCatalog(
                        pagingItems = trackItems,
                        listState = scrollStates.tracks,
                        emptyMessage = "No indexed tracks.",
                        key = Track::id,
                    ) { _, track ->
                        TrackRow(
                            track = track,
                            artworkClient = artworkClient,
                            isCurrent = track.id == currentTrackId,
                            onClick = {
                                val queue = trackItems.itemSnapshotList.items
                                val startIndex = queue.indexOfFirst { it.id == track.id }
                                if (startIndex >= 0) onTrackClick(queue, startIndex)
                            },
                            onDetailClick = { onTrackDetail(track.id) },
                        )
                    }
                    LibraryTab.Search -> SearchPane(
                        state = state,
                        listState = scrollStates.search,
                        artworkClient = artworkClient,
                        currentTrackId = currentTrackId,
                        onSearch = onSearch,
                        onTrackClick = onTrackClick,
                        onTrackDetail = onTrackDetail,
                        onLoadMoreSearch = onLoadMoreSearch,
                        onPairAgain = onPairAgain,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T : Any> PagingCatalog(
    pagingItems: LazyPagingItems<T>,
    listState: LazyListState,
    emptyMessage: String,
    key: (T) -> Any,
    row: @Composable (Int, T) -> Unit,
) {
    val refresh = pagingItems.loadState.refresh
    when {
        refresh is LoadState.Loading && pagingItems.itemCount == 0 -> LoadingScreen()
        refresh is LoadState.Error && pagingItems.itemCount == 0 -> ErrorScreen(
            message = refresh.error.message ?: "Could not load cached items.",
            actionLabel = "Retry",
            onAction = pagingItems::retry,
        )
        refresh is LoadState.NotLoading && pagingItems.itemCount == 0 -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(
                count = pagingItems.itemCount,
                key = pagingItems.itemKey(key),
            ) { index ->
                pagingItems[index]?.let { item -> row(index, item) }
            }
            when (val append = pagingItems.loadState.append) {
                is LoadState.Loading -> item(key = "paging-loading") {
                    CenteredProgress()
                }
                is LoadState.Error -> item(key = "paging-error") {
                    ErrorContent(
                        message = append.error.message ?: "Could not load more cached items.",
                        actionLabel = "Retry",
                        onAction = pagingItems::retry,
                    )
                }
                is LoadState.NotLoading -> Unit
            }
        }
    }
}

@Composable
private fun SearchPane(
    state: LibraryUiState,
    listState: LazyListState,
    artworkClient: ArtworkClient,
    currentTrackId: String?,
    onSearch: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onTrackDetail: (String) -> Unit,
    onLoadMoreSearch: () -> Unit,
    onPairAgain: () -> Unit,
) {
    var query by remember(state.searchQuery) { mutableStateOf(state.searchQuery) }
    val coroutineScope = rememberCoroutineScope()
    val submitSearch = {
        if (query.trim() != state.searchQuery) {
            coroutineScope.launch { listState.scrollToItem(0) }
        }
        onSearch(query)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) {
                    if (state.searchQuery.isNotEmpty()) {
                        coroutineScope.launch { listState.scrollToItem(0) }
                    }
                    onSearch("")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Tracks, artists, or albums") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
            trailingIcon = {
                IconButton(
                    onClick = { submitSearch() },
                    enabled = query.isNotBlank() && !state.searchLoading,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Search")
                }
            },
        )
        when {
            state.authenticationFailed -> ErrorContent(
                message = "Device access was revoked. Pair this device again.",
                actionLabel = "Pair again",
                onAction = onPairAgain,
            )
            state.searchLoading -> CenteredProgress()
            state.searchError != null -> Text(
                text = state.searchError,
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.error,
            )
            state.searchResults.items.isEmpty() -> Text(
                text = if (query.isBlank()) "Search the indexed library." else "No matching tracks.",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                itemsIndexed(state.searchResults.items, key = { _, track -> track.id }) { index, track ->
                    PaginationPrefetchEffect(index, state.searchResults, onLoadMoreSearch)
                    TrackRow(
                        track = track,
                        artworkClient = artworkClient,
                        isCurrent = track.id == currentTrackId,
                        onClick = { onTrackClick(state.searchResults.items, index) },
                        onDetailClick = { onTrackDetail(track.id) },
                    )
                }
                loadMoreItem(state.searchResults, onLoadMoreSearch)
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
    onEnqueueQueue: (List<Track>) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    val split = usesSplitDetail(velinWidthClass(), isLandscape())
    val header: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (split) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Text(artist.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${artist.albumCount} albums · ${artist.trackCount} tracks",
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlayAndEnqueueActions(
                playLabel = "Play artist",
                enabled = state.artistTracks.isNotEmpty() && !state.artistLoading,
                onPlay = { onPlayQueue(state.artistTracks, 0) },
                onEnqueue = { onEnqueueQueue(state.artistTracks) },
            )
        }
    }
    val tracks: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        when {
            state.artistLoading -> item { CenteredProgress() }
            state.artistError != null -> item {
                ErrorContent(
                    message = state.artistError,
                    actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
                    onAction = if (state.authenticationFailed) onPairAgain else onRetry,
                )
            }
            state.artistTracks.isEmpty() -> item {
                Text("This artist has no indexed tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> itemsIndexed(state.artistTracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    artworkClient = artworkClient,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onPlayQueue(state.artistTracks, index) },
                )
            }
        }
    }
    if (split) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) { header() }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                content = tracks,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
            item { header() }
            tracks()
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
            val split = usesSplitDetail(velinWidthClass(), isLandscape())
            val header: @Composable () -> Unit = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (split) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    ArtworkImage(
                        artworkClient = artworkClient,
                        artworkUrl = artworkClient.urlFor(track.coverId, size = 512),
                        modifier = Modifier.size(if (split) 160.dp else 180.dp),
                    )
                    Text(
                        text = track.title,
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = if (split) TextAlign.Start else TextAlign.Center,
                    )
                    track.artistName?.let { artistName ->
                        Text(
                            text = artistName,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable(enabled = track.artistId != null) {
                                    track.artistId?.let { artistId ->
                                        onOpenArtist(Artist(id = artistId, name = artistName, albumCount = 0, trackCount = 0))
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onPlay(track.toTrack()) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (track.id == currentTrackId) "Play again" else "Play")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onPlayNext(track.toTrack()) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Play next")
                            }
                            OutlinedButton(
                                onClick = { onAddToQueue(track.toTrack()) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Add to queue")
                            }
                        }
                    }
                }
            }
            val metadata: @Composable () -> Unit = {
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
            if (split) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) { header() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) { metadata() }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
                    item { header() }
                    item { metadata() }
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
    onEnqueueQueue: (List<Track>) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
) {
    val split = usesSplitDetail(velinWidthClass(), isLandscape())
    val header: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (split) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            ArtworkImage(
                artworkClient = artworkClient,
                artworkUrl = artworkClient.urlFor(album.coverId, size = 512),
                modifier = Modifier
                    .size(if (split) 132.dp else 200.dp)
                    .align(if (split) Alignment.Start else Alignment.CenterHorizontally),
            )
            Text(
                text = album.title,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (split) TextAlign.Start else TextAlign.Center,
            )
            val detail = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (split) TextAlign.Start else TextAlign.Center,
                )
            }
            PlayAndEnqueueActions(
                playLabel = "Play album",
                enabled = state.albumTracks.isNotEmpty() && !state.albumLoading,
                onPlay = { onPlayQueue(state.albumTracks, 0) },
                onEnqueue = { onEnqueueQueue(state.albumTracks) },
            )
        }
    }
    val tracks: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        when {
            state.albumLoading -> item { CenteredProgress() }
            state.albumError != null -> item {
                ErrorContent(
                    message = state.albumError,
                    actionLabel = if (state.authenticationFailed) "Pair again" else "Retry",
                    onAction = if (state.authenticationFailed) onPairAgain else onRetry,
                )
            }
            state.albumTracks.isEmpty() -> item {
                Text("This album has no indexed tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> itemsIndexed(state.albumTracks, key = { _, track -> track.id }) { index, track ->
                AlbumTrackRow(
                    track = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onPlayQueue(state.albumTracks, index) },
                )
            }
        }
    }
    if (split) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) { header() }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                content = tracks,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            item { header() }
            tracks()
        }
    }
}

@Composable
private fun AlbumTrackRow(track: Track, isCurrent: Boolean, onClick: () -> Unit) {
    val number = when {
        track.trackNumber == null -> "·"
        (track.discNumber ?: 1) > 1 -> "${track.discNumber}.${track.trackNumber}"
        else -> "%02d".format(track.trackNumber)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number,
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = listOfNotNull(track.artistName, track.format.uppercase()).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        track.durationMs?.let { duration ->
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 48.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun PlayAndEnqueueActions(
    playLabel: String,
    enabled: Boolean,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlay, enabled = enabled) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(playLabel)
        }
        TextButton(onClick = onEnqueue, enabled = enabled) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add to queue")
        }
    }
}

@Composable
private fun CenteredProgress() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DetailSection(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PaginationPrefetchEffect(
    index: Int,
    page: AccumulatedPage<*>,
    onLoadMore: () -> Unit,
) {
    val threshold = (page.items.size - 12).coerceAtLeast(0)
    if (index >= threshold && page.hasMore && !page.loadingMore && page.loadMoreError == null) {
        LaunchedEffect(page.nextCursor) { onLoadMore() }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.loadMoreItem(
    page: AccumulatedPage<*>,
    onLoadMore: () -> Unit,
) {
    if (page.hasMore || page.loadingMore) {
        item(key = "load-more") {
            if (!page.loadingMore && page.loadMoreError == null) {
                LaunchedEffect(page.nextCursor) { onLoadMore() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (page.loadMoreError != null) {
                    Button(onClick = onLoadMore) {
                        Text("Retry")
                    }
                } else {
                    CircularProgressIndicator()
                }
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
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = artist.name.firstOrNull()?.uppercase() ?: "V",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onClick: (() -> Unit)? = null,
    onDetailClick: (() -> Unit)? = null,
) {
    val technicalDetail = listOfNotNull(
        track.format.uppercase(),
        track.durationMs?.let(::formatDuration),
    ).joinToString(" · ")
    ItemRow(
        title = track.title,
        artworkClient = artworkClient,
        artworkUrl = artworkClient.urlFor(track.coverId),
        subtitle = listOfNotNull(track.artistName, track.albumTitle).joinToString(" · "),
        detail = if (isCurrent) "Now playing · $technicalDetail" else technicalDetail,
        emphasize = isCurrent,
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
    emphasize: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(interactionModifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        artworkClient?.let {
            ArtworkImage(
                artworkClient = it,
                artworkUrl = artworkUrl,
                modifier = Modifier.size(52.dp),
            )
        }
        val hasLeading = leading != null || artworkClient != null
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (hasLeading) 12.dp else 0.dp),
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
        modifier = Modifier.padding(start = if (artworkClient != null || leading != null) 64.dp else 0.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
