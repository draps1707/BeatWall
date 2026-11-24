package com.beatwall.lyrics

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class ProcessedArt(
    val coverArt: Bitmap,
    val blurredBackdrop: Bitmap,
    val primaryGlowColor: Int,
    val secondaryGlowColor: Int,
    val lyricAccentColor: Int
)

data class CircadianTheme(
    val minuteKey: Int,
    val backdrop: Bitmap,
    val primary: Int,
    val secondary: Int,
    val accent: Int
)

class ArtEffectProcessor {
    private val blurWidth = 360
    private val blurHeight = 780
    private val pool = arrayOf(
        Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
    )
    private var poolIdx = 0

    private val circadianBitmap = Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888)
    private var cachedCircadianTheme: CircadianTheme? = null

    private data class SkyAnchor(val minute: Int, val primary: Int, val secondary: Int, val accent: Int)

    // 7 Nuanced Sky Moods interpolated smoothly across 24 hours
    private val skyAnchors = listOf(
        SkyAnchor(240, 0xFF312E81.toInt(), 0xFF1E1B4B.toInt(), 0xFFA5B4FC.toInt()),  // 04:00 Pre-Dawn Indigo
        SkyAnchor(390, 0xFFB45309.toInt(), 0xFF7C2D12.toInt(), 0xFFFDBA74.toInt()),  // 06:30 Sunrise Saffron
        SkyAnchor(570, 0xFF0F766E.toInt(), 0xFF134E4A.toInt(), 0xFF5EEAD4.toInt()),  // 09:30 Crisp Morning Teal
        SkyAnchor(810, 0xFF1D4ED8.toInt(), 0xFF1E293B.toInt(), 0xFF7DD3FC.toInt()),  // 13:30 Afternoon Sapphire-Slate
        SkyAnchor(1050, 0xFF9A3412.toInt(), 0xFF701A75.toInt(), 0xFFFDA4AF.toInt()), // 17:30 Golden Hour Amber-Rose
        SkyAnchor(1170, 0xFF5B21B6.toInt(), 0xFF1E1B4B.toInt(), 0xFFC4B5FD.toInt()), // 19:30 Twilight Violet
        SkyAnchor(1320, 0xFF0F172A.toInt(), 0xFF064E3B.toInt(), 0xFF6EE7B7.toInt())  // 22:00 Deep Night Obsidian
    )

    private val grainPaint = Paint().apply {
        val grainSize = 128
        val pixels = IntArray(grainSize * grainSize)
        val rng = Random(1707)
        for (i in pixels.indices) {
            val v = rng.nextInt(256)
            pixels[i] = Color.argb(10, v, v, v)
        }
        val grainBmp = Bitmap.createBitmap(pixels, grainSize, grainSize, Bitmap.Config.ARGB_8888)
        shader = BitmapShader(grainBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    /**
     * Smoothly interpolates across 7 sky moods, re-baking the bitmap only every >= 10 minutes.
     * Applies 28% Late-Night OLED dimming between 22:00 and 05:00.
     */
    fun getCircadianTheme(minuteOfDay: Int): CircadianTheme {
        val existing = cachedCircadianTheme
        if (existing != null && abs(minuteOfDay - existing.minuteKey) < 10) {
            return existing
        }

        val (a, b, t) = findSkyBrackets(minuteOfDay)
        val primary = ColorUtils.blendARGB(a.primary, b.primary, t)
        val secondary = ColorUtils.blendARGB(a.secondary, b.secondary, t)
        val accent = ColorUtils.blendARGB(a.accent, b.accent, t)

        val canvas = Canvas(circadianBitmap)
        canvas.drawColor(0xFF05060A.toInt())

        val g1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                blurWidth * 0.28f, blurHeight * 0.30f, blurWidth * 1.05f,
                intArrayOf(withAlpha(primary, 195), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        val g2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                blurWidth * 0.76f, blurHeight * 0.68f, blurWidth * 1.05f,
                intArrayOf(withAlpha(secondary, 190), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), g1)
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), g2)

        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, blurHeight.toFloat(),
                intArrayOf(0xAA040508.toInt(), 0x20000000, 0x20000000, 0xCC040508.toInt()),
                floatArrayOf(0f, 0.28f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), vignettePaint)

        // Late-Night OLED Dimming (22:00 - 05:00)
        if (minuteOfDay >= 1320 || minuteOfDay < 300) {
            canvas.drawColor(0x46000000)
        }

        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), grainPaint)

        return CircadianTheme(minuteOfDay, circadianBitmap, primary, secondary, accent).also {
            cachedCircadianTheme = it
        }
    }

    private fun findSkyBrackets(minuteOfDay: Int): Triple<SkyAnchor, SkyAnchor, Float> {
        for (i in 0 until skyAnchors.lastIndex) {
            val a = skyAnchors[i]
            val b = skyAnchors[i + 1]
            if (minuteOfDay in a.minute..b.minute) {
                val t = (minuteOfDay - a.minute).toFloat() / (b.minute - a.minute).coerceAtLeast(1)
                return Triple(a, b, t)
            }
        }
        // Wrap around midnight (22:00 -> 04:00)
        val night = skyAnchors.last()
        val preDawn = skyAnchors.first()
        val span = (1440 - night.minute) + preDawn.minute
        val elapsed = if (minuteOfDay >= night.minute) minuteOfDay - night.minute else (1440 - night.minute) + minuteOfDay
        return Triple(night, preDawn, (elapsed.toFloat() / span).coerceIn(0f, 1f))
    }

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
        val crispCover = scaleSquareCover(rawCover, 720)

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
        canvas.drawColor(0xFF05060A.toInt())

        if (blurred != null) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 185 }
            canvas.drawBitmap(blurred, null, Rect(0, 0, blurWidth, blurHeight), paint)
            blurred.recycle()
        }

        val glowPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                blurWidth * 0.25f, blurHeight * 0.32f, blurWidth * 1.0f,
                intArrayOf(withAlpha(primaryGlow, 210), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        val glowPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                blurWidth * 0.78f, blurHeight * 0.66f, blurWidth * 1.0f,
                intArrayOf(withAlpha(secondaryGlow, 200), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), glowPaint1)
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), glowPaint2)

        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, blurHeight.toFloat(),
                intArrayOf(0xAA040508.toInt(), 0x24000000, 0x24000000, 0xCC040508.toInt()),
                floatArrayOf(0f, 0.28f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), vignettePaint)
        canvas.drawRect(0f, 0f, blurWidth.toFloat(), blurHeight.toFloat(), grainPaint)
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
        if (hsv[2] < 0.80f) hsv[2] = 0.92f
        if (hsv[1] < 0.40f) hsv[1] = 0.65f
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    }
}
