package com.beatwall.lyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.palette.graphics.Palette
import kotlin.math.max

data class ProcessedArt(
    val coverArt: Bitmap,
    val blurredBackdrop: Bitmap,
    val primaryGlowColor: Int,
    val secondaryGlowColor: Int,
    val lyricAccentColor: Int
)

class ArtEffectProcessor {
    private val blurWidth = 360
    private val blurHeight = 780
    private val pool = arrayOf(
        Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
    )
    private var poolIdx = 0

    fun process(rawCover: Bitmap): ProcessedArt {
        val palette = Palette.from(rawCover).maximumColorCount(16).generate()

        val rawPrimary = palette.getVibrantColor(
            palette.getDarkVibrantColor(palette.getDominantColor(0xFF1E293B.toInt()))
        )
        val rawSecondary = palette.getDarkVibrantColor(
            palette.getMutedColor(palette.getDarkMutedColor(0xFF0F172A.toInt()))
        )
        val rawAccent = palette.getLightVibrantColor(
            palette.getVibrantColor(0xFF7DD3FC.toInt())
        )

        val primaryGlow = clampLuminance(rawPrimary, maxV = 0.62f)
        val secondaryGlow = clampLuminance(rawSecondary, maxV = 0.52f)
        val accentGlow = ensureVibrantAccent(rawAccent)

        val targetSlot = pool[poolIdx]
        poolIdx = (poolIdx + 1) % pool.size

        renderBlurredBackdrop(rawCover, targetSlot, primaryGlow, secondaryGlow)
        val crispCover = scaleSquareCover(rawCover, 680)

        return ProcessedArt(
            coverArt = crispCover,
            blurredBackdrop = targetSlot,
            primaryGlowColor = primaryGlow,
            secondaryGlowColor = secondaryGlow,
            lyricAccentColor = accentGlow
        )
    }

    private fun renderBlurredBackdrop(
        src: Bitmap,
        dest: Bitmap,
        primaryGlow: Int,
        secondaryGlow: Int
    ) {
        val blurred = gpuBlurToBitmap(src, blurWidth, blurHeight, radius = 38f)
        val canvas = Canvas(dest)
        canvas.drawColor(0xFF07080D.toInt())

        if (blurred != null) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 195 }
            canvas.drawBitmap(blurred, null, Rect(0, 0, blurWidth, blurHeight), paint)
            blurred.recycle()
        }

        // Lush 2-color Apple-Music-style ambient radial glows
        val glowPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                blurWidth * 0.25f, blurHeight * 0.30f, blurWidth * 1.05f,
                intArrayOf(withAlpha(primaryGlow, 200), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        val glowPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                blurWidth * 0.78f, blurHeight * 0.68f, blurWidth * 1.05f,
                intArrayOf(withAlpha(secondaryGlow, 195), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), glowPaint1)
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), glowPaint2)

        // Balanced scrim so bright covers stay readable without crushing colors
        canvas.drawColor(0x36000000)
    }

    private fun gpuBlurToBitmap(src: Bitmap, w: Int, h: Int, radius: Float): Bitmap? {
        return try {
            val imageReader = ImageReader.newInstance(
                w, h, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
            )
            val renderer = HardwareRenderer()
            val node = RenderNode("BeatWallBlur").apply {
                setPosition(0, 0, w, h)
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            }
            val recCanvas = node.beginRecording()
            val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = src.width * scale
            val dh = src.height * scale
            val left = (w - dw) / 2f
            val top = (h - dh) / 2f
            recCanvas.drawBitmap(
                src, null,
                android.graphics.RectF(left, top, left + dw, top + dh),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            node.endRecording()

            renderer.setSurface(imageReader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            val image = imageReader.acquireNextImage()
            val hwBuffer = image?.hardwareBuffer
            val hwBmp = if (hwBuffer != null) Bitmap.wrapHardwareBuffer(hwBuffer, null) else null
            val swBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
            hwBmp?.recycle()
            hwBuffer?.close()
            image?.close()
            renderer.destroy()
            imageReader.close()
            swBmp
        } catch (_: Exception) {
            Bitmap.createScaledBitmap(src, w, h, true)
        }
    }

    private fun scaleSquareCover(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }

    private fun clampLuminance(color: Int, maxV: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] > maxV) hsv[2] = maxV
        if (hsv[1] < 0.35f) hsv[1] = 0.45f
        return Color.HSVToColor(hsv)
    }

    private fun ensureVibrantAccent(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[2] < 0.78f) hsv[2] = 0.90f
        if (hsv[1] < 0.40f) hsv[1] = 0.60f
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }
}
