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

    fun parse(rawLrc: String?, durationMs: Long = 0L): List<LyricLine> {
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

        if (parsed.isEmpty() || parsed.none { !it.isInterlude }) return emptyList()
        parsed.sortBy { it.timeMs }

        // Split ANY instrumental span (intro, mid-song drop/solo, explicit LRC blank/symbol line, or long outro)
        // into lively ~3s diagonal rows so long EDM drops & outros flow continuously instead of freezing!
        val result = ArrayList<LyricLine>(parsed.size + 32)
        var coveredUntilMs = -1L
        fun addDiagonalInterludeRows(startMs: Long, endMs: Long) {
            val span = (endMs - startMs).coerceAtLeast(1000L)
            val rows = ((span + 1400L) / 3000L).toInt().coerceIn(2, 40)
            val step = span / rows
            for (r in 0 until rows) {
                result.add(LyricLine(startMs + r * step, "• • •", isInterlude = true))
            }
            if (endMs > coveredUntilMs) coveredUntilMs = endMs
        }

        // Restore intro instrumentals for any song whose first vocal starts after 3.5s
        if (!parsed.first().isInterlude && parsed.first().timeMs > INTRO_GAP_MS) {
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

            if (curr.isInterlude) {
                if (curr.timeMs < coveredUntilMs) continue
                // Expand explicit LRC interlude/blank lines (mid-song drops AND long outros) across their full span!
                val nextReal = parsed.drop(i + 1).firstOrNull { !it.isInterlude }
                if (nextReal != null) {
                    val span = nextReal.timeMs - curr.timeMs
                    if (span >= 5500L) {
                        addDiagonalInterludeRows(curr.timeMs, nextReal.timeMs)
                    } else {
                        result.add(curr)
                    }
                } else if (durationMs > 0L && durationMs - curr.timeMs >= 5500L) {
                    addDiagonalInterludeRows(curr.timeMs, durationMs)
                } else {
                    result.add(curr)
                }
                continue
            }

            if (result.isNotEmpty() && !result.last().isInterlude) {
                val gap = curr.timeMs - result.last().timeMs
                if (gap > INTERLUDE_GAP_MS) {
                    addDiagonalInterludeRows(result.last().timeMs + 3500L, curr.timeMs)
                }
            }
            result.add(curr)
        }

        // Expand long instrumental outros after the final vocal lyric (e.g., 80s EDM outro with no trailing LRC tag)
        if (durationMs > 0L && result.isNotEmpty() && !result.last().isInterlude) {
            val outroStart = result.last().timeMs + 3500L
            if (durationMs - outroStart >= 5500L) {
                addDiagonalInterludeRows(outroStart, durationMs)
            }
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
