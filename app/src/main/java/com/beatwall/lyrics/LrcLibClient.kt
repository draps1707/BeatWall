package com.beatwall.lyrics

import android.content.Context
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

    suspend fun fetchSyncedLyrics(
        rawTitle: String,
        rawArtist: String,
        durationMs: Long
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        val clean = TitleSanitizer.clean(rawTitle, rawArtist)
        if (clean.title.isBlank()) return@withContext emptyList()

        val cacheKey = sha256("${clean.artist.lowercase()}|${clean.title.lowercase()}")
        val cachedFile = File(cacheDir, "$cacheKey.lrc")
        if (cachedFile.exists()) {
            cachedFile.setLastModified(System.currentTimeMillis())
            val raw = cachedFile.readText()
            return@withContext if (raw == NO_LYRICS_MARKER) emptyList() else LrcParser.parse(raw)
        }

        val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 0
        var syncedLrc = queryGetEndpoint(clean.title, clean.artist, durationSec)
        if (syncedLrc.isNullOrBlank()) {
            syncedLrc = querySearchEndpoint(clean.title, clean.artist, durationSec)
        }

        trimCacheIfNeeded()
        if (syncedLrc.isNullOrBlank()) {
            cachedFile.writeText(NO_LYRICS_MARKER)
            emptyList()
        } else {
            cachedFile.writeText(syncedLrc)
            LrcParser.parse(syncedLrc)
        }
    }

    private fun queryGetEndpoint(title: String, artist: String, durationSec: Int): String? {
        return try {
            val t = URLEncoder.encode(title, "UTF-8")
            val a = URLEncoder.encode(artist, "UTF-8")
            val durPart = if (durationSec > 0) "&duration=$durationSec" else ""
            val url = URL("https://lrclib.net/api/get?track_name=$t&artist_name=$a$durPart")
            val body = httpGet(url) ?: return null
            val json = JSONObject(body)
            json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    private fun querySearchEndpoint(title: String, artist: String, durationSec: Int): String? {
        return try {
            val q = URLEncoder.encode("$artist $title".trim(), "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$q")
            val body = httpGet(url) ?: return null
            val arr = JSONArray(body)
            var bestSynced: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val synced = obj.optString("syncedLyrics")
                if (synced.isBlank() || synced == "null") continue
                if (durationSec <= 0) return synced
                val d = obj.optInt("duration", 0)
                val diff = kotlin.math.abs(d - durationSec)
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

    private fun httpGet(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", "BeatWall/1.0.0 (https://github.com/beatwall)")
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
