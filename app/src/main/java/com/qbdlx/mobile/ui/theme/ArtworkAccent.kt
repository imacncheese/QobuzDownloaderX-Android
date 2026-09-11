package com.qbdlx.mobile.ui.theme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a dominant accent colour from album artwork.
 *
 * Uses AndroidX Palette rather than averaging pixels: an average of a colourful
 * cover tends toward muddy grey, whereas Palette deliberately surfaces a muted
 * *vibrant* swatch, which is what looks intentional as a theme accent.
 *
 * Resolves to null until a usable swatch is found, so callers fall back to their
 * preset rather than tinting with an arbitrary colour.
 */
@Composable
fun rememberArtworkAccent(
    artworkUrl: String?,
    enabled: Boolean,
): State<Color?> {
    val context = LocalContext.current
    val state = remember(artworkUrl, enabled) { mutableStateOf<Color?>(null) }

    LaunchedEffect(artworkUrl, enabled) {
        state.value = null
        if (!enabled || artworkUrl.isNullOrBlank()) return@LaunchedEffect

        state.value = withContext(Dispatchers.IO) {
            runCatching {
                // Coil's memory cache is already warm for on-screen artwork, so
                // this usually costs no extra network request.
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(false)
                    .size(160)
                    .build()

                val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
                val bitmap = (drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching null

                val palette = Palette.from(bitmap).clearFilters().generate()
                val swatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.mutedSwatch
                    ?: palette.dominantSwatch
                    ?: return@runCatching null

                Color(swatch.rgb)
            }.getOrNull()
        }
    }

    return state
}
