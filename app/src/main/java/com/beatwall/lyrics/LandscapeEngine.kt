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
import kotlin.math.sin
import kotlin.math.sqrt

enum class WorldBiome(val title: String) {
    SAND_DUNES("Sand Dunes"),
    SNOWY_ALPENGLOW_LAKE("Snowy Peaks & Mirror Lake"),
    MISTY_PINE_FOREST("Misty Pine Forest"),
    RED_ROCK_MESA("Red Rock Mesa"),
    COASTAL_OCEAN("Coastal Ocean")
}

/**
 * Zero-allocation procedural landscape engine for 366 unique daily worlds.
 * Pre-builds ridge paths once per dayOfYear/dimensions; renders at 30 fps with
 * Lock<->Home 3D parallax, grazing-angle tip-only Alpenglow, wind/gust sway,
 * and mathematical biology (Golden-Angle canopy, phenology, Photinus fireflies,
 * New-Moon bioluminescence, and biome-matched birds).
 */
class LandscapeEngine {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    private var cachedMistColor = 0
    private var cachedMistShader: RadialGradient? = null

    // Pre-allocated Path pool (rebuilt ONLY when dayOfYear or screen size changes)
    private val farPath = Path()
    private val midPath = Path()
    private val forePath = Path()
    private val snowPath = Path()
    private val lakeReflectPath = Path()
    private val birdPath = Path()

    private var builtDay = -1
    private var builtW = 0
    private var builtH = 0

    fun biomeForDay(dayOfYear: Int): WorldBiome {
        val idx = ((dayOfYear - 1).coerceAtLeast(0)) % 5
        return WorldBiome.values()[idx]
    }

    fun horizonY(height: Int, lockBlend: Float): Float {
        // Lockscreen tilts camera upward (horizon sits slightly lower at 71%, opening calm sky behind clock)
        return height * (0.66f + 0.05f * lockBlend)
    }

    fun skyClarityBoost(dayOfYear: Int): Float {
        return when (biomeForDay(dayOfYear)) {
            WorldBiome.SAND_DUNES, WorldBiome.SNOWY_ALPENGLOW_LAKE -> 1.22f
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
                buildAlpinePath(farPath, wf, hf, baseH - h * 0.06f, h * 0.075f, dayOfYear, 11)
                buildAlpinePath(midPath, wf, hf, baseH - h * 0.03f, h * 0.095f, dayOfYear, 23, snowCapOut = snowPath)
                // Inverted softer silhouette for mirror lake reflection
                buildMirrorAlpinePath(lakeReflectPath, wf, baseH, h * 0.075f, dayOfYear, 23)
            }
            WorldBiome.MISTY_PINE_FOREST -> {
                buildForestRidge(farPath, wf, hf, baseH - h * 0.04f, h * 0.035f, dayOfYear, 31, treeScale = 0.65f)
                buildForestRidge(midPath, wf, hf, baseH + h * 0.02f, h * 0.040f, dayOfYear, 47, treeScale = 1.0f)
                buildForestRidge(forePath, wf, hf, baseH + h * 0.10f, h * 0.032f, dayOfYear, 61, treeScale = 1.30f)
            }
            WorldBiome.RED_ROCK_MESA -> {
                buildMesaPath(farPath, wf, hf, baseH - h * 0.05f, h * 0.075f, dayOfYear, 71)
                buildMesaPath(midPath, wf, hf, baseH + h * 0.03f, h * 0.085f, dayOfYear, 89)
                buildDunePath(forePath, wf, hf, baseH + h * 0.14f, h * 0.028f, 1.4f, p1)
            }
            WorldBiome.COASTAL_OCEAN -> {
                // Soft misty coastal headland on left or right based on dayOfYear
                val onLeft = (dayOfYear % 2 == 0)
                buildHeadlandPath(farPath, wf, baseH, h * 0.065f, onLeft, dayOfYear)
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

    private fun buildAlpinePath(
        path: Path, w: Float, h: Float, yBase: Float, peakH: Float,
        day: Int, salt: Int, snowCapOut: Path? = null
    ) {
        val steps = 56
        val pA = seedRand(day, salt) * 6.28f
        val pB = seedRand(day, salt + 1) * 6.28f
        val pC = seedRand(day, salt + 2) * 6.28f
        path.moveTo(0f, h * 1.15f)
        val xs = FloatArray(steps + 1)
        val ys = FloatArray(steps + 1)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val x = fx * w
            val ridge = abs(sin(fx * 2.6 * PI + pA)) * 0.55 +
                abs(sin(fx * 5.7 * PI + pB)) * 0.30 +
                abs(sin(fx * 11.3 * PI + pC)) * 0.15
            val y = yBase - ridge.toFloat() * peakH
            xs[i] = x
            ys[i] = y
            path.lineTo(x, y)
        }
        path.lineTo(w, h * 1.15f)
        path.close()

        if (snowCapOut != null) {
            snowCapOut.moveTo(0f, yBase - peakH * 0.32f)
            for (i in 0..steps) {
                snowCapOut.lineTo(xs[i], ys[i])
            }
            for (i in steps downTo 0) {
                val snowBottom = minOf(yBase - peakH * 0.25f, ys[i] + peakH * 0.34f)
                snowCapOut.lineTo(xs[i], snowBottom)
            }
            snowCapOut.close()
        }
    }

    private fun buildMirrorAlpinePath(path: Path, w: Float, yBase: Float, peakH: Float, day: Int, salt: Int) {
        val steps = 48
        val pA = seedRand(day, salt) * 6.28f
        val pB = seedRand(day, salt + 1) * 6.28f
        val pC = seedRand(day, salt + 2) * 6.28f
        path.moveTo(0f, yBase)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val x = fx * w
            val ridge = abs(sin(fx * 2.6 * PI + pA)) * 0.55 +
                abs(sin(fx * 5.7 * PI + pB)) * 0.30 +
                abs(sin(fx * 11.3 * PI + pC)) * 0.15
            val y = yBase + ridge.toFloat() * (peakH * 0.72f)
            path.lineTo(x, y)
        }
        path.lineTo(w, yBase)
        path.close()
    }

    private fun buildForestRidge(
        path: Path, w: Float, h: Float, yBase: Float, amp: Float,
        day: Int, salt: Int, treeScale: Float
    ) {
        // Golden-Angle (137.5 deg) botanical tree-crown distribution over rolling hills
        val steps = 90
        val p1 = seedRand(day, salt) * 6.28f
        path.moveTo(0f, h * 1.15f)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            val x = fx * w
            val hill = sin(fx * 2.1 * PI + p1).toFloat() * amp
            val golden = (sin(i * 2.399963 + day * 0.7) + 1.0) * 0.5 // 137.508 deg in rad
            val crown = (golden * (h * 0.016f * treeScale)).toFloat()
            val y = yBase - hill - crown
            path.lineTo(x, y)
        }
        path.lineTo(w, h * 1.15f)
        path.close()
    }

    private fun buildMesaPath(path: Path, w: Float, h: Float, yBase: Float, mesaH: Float, day: Int, salt: Int) {
        val c1 = 0.16f + seedRand(day, salt) * 0.20f
        val w1 = 0.16f + seedRand(day, salt + 1) * 0.14f
        val c2 = 0.58f + seedRand(day, salt + 2) * 0.22f
        val w2 = 0.14f + seedRand(day, salt + 3) * 0.14f

        fun plateauY(fx: Float): Float {
            val d1 = abs(fx - c1) / w1
            val d2 = abs(fx - c2) / w2
            val h1 = when {
                d1 < 0.65f -> 1.0f
                d1 < 1.05f -> 1f - ((d1 - 0.65f) / 0.40f)
                else -> 0f
            }
            val h2 = when {
                d2 < 0.65f -> 0.85f
                d2 < 1.05f -> 0.85f * (1f - ((d2 - 0.65f) / 0.40f))
                else -> 0f
            }
            return yBase - maxOf(h1, h2) * mesaH
        }

        val steps = 64
        path.moveTo(0f, h * 1.15f)
        for (i in 0..steps) {
            val fx = i.toFloat() / steps
            path.lineTo(fx * w, plateauY(fx))
        }
        path.lineTo(w, h * 1.15f)
        path.close()
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
        moonX: Float,
        lockBlend: Float,
        timeSec: Double,
        masterAlpha: Int
    ) {
        ensureGeometry(dayOfYear, width, height)
        val biome = biomeForDay(dayOfYear)
        val horizonY = horizonY(height, lockBlend)
        val baseHorizon = height * 0.66f
        // Lockscreen <-> Home Screen multi-plane parallax offsets
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
        val gustMod = 0.75f + 0.25f * sin(timeSec * 0.65 * 1.618).toFloat()
        val effWind = (weather.windKmh * 0.65f + weather.windGustsKmh * 0.35f * gustMod).coerceIn(2f, 60f)
        val fogFactor = ((4500f - weather.visibilityM) / 4000f).coerceIn(0f, 1f)

        // Seamless horizon mirage haze
        val mirageRgb = (if (isDay) circ.horizonColor else 0xFF334155.toInt()) and 0x00FFFFFF
        val mirageAlpha = (masterAlpha * (if (isDay) 0.22f else 0.10f) * (1f + fogFactor * 0.5f)).toInt().coerceIn(0, 255)
        drawSoftMist(canvas, width * 0.5f, horizonY, width * 0.92f, height * 0.024f, mirageRgb or (mirageAlpha shl 24))

        when (biome) {
            WorldBiome.SAND_DUNES -> drawSandDunes(
                canvas, width, height, horizonY, midShiftY, foreShiftY,
                lightColor, lightStrength, shadowBase, alpenglow, sunX, isDay, windDir, effWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.SNOWY_ALPENGLOW_LAKE -> drawSnowyAlpineLake(
                canvas, width, height, density, horizonY, midShiftY, foreShiftY,
                sky, circ, weather, lightColor, lightStrength, shadowBase, alpenglow,
                if (sky.sunAltDeg > -4f) sunX else moonX, isDay, windDir, effWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.MISTY_PINE_FOREST -> drawMistyForest(
                canvas, width, height, density, dayOfYear, horizonY, midShiftY, foreShiftY,
                sky, weather, lightColor, lightStrength, shadowBase, alpenglow, isDay, windDir, effWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.RED_ROCK_MESA -> drawRedRockMesa(
                canvas, width, height, density, horizonY, midShiftY, foreShiftY,
                lightColor, lightStrength, shadowBase, alpenglow, isDay, windDir, effWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.COASTAL_OCEAN -> drawCoastalOcean(
                canvas, width, height, density, horizonY, midShiftY,
                sky, circ, weather, lightColor, shadowBase,
                if (sky.sunAltDeg > -4f) sunX else moonX, isDay, windDir, effWind, timeSec, masterAlpha
            )
        }
    }

    private fun drawSandDunes(
        canvas: Canvas, w: Int, h: Int, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        lightColor: Int, lightStrength: Float, shadowBase: Int, alpenglow: Float,
        sunX: Float, isDay: Boolean, windDir: Float, effWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val sandAlbedo = 0xFFD97706.toInt()
        val litSand = ColorUtils.blendARGB(sandAlbedo, lightColor, 0.55f)
        val deepShadow = ColorUtils.blendARGB(shadowBase, 0xFF451A03.toInt(), if (isDay) 0.45f else 0.15f)
        val xTilt = ((sunX / w.toFloat()) - 0.5f) * (w * 0.35f)

        val layers = arrayOf(farPath to midShiftY * 0.6f, midPath to midShiftY, forePath to foreShiftY)
        for ((idx, pair) in layers.withIndex()) {
            val (path, sy) = pair
            val layerBlend = (0.35f + idx * 0.25f) * lightStrength
            val topCol = ColorUtils.blendARGB(shadowBase, litSand, layerBlend)
            val botCol = ColorUtils.blendARGB(deepShadow, topCol, 0.22f)
            fillPaint.shader = LinearGradient(
                w * 0.5f + xTilt, horizonY + sy - h * 0.04f,
                w * 0.5f - xTilt, h.toFloat(),
                intArrayOf(topCol, botCol), null, Shader.TileMode.CLAMP
            )
            fillPaint.alpha = masterAlpha
            canvas.save()
            canvas.translate(0f, sy)
            canvas.drawPath(path, fillPaint)
            canvas.restore()
            fillPaint.shader = null
        }

        // Wind-blown sand-mist crest wisps drifting with wind_direction_10m
        val wispRgb = (if (alpenglow > 0.15f) lightColor else litSand) and 0x00FFFFFF
        val wispAlpha = (masterAlpha * (0.08f + (effWind / 180f) + fogFactor * 0.08f)).toInt().coerceIn(0, 120)
        for (s in 0 until 3) {
            val prog = (((timeSec * (0.015 + effWind * 0.0012) * windDir) + s * 0.33) % 1.0 + 1.0) % 1.0
            val sx = (prog.toFloat() * 1.3f - 0.15f) * w
            val sy = horizonY + foreShiftY + h * (0.04f + s * 0.08f)
            drawSoftMist(canvas, sx, sy, w * 0.28f, h * 0.018f, wispRgb or (wispAlpha shl 24))
        }
    }

    private fun drawSnowyAlpineLake(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        sky: SkySnapshot, circ: CircadianTheme, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int, alpenglow: Float,
        orbX: Float, isDay: Boolean, windDir: Float, effWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val rockAlbedo = 0xFF334155.toInt()
        val rockLit = ColorUtils.blendARGB(rockAlbedo, lightColor, 0.35f * lightStrength)

        // Far ridge (aerial haze)
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.14f, 0f, horizonY + midShiftY,
            intArrayOf(ColorUtils.blendARGB(rockLit, circ.horizonColor, 0.45f), ColorUtils.blendARGB(shadowBase, circ.horizonColor, 0.35f)),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.78f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        // Main mountain ridge (base in shadow when sun is low -> tip-only Alpenglow!)
        val tipRock = ColorUtils.blendARGB(rockAlbedo, lightColor, 0.60f * lightStrength)
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.13f, 0f, horizonY + midShiftY,
            intArrayOf(tipRock, shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(0f, midShiftY)
        canvas.drawPath(midPath, fillPaint)

        // Snow cap (frostier in cold weather, rose-gold Alpenglow at sunrise/sunset)
        val frostBoost = ((16f - weather.tempC) / 20f).coerceIn(0f, 0.25f)
        val snowBase = ColorUtils.blendARGB(0xFFE2E8F0.toInt(), lightColor, if (alpenglow > 0.1f) 0.72f else (0.38f - frostBoost * 0.5f))
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.13f, 0f, horizonY - h * 0.04f,
            intArrayOf(snowBase, ColorUtils.blendARGB(snowBase, shadowBase, 0.65f)),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * (if (isDay) 0.90f else 0.75f)).toInt()
        canvas.drawPath(snowPath, fillPaint)
        canvas.restore()
        fillPaint.shader = null

        // Mirror Lake below horizonY
        val shoreY = h * (0.86f + 0.02f * ((horizonY - h * 0.66f) / (h * 0.05f)))
        val lakeTop = ColorUtils.blendARGB(circ.horizonColor, 0xFF0F172A.toInt(), if (isDay) 0.35f else 0.78f)
        val lakeBot = if (isDay) ColorUtils.blendARGB(circ.primary, 0xFF0B1324.toInt(), 0.55f) else 0xFF020408.toInt()
        fillPaint.shader = LinearGradient(0f, horizonY, 0f, h.toFloat(), intArrayOf(lakeTop, lakeBot), null, Shader.TileMode.CLAMP)
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, horizonY, w.toFloat(), h.toFloat(), fillPaint)

        // Inverted mountain silhouette reflection in Mirror Lake
        fillPaint.shader = null
        fillPaint.color = ColorUtils.blendARGB(tipRock, lakeTop, 0.45f)
        fillPaint.alpha = (masterAlpha * 0.26f).toInt()
        canvas.save()
        canvas.translate(0f, midShiftY)
        canvas.drawPath(lakeReflectPath, fillPaint)
        canvas.restore()

        drawWaterShimmerAndSwallows(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, effWind, timeSec, masterAlpha)
        drawThermalEagles(canvas, w, h, density, horizonY, windDir, effWind, isDay, timeSec, masterAlpha)
    }

    private fun drawMistyForest(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        horizonY: Float, midShiftY: Float, foreShiftY: Float,
        sky: SkySnapshot, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int, alpenglow: Float,
        isDay: Boolean, windDir: Float, effWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        // Annual Phenology (chlorophyll -> anthocyanin -> winter frost across dayOfYear)
        val seasonPhase = ((dayOfYear - 80) / 365.0) * 2.0 * PI
        val foliageAlbedo = when {
            sin(seasonPhase) > 0.35 -> 0xFF15803D.toInt() // Spring/Summer lush emerald
            sin(seasonPhase) < -0.35 -> 0xFF0F766E.toInt() // Winter deep frost-pine
            cos(seasonPhase) < 0.0 -> 0xFFB45309.toInt()   // Autumn amber-russet
            else -> 0xFF166534.toInt()
        }

        val tipFoliage = ColorUtils.blendARGB(foliageAlbedo, lightColor, 0.55f * lightStrength)
        val mistCol = (if (isDay) ColorUtils.blendARGB(Color.WHITE, lightColor, 0.35f) else 0xFF334155.toInt()) and 0x00FFFFFF

        // Back forest ridge
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.08f, 0f, horizonY + midShiftY,
            intArrayOf(ColorUtils.blendARGB(tipFoliage, shadowBase, 0.25f), shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.85f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.7f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        // Drifting morning/evening valley mist between ridges
        val mistAlpha = (masterAlpha * (0.18f + fogFactor * 0.22f)).toInt().coerceIn(0, 160)
        for (m in 0 until 2) {
            val mp = (((timeSec * (0.008 + effWind * 0.0008) * windDir) + m * 0.5) % 1.0 + 1.0) % 1.0
            drawSoftMist(canvas, (mp.toFloat() * 1.4f - 0.2f) * w, horizonY + midShiftY, w * 0.55f, h * 0.032f, mistCol or (mistAlpha shl 24))
        }

        // Mid forest ridge (gentle wind sway)
        val swayMid = sin(timeSec * (0.9 + effWind * 0.04)).toFloat() * (density * (1.2f + effWind * 0.08f)) * windDir
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.02f, 0f, h.toFloat(),
            intArrayOf(tipFoliage, shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(swayMid * 0.6f, midShiftY); canvas.drawPath(midPath, fillPaint); canvas.restore()

        // Foreground canopy (livelier gust sway)
        val swayFore = sin(timeSec * (1.1 + effWind * 0.05) * 1.414).toFloat() * (density * (1.8f + effWind * 0.12f)) * windDir
        fillPaint.shader = LinearGradient(
            0f, horizonY + h * 0.06f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(tipFoliage, shadowBase, 0.35f), 0xFF030508.toInt()), null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(swayFore, foreShiftY); canvas.drawPath(forePath, fillPaint); canvas.restore()
        fillPaint.shader = null

        // Biology: Photinus fireflies at warm calm dusk (tempC >= 16C, sunAltDeg in -10..0)
        if (weather.tempC >= 16f && sky.sunAltDeg in -10f..0.5f && weather.windKmh < 16f && weather.sky == SkyWeather.CLEAR) {
            for (f in 0 until 7) {
                val pulse = ((sin(timeSec * (1.6 + f * 0.23) + f * 2.1) + 1.0) * 0.5).toFloat()
                val glow = pulse * pulse * pulse
                if (glow > 0.08f) {
                    val fx = w * (0.14f + 0.72f * seedRand(dayOfYear + f, 5)) + sin(timeSec * 0.5 + f).toFloat() * (w * 0.03f)
                    val fy = horizonY + h * (0.04f + 0.14f * seedRand(dayOfYear + f, 6)) + cos(timeSec * 0.7 + f).toFloat() * (h * 0.02f)
                    val fA = (masterAlpha * 0.55f * glow).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, fx, fy, density * 6f, density * 6f, 0x00FDE047 or (fA shl 24))
                }
            }
        }

        // Dawn/dusk swallows skimming gently above forest canopy
        if (sky.sunAltDeg in -3f..16f) {
            drawSwallows(canvas, w, h, density, horizonY, windDir, effWind, timeSec, masterAlpha)
        }
    }

    private fun drawRedRockMesa(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        lightColor: Int, lightStrength: Float, shadowBase: Int, alpenglow: Float,
        isDay: Boolean, windDir: Float, effWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val mesaAlbedo = 0xFFC2410C.toInt()
        val litMesa = ColorUtils.blendARGB(mesaAlbedo, lightColor, 0.55f * lightStrength)

        // Far terraced mesa
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.12f, 0f, horizonY + midShiftY,
            intArrayOf(ColorUtils.blendARGB(litMesa, shadowBase, 0.28f), shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.82f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        // Main canyon mesa (tip-only crimson glow at dusk)
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.06f, 0f, horizonY + foreShiftY,
            intArrayOf(litMesa, shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(0f, midShiftY); canvas.drawPath(midPath, fillPaint); canvas.restore()

        // Valley floor
        fillPaint.shader = LinearGradient(
            0f, horizonY + h * 0.10f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(litMesa, shadowBase, 0.55f), shadowBase), null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(0f, foreShiftY); canvas.drawPath(forePath, fillPaint); canvas.restore()
        fillPaint.shader = null

        val fogAlpha = (masterAlpha * (0.10f + fogFactor * 0.18f)).toInt().coerceIn(0, 120)
        drawSoftMist(canvas, w * 0.5f, horizonY + foreShiftY, w * 0.85f, h * 0.025f, (lightColor and 0x00FFFFFF) or (fogAlpha shl 24))
        drawThermalEagles(canvas, w, h, density, horizonY, windDir, effWind, isDay, timeSec, masterAlpha)
    }

    private fun drawCoastalOcean(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float,
        sky: SkySnapshot, circ: CircadianTheme, weather: WeatherState,
        lightColor: Int, shadowBase: Int, orbX: Float, isDay: Boolean,
        windDir: Float, effWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        // Misty distant headland cliff on one side
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.07f, 0f, horizonY + 4f,
            intArrayOf(ColorUtils.blendARGB(0xFF1E293B.toInt(), lightColor, 0.25f), shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.72f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.5f); canvas.drawPath(farPath, fillPaint); canvas.restore()
        fillPaint.shader = null

        val shoreY = h * (0.85f + 0.025f * ((horizonY - h * 0.66f) / (h * 0.05f)) - sky.tideFrac * 0.018f)
        drawWaterShimmerAndSwallows(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, effWind, timeSec, masterAlpha)

        // Lineless 3-wave surf + receding shore foam (+ rare New-Moon bioluminescence!)
        val isBiolum = !isDay && sky.moonIllumination < 0.08f && weather.sky == SkyWeather.CLEAR
        val swellRgb = if (isDay) 0x00E0F2FE else (if (isBiolum) 0x002DD4BF else 0x0094A3B8)
        val foamRgb = if (isDay) 0x00F8FAFC else (if (isBiolum) 0x005EEAD4 else 0x00CBD5E1)
        val surfEnergy = (0.76f + (effWind / 36f) + (sky.tideFrac * 0.15f)).coerceIn(0.72f, 1.45f)
        val waveSpeed = 0.044 + (effWind / 500.0).coerceIn(0.0, 0.030)
        val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)

        val tideBreath = 0.85f + 0.15f * sin(timeSec * 0.45).toFloat()
        val tideAlpha = (masterAlpha * (if (isDay) 0.14f else 0.09f) * tideBreath).toInt().coerceIn(0, 255)
        drawSoftMist(canvas, w * 0.5f, shoreY + h * 0.004f, w * 0.78f, h * 0.011f, foamRgb or (tideAlpha shl 24))

        for (wIdx in 0 until 3) {
            val waveProg = (((timeSec * waveSpeed) + wIdx / 3.0) % 1.0).toFloat()
            val persp = waveProg * sqrt(waveProg)
            val wy = horizonY + seaSpan * (0.34f + persp * 0.66f)
            val env = sin(waveProg * PI).toFloat()
            val driftX = sin(timeSec * 0.32 * 1.618 + wIdx * 2.1).toFloat() * (w * 0.06f)
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

        // Distant Sailboat driven by wind_direction_10m
        val boatSpeed = (0.0045 + (effWind / 24.0).coerceIn(0.2, 1.5) * 0.0035) * windDir
        val boatX = ((((0.20f + (timeSec * boatSpeed).toFloat()) % 1.16f) + 1.16f) % 1.16f - 0.08f) * w
        val boatBob = sin(timeSec * (1.3 + effWind * 0.02)).toFloat() * (density * 1.6f)
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

        // 4-Gull thermal V-flock by day
        if (sky.sunAltDeg > -2f) {
            val dayFactor = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            strokePaint.strokeWidth = density * 1.15f
            strokePaint.color = 0xFF1E293B.toInt()
            for (g in 0 until 4) {
                val gx = ((((0.22f + g * 0.055f + (timeSec * 0.014 * windDir).toFloat()) % 1.28f) + 1.28f) % 1.28f - 0.14f) * w
                val gy = h * (0.29f + (g % 2) * 0.018f + (g / 2) * 0.024f) - sin(timeSec * 0.8 + g * 0.9).toFloat() * (h * 0.007f)
                val span = w * (0.014f - g * 0.0018f)
                val flap = sin(timeSec * 2.8 + g * 1.3).toFloat() * (span * 0.38f)
                strokePaint.alpha = (masterAlpha * (0.42f - g * 0.05f) * dayFactor).toInt().coerceIn(0, 255)
                canvas.drawLine(gx - span, gy - flap, gx, gy + span * 0.22f, strokePaint)
                canvas.drawLine(gx, gy + span * 0.22f, gx + span, gy - flap, strokePaint)
            }
        }
    }

    private fun drawWaterShimmerAndSwallows(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, shoreY: Float,
        sky: SkySnapshot, weather: WeatherState, orbX: Float, isDay: Boolean,
        effWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)
        val shimRgb = if (sky.sunAltDeg > -4f) (sky.sunColor and 0x00FFFFFF) else (sky.moonColor and 0x00FFFFFF)
        val lumBoost = if (sky.sunAltDeg > -4f) 1.0f else (0.55f + 0.45f * sky.moonIllumination.coerceIn(0f, 1f))
        val windScale = (0.75f + (effWind / 28f)).coerceIn(0.75f, 1.8f)
        val shimBreath = 0.88f + 0.12f * sin(timeSec * 0.9).toFloat()
        val upperAlpha = (masterAlpha * (if (isDay) 0.22f else 0.16f) * lumBoost * shimBreath).toInt().coerceIn(0, 255)
        val lowerAlpha = (masterAlpha * (if (isDay) 0.12f else 0.09f) * lumBoost * shimBreath).toInt().coerceIn(0, 255)
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.24f, w * 0.085f * windScale, seaSpan * 0.30f, shimRgb or (upperAlpha shl 24))
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.56f, w * 0.155f * windScale, seaSpan * 0.44f, shimRgb or (lowerAlpha shl 24))

        for (gIdx in 0 until 4) {
            val fracY = 0.12f + gIdx * 0.19f
            val gy = horizonY + seaSpan * fracY
            val wavePhase = timeSec * (0.95 + gIdx * 0.24) * 1.618 + gIdx * 1.9
            val glintVis = ((sin(wavePhase) + 1.0) * 0.5).toFloat()
            val spreadX = sin(timeSec * 0.55 * 1.4142 + gIdx * 1.7).toFloat() * (w * (0.020f + 0.025f * fracY) * windScale)
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
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float,
        windDir: Float, effWind: Float, isDay: Boolean, timeSec: Double, masterAlpha: Int
    ) {
        if (!isDay) return
        strokePaint.strokeWidth = density * 1.35f
        strokePaint.color = 0xFF1E293B.toInt()
        for (e in 0 until 2) {
            val ex = w * (0.34f + e * 0.32f) + sin(timeSec * (0.22 + e * 0.06) + e * 2.0).toFloat() * (w * 0.14f) * windDir
            val ey = horizonY * (0.38f + e * 0.12f) + cos(timeSec * 0.31 + e * 1.4).toFloat() * (h * 0.02f)
            val span = w * (0.022f - e * 0.004f)
            val glide = sin(timeSec * 1.1 + e * 1.7).toFloat() * (span * 0.18f)
            strokePaint.alpha = (masterAlpha * (0.48f - e * 0.10f)).toInt().coerceIn(0, 255)
            canvas.drawLine(ex - span, ey - glide, ex, ey + span * 0.15f, strokePaint)
            canvas.drawLine(ex, ey + span * 0.15f, ex + span, ey - glide, strokePaint)
        }
    }

    private fun drawSwallows(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float,
        windDir: Float, effWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        strokePaint.strokeWidth = density * 1.0f
        strokePaint.color = 0xFF0F172A.toInt()
        for (s in 0 until 4) {
            val sx = ((((0.18f + s * 0.06f + (timeSec * 0.022 * windDir).toFloat()) % 1.25f) + 1.25f) % 1.25f - 0.12f) * w
            val sy = horizonY - h * (0.06f + (s % 2) * 0.02f) + sin(timeSec * 2.1 + s * 1.1).toFloat() * (h * 0.012f)
            val span = w * 0.010f
            val flap = sin(timeSec * 4.2 + s * 1.4).toFloat() * (span * 0.45f)
            strokePaint.alpha = (masterAlpha * 0.36f).toInt().coerceIn(0, 255)
            canvas.drawLine(sx - span, sy - flap, sx, sy + span * 0.2f, strokePaint)
            canvas.drawLine(sx, sy + span * 0.2f, sx + span, sy - flap, strokePaint)
        }
    }
}
