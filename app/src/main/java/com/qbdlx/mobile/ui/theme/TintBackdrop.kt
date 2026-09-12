package com.qbdlx.mobile.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Full-screen tinted backdrop drawn behind the whole UI.
 *
 * The now-playing artwork is scaled up and blurred, then washed with a gradient in
 * the theme's own background colour. The wash matters for two reasons: it keeps
 * text readable over an arbitrary cover, and it lets the background role stay
 * translucent so the artwork shows through everywhere.
 *
 * Nothing is drawn when [alpha] is zero, so the non-glassy path costs nothing.
 */
@Composable
fun TintBackdrop(
    artworkUrl: String?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    if (alpha <= 0.01f || artworkUrl.isNullOrBlank()) return

    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary

    Crossfade(
        // Crossfade on the URL so switching tracks eases the colour over instead
        // of snapping.
        targetState = artworkUrl,
        animationSpec = tween(700),
        label = "tint-backdrop",
    ) { url ->
        Box(modifier = modifier.fillMaxSize()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    // A large blur hides the artwork's detail but keeps its colour,
                    // which is what makes it usable as a background.
                    .blur(64.dp),
            )

            // Vertical wash: denser at the top and bottom so bars and titles stay
            // legible, letting more artwork through in the middle.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to background.copy(alpha = alpha),
                            0.45f to background.copy(alpha = alpha * 0.82f),
                            1f to background.copy(alpha = alpha),
                        )
                    ),
            )

            // A faint accent glow so the tint reads as intentional rather than as a
            // washed-out photo.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                primary.copy(alpha = 0.16f),
                                Color.Transparent,
                            )
                        )
                    ),
            )
        }
    }
}
