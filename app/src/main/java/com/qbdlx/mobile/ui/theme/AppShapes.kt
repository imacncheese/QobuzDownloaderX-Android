package com.qbdlx.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Global corner rounding.
 *
 * Material 3's default [Shapes] are not enough on their own because much of this
 * app draws its own rounded surfaces (cover art, list rows, chips). [LocalShapes]
 * exposes the scaled values so those call sites follow the same setting instead of
 * hardcoding a radius.
 */
data class AppShapes(
    /** 0f = square, 1f = Material default, 2f = pill-like. */
    val scale: Float,
) {
    private fun r(base: Dp): Dp = (base.value * scale).dp.coerceAtLeast(0.dp)

    /** Base radii before scaling, exposed so callers can reason about them. */
    val artworkSmallRadius: Dp get() = r(8.dp)
    val artworkMediumRadius: Dp get() = r(12.dp)
    val artworkLargeRadius: Dp get() = r(20.dp)
    val cardRadius: Dp get() = r(12.dp)
    val fieldRadius: Dp get() = r(14.dp)
    val barRadius: Dp get() = r(4.dp)

    /** Cover art thumbnails and larger artwork. */
    val artworkSmall: RoundedCornerShape get() = RoundedCornerShape(artworkSmallRadius)
    val artworkMedium: RoundedCornerShape get() = RoundedCornerShape(artworkMediumRadius)
    val artworkLarge: RoundedCornerShape get() = RoundedCornerShape(artworkLargeRadius)

    /** Cards, list rows and grouped settings sections. */
    val card: RoundedCornerShape get() = RoundedCornerShape(cardRadius)
    val cardLarge: RoundedCornerShape get() = RoundedCornerShape(r(20.dp))

    /** Text fields and chips. */
    val field: RoundedCornerShape get() = RoundedCornerShape(fieldRadius)
    val chip: RoundedCornerShape get() = RoundedCornerShape(r(8.dp))

    /** Full progress / seek bars. */
    val bar: RoundedCornerShape get() = RoundedCornerShape(barRadius)

    /** Material role shapes, scaled to match. */
    fun toMaterialShapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(r(4.dp)),
        small = RoundedCornerShape(r(8.dp)),
        medium = RoundedCornerShape(r(12.dp)),
        large = RoundedCornerShape(r(16.dp)),
        extraLarge = RoundedCornerShape(r(28.dp)),
    )

    companion object {
        const val MIN_SCALE = 0f
        const val MAX_SCALE = 2f
        const val DEFAULT_SCALE = 1f

        fun clamp(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

        /** A short human label for the current setting. */
        fun describe(scale: Float): String = when {
            scale <= 0.1f -> "Square"
            scale < 0.75f -> "Slightly rounded"
            scale < 1.25f -> "Rounded"
            scale < 1.75f -> "Very rounded"
            else -> "Pill"
        }
    }
}

val LocalShapes = staticCompositionLocalOf { AppShapes(AppShapes.DEFAULT_SCALE) }
