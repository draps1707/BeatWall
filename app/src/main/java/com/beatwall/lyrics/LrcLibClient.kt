package com.beatwall.lyrics

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class LrcLibClient(context: Context) {
    private val cacheDir = File(context.cacheDir, "lrc_cache").apply { mkdirs() }
    private val maxCacheFiles = 500
    private val memCache = LruCache<String, List<LyricLine>>(30)
    private val anyParensRegex = Regex("""\s*[\(\[].*?[\)\]]""")

    suspend fun fetchSyncedLyrics(
        rawTitle: String,
        rawArtist: String,
        durationMs: Long
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val clean = TitleSanitizer.clean(rawTitle, rawArtist)
        if (clean.title.isBlank()) return@withContext emptyList()

        val cacheKey = sha256("${clean.artist.lowercase()}|${clean.title.lowercase()}")
        memCache.get(cacheKey)?.let { return@withContext it }

        val cachedFile = File(cacheDir, "$cacheKey.lrc")
        if (cachedFile.exists()) {
            cachedFile.setLastModified(System.currentTimeMillis())
            val raw = cachedFile.readText()
            val parsed = if (raw == NO_LYRICS_MARKER) emptyList() else LrcParser.parse(raw)
            memCache.put(cacheKey, parsed)
            return@withContext parsed
        }

        val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 0
        val primaryArtist = clean.artist.split(',', '&').firstOrNull()?.trim().orEmpty()
        val strippedTitle = clean.title.replace(anyParensRegex, "").trim()
        val searchTitle = strippedTitle.ifEmpty { clean.title }
        val searchArtist = primaryArtist.ifEmpty { clean.artist }

        var fallbackPlain: String? = null

        // Tier 1: LRCLIB (Exact -> Primary Artist -> Stripped Parenthetical -> Search)
        var (syncedLrc, plain1) = queryGetEndpoint(clean.title, clean.artist, durationSec)
        if (fallbackPlain.isNullOrBlank()) fallbackPlain = plain1

        if (syncedLrc.isNullOrBlank() && primaryArtist.isNotEmpty() && primaryArtist != clean.artist) {
            val (s2, p2) = queryGetEndpoint(clean.title, primaryArtist, durationSec)
            syncedLrc = s2
            if (fallbackPlain.isNullOrBlank()) fallbackPlain = p2
        }
        if (syncedLrc.isNullOrBlank() && strippedTitle.isNotEmpty() && strippedTitle != clean.title) {
            val (s3, p3) = queryGetEndpoint(strippedTitle, searchArtist, durationSec)
            syncedLrc = s3
            if (fallbackPlain.isNullOrBlank()) fallbackPlain = p3
        }
        if (syncedLrc.isNullOrBlank()) {
            val (s4, p4) = querySearchEndpoint(searchTitle, searchArtist, durationSec)
            syncedLrc = s4
            if (fallbackPlain.isNullOrBlank()) fallbackPlain = p4
        }

        // Tier 2: SimpMusic Lyrics API
        if (syncedLrc.isNullOrBlank()) {
            syncedLrc = querySimpMusicApi(searchTitle, searchArtist, durationSec)
        }

        // Tier 3: NetEase Cloud Music Public LRC Search
        if (syncedLrc.isNullOrBlank()) {
            syncedLrc = queryNetEaseApi(searchTitle, searchArtist, durationSec)
        }

        // Tier 4: LRCLIB plainLyrics gentle duration-paced fallback
        if (syncedLrc.isNullOrBlank() && !fallbackPlain.isNullOrBlank()) {
            syncedLrc = synthesizeLrcFromPlain(fallbackPlain, durationMs)
        }

        trimCacheIfNeeded()
        if (syncedLrc.isNullOrBlank()) {
            cachedFile.writeText(NO_LYRICS_MARKER)
            memCache.put(cacheKey, emptyList())
            emptyList()
        } else {
            cachedFile.writeText(syncedLrc)
            val parsed = LrcParser.parse(syncedLrc)
            memCache.put(cacheKey, parsed)
            parsed
        }
    }

    private fun synthesizeLrcFromPlain(plain: String, durationMs: Long): String? {
        val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val totalMs = if (durationMs > 20_000L) durationMs else 210_000L
        val startMs = 5_000L
        val usableMs = (totalMs * 0.90 - startMs).toLong().coerceAtLeast(lines.size * 1500L)
        val stepMs = usableMs / lines.size.coerceAtLeast(1)

        val sb = StringBuilder()
        for (i in lines.indices) {
            val tMs = startMs + i * stepMs
            val mins = (tMs / 60_000L)
            val secs = (tMs % 60_000L) / 1000L
            val cs = (tMs % 1000L) / 10L
            sb.append("[%02d:%02d.%02d]%s\n".format(mins, secs, cs, lines[i]))
        }
        return sb.toString()
    }

    private fun queryGetEndpoint(title: String, artist: String, durationSec: Int): Pair<String?, String?> {
        return try {
            val t = URLEncoder.encode(title, "UTF-8")
            val a = URLEncoder.encode(artist, "UTF-8")
            val durPart = if (durationSec > 0) "&duration=$durationSec" else ""
            val url = URL("https://lrclib.net/api/get?track_name=$t&artist_name=$a$durPart")
            val body = httpGet(url) ?: return null to null
            val json = JSONObject(body)
            val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
            val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
            synced to plain
        } catch (_: Exception) {
            null to null
        }
    }

    private fun querySearchEndpoint(title: String, artist: String, durationSec: Int): Pair<String?, String?> {
        return try {
            val q = URLEncoder.encode("$artist $title".trim(), "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$q")
            val body = httpGet(url) ?: return null to null
            val arr = JSONArray(body)
            var bestSynced: String? = null
            var bestPlain: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                val plain = obj.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                if (bestPlain == null && plain != null) bestPlain = plain
                if (synced == null) continue
                if (durationSec <= 0) return synced to (plain ?: bestPlain)
                val d = obj.optInt("duration", 0)
                val diff = kotlin.math.abs(d - durationSec)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestSynced = synced
                }
            }
            bestSynced to bestPlain
        } catch (_: Exception) {
            null to null
        }
    }

    private fun querySimpMusicApi(title: String, artist: String, durationSec: Int): String? {
        return try {
            val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val url = URL("https://api-lyrics.simpmusic.org/v1/search?q=$q")
            val body = httpGet(url) ?: return null
            val root = JSONObject(body)
            val data = root.optJSONArray("data") ?: return null
            var bestSynced: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val synced = obj.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                    ?: obj.optString("lrc").takeIf { it.isNotBlank() && it != "null" }
                    ?: continue
                if (durationSec <= 0) return synced
                val d = obj.optInt("duration", 0)
                val diff = if (d > 0) kotlin.math.abs(d - durationSec) else 999
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestSynced = synced
                }
            }
            bestSynced
        } catch (_: Exception) {
            null
        }
    }

    private fun queryNetEaseApi(title: String, artist: String, durationSec: Int): String? {
        return try {
            val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val searchUrl = URL("https://music.163.com/api/search/get/web?s=$q&type=1&limit=3")
            val searchBody = httpGet(searchUrl) ?: return null
            val songs = JSONObject(searchBody)
                .optJSONObject("result")
                ?.optJSONArray("songs") ?: return null

            var bestSongId = -1L
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                val id = s.optLong("id", -1L)
                if (id <= 0L) continue
                val dSec = (s.optLong("duration", 0L) / 1000L).toInt()
                val diff = if (durationSec > 0 && dSec > 0) kotlin.math.abs(dSec - durationSec) else 0
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestSongId = id
                }
            }
            if (bestSongId <= 0L) return null

            val lyricUrl = URL("https://music.163.com/api/song/lyric?id=$bestSongId&lv=1")
            val lyricBody = httpGet(lyricUrl) ?: return null
            val lrc = JSONObject(lyricBody)
                .optJSONObject("lrc")
                ?.optString("lyric")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (lrc != null && LrcParser.parse(lrc).isNotEmpty()) lrc else null
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 2000
            setRequestProperty("User-Agent", "BeatWall/5.1.0 (https://github.com/beatwall)")
        }
        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= maxCacheFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxCacheFiles)
            .forEach { it.delete() }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val NO_LYRICS_MARKER = "__NO_SYNCED_LYRICS__"
    }
}
