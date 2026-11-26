package com.beatwall.lyrics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class WorldBiome(val title: String) {
    SAND_DUNES("Sand Dunes"),
    SNOWY_ALPENGLOW_LAKE("Snowy Peaks & Mirror Lake"),
    MISTY_PINE_FOREST("Misty Pine Forest"),
    GOLDEN_SAVANNA("Emerald Savanna & Meadow"),
    COASTAL_OCEAN("Coastal Ocean"),
    SUNLIT_WOODLAND("Sunlit Woodland"),
    COASTAL_REEDS("Coastal Reeds & Wildflowers")
}

/**
 * Zero-allocation procedural landscape engine for 366 unique daily worlds across 6 biomes.
 * Uses steady weather.windKmh for linear timeSec drift (never oscillating gusts),
 * pre-builds all terrain/pine/acacia/woodland Paths once per dayOfYear/size, and renders
 * with Lock<->Home 3D parallax, tip-only Alpenglow, live god rays, and biome-matched life.
 */
class LandscapeEngine {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    private var cachedMistColor = 0
    private var cachedMistShader: RadialGradient? = null

    private var cachedRayColor = 0
    private var cachedRayShader: LinearGradient? = null

    private val farPath = Path()
    private val midPath = Path()
    private val forePath = Path()
    private val snowPath = Path()
    private val lakeReflectPath = Path()
    private val birdPath = Path()
    private val rayPath = Path()
    private val floraPath = Path()

    private var builtDay = -1
    private var builtW = 0
    private var builtH = 0

    fun biomeForDay(dayOfYear: Int): WorldBiome {
        val idx = ((dayOfYear - 1).coerceAtLeast(0)) % WorldBiome.values().size
        return WorldBiome.values()[idx]
    }

    fun horizonY(height: Int, lockBlend: Float): Float {
        return height * (0.66f + 0.05f * lockBlend)
    }

    fun skyClarityBoost(dayOfYear: Int): Float {
        return when (biomeForDay(dayOfYear)) {
            WorldBiome.SAND_DUNES, WorldBiome.SNOWY_ALPENGLOW_LAKE, WorldBiome.GOLDEN_SAVANNA -> 1.20f
            else -> 1.0f
        }
    }

    private fun seedRand(day: Int, salt: Int): Float {
        val x = sin(day * 12.9898 + salt * 78.233) * 43758.5453
        return (x - kotlin.math.floor(x)).toFloat()
    }

    private fun ensureGeometry(dayOfYear: Int, w: Int, h: Int) {
        if (builtDay == dayOfYear && builtW == w && builtH == h) return
        builtDay = dayOfYear
        builtW = w
        builtH = h

        val biome = biomeForDay(dayOfYear)
        val baseH = h * 0.66f
        val wf = w.toFloat()
        val hf = h.toFloat()

        farPath.reset()
        midPath.reset()
        forePath.reset()
        snowPath.reset()
        lakeReflectPath.reset()

        val p1 = seedRand(dayOfYear, 1) * 6.28f
        val p2 = seedRand(dayOfYear, 2) * 6.28f
        val p3 = seedRand(dayOfYear, 3) * 6.28f

        when (biome) {
            WorldBiome.SAND_DUNES -> {
                buildDunePath(farPath, wf, hf, baseH - h * 0.02f, h * 0.032f, 1.8f, p1)
                buildDunePath(midPath, wf, hf, baseH + h * 0.05f, h * 0.045f, 1.35f, p2 + 1.2f)
                buildDunePath(forePath, wf, hf, baseH + h * 0.14f, h * 0.055f, 1.05f, p3 + 2.4f)
            }
            WorldBiome.SNOWY_ALPENGLOW_LAKE -> {
                buildAlpinePeaks(farPath, wf, hf, baseH - h * 0.04f, h * 0.095f, dayOfYear, 11)
                buildAlpinePeaks(midPath, wf, hf, baseH - h * 0.01f, h * 0.125f, dayOfYear, 23, snowCapOut = snowPath)
                buildMirrorAlpine(lakeReflectPath, wf, baseH, h * 0.090f, dayOfYear, 23)
            }
            WorldBiome.MISTY_PINE_FOREST -> {
                buildMistyPineLayer(farPath, wf, hf, baseH - h * 0.02f, h * 0.105f, dayOfYear, 31, count = 18)
                buildMistyPineLayer(midPath, wf, hf, baseH + h * 0.05f, h * 0.120f, dayOfYear, 47, count = 15)
                buildMistyPineLayer(forePath, wf, hf, baseH + h * 0.14f, h * 0.135f, dayOfYear, 61, count = 12)
            }
            WorldBiome.GOLDEN_SAVANNA -> {
                buildDunePath(farPath, wf, hf, baseH - h * 0.015f, h * 0.014f, 1.1f, p1)
                buildSavannaMeadowWithAcacias(midPath, wf, hf, baseH + h * 0.035f, h * 0.016f, dayOfYear, 71)
                buildDunePath(forePath, wf, hf, baseH + h * 0.14f, h * 0.018f, 0.9f, p3)
            }
            WorldBiome.COASTAL_OCEAN -> {
                val onLeft = (dayOfYear % 2 == 0)
                buildHeadlandPath(farPath, wf, baseH, h * 0.065f, onLeft, dayOfYear)
            }
            WorldBiome.SUNLIT_WOODLAND -> {
                buildWoodlandTrunks(farPath, wf, hf, dayOfYear, salt = 83, count = 6, minW = 0.010f, maxW = 0.017f, openCenter = false)
                buildWoodlandTrunks(midPath, wf, hf, dayOfYear, salt = 97, count = 5, minW = 0.018f, maxW = 0.028f, openCenter = true)
                buildWoodlandForeFrame(forePath, wf, hf, baseH + h * 0.15f, dayOfYear, salt = 113)
            }
            WorldBiome.COASTAL_REEDS -> {
                val onLeft = (dayOfYear % 2 == 1)
                buildHeadlandPath(farPath, wf, baseH, h * 0.045f, onLeft, dayOfYear)
            }
        }
    }

    private fun buildDunePath(path: Path, w: Float, h: Float, yBase: Float, amp: Float, freq: Float, phase: Float) {
        val steps = 48
        path.moveTo(0f, h * 1.15f)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val x = fx * w
            val crest = sin(fx * freq * PI + phase) * 0.65 + sin(fx * freq * 2.15 * PI + phase * 0.7) * 0.35
            val y = yBase - crest.toFloat() * amp
            path.lineTo(x, y)
        }
        path.lineTo(w, h * 1.15f)
        path.close()
    }

    private fun alpineHeightAt(fx: Float, day: Int, salt: Int): Float {
        val cMain = 0.32f + seedRand(day, salt) * 0.36f
        val cSub = if (cMain < 0.5f) cMain + 0.32f else cMain - 0.32f
        val mainPeak = (1f - abs(fx - cMain) / 0.28f).coerceIn(0f, 1f).pow(1.45f)
        val subPeak = (1f - abs(fx - cSub) / 0.24f).coerceIn(0f, 1f).pow(1.6f) * 0.68f
        val farShoulder = (1f - abs(fx - (1f - cMain)) / 0.35f).coerceIn(0f, 1f).pow(1.8f) * 0.38f
        val crag = (1f - abs(sin(fx * 9.5 * PI + seedRand(day, salt + 1) * 6.28))) * 0.14f
        return (maxOf(mainPeak, subPeak, farShoulder) + crag.toFloat() * (0.4f + mainPeak * 0.6f)).coerceIn(0f, 1.1f)
    }

    private fun buildAlpinePeaks(
        path: Path, w: Float, h: Float, yBase: Float, peakH: Float,
        day: Int, salt: Int, snowCapOut: Path? = null
    ) {
        val steps = 72
        path.moveTo(0f, h * 1.15f)
        val xs = FloatArray(steps + 1)
        val ys = FloatArray(steps + 1)
        val hs = FloatArray(steps + 1)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val x = fx * w
            val relH = alpineHeightAt(fx, day, salt)
            val y = yBase - relH * peakH
            xs[i] = x
            ys[i] = y
            hs[i] = relH
            path.lineTo(x, y)
        }
        path.lineTo(w, h * 1.15f)
        path.close()

        if (snowCapOut != null) {
            snowCapOut.moveTo(0f, yBase - peakH * 0.30f)
            for (i in 0..steps) {
                snowCapOut.lineTo(xs[i], ys[i])
            }
            for (i in steps downTo 0) {
                val fx = i.toFloat() / steps
                val snowDepth = peakH * (0.10f + 0.26f * hs[i] + 0.06f * sin(fx * 14.0 * PI + day).toFloat())
                val snowBottom = minOf(yBase - peakH * 0.20f, ys[i] + snowDepth)
                snowCapOut.lineTo(xs[i], snowBottom)
            }
            snowCapOut.close()
        }
    }

    private fun buildMirrorAlpine(path: Path, w: Float, yBase: Float, peakH: Float, day: Int, salt: Int) {
        val steps = 56
        path.moveTo(0f, yBase)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val y = yBase + alpineHeightAt(fx, day, salt) * (peakH * 0.72f)
            path.lineTo(fx * w, y)
        }
        path.lineTo(w, yBase)
        path.close()
    }

    private fun buildMistyPineLayer(
        path: Path, w: Float, h: Float, yBase: Float, treeMaxH: Float,
        day: Int, salt: Int, count: Int
    ) {
        // Slender overlapping conifer spires on a continuous forested slope (no horizontal seam)
        path.moveTo(-w * 0.04f, h * 1.15f)
        path.lineTo(-w * 0.04f, yBase)
        val p1 = seedRand(day, salt) * 6.28f
        for (i in 0 until count) {
            val baseFx = (i + 0.15f + 0.70f * seedRand(day + i, salt + 3)) / count
            val cx = baseFx * w
            val hillY = yBase - sin(baseFx * 1.7 * PI + p1).toFloat() * (h * 0.018f)
            val th = treeMaxH * (0.52f + 0.48f * seedRand(day + i, salt + 7))
            val tw = w * (0.018f + 0.014f * seedRand(day + i, salt + 11))

            path.lineTo(cx - tw * 0.95f, hillY)
            path.lineTo(cx - tw * 0.62f, hillY - th * 0.28f)
            path.lineTo(cx - tw * 0.34f, hillY - th * 0.25f)
            path.lineTo(cx - tw * 0.46f, hillY - th * 0.56f)
            path.lineTo(cx - tw * 0.20f, hillY - th * 0.52f)
            path.lineTo(cx - tw * 0.26f, hillY - th * 0.80f)
            path.lineTo(cx - tw * 0.10f, hillY - th * 0.77f)
            path.lineTo(cx, hillY - th)
            path.lineTo(cx + tw * 0.10f, hillY - th * 0.77f)
            path.lineTo(cx + tw * 0.26f, hillY - th * 0.80f)
            path.lineTo(cx + tw * 0.20f, hillY - th * 0.52f)
            path.lineTo(cx + tw * 0.46f, hillY - th * 0.56f)
            path.lineTo(cx + tw * 0.34f, hillY - th * 0.25f)
            path.lineTo(cx + tw * 0.62f, hillY - th * 0.28f)
            path.lineTo(cx + tw * 0.95f, hillY)
        }
        path.lineTo(w * 1.04f, yBase)
        path.lineTo(w * 1.04f, h * 1.15f)
        path.close()
    }

    private fun buildSavannaMeadowWithAcacias(
        path: Path, w: Float, h: Float, yBase: Float, amp: Float, day: Int, salt: Int
    ) {
        val p1 = seedRand(day, salt) * 6.28f
        val steps = 56
        path.moveTo(0f, h * 1.15f)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val y = yBase - sin(fx * 1.2 * PI + p1).toFloat() * amp
            path.lineTo(fx * w, y)
        }
        path.lineTo(w, h * 1.15f)
        path.close()

        // 2 iconic flat-topped Acacias with Y-forked trunks and multi-lobe layered canopies
        for (a in 0 until 2) {
            val fx = if (a == 0) (0.22f + 0.14f * seedRand(day, salt + 5)) else (0.66f + 0.16f * seedRand(day, salt + 9))
            val tx = fx * w
            val ty = yBase - sin(fx * 1.2 * PI + p1).toFloat() * amp + 3f
            val trunkH = h * (0.048f + 0.014f * seedRand(day + a, salt + 12))
            val tw = w * 0.0065f
            val canopyW = w * (0.125f + 0.035f * a)
            val canopyH = h * 0.011f
            val forkY = ty - trunkH * 0.52f

            // Main trunk up to fork
            path.moveTo(tx - tw, ty)
            path.lineTo(tx - tw * 0.65f, forkY)
            path.lineTo(tx + tw * 0.65f, forkY)
            path.lineTo(tx + tw, ty)
            path.close()

            // Left & right Y-fork branches
            path.moveTo(tx - tw * 0.5f, forkY)
            path.lineTo(tx - canopyW * 0.24f, ty - trunkH)
            path.lineTo(tx - canopyW * 0.20f, ty - trunkH)
            path.lineTo(tx + tw * 0.1f, forkY)
            path.close()

            path.moveTo(tx - tw * 0.1f, forkY)
            path.lineTo(tx + canopyW * 0.22f, ty - trunkH)
            path.lineTo(tx + canopyW * 0.26f, ty - trunkH)
            path.lineTo(tx + tw * 0.5f, forkY)
            path.close()

            // Multi-lobe flat Acacia crown (center + left + right)
            rect.set(tx - canopyW * 0.38f, ty - trunkH - canopyH * 1.15f, tx + canopyW * 0.38f, ty - trunkH + canopyH * 0.15f)
            path.addOval(rect, Path.Direction.CW)
            rect.set(tx - canopyW * 0.54f, ty - trunkH - canopyH * 0.65f, tx - canopyW * 0.02f, ty - trunkH + canopyH * 0.25f)
            path.addOval(rect, Path.Direction.CW)
            rect.set(tx + canopyW * 0.02f, ty - trunkH - canopyH * 0.72f, tx + canopyW * 0.54f, ty - trunkH + canopyH * 0.22f)
            path.addOval(rect, Path.Direction.CW)
        }
    }

    private fun buildHeadlandPath(path: Path, w: Float, yBase: Float, cliffH: Float, onLeft: Boolean, day: Int) {
        val span = 0.28f + seedRand(day, 99) * 0.12f
        val steps = 28
        path.moveTo(if (onLeft) 0f else w * (1f - span), yBase + 2f)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val fx = if (onLeft) t * span else (1f - span) + t * span
            val profile = if (onLeft) (1f - t) else t
            val bump = 0.85f + 0.15f * sin(t * 4.5 * PI + day).toFloat()
            val y = yBase - (profile * profile) * cliffH * bump
            path.lineTo(fx * w, y)
        }
        path.lineTo(if (onLeft) w * span else w, yBase + 2f)
        path.close()
    }

    private fun buildWoodlandTrunks(
        path: Path, w: Float, h: Float, day: Int, salt: Int,
        count: Int, minW: Float, maxW: Float, openCenter: Boolean
    ) {
        for (i in 0 until count) {
            var fx = (i + 0.20f + 0.60f * seedRand(day + i, salt + 3)) / count
            if (openCenter && fx in 0.38f..0.62f) {
                fx = if (fx < 0.5f) 0.28f - (0.5f - fx) * 0.4f else 0.72f + (fx - 0.5f) * 0.4f
            }
            val cx = fx * w
            val lean = (seedRand(day + i, salt + 7) - 0.5f) * (w * 0.024f)
            val tw = w * (minW + (maxW - minW) * seedRand(day + i, salt + 11))
            val topY = -h * 0.04f
            val botY = h * 1.05f

            path.moveTo(cx - tw * 0.62f, botY)
            path.lineTo(cx + lean - tw * 0.38f, topY)
            path.lineTo(cx + lean + tw * 0.38f, topY)
            path.lineTo(cx + tw * 0.62f, botY)
            path.close()

            // Subtle upper canopy branch silhouette
            val bY = h * (0.14f + 0.14f * seedRand(day + i, salt + 15))
            val bDir = if (cx < w * 0.5f) 1f else -1f
            val bLen = w * (0.09f + 0.06f * seedRand(day + i, salt + 19)) * bDir
            path.moveTo(cx + lean * 0.7f, bY)
            path.lineTo(cx + lean * 0.7f + bLen, bY - h * 0.045f)
            path.lineTo(cx + lean * 0.7f + bLen * 0.88f, bY - h * 0.036f)
            path.lineTo(cx + lean * 0.7f, bY + tw * 0.45f)
            path.close()
        }
    }

    private fun buildWoodlandForeFrame(path: Path, w: Float, h: Float, floorY: Float, day: Int, salt: Int) {
        // Gentle mossy woodland floor mound
        val steps = 40
        path.moveTo(0f, h * 1.15f)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val dip = sin(fx * PI).toFloat() * (h * 0.024f)
            val bump = sin(fx * 3.5 * PI + day).toFloat() * (h * 0.008f)
            path.lineTo(fx * w, floorY + dip + bump)
        }
        path.lineTo(w, h * 1.15f)
        path.close()

        // 4 dark framing foreground trunks on the left & right edges (keeps center open for sun/moon & clock)
        val slots = floatArrayOf(0.06f, 0.18f, 0.82f, 0.94f)
        for (i in slots.indices) {
            val cx = (slots[i] + (seedRand(day + i, salt) - 0.5f) * 0.04f) * w
            val lean = (if (i < 2) 1f else -1f) * w * 0.015f
            val tw = w * (0.030f + 0.014f * seedRand(day + i, salt + 5))
            path.moveTo(cx - tw * 0.7f, h * 1.05f)
            path.lineTo(cx + lean - tw * 0.45f, -h * 0.04f)
            path.lineTo(cx + lean + tw * 0.45f, -h * 0.04f)
            path.lineTo(cx + tw * 0.7f, h * 1.05f)
            path.close()
        }
    }

    private fun drawSoftMist(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, colWithAlpha: Int) {
        val sh = if (cachedMistColor == colWithAlpha && cachedMistShader != null) {
            cachedMistShader!!
        } else {
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(colWithAlpha, (colWithAlpha and 0x00FFFFFF) or (((Color.alpha(colWithAlpha) * 0.35f).toInt()) shl 24), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            ).also {
                cachedMistColor = colWithAlpha
                cachedMistShader = it
            }
        }
        shaderMatrix.reset()
        shaderMatrix.setScale(rx, ry)
        shaderMatrix.postTranslate(cx, cy)
        sh.setLocalMatrix(shaderMatrix)
        mistPaint.shader = sh
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(rect, mistPaint)
        mistPaint.shader = null
    }

    private fun getRayShader(colWithAlpha: Int): LinearGradient {
        if (cachedRayColor == colWithAlpha && cachedRayShader != null) return cachedRayShader!!
        val rgb = colWithAlpha and 0x00FFFFFF
        val a = Color.alpha(colWithAlpha)
        return LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(
                rgb or ((a * 0.90f).toInt() shl 24),
                rgb or ((a * 0.45f).toInt() shl 24),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        ).also {
            cachedRayColor = colWithAlpha
            cachedRayShader = it
        }
    }

    fun drawForeground(
        canvas: Canvas,
        width: Int,
        height: Int,
        density: Float,
        dayOfYear: Int,
        sky: SkySnapshot,
        circ: CircadianTheme,
        weather: WeatherState,
        sunX: Float,
        sunY: Float,
        moonX: Float,
        moonY: Float,
        lockBlend: Float,
        timeSec: Double,
        masterAlpha: Int
    ) {
        ensureGeometry(dayOfYear, width, height)
        val biome = biomeForDay(dayOfYear)
        val horizonY = horizonY(height, lockBlend)
        val baseHorizon = height * 0.66f
        val midShiftY = (horizonY - baseHorizon) * 0.72f
        val foreShiftY = (horizonY - baseHorizon) * 1.15f

        val isDay = sky.sunAltDeg > -2f
        val alpenglow = if (sky.sunAltDeg in -4f..10f) {
            (1f - abs(sky.sunAltDeg - 2f) / 8f).coerceIn(0f, 1f)
        } else 0f
        val lightColor = when {
            alpenglow > 0.1f -> ColorUtils.blendARGB(sky.sunColor, sky.sunCoronaColor, alpenglow * 0.75f)
            isDay -> sky.sunColor
            else -> sky.moonColor
        }
        val lightStrength = when {
            sky.sunAltDeg > 8f -> 0.85f
            sky.sunAltDeg > -4f -> 0.55f + 0.30f * ((sky.sunAltDeg + 4f) / 12f)
            sky.moonAltDeg > 0f -> 0.30f * (0.4f + 0.6f * sky.moonIllumination)
            else -> 0.08f
        }
        val shadowBase = if (isDay) ColorUtils.blendARGB(0xFF0F172A.toInt(), circ.horizonColor, 0.25f) else 0xFF03050A.toInt()
        val windDir = if (weather.windDirDeg in 0f..180f) -1f else 1f
        val steadyWind = weather.windKmh.coerceIn(2f, 60f)
        val fogFactor = ((4500f - weather.visibilityM) / 4000f).coerceIn(0f, 1f)

        if (biome != WorldBiome.SUNLIT_WOODLAND) {
            val mirageRgb = (if (isDay) circ.horizonColor else 0xFF334155.toInt()) and 0x00FFFFFF
            val mirageAlpha = (masterAlpha * (if (isDay) 0.22f else 0.10f) * (1f + fogFactor * 0.5f)).toInt().coerceIn(0, 255)
            drawSoftMist(canvas, width * 0.5f, horizonY, width * 0.92f, height * 0.024f, mirageRgb or (mirageAlpha shl 24))
        }

        when (biome) {
            WorldBiome.SAND_DUNES -> drawSandDunes(
                canvas, width, height, density, horizonY, midShiftY, foreShiftY,
                lightColor, lightStrength, alpenglow, sunX, isDay, windDir, steadyWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.SNOWY_ALPENGLOW_LAKE -> drawSnowyAlpineLake(
                canvas, width, height, density, horizonY, midShiftY,
                sky, circ, weather, lightColor, lightStrength, shadowBase, alpenglow,
                if (sky.sunAltDeg > -4f) sunX else moonX, isDay, windDir, steadyWind, timeSec, masterAlpha
            )
            WorldBiome.MISTY_PINE_FOREST -> drawMistyForest(
                canvas, width, height, density, dayOfYear, horizonY, midShiftY, foreShiftY,
                sky, weather, lightColor, lightStrength, shadowBase, isDay, windDir, steadyWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.GOLDEN_SAVANNA -> drawGoldenSavanna(
                canvas, width, height, density, horizonY, midShiftY, foreShiftY,
                lightColor, lightStrength, alpenglow, isDay, windDir, steadyWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.COASTAL_OCEAN -> drawCoastalOcean(
                canvas, width, height, density, horizonY, midShiftY,
                sky, weather, lightColor, shadowBase,
                if (sky.sunAltDeg > -4f) sunX else moonX, isDay, windDir, steadyWind, timeSec, masterAlpha
            )
            WorldBiome.SUNLIT_WOODLAND -> drawSunlitWoodland(
                canvas, width, height, density, dayOfYear, horizonY, midShiftY, foreShiftY,
                sky, circ, weather, lightColor, lightStrength, shadowBase,
                sunX, sunY, moonX, moonY, isDay, windDir, steadyWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.COASTAL_REEDS -> drawCoastalReeds(
                canvas, width, height, density, dayOfYear, horizonY, midShiftY,
                sky, circ, weather, lightColor, lightStrength, shadowBase,
                if (sky.sunAltDeg > -4f) sunX else moonX, isDay, windDir, steadyWind, timeSec, masterAlpha
            )
        }
    }

    private fun drawSandDunes(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        lightColor: Int, lightStrength: Float, alpenglow: Float,
        sunX: Float, isDay: Boolean, windDir: Float, steadyWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val sandAlbedo = 0xFFE69A28.toInt()
        val litSand = ColorUtils.blendARGB(sandAlbedo, lightColor, 0.45f)
        val deepShadow = if (isDay) 0xFF4A2511.toInt() else 0xFF0A0F1E.toInt()
        val xTilt = ((sunX / w.toFloat()) - 0.5f) * (w * 0.35f)

        fillPaint.shader = null
        fillPaint.color = ColorUtils.blendARGB(deepShadow, litSand, 0.25f * lightStrength)
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, horizonY - h * 0.01f, w.toFloat(), h.toFloat(), fillPaint)

        val layers = arrayOf(farPath to midShiftY * 0.6f, midPath to midShiftY, forePath to foreShiftY)
        for ((idx, pair) in layers.withIndex()) {
            val (path, sy) = pair
            val layerBlend = (0.42f + idx * 0.24f) * lightStrength
            val topCol = ColorUtils.blendARGB(deepShadow, litSand, layerBlend)
            val botCol = ColorUtils.blendARGB(deepShadow, topCol, 0.26f)
            fillPaint.shader = LinearGradient(
                w * 0.5f + xTilt, horizonY + sy - h * 0.04f,
                w * 0.5f - xTilt, h.toFloat(),
                intArrayOf(topCol, botCol), null, Shader.TileMode.CLAMP
            )
            fillPaint.alpha = masterAlpha
            canvas.save(); canvas.translate(0f, sy); canvas.drawPath(path, fillPaint); canvas.restore()
            fillPaint.shader = null
        }

        val wispRgb = (if (alpenglow > 0.15f) lightColor else litSand) and 0x00FFFFFF
        val wispAlpha = (masterAlpha * (0.07f + (steadyWind / 220f) + fogFactor * 0.08f)).toInt().coerceIn(0, 110)
        for (s in 0 until 3) {
            val prog = (((timeSec * (0.0045 + steadyWind * 0.00025) * windDir) + s * 0.33) % 1.0 + 1.0) % 1.0
            val sx = (prog.toFloat() * 1.3f - 0.15f) * w
            val sy = horizonY + foreShiftY + h * (0.04f + s * 0.08f)
            drawSoftMist(canvas, sx, sy, w * 0.28f, h * 0.018f, wispRgb or (wispAlpha shl 24))
        }

        drawThermalEagles(canvas, w, h, density, windDir, isDay, timeSec, masterAlpha)
    }

    private fun drawSnowyAlpineLake(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float,
        sky: SkySnapshot, circ: CircadianTheme, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int, alpenglow: Float,
        orbX: Float, isDay: Boolean, windDir: Float, steadyWind: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val rockAlbedo = 0xFF334155.toInt()
        val rockLit = ColorUtils.blendARGB(rockAlbedo, lightColor, 0.35f * lightStrength)

        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.14f, 0f, horizonY + midShiftY,
            intArrayOf(ColorUtils.blendARGB(rockLit, circ.horizonColor, 0.45f), ColorUtils.blendARGB(shadowBase, circ.horizonColor, 0.35f)),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.78f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        val tipRock = ColorUtils.blendARGB(rockAlbedo, lightColor, 0.60f * lightStrength)
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.14f, 0f, horizonY + midShiftY,
            intArrayOf(tipRock, shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(0f, midShiftY)
        canvas.drawPath(midPath, fillPaint)

        val frostBoost = ((16f - weather.tempC) / 20f).coerceIn(0f, 0.25f)
        val snowBase = ColorUtils.blendARGB(0xFFE2E8F0.toInt(), lightColor, if (alpenglow > 0.1f) 0.72f else (0.38f - frostBoost * 0.5f))
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.14f, 0f, horizonY - h * 0.03f,
            intArrayOf(snowBase, ColorUtils.blendARGB(snowBase, shadowBase, 0.75f)),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * (if (isDay) 0.88f else 0.72f)).toInt()
        canvas.drawPath(snowPath, fillPaint)
        canvas.restore()
        fillPaint.shader = null

        val shoreY = h * (0.86f + 0.02f * ((horizonY - h * 0.66f) / (h * 0.05f)))
        val lakeTop = ColorUtils.blendARGB(circ.horizonColor, 0xFF0F172A.toInt(), if (isDay) 0.35f else 0.78f)
        val lakeBot = if (isDay) ColorUtils.blendARGB(circ.primary, 0xFF0B1324.toInt(), 0.55f) else 0xFF020408.toInt()
        fillPaint.shader = LinearGradient(0f, horizonY, 0f, h.toFloat(), intArrayOf(lakeTop, lakeBot), null, Shader.TileMode.CLAMP)
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, horizonY, w.toFloat(), h.toFloat(), fillPaint)

        fillPaint.shader = null
        fillPaint.color = ColorUtils.blendARGB(tipRock, lakeTop, 0.45f)
        fillPaint.alpha = (masterAlpha * 0.24f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY); canvas.drawPath(lakeReflectPath, fillPaint); canvas.restore()

        drawWaterShimmer(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)
        drawThermalEagles(canvas, w, h, density, windDir, isDay, timeSec, masterAlpha)
    }

    private fun drawMistyForest(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        horizonY: Float, midShiftY: Float, foreShiftY: Float,
        sky: SkySnapshot, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int,
        isDay: Boolean, windDir: Float, steadyWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val seasonPhase = ((dayOfYear - 80) / 365.0) * 2.0 * PI
        val foliageAlbedo = when {
            sin(seasonPhase) > 0.35 -> 0xFF15803D.toInt()
            sin(seasonPhase) < -0.35 -> 0xFF0F766E.toInt()
            cos(seasonPhase) < 0.0 -> 0xFFB45309.toInt()
            else -> 0xFF166534.toInt()
        }

        val tipFoliage = ColorUtils.blendARGB(foliageAlbedo, lightColor, 0.42f * lightStrength)
        val mistRgb = (if (isDay) ColorUtils.blendARGB(Color.WHITE, lightColor, 0.25f) else 0xFF334155.toInt()) and 0x00FFFFFF

        // Far pale misty pines (upper spires emerge softly from valley fog)
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.14f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(tipFoliage, mistRgb or 0xFF000000.toInt(), 0.58f), shadowBase),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.65f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        // Heavy serene valley mist blanketing lower halves of far pines
        val mistAlpha = (masterAlpha * (0.34f + fogFactor * 0.24f)).toInt().coerceIn(0, 200)
        for (m in 0 until 2) {
            val mp = (((timeSec * (0.0024 + steadyWind * 0.00018) * windDir) + m * 0.5) % 1.0 + 1.0) % 1.0
            drawSoftMist(canvas, (mp.toFloat() * 1.4f - 0.2f) * w, horizonY + midShiftY * 0.8f + h * 0.015f, w * 0.68f, h * 0.055f, mistRgb or (mistAlpha shl 24))
        }

        // Mid slender pines
        val swayMid = sin(timeSec * 0.42).toFloat() * (density * (0.6f + steadyWind * 0.025f)) * windDir
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.06f, 0f, h.toFloat(),
            intArrayOf(tipFoliage, shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.90f).toInt()
        canvas.save(); canvas.translate(swayMid, midShiftY); canvas.drawPath(midPath, fillPaint); canvas.restore()

        // Second lighter valley mist ribbon between mid and foreground pines
        val midMistAlpha = (masterAlpha * (0.20f + fogFactor * 0.18f)).toInt().coerceIn(0, 150)
        drawSoftMist(canvas, w * 0.5f, horizonY + foreShiftY + h * 0.06f, w * 0.90f, h * 0.042f, mistRgb or (midMistAlpha shl 24))

        // Foreground pines (dark grounded silhouettes)
        val swayFore = sin(timeSec * 0.52 * 1.414).toFloat() * (density * (0.9f + steadyWind * 0.035f)) * windDir
        fillPaint.shader = LinearGradient(
            0f, horizonY + h * 0.03f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(tipFoliage, shadowBase, 0.50f), 0xFF030508.toInt()),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(swayFore, foreShiftY); canvas.drawPath(forePath, fillPaint); canvas.restore()
        fillPaint.shader = null

        // Warm-dusk Photinus fireflies
        if (weather.tempC >= 16f && sky.sunAltDeg in -10f..0.5f && weather.windKmh < 16f && weather.sky == SkyWeather.CLEAR) {
            for (f in 0 until 7) {
                val pulse = ((sin(timeSec * (1.2 + f * 0.19) + f * 2.1) + 1.0) * 0.5).toFloat()
                val glow = pulse * pulse * pulse
                if (glow > 0.08f) {
                    val fx = w * (0.14f + 0.72f * seedRand(dayOfYear + f, 5)) + sin(timeSec * 0.35 + f).toFloat() * (w * 0.025f)
                    val fy = horizonY + h * (0.04f + 0.14f * seedRand(dayOfYear + f, 6)) + cos(timeSec * 0.45 + f).toFloat() * (h * 0.015f)
                    val fA = (masterAlpha * 0.55f * glow).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, fx, fy, density * 6f, density * 6f, 0x00FDE047 or (fA shl 24))
                }
            }
        }

        // Daytime forest swallows gliding cleanly in open sky above the tallest spires
        if (isDay) {
            drawSwallows(canvas, w, h, density, windDir, timeSec, masterAlpha)
        }
    }

    private fun drawGoldenSavanna(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        lightColor: Int, lightStrength: Float, alpenglow: Float,
        isDay: Boolean, windDir: Float, steadyWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        // Lush Emerald / Sage Meadow (distinct from Sand Dunes)
        val meadowAlbedo = if (alpenglow > 0.25f) 0xFF65A30D.toInt() else 0xFF4D7C0F.toInt()
        val litGrass = ColorUtils.blendARGB(meadowAlbedo, lightColor, 0.40f)
        val deepGrass = if (isDay) 0xFF1A2E05.toInt() else 0xFF040A06.toInt()

        fillPaint.shader = null
        fillPaint.color = ColorUtils.blendARGB(deepGrass, litGrass, 0.28f * lightStrength)
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, horizonY - h * 0.01f, w.toFloat(), h.toFloat(), fillPaint)

        // Far misty sage-emerald plain
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.04f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(deepGrass, litGrass, 0.52f * lightStrength), deepGrass),
            null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        // Soft morning mist above meadow
        val mistAlpha = (masterAlpha * (0.14f + fogFactor * 0.16f)).toInt().coerceIn(0, 140)
        drawSoftMist(canvas, w * 0.5f, horizonY + midShiftY, w * 0.88f, h * 0.028f, (lightColor and 0x00FFFFFF) or (mistAlpha shl 24))

        // Mid emerald meadow + Y-forked multi-lobe Acacia silhouettes
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.04f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(deepGrass, litGrass, 0.34f * lightStrength), 0xFF071108.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(0f, midShiftY); canvas.drawPath(midPath, fillPaint); canvas.restore()

        // Foreground lush grass slope
        fillPaint.shader = LinearGradient(
            0f, horizonY + h * 0.10f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(deepGrass, litGrass, 0.45f * lightStrength), deepGrass),
            null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(0f, foreShiftY); canvas.drawPath(forePath, fillPaint); canvas.restore()
        fillPaint.shader = null

        drawThermalEagles(canvas, w, h, density, windDir, isDay, timeSec, masterAlpha)
    }

    private fun drawSunlitWoodland(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        horizonY: Float, midShiftY: Float, foreShiftY: Float,
        sky: SkySnapshot, circ: CircadianTheme, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int,
        sunX: Float, sunY: Float, moonX: Float, moonY: Float,
        isDay: Boolean, windDir: Float, steadyWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val mossAlbedo = 0xFF1E5631.toInt()
        val litMoss = ColorUtils.blendARGB(mossAlbedo, lightColor, 0.38f * lightStrength)
        val mistRgb = (if (isDay) ColorUtils.blendARGB(Color.WHITE, lightColor, 0.35f) else 0xFF334155.toInt()) and 0x00FFFFFF

        // 1. Far misty woodland trunks
        fillPaint.shader = null
        fillPaint.color = ColorUtils.blendARGB(litMoss, mistRgb or 0xFF000000.toInt(), 0.58f)
        fillPaint.alpha = (masterAlpha * 0.42f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.4f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        // 2. Soft low woodland mist
        val mistAlpha = (masterAlpha * (0.20f + fogFactor * 0.22f)).toInt().coerceIn(0, 175)
        drawSoftMist(canvas, w * 0.5f, horizonY + h * 0.08f, w * 0.95f, h * 0.10f, mistRgb or (mistAlpha shl 24))

        // 3. Mid woodland trunks
        fillPaint.color = ColorUtils.blendARGB(0xFF143321.toInt(), shadowBase, 0.55f)
        fillPaint.alpha = (masterAlpha * 0.72f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.7f); canvas.drawPath(midPath, fillPaint); canvas.restore()

        // 4. Live Volumetric God Rays streaming from live sunPos or moonPos
        val orbVisible = if (isDay) sky.sunAltDeg > -1f else sky.moonAltDeg > 1f
        if (orbVisible) {
            val ox = if (isDay) sunX else moonX
            val oy = (if (isDay) sunY else moonY).coerceIn(h * 0.04f, horizonY - h * 0.05f)
            val cloudScale = (1f - weather.cloudCoverPct / 135f).coerceIn(0.18f, 1f)
            val moonScale = if (isDay) 1f else (0.30f + 0.70f * sky.moonIllumination.coerceIn(0f, 1f))
            val rayBreath = 0.88f + 0.12f * sin(timeSec * 0.35).toFloat()
            val rayBaseCol = if (isDay) sky.sunCoronaColor else sky.moonColor
            val rayAlpha = (masterAlpha * (if (isDay) 0.18f else 0.11f) * cloudScale * moonScale * rayBreath).toInt().coerceIn(0, 255)

            if (rayAlpha > 2) {
                val sh = getRayShader((rayBaseCol and 0x00FFFFFF) or (rayAlpha shl 24))
                val rayLen = h * 0.85f
                shaderMatrix.reset()
                shaderMatrix.setScale(1f, rayLen)
                shaderMatrix.postTranslate(0f, oy)
                sh.setLocalMatrix(shaderMatrix)
                rayPaint.shader = sh

                val spreads = floatArrayOf(-0.34f, -0.15f, 0.02f, 0.18f, 0.35f)
                val widths = floatArrayOf(0.11f, 0.14f, 0.12f, 0.15f, 0.10f)
                for (r in spreads.indices) {
                    val targetX = w * 0.5f + w * spreads[r] + ((w * 0.5f - ox) * 0.22f)
                    val halfW = w * widths[r]
                    rayPath.reset()
                    rayPath.moveTo(ox - w * 0.012f, oy)
                    rayPath.lineTo(ox + w * 0.012f, oy)
                    rayPath.lineTo(targetX + halfW, oy + rayLen)
                    rayPath.lineTo(targetX - halfW, oy + rayLen)
                    rayPath.close()
                    canvas.drawPath(rayPath, rayPaint)
                }
                rayPaint.shader = null
            }
        }

        // 5. Foreground framing trunks & mossy forest floor
        fillPaint.shader = LinearGradient(
            0f, h * 0.55f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(0xFF0A1D12.toInt(), shadowBase, 0.45f), 0xFF030705.toInt()),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(0f, foreShiftY * 0.5f); canvas.drawPath(forePath, fillPaint); canvas.restore()
        fillPaint.shader = null

        // 6. Slowly swaying bottom grass & ferns (steady wind physics, zero allocations)
        val floorBaseY = h * 0.85f + foreShiftY * 0.5f
        val swayFreq = 0.72 + (steadyWind / 45.0).coerceIn(0.0, 0.45)
        val windLean = (steadyWind / 60f) * density * 8f * windDir
        strokePaint.color = ColorUtils.blendARGB(litMoss, 0xFF07140C.toInt(), 0.55f)
        strokePaint.alpha = (masterAlpha * 0.88f).toInt()
        strokePaint.strokeWidth = density * 1.8f
        for (b in 0 until 26) {
            val bx = w * (0.04f + 0.92f * seedRand(dayOfYear + b, 131))
            val by = floorBaseY + h * (0.02f + 0.11f * seedRand(dayOfYear + b, 137))
            val bh = h * (0.032f + 0.034f * seedRand(dayOfYear + b, 149))
            val sway = sin(timeSec * swayFreq + b * 0.65).toFloat() * (density * (2.5f + steadyWind * 0.10f)) + windLean
            floraPath.reset()
            floraPath.moveTo(bx, by)
            floraPath.quadTo(bx + sway * 0.35f, by - bh * 0.55f, bx + sway, by - bh)
            canvas.drawPath(floraPath, strokePaint)
        }

        // 7. Small woodland mushrooms (earthy-cream by day -> cyan-teal bioluminescent at night)
        val nightGlow = ((-sky.sunAltDeg - 0.5f) / 6f).coerceIn(0f, 1f)
        for (m in 0 until 6) {
            val mx = w * (0.14f + 0.72f * seedRand(dayOfYear + m, 157))
            val my = floorBaseY + h * (0.015f + 0.065f * seedRand(dayOfYear + m, 163))
            val capR = density * (4.2f + 2.4f * seedRand(dayOfYear + m, 167))

            if (nightGlow > 0.05f) {
                val breath = 0.78f + 0.22f * sin(timeSec * 1.15 + m * 1.4).toFloat()
                val glowA = (masterAlpha * 0.52f * nightGlow * breath).toInt().coerceIn(0, 255)
                drawSoftMist(canvas, mx, my - capR * 0.8f, capR * 3.4f, capR * 2.6f, 0x002DD4BF or (glowA shl 24))
            }

            // Mushroom stem
            strokePaint.color = if (nightGlow > 0.3f) 0xFF99F6E4.toInt() else ColorUtils.blendARGB(0xFFD6D3D1.toInt(), circ.horizonColor, 0.35f)
            strokePaint.alpha = (masterAlpha * 0.85f).toInt()
            strokePaint.strokeWidth = density * 1.6f
            canvas.drawLine(mx, my, mx + density * 0.8f, my - capR * 1.1f, strokePaint)

            // Mushroom dome cap
            fillPaint.color = if (nightGlow > 0.25f) {
                ColorUtils.blendARGB(0xFFCBD5E1.toInt(), 0xFF2DD4BF.toInt(), nightGlow)
            } else {
                ColorUtils.blendARGB(0xFFE7E5E4.toInt(), lightColor, 0.30f)
            }
            fillPaint.alpha = (masterAlpha * 0.92f).toInt()
            rect.set(mx - capR, my - capR * 1.75f, mx + capR, my - capR * 0.45f)
            canvas.drawArc(rect, 180f, 180f, true, fillPaint)
        }

        // 8. Daytime butterflies (tucked away in rain or at night)
        if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
            val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            for (bf in 0 until 4) {
                val bx = w * (0.22f + 0.56f * seedRand(dayOfYear + bf, 173)) + sin(timeSec * (0.28 + bf * 0.05) + bf * 1.9).toFloat() * (w * 0.10f)
                val by = h * (0.71f + 0.09f * seedRand(dayOfYear + bf, 179)) + sin(timeSec * 0.62 + bf * 2.3).toFloat() * (h * 0.024f)
                val wingSpan = density * (4.8f + (bf % 2) * 1.2f)
                val flutter = abs(sin(timeSec * 5.2 + bf * 1.7)).toFloat().coerceAtLeast(0.18f)
                val wingW = wingSpan * flutter
                fillPaint.color = if (bf % 2 == 0) ColorUtils.blendARGB(0xFFFBBF24.toInt(), sky.sunColor, 0.35f) else 0xFF7DD3FC.toInt()
                fillPaint.alpha = (masterAlpha * 0.82f * bfVis).toInt().coerceIn(0, 255)
                floraPath.reset()
                floraPath.moveTo(bx, by)
                floraPath.quadTo(bx - wingW, by - wingSpan * 0.85f, bx - wingW * 0.85f, by + wingSpan * 0.25f)
                floraPath.close()
                floraPath.moveTo(bx, by)
                floraPath.quadTo(bx + wingW, by - wingSpan * 0.85f, bx + wingW * 0.85f, by + wingSpan * 0.25f)
                floraPath.close()
                canvas.drawPath(floraPath, fillPaint)
            }
        }

        // 9. Nighttime Photinus fireflies drifting between the woodland trunks
        if (!isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
            for (f in 0 until 9) {
                val pulse = ((sin(timeSec * (1.1 + f * 0.17) + f * 2.3) + 1.0) * 0.5).toFloat()
                val glow = pulse * pulse * pulse
                if (glow > 0.08f) {
                    val fx = w * (0.16f + 0.68f * seedRand(dayOfYear + f, 191)) + sin(timeSec * 0.32 + f).toFloat() * (w * 0.035f)
                    val fy = h * (0.58f + 0.26f * seedRand(dayOfYear + f, 193)) + cos(timeSec * 0.41 + f).toFloat() * (h * 0.022f)
                    val fA = (masterAlpha * 0.65f * glow).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, fx, fy, density * 6.5f, density * 6.5f, 0x00FDE047 or (fA shl 24))
                }
            }
        }
    }

    private fun drawCoastalOcean(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float,
        sky: SkySnapshot, weather: WeatherState,
        lightColor: Int, shadowBase: Int, orbX: Float, isDay: Boolean,
        windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.07f, 0f, horizonY + 4f,
            intArrayOf(ColorUtils.blendARGB(0xFF1E293B.toInt(), lightColor, 0.25f), shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.72f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.5f); canvas.drawPath(farPath, fillPaint); canvas.restore()
        fillPaint.shader = null

        val shoreY = h * (0.85f + 0.025f * ((horizonY - h * 0.66f) / (h * 0.05f)) - sky.tideFrac * 0.018f)
        drawWaterShimmer(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

        val isBiolum = !isDay && sky.moonIllumination < 0.08f && weather.sky == SkyWeather.CLEAR
        val swellRgb = if (isDay) 0x00E0F2FE else (if (isBiolum) 0x002DD4BF else 0x0094A3B8)
        val foamRgb = if (isDay) 0x00F8FAFC else (if (isBiolum) 0x005EEAD4 else 0x00CBD5E1)
        val surfEnergy = (0.76f + (steadyWind / 36f) + (sky.tideFrac * 0.15f)).coerceIn(0.72f, 1.45f)
        val waveSpeed = 0.024 + (steadyWind / 800.0).coerceIn(0.0, 0.015)
        val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)

        val tideBreath = 0.85f + 0.15f * sin(timeSec * 0.45).toFloat()
        val tideAlpha = (masterAlpha * (if (isDay) 0.14f else 0.09f) * tideBreath).toInt().coerceIn(0, 255)
        drawSoftMist(canvas, w * 0.5f, shoreY + h * 0.004f, w * 0.78f, h * 0.011f, foamRgb or (tideAlpha shl 24))

        for (wIdx in 0 until 3) {
            val waveProg = (((timeSec * waveSpeed) + wIdx / 3.0) % 1.0).toFloat()
            val persp = waveProg * sqrt(waveProg)
            val wy = horizonY + seaSpan * (0.34f + persp * 0.66f)
            val env = sin(waveProg * PI).toFloat()
            val driftX = sin(timeSec * 0.16 * 1.618 + wIdx * 2.1).toFloat() * (w * 0.05f)
            val cx = w * (0.44f + (wIdx % 2) * 0.12f) + driftX
            val halfW = w * (0.30f + 0.12f * waveProg)
            val bandH = h * (0.006f + 0.008f * waveProg)
            val glowAlpha = (masterAlpha * env * (if (isDay) 0.17f else 0.10f) * surfEnergy).toInt().coerceIn(0, 255)
            if (glowAlpha > 2) {
                drawSoftMist(canvas, cx, wy, halfW, bandH, swellRgb or (glowAlpha shl 24))
            }
            if (waveProg > 0.66f) {
                val washFrac = ((waveProg - 0.66f) / 0.34f).coerceIn(0f, 1f)
                val washEnv = sin(washFrac * PI).toFloat()
                val washY = shoreY + h * (0.009f * washEnv)
                val washAlpha = (masterAlpha * washEnv * (if (isDay) 0.22f else 0.12f) * surfEnergy).toInt().coerceIn(0, 255)
                if (washAlpha > 2) {
                    drawSoftMist(canvas, cx, washY, halfW * 1.12f, h * (0.008f + 0.008f * washEnv), foamRgb or (washAlpha shl 24))
                }
            }
        }

        val boatSpeed = (0.0018 + (steadyWind / 60.0).coerceIn(0.0, 1.0) * 0.0010) * windDir
        val boatX = ((((0.20f + (timeSec * boatSpeed).toFloat()) % 1.16f) + 1.16f) % 1.16f - 0.08f) * w
        val boatBob = sin(timeSec * 1.1).toFloat() * (density * 1.4f)
        val boatY = horizonY + h * 0.013f + boatBob
        fillPaint.color = if (isDay) 0xFF0F172A.toInt() else 0xFF020617.toInt()
        fillPaint.alpha = (masterAlpha * 0.60f).toInt()
        val bw = w * 0.025f
        val bh = w * 0.021f
        rect.set(boatX - bw * 0.5f, boatY, boatX + bw * 0.5f, boatY + density * 2.2f)
        canvas.drawRoundRect(rect, density, density, fillPaint)
        birdPath.reset()
        birdPath.moveTo(boatX - bw * 0.05f, boatY)
        birdPath.lineTo(boatX - bw * 0.05f, boatY - bh)
        birdPath.lineTo(boatX + bw * 0.42f * windDir, boatY - density * 1.5f)
        birdPath.close()
        canvas.drawPath(birdPath, fillPaint)

        if (sky.sunAltDeg > -2f) {
            val dayFactor = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            strokePaint.strokeWidth = density * 1.15f
            strokePaint.color = 0xFF1E293B.toInt()
            for (g in 0 until 4) {
                val gx = ((((0.22f + g * 0.055f + (timeSec * 0.0085 * windDir).toFloat()) % 1.28f) + 1.28f) % 1.28f - 0.14f) * w
                val gy = h * (0.29f + (g % 2) * 0.018f + (g / 2) * 0.024f) - sin(timeSec * 0.8 + g * 0.9).toFloat() * (h * 0.007f)
                val span = w * (0.014f - g * 0.0018f)
                val flap = sin(timeSec * 1.8 + g * 1.3).toFloat() * (span * 0.34f)
                strokePaint.alpha = (masterAlpha * (0.42f - g * 0.05f) * dayFactor).toInt().coerceIn(0, 255)
                canvas.drawLine(gx - span, gy - flap, gx, gy + span * 0.22f, strokePaint)
                canvas.drawLine(gx, gy + span * 0.22f, gx + span, gy - flap, strokePaint)
            }
        }
    }


    private fun drawCoastalReeds(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        horizonY: Float, midShiftY: Float,
        sky: SkySnapshot, circ: CircadianTheme, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int,
        orbX: Float, isDay: Boolean, windDir: Float, steadyWind: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        // 1. Distant low coastal headland
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.05f, 0f, horizonY + 4f,
            intArrayOf(ColorUtils.blendARGB(0xFF1E293B.toInt(), lightColor, 0.25f), shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.65f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.5f); canvas.drawPath(farPath, fillPaint); canvas.restore()
        fillPaint.shader = null

        // 2. Shimmering coastal water + sun/moon reflection column seen behind the reeds
        val shoreY = h * 0.94f
        drawWaterShimmer(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

        // 3. Soft foreground shore tint at the very bottom
        val bankColor = if (isDay) ColorUtils.blendARGB(0xFF1C1917.toInt(), lightColor, 0.22f * lightStrength) else 0xFF04060A.toInt()
        fillPaint.shader = LinearGradient(0f, h * 0.88f, 0f, h.toFloat(), intArrayOf(Color.TRANSPARENT, bankColor), null, Shader.TileMode.CLAMP)
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, h * 0.88f, w.toFloat(), h.toFloat(), fillPaint)
        fillPaint.shader = null

        // 4. Tall feathery coastal reeds & sea-oat plumes swaying slowly in steady wind (leaves center reflection open)
        val swayFreq = 0.68 + (steadyWind / 50.0).coerceIn(0.0, 0.40)
        val windLean = (steadyWind / 55f) * w * 0.045f * windDir
        val stemCol = if (isDay) ColorUtils.blendARGB(0xFF1F2418.toInt(), lightColor, 0.28f * lightStrength) else 0xFF070B12.toInt()
        val plumeCol = if (isDay) ColorUtils.blendARGB(0xFFD6B98C.toInt(), lightColor, 0.58f) else ColorUtils.blendARGB(0xFF334155.toInt(), sky.moonColor, 0.30f)

        for (r in 0 until 16) {
            val sideLeft = (r % 2 == 0)
            val rxFrac = if (sideLeft) (0.03f + 0.33f * seedRand(dayOfYear + r, 211)) else (0.64f + 0.33f * seedRand(dayOfYear + r, 211))
            val bx = rxFrac * w
            val by = h * (0.98f + 0.04f * seedRand(dayOfYear + r, 213))
            val reedH = h * (0.24f + 0.16f * seedRand(dayOfYear + r, 219))
            val sway = sin(timeSec * swayFreq + r * 0.72).toFloat() * (density * (5.5f + steadyWind * 0.18f)) + windLean * (0.65f + 0.35f * seedRand(dayOfYear + r, 223))
            val tipX = bx + sway
            val tipY = by - reedH

            // Slender curving reed stem
            strokePaint.color = stemCol
            strokePaint.alpha = (masterAlpha * 0.88f).toInt()
            strokePaint.strokeWidth = density * (1.4f + 0.6f * seedRand(dayOfYear + r, 227))
            floraPath.reset()
            floraPath.moveTo(bx, by)
            floraPath.quadTo(bx + sway * 0.30f, by - reedH * 0.52f, tipX, tipY)
            canvas.drawPath(floraPath, strokePaint)

            // Feathery sea-oat plume along upper 22% of the reed
            val plumeLen = reedH * 0.22f
            val plumeW = density * (4.2f + 2.0f * seedRand(dayOfYear + r, 229))
            fillPaint.color = plumeCol
            fillPaint.alpha = (masterAlpha * (if (isDay) 0.78f else 0.52f)).toInt()
            for (p in 0 until 4) {
                val frac = p / 3.5f
                val px = tipX - sway * (frac * 0.16f)
                val py = tipY + plumeLen * frac
                val rx = plumeW * (0.55f + 0.45f * frac)
                val ry = plumeLen * 0.28f
                rect.set(px - rx, py - ry, px + rx, py + ry)
                canvas.drawOval(rect, fillPaint)
            }
        }

        // 5. Delicate coastal wildflowers swaying gently in the foreground
        for (fl in 0 until 6) {
            val sideLeft = (fl % 2 == 0)
            val fxFrac = if (sideLeft) (0.08f + 0.26f * seedRand(dayOfYear + fl, 241)) else (0.66f + 0.26f * seedRand(dayOfYear + fl, 241))
            val bx = fxFrac * w
            val by = h * (0.97f + 0.03f * seedRand(dayOfYear + fl, 247))
            val fh = h * (0.14f + 0.09f * seedRand(dayOfYear + fl, 251))
            val sway = sin(timeSec * (swayFreq * 1.15) + fl * 1.1).toFloat() * (density * (4.5f + steadyWind * 0.14f)) + windLean * 0.55f
            val tx = bx + sway
            val ty = by - fh

            strokePaint.color = stemCol
            strokePaint.alpha = (masterAlpha * 0.85f).toInt()
            strokePaint.strokeWidth = density * 1.2f
            floraPath.reset()
            floraPath.moveTo(bx, by)
            floraPath.quadTo(bx + sway * 0.35f, by - fh * 0.55f, tx, ty)
            canvas.drawPath(floraPath, strokePaint)

            val bloomCol = if (fl % 2 == 0) ColorUtils.blendARGB(0xFFF472B6.toInt(), lightColor, 0.25f) else ColorUtils.blendARGB(0xFFFBBF24.toInt(), lightColor, 0.30f)
            fillPaint.color = if (isDay) bloomCol else ColorUtils.blendARGB(bloomCol, 0xFF1E293B.toInt(), 0.65f)
            fillPaint.alpha = (masterAlpha * (if (isDay) 0.90f else 0.48f)).toInt()
            val br = density * (4.2f + 1.5f * (fl % 2))
            canvas.drawCircle(tx, ty, br, fillPaint)
        }

        // 6. 2 unhurried daytime butterflies drifting between the blooms
        if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
            val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            for (bf in 0 until 2) {
                val baseX = if (bf == 0) w * 0.24f else w * 0.76f
                val bx = baseX + sin(timeSec * (0.26 + bf * 0.06) + bf * 2.1).toFloat() * (w * 0.11f)
                val by = h * (0.74f + bf * 0.06f) + sin(timeSec * 0.58 + bf * 1.8).toFloat() * (h * 0.024f)
                val wingSpan = density * (5.2f + bf * 0.8f)
                val flutter = abs(sin(timeSec * 4.8 + bf * 1.5)).toFloat().coerceAtLeast(0.18f)
                val wingW = wingSpan * flutter
                fillPaint.color = if (bf == 0) ColorUtils.blendARGB(0xFFFBBF24.toInt(), sky.sunColor, 0.35f) else 0xFFBAE6FD.toInt()
                fillPaint.alpha = (masterAlpha * 0.86f * bfVis).toInt().coerceIn(0, 255)
                floraPath.reset()
                floraPath.moveTo(bx, by)
                floraPath.quadTo(bx - wingW, by - wingSpan * 0.85f, bx - wingW * 0.85f, by + wingSpan * 0.25f)
                floraPath.close()
                floraPath.moveTo(bx, by)
                floraPath.quadTo(bx + wingW, by - wingSpan * 0.85f, bx + wingW * 0.85f, by + wingSpan * 0.25f)
                floraPath.close()
                canvas.drawPath(floraPath, fillPaint)
            }
        }
    }

    private fun drawWaterShimmer(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, shoreY: Float,
        sky: SkySnapshot, weather: WeatherState, orbX: Float, isDay: Boolean,
        steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)
        val shimRgb = if (sky.sunAltDeg > -4f) (sky.sunColor and 0x00FFFFFF) else (sky.moonColor and 0x00FFFFFF)
        val lumBoost = if (sky.sunAltDeg > -4f) 1.0f else (0.55f + 0.45f * sky.moonIllumination.coerceIn(0f, 1f))
        val windScale = (0.75f + (steadyWind / 28f)).coerceIn(0.75f, 1.8f)
        val shimBreath = 0.90f + 0.10f * sin(timeSec * 0.45).toFloat()
        val upperAlpha = (masterAlpha * (if (isDay) 0.22f else 0.16f) * lumBoost * shimBreath).toInt().coerceIn(0, 255)
        val lowerAlpha = (masterAlpha * (if (isDay) 0.12f else 0.09f) * lumBoost * shimBreath).toInt().coerceIn(0, 255)
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.24f, w * 0.085f * windScale, seaSpan * 0.30f, shimRgb or (upperAlpha shl 24))
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.56f, w * 0.155f * windScale, seaSpan * 0.44f, shimRgb or (lowerAlpha shl 24))

        for (gIdx in 0 until 4) {
            val fracY = 0.12f + gIdx * 0.19f
            val gy = horizonY + seaSpan * fracY
            val wavePhase = timeSec * (0.45 + gIdx * 0.12) * 1.618 + gIdx * 1.9
            val glintVis = ((sin(wavePhase) + 1.0) * 0.5).toFloat()
            val spreadX = sin(timeSec * 0.28 * 1.4142 + gIdx * 1.7).toFloat() * (w * (0.018f + 0.022f * fracY) * windScale)
            val rx = w * (0.038f + 0.032f * fracY) * windScale
            val ry = seaSpan * (0.045f + 0.035f * fracY)
            val gAlpha = (masterAlpha * (0.14f - 0.07f * fracY) * lumBoost * (0.45f + 0.55f * glintVis)).toInt().coerceIn(0, 255)
            if (gAlpha > 2) {
                drawSoftMist(canvas, orbX + spreadX, gy, rx, ry, shimRgb or (gAlpha shl 24))
            }
        }

        if (weather.sky == SkyWeather.RAIN || weather.sky == SkyWeather.THUNDERSTORM) {
            strokePaint.strokeWidth = density * 0.8f
            strokePaint.color = 0xFFBAE6FD.toInt()
            for (rp in 0 until 4) {
                val hRp = abs(sin(rp * 12.9898 + 1.3)).toFloat()
                val rpProg = (((timeSec * (0.50 + hRp * 0.20)) + rp * 0.25 + hRp * 0.31) % 1.0).toFloat()
                val rx = w * (0.12f + 0.76f * hRp)
                val ry = horizonY + seaSpan * (0.22f + 0.54f * abs(sin(rp * 78.233)).toFloat())
                val rad = w * (0.01f + rpProg * 0.035f)
                strokePaint.alpha = (masterAlpha * 0.24f * (1f - rpProg)).toInt().coerceIn(0, 255)
                rect.set(rx - rad, ry - rad * 0.28f, rx + rad, ry + rad * 0.28f)
                canvas.drawOval(rect, strokePaint)
            }
        }
    }

    private fun drawThermalEagles(
        canvas: Canvas, w: Int, h: Int, density: Float,
        windDir: Float, isDay: Boolean, timeSec: Double, masterAlpha: Int
    ) {
        if (!isDay) return
        strokePaint.strokeWidth = density * 1.35f
        strokePaint.color = 0xFF1E293B.toInt()
        for (e in 0 until 2) {
            val ex = w * (0.34f + e * 0.32f) + sin(timeSec * (0.16 + e * 0.04) + e * 2.0).toFloat() * (w * 0.14f) * windDir
            val ey = h * (0.24f + e * 0.08f) + cos(timeSec * 0.22 + e * 1.4).toFloat() * (h * 0.018f)
            val span = w * (0.022f - e * 0.004f)
            val glide = sin(timeSec * 0.55 + e * 1.7).toFloat() * (span * 0.16f)
            strokePaint.alpha = (masterAlpha * (0.48f - e * 0.10f)).toInt().coerceIn(0, 255)
            canvas.drawLine(ex - span, ey - glide, ex, ey + span * 0.15f, strokePaint)
            canvas.drawLine(ex, ey + span * 0.15f, ex + span, ey - glide, strokePaint)
        }
    }

    private fun drawSwallows(
        canvas: Canvas, w: Int, h: Int, density: Float,
        windDir: Float, timeSec: Double, masterAlpha: Int
    ) {
        strokePaint.strokeWidth = density * 1.0f
        strokePaint.color = 0xFF0F172A.toInt()
        for (s in 0 until 4) {
            val sx = ((((0.18f + s * 0.06f + (timeSec * 0.009 * windDir).toFloat()) % 1.25f) + 1.25f) % 1.25f - 0.12f) * w
            val sy = h * (0.26f + (s % 2) * 0.025f + (s / 2) * 0.020f) + sin(timeSec * 1.4 + s * 1.1).toFloat() * (h * 0.009f)
            val span = w * 0.011f
            val flap = sin(timeSec * 2.1 + s * 1.4).toFloat() * (span * 0.38f)
            strokePaint.alpha = (masterAlpha * 0.42f).toInt().coerceIn(0, 255)
            canvas.drawLine(sx - span, sy - flap, sx, sy + span * 0.2f, strokePaint)
            canvas.drawLine(sx, sy + span * 0.2f, sx + span, sy - flap, strokePaint)
        }
    }
}
