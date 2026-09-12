package com.qbdlx.mobile.lyrics

/**
 * Parser for the LRC synchronised-lyrics format.
 *
 * LRC is a plain text file where each line carries one or more `[mm:ss.xx]`
 * stamps in front of the text, plus a handful of `[key:value]` metadata tags.
 * Sources are wildly inconsistent, so the parser is deliberately forgiving:
 * fractions are accepted as one, two or three digits, either `.` or `:` may
 * separate them from the seconds, a line may carry several stamps, and anything
 * that does not parse is treated as untimed text rather than as an error.
 *
 * Every closing bracket in the patterns below is escaped. A bare `]` happens to
 * work on both engines, but a bare `}` does not work on Android, and one
 * unescaped bracket in this file already cost a release. Keeping the rule
 * uniform means the next edit cannot reintroduce that.
 */
object Lrc {

    /** `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]` or `[mm:ss:xx]`. */
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    /** `[offset:+250]` shifts every stamp, in milliseconds. */
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)

    /** `[ar:...]`, `[ti:...]`, `[al:...]` and friends. */
    private val metaTag = Regex("""\[[a-zA-Z#]+:[^\]]*\]""")

    /**
     * Parses [raw] into timestamped lines, sorted by time.
     *
     * Returns an empty list when the text has no usable timestamps, which is how
     * callers tell a synced source from an unsynced one. Use [parsePlain] for
     * untimed text.
     */
    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()

        val offset = offsetTag.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = ArrayList<LyricLine>()

        for (rawLine in raw.split('\n')) {
            val line = rawLine.trimEnd('\r')
            val stamps = timeTag.findAll(line).toList()
            if (stamps.isEmpty()) continue

            // Text is whatever follows the last stamp. Anything before it is
            // metadata that happened to share the line.
            val text = line.substring(stamps.last().range.last + 1).trim()
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
                val fraction = stamp.groupValues[3]

                // A one or two digit fraction is hundredths, so pad rather than
                // guess: "22" is 220 ms, not 22 ms.
                val millis = when {
                    fraction.isEmpty() -> 0L
                    else -> fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                }

                // The convention LRC players use: a positive offset pulls the
                // lyrics earlier, so it is subtracted from the stamp.
                val time = (minutes * 60_000 + seconds * 1000 + millis) - offset
                lines.add(LyricLine(timeMs = time.coerceAtLeast(0L), text = text))
            }
        }

        // A stable sort keeps lines that share a stamp in file order, which is
        // what parallel translations rely on.
        return lines.sortedBy { it.timeMs }
    }

    /**
     * Strips metadata tags and returns the remaining text, for sources that
     * supply unsynchronised lyrics with LRC-style headers attached.
     */
    fun parsePlain(raw: String): String? {
        if (raw.isBlank()) return null
        val body = raw.split('\n')
            .joinToString("\n") { it.trimEnd('\r') }
            .replace(metaTag, "")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
        return body.ifBlank { null }
    }
}
