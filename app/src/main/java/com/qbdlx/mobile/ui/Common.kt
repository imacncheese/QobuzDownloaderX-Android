package com.qbdlx.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qbdlx.mobile.download.RenameTemplates

/**
 * Shared bits of presentation logic.
 */

/** Qobuz artwork URLs are always square CDN links ending in `_600.jpg`. */
fun artworkUrl(raw: String?, size: Int = 600): String? {
    if (raw.isNullOrBlank()) return null
    return raw.replace(Regex("""_\d+\.jpg$"""), "_$size.jpg")
}

fun formatDuration(seconds: Int?): String {
    if (seconds == null || seconds <= 0) return "--:--"
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

fun formatBitDepthRate(bitDepth: Int?, sampleRate: Double?): String? {
    if (bitDepth == null && sampleRate == null) return null
    val d = bitDepth?.let { "$it-bit" } ?: ""
    // RenameTemplates owns the kHz conversion; the album screen used to divide by
    // 1000 on its own, which is how a 96 kHz release displayed as "0.1 kHz".
    val r = sampleRate?.let { RenameTemplates.fmtRate(it).replace("kHz", " kHz") } ?: ""
    return listOf(d, r).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null }
}

@Composable
fun ScreenScaffold(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
