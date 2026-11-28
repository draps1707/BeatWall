package com.beatwall.lyrics

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val isInterlude: Boolean = false
)

object LrcParser {
    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val META_TAG_REGEX = Regex("""^\[(?:ti|ar|al|au|length|by|offset|re|ve):.*]$""", RegexOption.IGNORE_CASE)
    private val SYMBOL_ONLY_REGEX = Regex("""^[\s♪♫•·.\-_*~]+$""")
    private const val INTRO_GAP_MS = 3500L
    private const val INTERLUDE_GAP_MS = 10000L

    fun parse(rawLrc: String?): List<LyricLine> {
        if (rawLrc.isNullOrBlank()) return emptyList()

        val parsed = mutableListOf<LyricLine>()
        for (rawLine in rawLrc.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || META_TAG_REGEX.matches(trimmed)) continue

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val text = TIMESTAMP_REGEX.replace(trimmed, "").trim()
            val isBlankOrSymbol = text.isEmpty() || SYMBOL_ONLY_REGEX.matches(text)

            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracStr = m.groupValues[3]
                val ms = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100L
                    2 -> fracStr.toLong() * 10L
                    else -> fracStr.substring(0, 3).toLong()
                }
                val timeMs = min * 60_000L + sec * 1_000L + ms
                parsed.add(
                    LyricLine(
                        timeMs = timeMs,
                        text = if (isBlankOrSymbol) "• • •" else text,
                        isInterlude = isBlankOrSymbol
                    )
                )
            }
        }

        if (parsed.isEmpty()) return emptyList()
        parsed.sortBy { it.timeMs }

        // Helper to split an instrumental span into 2..5 evenly paced diagonal rows (~2.5-3.5s each)
        val result = ArrayList<LyricLine>(parsed.size + 16)
        fun addDiagonalInterludeRows(startMs: Long, endMs: Long) {
            val span = (endMs - startMs).coerceAtLeast(1000L)
            val rows = when {
                span >= 14_000L -> 5
                span >= 9_500L -> 4
                span >= 6_000L -> 3
                else -> 2
            }
            val step = span / rows
            for (r in 0 until rows) {
                result.add(LyricLine(startMs + r * step, "• • •", isInterlude = true))
            }
        }

        // Restore intro instrumentals for any song whose first vocal starts after 3.5s
        if (parsed.first().timeMs > INTRO_GAP_MS) {
            addDiagonalInterludeRows(0L, parsed.first().timeMs)
        }

        for (i in parsed.indices) {
            val curr = parsed[i]
            if (result.isNotEmpty() && result.last().timeMs == curr.timeMs) {
                if (!curr.isInterlude) {
                    result[result.lastIndex] = curr
                }
                continue
            }
            if (result.isNotEmpty() && !result.last().isInterlude && !curr.isInterlude) {
                val gap = curr.timeMs - result.last().timeMs
                if (gap > INTERLUDE_GAP_MS) {
                    addDiagonalInterludeRows(result.last().timeMs + 3500L, curr.timeMs)
                }
            }
            result.add(curr)
        }
        return result
    }

    /**
     * Binary search for the active lyric line index at [positionMs].
     * Returns -1 if before the first line.
     */
    fun findLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines[0].timeMs) return -1

        var low = 0
        var high = lines.lastIndex
        var ans = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return ans
    }
}
