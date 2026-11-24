package com.beatwall.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

class IdleWallpaperStore(private val context: Context) {
    private val homeFile = File(context.filesDir, "idle_home.jpg")
    private val lockFile = File(context.filesDir, "idle_lock.jpg")

    @Volatile private var homeBitmap: Bitmap? = null
    @Volatile private var lockBitmap: Bitmap? = null

    fun hasHomeIdle(): Boolean = homeFile.exists()
    fun hasLockIdle(): Boolean = lockFile.exists()

    fun saveHomeIdle(uri: Uri): Boolean = saveScaledImage(uri, homeFile).also {
        if (it) homeBitmap = decodeScaled(homeFile)
    }

    fun saveLockIdle(uri: Uri): Boolean = saveScaledImage(uri, lockFile).also {
        if (it) lockBitmap = decodeScaled(lockFile)
    }

    fun getHomeBitmap(): Bitmap? {
        if (homeBitmap == null && homeFile.exists()) {
            homeBitmap = decodeScaled(homeFile)
        }
        return homeBitmap
    }

    fun getLockBitmap(): Bitmap? {
        if (lockBitmap == null && lockFile.exists()) {
            lockBitmap = decodeScaled(lockFile)
        }
        return lockBitmap
    }

    private fun saveScaledImage(uri: Uri, target: File): Boolean {
        return try {
            val dm = context.resources.displayMetrics
            val reqW = dm.widthPixels.coerceAtLeast(1080)
            val reqH = dm.heightPixels.coerceAtLeast(2400)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            var inSample = 1
            while (bounds.outWidth / (inSample * 2) >= reqW &&
                bounds.outHeight / (inSample * 2) >= reqH
            ) {
                inSample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false

            FileOutputStream(target).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
            }
            bmp.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeScaled(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }
}
