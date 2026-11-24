package com.beatwall.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

data class SavedLastTrack(
    val title: String,
    val artist: String,
    val cover: Bitmap
)

class LastCoverStore(context: Context) {
    private val coverFile = File(context.filesDir, "last_cover.jpg")
    private val metaFile = File(context.filesDir, "last_meta.txt")

    fun save(title: String, artist: String, cover: Bitmap) {
        try {
            FileOutputStream(coverFile).use { out ->
                cover.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            metaFile.writeText("$title\n$artist")
        } catch (_: Exception) {
        }
    }

    fun load(): SavedLastTrack? {
        if (!coverFile.exists() || !metaFile.exists()) return null
        return try {
            val bmp = BitmapFactory.decodeFile(coverFile.absolutePath) ?: return null
            val lines = metaFile.readLines()
            val title = lines.getOrNull(0)?.ifBlank { "BeatWall" } ?: "BeatWall"
            val artist = lines.getOrNull(1) ?: ""
            SavedLastTrack(title, artist, bmp)
        } catch (_: Exception) {
            null
        }
    }
}
