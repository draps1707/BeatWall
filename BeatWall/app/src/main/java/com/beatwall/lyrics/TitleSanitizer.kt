package com.beatwall.lyrics

object TitleSanitizer {
    private val BRACKET_NOISE = Regex(
        """[\(\[\{].*?(official|video|audio|lyrics?|mv|hd|4k|8k|remaster(ed)?|live|version|visualizer|full\s*song|from\s*["'].*?["']).*?[\)\]\}]""",
        RegexOption.IGNORE_CASE
    )
    private val PIPE_SUFFIX = Regex("""\s*[\|\u2014]\s*.*$""")
    private val SMART_SHUFFLE_SUFFIX = Regex("""\s*[•·]\s*From\s+.*$""", RegexOption.IGNORE_CASE)
    private val FEAT_SUFFIX = Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE)
    private val TOPIC_VEVO = Regex("""(\s*-\s*Topic$|VEVO$)""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    data class CleanTrack(val title: String, val artist: String)

    fun clean(rawTitle: String, rawArtist: String): CleanTrack {
        var title = rawTitle
            .replace(BRACKET_NOISE, "")
            .replace(PIPE_SUFFIX, "")
            .replace(FEAT_SUFFIX, "")
            .replace(MULTI_SPACE, " ")
            .trim()

        var artist = rawArtist
            .replace(SMART_SHUFFLE_SUFFIX, "")
            .replace(TOPIC_VEVO, "")
            .replace(FEAT_SUFFIX, "")
            .replace(MULTI_SPACE, " ")
            .trim()

        if (title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (left.isNotEmpty() && right.isNotEmpty() &&
                (artist.isEmpty() || artist.contains("T-Series", true) ||
                 artist.contains("Sony", true) || artist.contains("Zee", true) ||
                 !title.contains(artist, true))
            ) {
                artist = left
                title = right
            }
        }

        return CleanTrack(
            title = title.ifEmpty { rawTitle.trim() },
            artist = artist.ifEmpty { rawArtist.trim() }
        )
    }
}
