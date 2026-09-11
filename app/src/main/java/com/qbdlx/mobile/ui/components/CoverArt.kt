package com.qbdlx.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.qbdlx.mobile.ui.theme.LocalShapes

/**
 * Album artwork thumbnail.
 *
 * The corner radius comes from [LocalShapes] so the app-wide "corner rounding"
 * setting applies here too, rather than being hardcoded per call site.
 */
@Composable
fun CoverArt(
    url: String?,
    size: Dp,
    fallback: ImageVector = Icons.Filled.Album,
    shape: RoundedCornerShape = LocalShapes.current.artworkSmall,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = fallback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Larger artwork for detail screens, with a soft shadow so it lifts off the
 * blurred header behind it.
 */
@Composable
fun AlbumArtwork(
    url: String?,
    size: Dp,
    shape: RoundedCornerShape = LocalShapes.current.artworkLarge,
    prominent: Boolean = false,
) {
    CoverArt(url = url, size = size, shape = shape, fallback = Icons.Filled.Album)
}
