package com.beatwall.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
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
    COASTAL_REEDS("Coastal Reeds & Wildflowers"),
    CRYSTAL_COVE("Crystal Boulder Cove")
}

/**
 * Hybrid Alpha-Masked WebP + AGSL RuntimeShader + Zero-Allocation Physics Landscape Engine (v8.0.0).
 * - Decodes only the 1 active biome's WebP textures into RAM and recycles on biome switch.
 * - Uses Android 13+ AGSL RuntimeShader for soft volumetric god rays & animated underwater caustics.
 * - Uses steady weather.windKmh for linear timeSec drift (never oscillating gusts).
 */
class LandscapeEngine {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val bmpMatrix = Matrix()
    private val rect = RectF()

    private var cachedMistColor = 0
    private var cachedMistShader: RadialGradient? = null
    private var cachedRayColor = 0
    private var cachedRayShader: LinearGradient? = null

    private var loadedBiome: WorldBiome? = null
    private var bmpPrimary: Bitmap? = null
    private var bmpSecondary: Bitmap? = null
    private var cachedFilterMul = 0
    private var cachedColorFilter: LightingColorFilter? = null

    private var agslGodRayShader: Any? = null
    private var agslCausticShader: Any? = null

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

    private fun ensureBiomeBitmaps(context: Context, biome: WorldBiome) {
        if (loadedBiome == biome) return
        bmpPrimary?.recycle()
        bmpSecondary?.recycle()
        bmpPrimary = null
        bmpSecondary = null
        loadedBiome = biome

        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        when (biome) {
            WorldBiome.SUNLIT_WOODLAND -> {
                bmpPrimary = BitmapFactory.decodeResource(context.resources, R.drawable.woodland_canopy, opts)
                bmpSecondary = BitmapFactory.decodeResource(context.resources, R.drawable.woodland_floor, opts)
            }
            WorldBiome.COASTAL_REEDS -> {
                bmpPrimary = BitmapFactory.decodeResource(context.resources, R.drawable.reeds_fore, opts)
            }
            WorldBiome.CRYSTAL_COVE -> {
                bmpPrimary = BitmapFactory.decodeResource(context.resources, R.drawable.cove_boulders, opts)
            }
            else -> Unit
        }
    }

    private fun applyLiveTint(mulColor: Int, alpha: Int) {
        if (cachedFilterMul != mulColor || cachedColorFilter == null) {
            cachedFilterMul = mulColor
            cachedColorFilter = LightingColorFilter(mulColor, 0x00000000)
        }
        bmpPaint.colorFilter = cachedColorFilter
        bmpPaint.alpha = alpha.coerceIn(0, 255)
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
            WorldBiome.COASTAL_REEDS -> {
                val onLeft = (dayOfYear % 2 == 1)
                buildHeadlandPath(farPath, wf, baseH, h * 0.042f, onLeft, dayOfYear)
            }
            WorldBiome.CRYSTAL_COVE -> {
                buildDunePath(farPath, wf, hf, baseH - h * 0.01f, h * 0.020f, 1.3f, p1)
            }
            WorldBiome.SUNLIT_WOODLAND -> Unit
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

        for (a in 0 until 2) {
            val fx = if (a == 0) (0.22f + 0.14f * seedRand(day, salt + 5)) else (0.66f + 0.16f * seedRand(day, salt + 9))
            val tx = fx * w
            val ty = yBase - sin(fx * 1.2 * PI + p1).toFloat() * amp + 3f
            val trunkH = h * (0.048f + 0.014f * seedRand(day + a, salt + 12))
            val tw = w * 0.0065f
            val canopyW = w * (0.125f + 0.035f * a)
            val canopyH = h * 0.011f
            val forkY = ty - trunkH * 0.52f

            path.moveTo(tx - tw, ty)
            path.lineTo(tx - tw * 0.65f, forkY)
            path.lineTo(tx + tw * 0.65f, forkY)
            path.lineTo(tx + tw, ty)
            path.close()

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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgslGodRays(canvas: Canvas, w: Int, h: Int, ox: Float, oy: Float, rayCol: Int, intensity: Float, timeSec: Double) {
        val shader = (agslGodRayShader as? RuntimeShader) ?: RuntimeShader(
            """
            uniform float2 uRes;
            uniform float2 uOrb;
            uniform float uTime;
            uniform float uIntensity;
            layout(color) uniform half4 uColor;
            half4 main(float2 fragCoord) {
                float2 d = float2((fragCoord.x - uOrb.x) / uRes.y, (fragCoord.y - uOrb.y) / uRes.y);
                float dist = length(d);
                float ang = atan(d.y, d.x);
                float s1 = 0.5 + 0.5 * sin(ang * 11.0 + uTime * 0.08 + 0.4);
                float s2 = 0.5 + 0.5 * cos(ang * 17.0 - uTime * 0.05 - 0.9);
                float shafts = pow(s1 * s2, 1.85);
                float yNorm = fragCoord.y / uRes.y;
                float below = smoothstep(0.12, 0.30, yNorm);
                float fadeBot = 1.0 - smoothstep(0.74, 0.95, yNorm);
                float radial = exp(-dist * 1.15);
                float a = clamp(shafts * radial * below * fadeBot * uIntensity, 0.0, 0.65);
                return half4(uColor.rgb * a, a);
            }
            """.trimIndent()
        ).also { agslGodRayShader = it }

        shader.setFloatUniform("uRes", w.toFloat(), h.toFloat())
        shader.setFloatUniform("uOrb", ox, oy)
        shader.setFloatUniform("uTime", (timeSec % 628.0).toFloat())
        shader.setFloatUniform("uIntensity", intensity)
        shader.setColorUniform("uColor", rayCol)
        rayPaint.shader = shader
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), rayPaint)
        rayPaint.shader = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawAgslCrystalWater(
        canvas: Canvas, w: Int, h: Int, horizonY: Float, orbX: Float,
        topCol: Int, botCol: Int, causticCol: Int, shimCol: Int,
        windSpeed: Float, causticStrength: Float, timeSec: Double
    ) {
        val shader = (agslCausticShader as? RuntimeShader) ?: RuntimeShader(
            """
            uniform float2 uRes;
            uniform float uHorizonY;
            uniform float uOrbX;
            uniform float uTime;
            uniform float uWind;
            uniform float uCausticStr;
            layout(color) uniform half4 uTopCol;
            layout(color) uniform half4 uBotCol;
            layout(color) uniform half4 uCausticCol;
            layout(color) uniform half4 uShimCol;
            half4 main(float2 fragCoord) {
                float span = max(uRes.y - uHorizonY, 1.0);
                float wt = clamp((fragCoord.y - uHorizonY) / span, 0.0, 1.0);
                half4 water = mix(uTopCol, uBotCol, half(wt));
                float nx = fragCoord.x / uRes.x;
                float ny = wt;
                float drift = uTime * (0.22 + uWind * 0.012);
                float c1 = 0.5 + 0.5 * sin(nx * 20.0 + ny * 14.0 + drift + sin(ny * 16.0 - drift * 0.7));
                float c2 = 0.5 + 0.5 * cos(nx * 15.0 - ny * 18.0 - drift * 0.8 + 0.6);
                float caustic = pow(c1 * c2, 3.2) * smoothstep(0.0, 0.16, wt) * uCausticStr;
                float dxOrb = (fragCoord.x - uOrbX) / (uRes.x * (0.075 + wt * 0.06));
                float ripple = 0.85 + 0.15 * sin(ny * 36.0 - uTime * 1.4);
                float shim = exp(-dxOrb * dxOrb) * smoothstep(0.0, 0.05, wt) * (1.0 - wt * 0.65) * ripple * 0.30;
                half3 rgb = water.rgb + uCausticCol.rgb * half(caustic) + uShimCol.rgb * half(shim);
                return half4(clamp(rgb, half3(0.0), half3(1.0)), uTopCol.a);
            }
            """.trimIndent()
        ).also { agslCausticShader = it }

        shader.setFloatUniform("uRes", w.toFloat(), h.toFloat())
        shader.setFloatUniform("uHorizonY", horizonY)
        shader.setFloatUniform("uOrbX", orbX)
        shader.setFloatUniform("uTime", (timeSec % 628.0).toFloat())
        shader.setFloatUniform("uWind", windSpeed)
        shader.setFloatUniform("uCausticStr", causticStrength)
        shader.setColorUniform("uTopCol", topCol)
        shader.setColorUniform("uBotCol", botCol)
        shader.setColorUniform("uCausticCol", causticCol)
        shader.setColorUniform("uShimCol", shimCol)
        rayPaint.shader = shader
        canvas.drawRect(0f, horizonY, w.toFloat(), h.toFloat(), rayPaint)
        rayPaint.shader = null
    }

    fun drawForeground(
        context: Context,
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
        val biome = biomeForDay(dayOfYear)
        ensureGeometry(dayOfYear, width, height)
        ensureBiomeBitmaps(context, biome)

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
                sky, circ, weather, lightColor, lightStrength,
                sunX, sunY, moonX, moonY, isDay, windDir, steadyWind, fogFactor, timeSec, masterAlpha
            )
            WorldBiome.COASTAL_REEDS -> drawCoastalReeds(
                canvas, width, height, density, horizonY, midShiftY, foreShiftY,
                sky, weather, lightColor, shadowBase,
                if (sky.sunAltDeg > -4f) sunX else moonX, isDay, windDir, steadyWind, timeSec, masterAlpha
            )
            WorldBiome.CRYSTAL_COVE -> drawCrystalCove(
                canvas, width, height, density, horizonY, midShiftY, foreShiftY,
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

        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.14f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(tipFoliage, mistRgb or 0xFF000000.toInt(), 0.58f), shadowBase),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.65f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        val mistAlpha = (masterAlpha * (0.34f + fogFactor * 0.24f)).toInt().coerceIn(0, 200)
        for (m in 0 until 2) {
            val mp = (((timeSec * (0.0024 + steadyWind * 0.00018) * windDir) + m * 0.5) % 1.0 + 1.0) % 1.0
            drawSoftMist(canvas, (mp.toFloat() * 1.4f - 0.2f) * w, horizonY + midShiftY * 0.8f + h * 0.015f, w * 0.68f, h * 0.055f, mistRgb or (mistAlpha shl 24))
        }

        val swayMid = sin(timeSec * 0.42).toFloat() * (density * (0.6f + steadyWind * 0.025f)) * windDir
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.06f, 0f, h.toFloat(),
            intArrayOf(tipFoliage, shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.90f).toInt()
        canvas.save(); canvas.translate(swayMid, midShiftY); canvas.drawPath(midPath, fillPaint); canvas.restore()

        val midMistAlpha = (masterAlpha * (0.20f + fogFactor * 0.18f)).toInt().coerceIn(0, 150)
        drawSoftMist(canvas, w * 0.5f, horizonY + foreShiftY + h * 0.06f, w * 0.90f, h * 0.042f, mistRgb or (midMistAlpha shl 24))

        val swayFore = sin(timeSec * 0.52 * 1.414).toFloat() * (density * (0.9f + steadyWind * 0.035f)) * windDir
        fillPaint.shader = LinearGradient(
            0f, horizonY + h * 0.03f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(tipFoliage, shadowBase, 0.50f), 0xFF030508.toInt()),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.save(); canvas.translate(swayFore, foreShiftY); canvas.drawPath(forePath, fillPaint); canvas.restore()
        fillPaint.shader = null

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
        val meadowAlbedo = if (alpenglow > 0.25f) 0xFF65A30D.toInt() else 0xFF4D7C0F.toInt()
        val litGrass = ColorUtils.blendARGB(meadowAlbedo, lightColor, 0.40f)
        val deepGrass = if (isDay) 0xFF1A2E05.toInt() else 0xFF040A06.toInt()

        fillPaint.shader = null
        fillPaint.color = ColorUtils.blendARGB(deepGrass, litGrass, 0.28f * lightStrength)
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, horizonY - h * 0.01f, w.toFloat(), h.toFloat(), fillPaint)

        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.04f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(deepGrass, litGrass, 0.52f * lightStrength), deepGrass),
            null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(0f, midShiftY * 0.65f); canvas.drawPath(farPath, fillPaint); canvas.restore()

        val mistAlpha = (masterAlpha * (0.14f + fogFactor * 0.16f)).toInt().coerceIn(0, 140)
        drawSoftMist(canvas, w * 0.5f, horizonY + midShiftY, w * 0.88f, h * 0.028f, (lightColor and 0x00FFFFFF) or (mistAlpha shl 24))

        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.04f, 0f, h.toFloat(),
            intArrayOf(ColorUtils.blendARGB(deepGrass, litGrass, 0.34f * lightStrength), 0xFF071108.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.save(); canvas.translate(0f, midShiftY); canvas.drawPath(midPath, fillPaint); canvas.restore()

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
        lightColor: Int, lightStrength: Float,
        sunX: Float, sunY: Float, moonX: Float, moonY: Float,
        isDay: Boolean, windDir: Float, steadyWind: Float, fogFactor: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        // 0. Seamless deep-woods haze (dissolves the 66% sky cutoff line)
        val hazeTop = if (isDay) ColorUtils.blendARGB(circ.horizonColor, 0xFF2B4D36.toInt(), 0.35f) else 0xFF0E1B2B.toInt()
        val hazeBot = if (isDay) 0xFF132619.toInt() else 0xFF050A10.toInt()
        fillPaint.shader = LinearGradient(
            0f, h * 0.45f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, hazeTop, hazeBot),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.alpha = masterAlpha
        canvas.drawRect(0f, h * 0.45f, w.toFloat(), h.toFloat(), fillPaint)
        fillPaint.shader = null

        // Live color grading tint (warm golden sunlit vs. cool silver-blue nocturnal moonlight)
        val mulTint = if (isDay) {
            ColorUtils.blendARGB(0xFFFFFFFF.toInt(), lightColor, 0.22f)
        } else {
            val moonStrength = (0.35f + 0.65f * sky.moonIllumination.coerceIn(0f, 1f))
            ColorUtils.blendARGB(0xFF2E425C.toInt(), sky.moonColor, 0.25f * moonStrength)
        }

        // 1. Photorealistic dense canopy & mossy trunks with gentle breeze sway
        val canopyBmp = bmpPrimary
        if (canopyBmp != null && !canopyBmp.isRecycled) {
            val sx = w.toFloat() / canopyBmp.width
            val sy = h.toFloat() / canopyBmp.height
            val scale = maxOf(sx, sy) * 1.03f
            val swayX = sin(timeSec * 0.40).toFloat() * (density * (1.2f + steadyWind * 0.04f)) * windDir
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, scale)
            bmpMatrix.postTranslate((w - canopyBmp.width * scale) * 0.5f + swayX, midShiftY * 0.35f - h * 0.015f)
            applyLiveTint(mulTint, masterAlpha)
            canvas.drawBitmap(canopyBmp, bmpMatrix, bmpPaint)
        }

        // 2. Photorealistic mossy forest floor, ferns & natural mushrooms
        val floorBmp = bmpSecondary
        if (floorBmp != null && !floorBmp.isRecycled) {
            val sx = w.toFloat() / floorBmp.width
            val sy = h.toFloat() / floorBmp.height
            val scale = maxOf(sx, sy) * 1.02f
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, scale)
            bmpMatrix.postTranslate((w - floorBmp.width * scale) * 0.5f, (h - floorBmp.height * scale) + foreShiftY * 0.35f)
            applyLiveTint(mulTint, masterAlpha)
            canvas.drawBitmap(floorBmp, bmpMatrix, bmpPaint)
        }

        // 3. AGSL Volumetric God Rays streaming down through the canopy gaps
        val orbVisible = if (isDay) sky.sunAltDeg > -1f else sky.moonAltDeg > 1f
        if (orbVisible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ox = if (isDay) sunX else moonX
            val oy = (if (isDay) sunY else moonY).coerceIn(h * 0.12f, h * 0.36f)
            val cloudScale = (1f - weather.cloudCoverPct / 135f).coerceIn(0.18f, 1f)
            val moonScale = if (isDay) 1f else (0.30f + 0.70f * sky.moonIllumination.coerceIn(0f, 1f))
            val rayCol = if (isDay) ColorUtils.blendARGB(sky.sunCoronaColor, 0xFFFFE08A.toInt(), 0.35f) else sky.moonColor
            val intensity = (if (isDay) 0.46f else 0.28f) * cloudScale * moonScale * (masterAlpha / 255f)
            if (intensity > 0.02f) {
                drawAgslGodRays(canvas, w, h, ox, oy, rayCol, intensity, timeSec)
            }
        }

        // 4. Subtle misty depth veil near lower trunks
        val mistRgb = (if (isDay) ColorUtils.blendARGB(circ.horizonColor, lightColor, 0.35f) else 0xFF334155.toInt()) and 0x00FFFFFF
        val mistAlpha = (masterAlpha * (0.14f + fogFactor * 0.16f)).toInt().coerceIn(0, 140)
        drawSoftMist(canvas, w * 0.5f, h * 0.58f, w * 0.85f, h * 0.14f, mistRgb or (mistAlpha shl 24))

        // 5. Nighttime cyan-teal bioluminescent glow right on the real photographic mushrooms + Photinus fireflies
        val nightGlow = ((-sky.sunAltDeg - 0.5f) / 6f).coerceIn(0f, 1f)
        if (nightGlow > 0.05f) {
            val mushX = floatArrayOf(0.585f, 0.553f, 0.462f, 0.432f, 0.376f)
            val mushY = floatArrayOf(0.720f, 0.742f, 0.708f, 0.654f, 0.638f)
            for (m in mushX.indices) {
                val breath = 0.78f + 0.22f * sin(timeSec * 1.15 + m * 1.4).toFloat()
                val glowA = (masterAlpha * 0.48f * nightGlow * breath).toInt().coerceIn(0, 255)
                val mx = mushX[m] * w
                val my = mushY[m] * h + foreShiftY * 0.35f
                drawSoftMist(canvas, mx, my, density * 14f, density * 10f, 0x002DD4BF or (glowA shl 24))
            }
        }

        if (!isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
            for (f in 0 until 8) {
                val pulse = ((sin(timeSec * (1.1 + f * 0.17) + f * 2.3) + 1.0) * 0.5).toFloat()
                val glow = pulse * pulse * pulse
                if (glow > 0.08f) {
                    val fx = w * (0.18f + 0.64f * seedRand(dayOfYear + f, 191)) + sin(timeSec * 0.32 + f).toFloat() * (w * 0.035f)
                    val fy = h * (0.45f + 0.28f * seedRand(dayOfYear + f, 193)) + cos(timeSec * 0.41 + f).toFloat() * (h * 0.022f)
                    val fA = (masterAlpha * 0.65f * glow).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, fx, fy, density * 7f, density * 7f, 0x00FDE047 or (fA shl 24))
                }
            }
        }

        // 6. Daytime double-wing butterflies fluttering gently near the ferns
        if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
            val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            for (bf in 0 until 2) {
                val bx = w * (0.36f + bf * 0.26f) + sin(timeSec * (0.24 + bf * 0.05) + bf * 1.9).toFloat() * (w * 0.09f)
                val by = h * (0.58f + bf * 0.07f) + sin(timeSec * 0.54 + bf * 2.3).toFloat() * (h * 0.024f)
                val col = if (bf == 0) ColorUtils.blendARGB(0xFFF59E0B.toInt(), sky.sunColor, 0.30f) else 0xFF7DD3FC.toInt()
                drawButterfly(canvas, bx, by, density * (9.5f + bf * 1.5f), col, (masterAlpha * 0.88f * bfVis).toInt(), timeSec, bf * 1.7)
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
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        sky: SkySnapshot, weather: WeatherState,
        lightColor: Int, shadowBase: Int, orbX: Float, isDay: Boolean,
        windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        // 1. Distant low headland
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.05f, 0f, horizonY + 4f,
            intArrayOf(ColorUtils.blendARGB(0xFF1E293B.toInt(), lightColor, 0.25f), shadowBase), null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.65f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.5f); canvas.drawPath(farPath, fillPaint); canvas.restore()
        fillPaint.shader = null

        // 2. Live shimmering water & sun/moon reflection column
        val shoreY = h * 0.95f
        drawWaterShimmer(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

        // 3. Photorealistic feathery sea-oat reeds & wildflowers with gentle wind sway
        val reedsBmp = bmpPrimary
        if (reedsBmp != null && !reedsBmp.isRecycled) {
            val sx = w.toFloat() / reedsBmp.width
            val sy = h.toFloat() / reedsBmp.height
            val scale = maxOf(sx, sy) * 1.02f
            val sway = sin(timeSec * (0.62 + steadyWind * 0.008)).toFloat() * (0.010f + (steadyWind / 60f) * 0.012f) * windDir
            val mulTint = if (isDay) {
                ColorUtils.blendARGB(0xFFFFFFFF.toInt(), lightColor, 0.42f)
            } else {
                ColorUtils.blendARGB(0xFF283A52.toInt(), sky.moonColor, 0.25f)
            }
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, scale)
            // Anchor bottom while gently leaning upper plumes with the wind
            bmpMatrix.postSkew(sway, 0f, w * 0.5f, h.toFloat())
            bmpMatrix.postTranslate((w - reedsBmp.width * scale) * 0.5f, (h - reedsBmp.height * scale) + foreShiftY * 0.45f)
            applyLiveTint(mulTint, masterAlpha)
            canvas.drawBitmap(reedsBmp, bmpMatrix, bmpPaint)
        }

        // 4. 2 unhurried daytime butterflies drifting between the blooms
        if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
            val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            for (bf in 0 until 2) {
                val baseX = if (bf == 0) w * 0.28f else w * 0.72f
                val bx = baseX + sin(timeSec * (0.26 + bf * 0.06) + bf * 2.1).toFloat() * (w * 0.10f)
                val by = h * (0.64f + bf * 0.06f) + sin(timeSec * 0.58 + bf * 1.8).toFloat() * (h * 0.024f)
                val col = if (bf == 0) ColorUtils.blendARGB(0xFFF59E0B.toInt(), sky.sunColor, 0.30f) else 0xFFBAE6FD.toInt()
                drawButterfly(canvas, bx, by, density * (9.5f + bf * 1.5f), col, (masterAlpha * 0.88f * bfVis).toInt(), timeSec, bf * 1.5)
            }
        }
    }

    private fun drawCrystalCove(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, midShiftY: Float, foreShiftY: Float,
        sky: SkySnapshot, circ: CircadianTheme, weather: WeatherState,
        lightColor: Int, lightStrength: Float, shadowBase: Int,
        orbX: Float, isDay: Boolean, windDir: Float, steadyWind: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        // 1. Distant low mountain ridge behind the cove
        fillPaint.shader = LinearGradient(
            0f, horizonY - h * 0.04f, 0f, horizonY + 4f,
            intArrayOf(ColorUtils.blendARGB(0xFF334155.toInt(), circ.horizonColor, 0.45f), shadowBase),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (masterAlpha * 0.65f).toInt()
        canvas.save(); canvas.translate(0f, midShiftY * 0.4f); canvas.drawPath(farPath, fillPaint); canvas.restore()
        fillPaint.shader = null

        // 2. Crystal-clear teal shallows + AGSL animated underwater caustics + shimmer
        val topWater = if (isDay) ColorUtils.blendARGB(circ.horizonColor, 0xFF0E7490.toInt(), 0.58f) else 0xFF0B192C.toInt()
        val botWater = if (isDay) ColorUtils.blendARGB(0xFF065F46.toInt(), 0xFF0E7490.toInt(), 0.55f) else 0xFF020612.toInt()
        val causticCol = if (isDay) ColorUtils.blendARGB(0xFF5EEAD4.toInt(), lightColor, 0.35f) else sky.moonColor
        val shimCol = if (sky.sunAltDeg > -4f) sky.sunColor else sky.moonColor
        val causticStr = (if (isDay) 0.42f else 0.14f) * lightStrength * (masterAlpha / 255f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val aTop = (topWater and 0x00FFFFFF) or (masterAlpha shl 24)
            val aBot = (botWater and 0x00FFFFFF) or (masterAlpha shl 24)
            drawAgslCrystalWater(canvas, w, h, horizonY, orbX, aTop, aBot, causticCol, shimCol, steadyWind, causticStr, timeSec)
        } else {
            fillPaint.shader = LinearGradient(0f, horizonY, 0f, h.toFloat(), intArrayOf(topWater, botWater), null, Shader.TileMode.CLAMP)
            fillPaint.alpha = masterAlpha
            canvas.drawRect(0f, horizonY, w.toFloat(), h.toFloat(), fillPaint)
            fillPaint.shader = null
            drawWaterShimmer(canvas, w, h, density, horizonY, h * 0.94f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)
        }

        // 3. Soft inverted mirror reflection of granite boulders + seated granite boulders flush at horizonY
        val boulderBmp = bmpPrimary
        if (boulderBmp != null && !boulderBmp.isRecycled) {
            val sx = w.toFloat() / boulderBmp.width
            val sy = h.toFloat() / boulderBmp.height
            val scale = maxOf(sx, sy)
            val dx = (w - boulderBmp.width * scale) * 0.5f
            // In cove_boulders.webp, flat waterline sits near ~60% height; align it flush at horizonY
            val boulderTopY = horizonY - (boulderBmp.height * scale * 0.60f) + midShiftY * 0.5f
            val mulTint = if (isDay) {
                ColorUtils.blendARGB(0xFFFFFFFF.toInt(), lightColor, 0.34f)
            } else {
                ColorUtils.blendARGB(0xFF223044.toInt(), sky.moonColor, 0.25f)
            }

            // Soft mirror reflection below horizonY
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, -scale * 0.52f)
            bmpMatrix.postTranslate(dx, horizonY + (boulderBmp.height * scale * 0.60f * 0.52f) + midShiftY * 0.5f)
            applyLiveTint(ColorUtils.blendARGB(mulTint, topWater, 0.45f), (masterAlpha * 0.28f).toInt())
            canvas.save()
            canvas.clipRect(0f, horizonY, w.toFloat(), horizonY + h * 0.14f)
            canvas.drawBitmap(boulderBmp, bmpMatrix, bmpPaint)
            canvas.restore()

            // Crisp sun-warmed granite boulders seated flush on the waterline
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, scale)
            bmpMatrix.postTranslate(dx, boulderTopY)
            applyLiveTint(mulTint, masterAlpha)
            canvas.drawBitmap(boulderBmp, bmpMatrix, bmpPaint)
        }

        // 4. 5 sleek fish swimming lazily through the clear cove shallows
        val fishCol = if (isDay) ColorUtils.blendARGB(0xFF062C33.toInt(), botWater, 0.35f) else 0xFF020617.toInt()
        fillPaint.color = fishCol
        fillPaint.alpha = (masterAlpha * (if (isDay) 0.58f else 0.32f)).toInt()
        for (f in 0 until 5) {
            val dir = if (f % 2 == 0) 1f else -1f
            val speed = 0.015 + (f % 3) * 0.004
            val fxFrac = ((((0.15 + f * 0.19) + timeSec * speed * dir) % 1.20) + 1.20) % 1.20 - 0.10
            val fx = fxFrac.toFloat() * w
            val fy = h * (0.76f + (f % 4) * 0.042f) + sin(timeSec * 0.8 + f * 1.7).toFloat() * (h * 0.012f)
            val len = density * (10.5f + (f % 3) * 2.5f)
            val tailWag = sin(timeSec * 4.5 + f * 1.3).toFloat() * (len * 0.24f)

            floraPath.reset()
            floraPath.moveTo(fx + len * 0.55f * dir, fy)
            floraPath.quadTo(fx, fy - len * 0.26f, fx - len * 0.45f * dir, fy + tailWag * 0.3f)
            floraPath.lineTo(fx - len * 0.76f * dir, fy - len * 0.24f + tailWag)
            floraPath.lineTo(fx - len * 0.76f * dir, fy + len * 0.24f + tailWag)
            floraPath.lineTo(fx - len * 0.45f * dir, fy + tailWag * 0.3f)
            floraPath.quadTo(fx, fy + len * 0.26f, fx + len * 0.55f * dir, fy)
            floraPath.close()
            canvas.drawPath(floraPath, fillPaint)
        }

        // 5. High open-sky birds
        if (isDay) {
            drawSwallows(canvas, w, h, density, windDir, timeSec, masterAlpha)
        }
    }

    private fun drawButterfly(
        canvas: Canvas, bx: Float, by: Float, wingSpan: Float,
        color: Int, alpha: Int, timeSec: Double, phase: Double
    ) {
        val flutter = abs(sin(timeSec * 4.4 + phase)).toFloat().coerceAtLeast(0.22f)
        val ww = wingSpan * flutter
        fillPaint.color = color
        fillPaint.alpha = alpha.coerceIn(0, 255)

        floraPath.reset()
        floraPath.moveTo(bx, by)
        floraPath.quadTo(bx - ww * 1.05f, by - wingSpan * 0.95f, bx - ww * 0.85f, by - wingSpan * 0.05f)
        floraPath.close()
        floraPath.moveTo(bx, by + wingSpan * 0.05f)
        floraPath.quadTo(bx - ww * 0.80f, by + wingSpan * 0.65f, bx - ww * 0.25f, by + wingSpan * 0.52f)
        floraPath.close()
        floraPath.moveTo(bx, by)
        floraPath.quadTo(bx + ww * 1.05f, by - wingSpan * 0.95f, bx + ww * 0.85f, by - wingSpan * 0.05f)
        floraPath.close()
        floraPath.moveTo(bx, by + wingSpan * 0.05f)
        floraPath.quadTo(bx + ww * 0.80f, by + wingSpan * 0.65f, bx + ww * 0.25f, by + wingSpan * 0.52f)
        floraPath.close()
        canvas.drawPath(floraPath, fillPaint)
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
