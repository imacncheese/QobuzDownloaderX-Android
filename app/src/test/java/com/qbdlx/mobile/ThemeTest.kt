package com.qbdlx.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.qbdlx.mobile.ui.theme.AppShapes
import com.qbdlx.mobile.ui.theme.ThemeMode
import com.qbdlx.mobile.ui.theme.ThemePalette
import com.qbdlx.mobile.ui.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theming is mostly arithmetic over colours and radii. These tests pin the parts
 * that are easy to get subtly wrong: persistence round-trips, radius scaling, and
 * accent legibility.
 */
class ThemeTest {

    /** CornerSize.toPx needs a density; 1f keeps dp values readable in assertions. */
    private val density = Density(1f)

    private fun androidx.compose.foundation.shape.CornerSize.px(): Float =
        toPx(androidx.compose.ui.geometry.Size(100f, 100f), density)

    // ------------------------------------------------------------- persistence

    @Test
    fun `theme preset ids round trip`() {
        ThemePreset.entries.forEach { preset ->
            assertEquals(preset, ThemePreset.fromId(preset.id))
        }
        // Unknown or absent values must not crash a launch; fall back to default.
        assertEquals(ThemePreset.QOBUZ, ThemePreset.fromId(null))
        assertEquals(ThemePreset.QOBUZ, ThemePreset.fromId("nonsense"))
        assertEquals(ThemePreset.QOBUZ, ThemePreset.fromId(""))
    }

    @Test
    fun `theme mode ids round trip`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromId(mode.id))
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("nonsense"))
    }

    @Test
    fun `preset ids are unique`() {
        val ids = ThemePreset.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `mode ids are unique`() {
        val ids = ThemeMode.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    // ------------------------------------------------------------------ shapes

    @Test
    fun `corner scale is clamped to the supported range`() {
        assertEquals(AppShapes.MIN_SCALE, AppShapes.clamp(-5f))
        assertEquals(AppShapes.MAX_SCALE, AppShapes.clamp(99f))
        assertEquals(1f, AppShapes.clamp(1f))
        assertEquals(0.5f, AppShapes.clamp(0.5f))
    }

    @Test
    fun `a zero scale produces square corners everywhere`() {
        val shapes = AppShapes(AppShapes.MIN_SCALE)
        assertEquals(0f, shapes.artworkSmallRadius.value)
        assertEquals(0f, shapes.cardRadius.value)
        assertEquals(0f, shapes.fieldRadius.value)
        // Material role shapes are derived from the same scale.
        assertEquals(0f, shapes.toMaterialShapes().medium.topStart.px())
    }

    @Test
    fun `scaling up rounds more, and radii never go negative`() {
        val square = AppShapes(0f)
        val normal = AppShapes(1f)
        val pill = AppShapes(2f)

        assertTrue(
            "larger scale must round more",
            pill.cardRadius.value > normal.cardRadius.value,
        )
        assertTrue(
            "scale 1 must round more than scale 0",
            normal.cardRadius.value > square.cardRadius.value,
        )
        // Default scale must reproduce Material's usual 12dp card corner.
        assertEquals(12f, normal.cardRadius.value, 0.01f)
        // A negative scale must clamp to zero rather than produce invalid geometry.
        val negative = AppShapes(-3f)
        assertEquals(0f, negative.cardRadius.value)
    }

    @Test
    fun `material role shapes follow the scale`() {
        val square = AppShapes(0f).toMaterialShapes()
        val normal = AppShapes(1f).toMaterialShapes()

        assertEquals("scale 0 must square the medium shape", 0f, square.medium.topStart.px())
        assertEquals("scale 1 must match Material's 12dp medium", 12f, normal.medium.topStart.px())
    }

    @Test
    fun `descriptions cover the whole range without gaps`() {
        val samples = listOf(0f, 0.5f, 1f, 1.5f, 2f)
        val labels = samples.map { AppShapes.describe(it) }
        assertTrue("all labels non-empty", labels.all { it.isNotBlank() })
        assertNotEquals("extremes must read differently", labels.first(), labels.last())
    }

    // ----------------------------------------------------------------- colours

    @Test
    fun `an unknown preset still yields a complete scheme`() {
        // Guards against a preset being added without colour roles for both modes.
        ThemePreset.entries.forEach { preset ->
            listOf(true, false).forEach { dark ->
                val scheme = ThemePalette.schemeFor(preset, dark)
                assertTrue("$preset dark=$dark primary", scheme.primary.alpha > 0f)
                assertTrue("$preset dark=$dark background", scheme.background.alpha > 0f)
                assertTrue("$preset dark=$dark surfaceVariant", scheme.surfaceVariant.alpha > 0f)
            }
        }
    }

    @Test
    fun `dark and light schemes differ for every preset`() {
        ThemePreset.entries.forEach { preset ->
            val dark = ThemePalette.schemeFor(preset, dark = true)
            val light = ThemePalette.schemeFor(preset, dark = false)
            assertNotEquals(
                "$preset should not render identically in both modes",
                dark.background,
                light.background,
            )
        }
    }

    @Test
    fun `amoled uses a true black background in dark mode`() {
        val scheme = ThemePalette.schemeFor(ThemePreset.AMOLED, dark = true)
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }

    @Test
    fun `luminance spans black to white`() {
        assertEquals(0f, ThemePalette.luminanceOf(Color.Black), 0.001f)
        assertEquals(1f, ThemePalette.luminanceOf(Color.White), 0.001f)
        assertTrue(
            "green reads brighter than blue",
            ThemePalette.luminanceOf(Color.Green) > ThemePalette.luminanceOf(Color.Blue),
        )
    }

    @Test
    fun `contrast ratio matches the WCAG reference points`() {
        // Black on white is the maximum, 21:1.
        assertEquals(21f, ThemePalette.contrastRatio(Color.Black, Color.White), 0.1f)
        // A colour against itself is 1:1.
        assertEquals(1f, ThemePalette.contrastRatio(Color.Red, Color.Red), 0.01f)
        // Symmetric.
        assertEquals(
            ThemePalette.contrastRatio(Color.Black, Color.White),
            ThemePalette.contrastRatio(Color.White, Color.Black),
            0.01f,
        )
    }

    @Test
    fun `a dark accent on a dark surface is lifted to a legible contrast`() {
        val surface = Color(0xFF0E0B14)
        val result = ThemePalette.legibleAccent(Color(0xFF101010), surface)
        assertTrue(
            "must reach the contrast target",
            ThemePalette.contrastRatio(result, surface) >= 4.5f,
        )
    }

    @Test
    fun `a pale accent on a light surface is darkened to a legible contrast`() {
        val surface = Color(0xFFFDF8FF)
        val result = ThemePalette.legibleAccent(Color(0xFFF4F4F4), surface)
        assertTrue(
            "must reach the contrast target",
            ThemePalette.contrastRatio(result, surface) >= 4.5f,
        )
    }

    @Test
    fun `an already legible accent is left untouched`() {
        // The dark-mode primary already clears 4.5:1 on the dark background;
        // lightening a passing colour would wash it out for no accessibility gain.
        val darkSurface = Color(0xFF0E0B14)
        val themePrimary = Color(0xFFB9A7FF)
        assertTrue(
            "fixture must actually pass the target",
            ThemePalette.contrastRatio(themePrimary, darkSurface) >= 4.5f,
        )
        assertEquals(themePrimary, ThemePalette.legibleAccent(themePrimary, darkSurface))

        val lightSurface = Color(0xFFFDF8FF)
        val darkAccent = Color(0xFF3A1FC4)
        assertTrue(
            "fixture must actually pass the target",
            ThemePalette.contrastRatio(darkAccent, lightSurface) >= 4.5f,
        )
        assertEquals(darkAccent, ThemePalette.legibleAccent(darkAccent, lightSurface))
    }

    @Test
    fun `a brand accent that fails the text target is lifted`() {
        // #6C4CF1 on the dark background is only ~3.7:1 - fine for large UI shapes
        // but short of the 4.5:1 text target, so it must be adjusted.
        val darkSurface = Color(0xFF0E0B14)
        val brandPurple = Color(0xFF6C4CF1)
        assertTrue(
            "fixture should be below target to be meaningful",
            ThemePalette.contrastRatio(brandPurple, darkSurface) < 4.5f,
        )
        val adjusted = ThemePalette.legibleAccent(brandPurple, darkSurface)
        assertTrue(
            "adjusted colour must reach the target",
            ThemePalette.contrastRatio(adjusted, darkSurface) >= 4.5f,
        )
    }

    @Test
    fun `the dark-mode overload targets the dark background`() {
        val lifted = ThemePalette.legibleAccent(Color(0xFF101010), dark = true)
        assertTrue(
            "result must be legible on the dark background",
            ThemePalette.contrastRatio(lifted, Color(0xFF0E0B14)) >= 4.5f,
        )
    }

    @Test
    fun `an artwork tint overrides the preset accent`() {
        val tint = Color(0xFFE91E63)
        val scheme = ThemePalette.schemeFor(ThemePreset.QOBUZ, dark = true, tint = tint)
        assertNotEquals(
            "the tint must replace the preset primary",
            ThemePalette.schemeFor(ThemePreset.QOBUZ, dark = true).primary,
            scheme.primary,
        )
    }
}

