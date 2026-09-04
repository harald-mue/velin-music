package com.haraldmue.velin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.haraldmue.velin.data.ArtworkClient

/** Displays authenticated cover artwork over a stable, token-free placeholder. */
@Composable
fun ArtworkImage(
    artworkClient: ArtworkClient,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "V",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Light,
            )
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    imageLoader = artworkClient.imageLoader,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}
