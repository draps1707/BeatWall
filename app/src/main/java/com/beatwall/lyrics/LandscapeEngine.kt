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
    FIREWATCH_LOOKOUT("Firewatch Lookout"),
    SAND_DUNES("Sand Dunes"),
    SNOWY_ALPENGLOW_LAKE("Snowy Peaks & Lake"),
    EMERALD_SAVANNA("Emerald Savanna"),
    COASTAL_OCEAN("Coastal Ocean"),
    SUNLIT_WOODLAND("Sunlit Woodland"),
    COASTAL_REEDS("Coastal Reeds"),
    CRYSTAL_COVE("Crystal Cove"),
    KYOTO_TORII("Kyoto Torii Lake"),
    LIGHTHOUSE_CLIFF("Lighthouse Cliff"),
    AURORA_FJORD("Nordic Aurora Fjord"),
    VALLEY_STAG("Valley Lake & Stag"),
    CAPPADOCIA_BALLOONS("Cappadocia Balloons"),
    ODISHA_BANYAN_GHAT("Odisha Banyan Ghat"),
    HIMALAYAN_MONASTERY("Himalayan Monastery"),
    ALPINE_VIADUCT("Alpine Stone Viaduct"),
    CANYON_WINDMILL("Canyon & Windmill"),
    MISTY_BAMBOO("Misty Bamboo Grove"),
    VOLCANIC_CALDERA("Volcanic Caldera"),
    HIGHLAND_LOCH("Highland Loch & Ruin")
}

/**
 * Pure-Vector Firewatch Minimalist 20-World Landscape Engine (v10.0.0).
 * - Zero WebP bitmaps, zero RuntimeShaders, zero Random dice rolls, zero per-frame steady allocations.
 * - Pre-builds multi-octave fractal ridges, solid-wound 16-branch feathery conifers, and architectural
 *   silhouettes ONCE per dayOfYear/size.
 * - Exact waterline anchoring (full horizonShiftY) so zero headlands, cliffs, or walls ever float.
 */
class LandscapeEngine {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    private var cachedMistColor = 0
    private var cachedMistShader: RadialGradient? = null

    private val layerPaths = Array(5) { Path().apply { fillType = Path.FillType.WINDING } }
    private val accentPath = Path().apply { fillType = Path.FillType.WINDING }
    private val reflectPath = Path().apply { fillType = Path.FillType.WINDING }
    private val dynPath = Path().apply { fillType = Path.FillType.WINDING }

    private var focalX = 0f
    private var focalY = 0f
    private var focalW = 0f
    private var focalH = 0f
    private var auxX = 0f
    private var auxY = 0f

    private var builtDay = -1
    private var builtW = 0
    private var builtH = 0

    fun biomeForDay(dayOfYear: Int): WorldBiome {
        val idx = ((dayOfYear - 1).coerceAtLeast(0)) % WorldBiome.values().size
        return WorldBiome.values()[idx]
    }

    fun horizonY(height: Int, lockBlend: Float): Float {
        return height * (0.64f + 0.05f * lockBlend)
    }

    fun skyClarityBoost(dayOfYear: Int): Float {
        return when (biomeForDay(dayOfYear)) {
            WorldBiome.FIREWATCH_LOOKOUT, WorldBiome.SAND_DUNES,
            WorldBiome.SNOWY_ALPENGLOW_LAKE, WorldBiome.AURORA_FJORD,
            WorldBiome.VALLEY_STAG, WorldBiome.HIMALAYAN_MONASTERY,
            WorldBiome.ALPINE_VIADUCT -> 1.22f
            else -> 1.05f
        }
    }

    private fun seedRand(seed: Int, salt: Int): Float {
        val x = sin(seed * 12.9898 + salt * 78.233) * 43758.5453
        return (x - kotlin.math.floor(x)).toFloat()
    }

    private fun fbm1d(xNorm: Float, seed: Int, octaves: Int = 5): Float {
        var v = 0f
        var amp = 0.55f
        var freq = 2.2
        for (o in 0 until octaves) {
            val p = seedRand(seed, 10 + o * 7) * 6.28318
            val n = (1.0 - abs(sin(xNorm * freq * PI + p))).toFloat()
            v += n.pow(1.35f) * amp
            freq *= 2.05
            amp *= 0.48f
        }
        return v
    }

    // Solid-wound 14-to-20 branch irregular drooping Firewatch conifer (zero trunk dots!)
    private fun addFeatheryPine(path: Path, cx: Float, baseY: Float, treeH: Float, maxHalfW: Float, seed: Int) {
        val tw = maxOf(1.4f, maxHalfW * 0.12f)
        path.moveTo(cx - tw, baseY + 6f)
        path.lineTo(cx - tw * 0.25f, baseY - treeH * 0.96f)
        path.lineTo(cx + tw * 0.25f, baseY - treeH * 0.96f)
        path.lineTo(cx + tw, baseY + 6f)
        path.close()

        path.moveTo(cx - maxHalfW * 0.13f, baseY - treeH * 0.85f)
        path.lineTo(cx, baseY - treeH)
        path.lineTo(cx + maxHalfW * 0.13f, baseY - treeH * 0.85f)
        path.close()

        val tiers = (13 + (seedRand(seed, 1) * 7f).toInt()).coerceIn(13, 20)
        for (t in 0 until tiers) {
            val frac = t / tiers.toFloat()
            val yAtt = baseY - treeH * (0.10f + frac * 0.84f)
            val env = (1f - frac * 0.90f).pow(0.85f)
            val thick = maxOf(1.4f, treeH * 0.030f * (1f - frac * 0.55f))

            // Right branch (CW)
            val jitR = 0.72f + 0.56f * seedRand(seed + t * 13, 7)
            val bwR = maxHalfW * env * jitR
            val drR = treeH * (0.045f - frac * 0.025f) * (0.8f + 0.4f * seedRand(seed + t, 11))
            path.moveTo(cx, yAtt - thick * 0.5f)
            path.lineTo(cx + bwR * 0.55f, yAtt + drR * 0.35f - thick * 0.25f)
            path.lineTo(cx + bwR, yAtt + drR)
            path.lineTo(cx + bwR * 0.65f, yAtt + drR * 0.95f + thick * 0.45f)
            path.lineTo(cx + bwR * 0.28f, yAtt + drR * 0.65f + thick * 0.65f)
            path.lineTo(cx, yAtt + thick * 0.95f)
            path.close()

            // Left branch (also CW so WINDING fill never cancels trunk!)
            val jitL = 0.72f + 0.56f * seedRand(seed + t * 13, 3)
            val bwL = maxHalfW * env * jitL
            val drL = treeH * (0.045f - frac * 0.025f) * (0.8f + 0.4f * seedRand(seed + t, 17))
            path.moveTo(cx, yAtt + thick * 0.95f)
            path.lineTo(cx - bwL * 0.28f, yAtt + drL * 0.65f + thick * 0.65f)
            path.lineTo(cx - bwL * 0.65f, yAtt + drL * 0.95f + thick * 0.45f)
            path.lineTo(cx - bwL, yAtt + drL)
            path.lineTo(cx - bwL * 0.55f, yAtt + drL * 0.35f - thick * 0.25f)
            path.lineTo(cx, yAtt - thick * 0.5f)
            path.close()
        }
    }

    private fun addLineQuad(path: Path, x0: Float, y0: Float, x1: Float, y1: Float, halfW: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = (-dy / len) * halfW
        val ny = (dx / len) * halfW
        path.moveTo(x0 - nx, y0 - ny)
        path.lineTo(x1 - nx, y1 - ny)
        path.lineTo(x1 + nx, y1 + ny)
        path.lineTo(x0 + nx, y0 + ny)
        path.close()
    }

    private fun addLookoutTower(path: Path, tx: Float, baseY: Float, towerH: Float) {
        val cabY = baseY - towerH * 0.74f
        val cabW = towerH * 0.28f
        val cabH = towerH * 0.16f
        val balW = towerH * 0.42f
        val botW = towerH * 0.34f
        val topW = towerH * 0.20f
        val legHW = maxOf(1.2f, towerH * 0.011f)
        val braceHW = maxOf(0.8f, legHW * 0.65f)

        addLineQuad(path, tx - botW * 0.5f, baseY + 14f, tx - topW * 0.5f, cabY, legHW)
        addLineQuad(path, tx + botW * 0.5f, baseY + 14f, tx + topW * 0.5f, cabY, legHW)
        addLineQuad(path, tx - botW * 0.18f, baseY + 14f, tx - topW * 0.14f, cabY, braceHW)
        addLineQuad(path, tx + botW * 0.18f, baseY + 14f, tx + topW * 0.14f, cabY, braceHW)

        for (i in 0 until 4) {
            val f0 = i / 4f
            val f1 = (i + 1) / 4f
            val y0 = baseY - (baseY - cabY) * f0
            val y1 = baseY - (baseY - cabY) * f1
            val w0 = botW * (1f - f0) + topW * f0
            val w1 = botW * (1f - f1) + topW * f1
            addLineQuad(path, tx - w0 * 0.5f, y0, tx + w0 * 0.5f, y0, braceHW)
            addLineQuad(path, tx - w0 * 0.5f, y0, tx + w1 * 0.5f, y1, braceHW)
            addLineQuad(path, tx + w0 * 0.5f, y0, tx - w1 * 0.5f, y1, braceHW)
        }

        path.addRect(tx - balW * 0.5f, cabY - 2.5f, tx + balW * 0.5f, cabY + 2.5f, Path.Direction.CW)
        val railY = cabY - cabH * 0.36f
        addLineQuad(path, tx - balW * 0.5f, railY, tx + balW * 0.5f, railY, braceHW)
        for (p in 0..6) {
            val px = tx - balW * 0.5f + balW * (p / 6f)
            addLineQuad(path, px, cabY, px, railY, braceHW * 0.7f)
        }

        path.addRect(tx - cabW * 0.5f, cabY - cabH * 0.42f, tx + cabW * 0.5f, cabY, Path.Direction.CW)
        path.addRect(tx - cabW * 0.5f, cabY - cabH, tx - cabW * 0.38f, cabY, Path.Direction.CW)
        path.addRect(tx + cabW * 0.38f, cabY - cabH, tx + cabW * 0.5f, cabY, Path.Direction.CW)
        addLineQuad(path, tx, cabY - cabH, tx, cabY, braceHW)

        val roofW = balW * 1.14f
        val roofY = cabY - cabH
        path.moveTo(tx - roofW * 0.5f, roofY + 3f)
        path.lineTo(tx, roofY - towerH * 0.13f)
        path.lineTo(tx + roofW * 0.5f, roofY + 3f)
        path.close()

        focalX = tx; focalY = cabY - cabH * 0.68f; focalW = cabW * 0.74f; focalH = cabH * 0.46f
    }

    private fun addToriiGate(path: Path, refPath: Path, tx: Float, baseY: Float, gateH: Float) {
        val span = gateH * 0.78f
        val pw = gateH * 0.085f
        val topY = baseY - gateH
        addLineQuad(path, tx - span * 0.36f, baseY + 3f, tx - span * 0.33f, topY + gateH * 0.12f, pw * 0.5f)
        addLineQuad(path, tx + span * 0.36f, baseY + 3f, tx + span * 0.33f, topY + gateH * 0.12f, pw * 0.5f)
        path.moveTo(tx - span * 0.62f, topY - gateH * 0.05f)
        path.quadTo(tx, topY + gateH * 0.08f, tx + span * 0.62f, topY - gateH * 0.05f)
        path.lineTo(tx + span * 0.56f, topY + gateH * 0.10f)
        path.quadTo(tx, topY + gateH * 0.17f, tx - span * 0.56f, topY + gateH * 0.10f)
        path.close()
        path.addRect(tx - span * 0.48f, topY + gateH * 0.24f, tx + span * 0.48f, topY + gateH * 0.31f, Path.Direction.CW)
        path.addRect(tx - pw * 0.45f, topY + gateH * 0.10f, tx + pw * 0.45f, topY + gateH * 0.27f, Path.Direction.CW)

        // Inverted soft reflection below pillars
        refPath.addRect(tx - span * 0.36f - pw * 0.5f, baseY, tx - span * 0.36f + pw * 0.5f, baseY + gateH * 0.55f, Path.Direction.CW)
        refPath.addRect(tx + span * 0.36f - pw * 0.5f, baseY, tx + span * 0.36f + pw * 0.5f, baseY + gateH * 0.55f, Path.Direction.CW)
        refPath.addRect(tx - span * 0.48f, baseY + gateH * 0.42f, tx + span * 0.48f, baseY + gateH * 0.48f, Path.Direction.CW)
    }

    private fun addLighthouse(path: Path, lx: Float, baseY: Float, lhH: Float) {
        val botW = lhH * 0.24f
        val topW = lhH * 0.14f
        val deckY = baseY - lhH * 0.76f
        path.moveTo(lx - botW * 0.5f, baseY + 12f)
        path.lineTo(lx - topW * 0.5f, deckY)
        path.lineTo(lx + topW * 0.5f, deckY)
        path.lineTo(lx + botW * 0.5f, baseY + 12f)
        path.close()
        path.addRect(lx - topW * 0.85f, deckY - 3f, lx + topW * 0.85f, deckY + 3f, Path.Direction.CW)
        val lanTop = deckY - lhH * 0.14f
        addLineQuad(path, lx - topW * 0.48f, deckY, lx - topW * 0.48f, lanTop, 1.5f)
        addLineQuad(path, lx + topW * 0.48f, deckY, lx + topW * 0.48f, lanTop, 1.5f)
        path.moveTo(lx - topW * 0.65f, lanTop)
        path.quadTo(lx, lanTop - lhH * 0.13f, lx + topW * 0.65f, lanTop)
        path.close()
        addLineQuad(path, lx, lanTop - lhH * 0.08f, lx, lanTop - lhH * 0.16f, 1.2f)
        focalX = lx; focalY = (deckY + lanTop) * 0.5f; focalW = topW * 0.9f; focalH = lhH * 0.12f
    }

    private fun addStagSilhouette(path: Path, sx: Float, baseY: Float, sH: Float) {
        addLineQuad(path, sx - sH * 0.24f, baseY + 3f, sx - sH * 0.20f, baseY - sH * 0.44f, sH * 0.025f)
        addLineQuad(path, sx - sH * 0.14f, baseY + 3f, sx - sH * 0.12f, baseY - sH * 0.44f, sH * 0.024f)
        addLineQuad(path, sx + sH * 0.16f, baseY + 3f, sx + sH * 0.14f, baseY - sH * 0.42f, sH * 0.025f)
        addLineQuad(path, sx + sH * 0.26f, baseY + 3f, sx + sH * 0.22f, baseY - sH * 0.42f, sH * 0.024f)
        path.moveTo(sx - sH * 0.26f, baseY - sH * 0.42f)
        path.quadTo(sx, baseY - sH * 0.56f, sx + sH * 0.26f, baseY - sH * 0.40f)
        path.quadTo(sx, baseY - sH * 0.34f, sx - sH * 0.26f, baseY - sH * 0.42f)
        path.close()
        path.moveTo(sx + sH * 0.14f, baseY - sH * 0.46f)
        path.lineTo(sx + sH * 0.28f, baseY - sH * 0.78f)
        path.lineTo(sx + sH * 0.40f, baseY - sH * 0.76f)
        path.lineTo(sx + sH * 0.34f, baseY - sH * 0.68f)
        path.lineTo(sx + sH * 0.26f, baseY - sH * 0.42f)
        path.close()
        val ax = sx + sH * 0.28f
        val ay = baseY - sH * 0.78f
        addLineQuad(path, ax, ay, ax - sH * 0.06f, ay - sH * 0.24f, sH * 0.014f)
        addLineQuad(path, ax - sH * 0.04f, ay - sH * 0.14f, ax + sH * 0.06f, ay - sH * 0.22f, sH * 0.011f)
        addLineQuad(path, ax + sH * 0.04f, ay, ax + sH * 0.14f, ay - sH * 0.22f, sH * 0.014f)
        addLineQuad(path, ax + sH * 0.10f, ay - sH * 0.12f, ax + sH * 0.20f, ay - sH * 0.18f, sH * 0.011f)
    }

    private fun addArchitecturalAcacia(path: Path, tx: Float, baseY: Float, treeH: Float, spanW: Float, seed: Int) {
        val lean = (seedRand(seed, 5) - 0.5f) * spanW * 0.18f
        val forkY = baseY - treeH * 0.46f
        val tw = maxOf(2f, treeH * 0.042f)
        addLineQuad(path, tx, baseY + 6f, tx + lean * 0.5f, forkY, tw)
        val bTargets = floatArrayOf(-0.34f, 0.04f, 0.36f)
        val bHeights = floatArrayOf(0.92f, 1.0f, 0.86f)
        for (b in 0 until 3) {
            val bx = tx + lean + spanW * bTargets[b]
            val by = baseY - treeH * bHeights[b]
            addLineQuad(path, tx + lean * 0.5f, forkY, bx, by, tw * 0.55f)
            val cw = spanW * (0.30f + 0.08f * (1 - abs(b - 1)))
            val ch = treeH * 0.15f
            path.moveTo(bx - cw, by + ch * 0.25f)
            path.lineTo(bx - cw * 0.78f, by - ch * 0.45f)
            path.lineTo(bx - cw * 0.30f, by - ch * 0.74f)
            path.lineTo(bx + cw * 0.22f, by - ch * 0.66f)
            path.lineTo(bx + cw * 0.76f, by - ch * 0.38f)
            path.lineTo(bx + cw, by + ch * 0.22f)
            path.close()
        }
    }

    private fun buildFractalRidge(
        path: Path, w: Float, h: Float, baseYFrac: Float,
        centerPeakFrac: Float, peakPos: Float, cragScale: Float,
        bowlFrac: Float, seed: Int, steps: Int = 180, clampMaxY: Float = h * 1.15f
    ): FloatArray {
        val ys = FloatArray(steps + 1)
        path.moveTo(-w * 0.02f, clampMaxY)
        for (i in 0..steps) {
            val fx = i / steps.toFloat()
            val peak = (1f - abs(fx - peakPos) / 0.34f).coerceIn(0f, 1f).pow(1.45f) * centerPeakFrac
            val shL = (1f - abs(fx - 0.14f) / 0.26f).coerceIn(0f, 1f).pow(1.6f) * (centerPeakFrac * 0.52f)
            val shR = (1f - abs(fx - 0.86f) / 0.28f).coerceIn(0f, 1f).pow(1.6f) * (centerPeakFrac * 0.58f)
            val bowl = sin(fx * PI).toFloat() * bowlFrac
            val crag = fbm1d(fx, seed, octaves = 5) * cragScale
            val y = minOf(clampMaxY, h * (baseYFrac + bowl - peak - shL - shR - crag))
            ys[i] = y
            path.lineTo(fx * w, y)
        }
        path.lineTo(w * 1.02f, clampMaxY)
        path.close()
        return ys
    }

    private fun buildHeadland(path: Path, w: Float, yBase: Float, cliffH: Float, onLeft: Boolean, day: Int) {
        val span = 0.28f + seedRand(day, 99) * 0.12f
        val steps = 36
        path.moveTo(if (onLeft) 0f else w * (1f - span), yBase + 3f)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val fx = if (onLeft) t * span else (1f - span) + t * span
            val profile = if (onLeft) (1f - t) else t
            val crag = 0.84f + 0.22f * fbm1d(t, day + 5, 3)
            val y = yBase - (profile * profile) * cliffH * crag
            path.lineTo(fx * w, y)
        }
        path.lineTo(if (onLeft) w * span else w, yBase + 3f)
        path.close()
    }

    private fun ensureGeometry(dayOfYear: Int, w: Int, h: Int) {
        if (builtDay == dayOfYear && builtW == w && builtH == h) return
        builtDay = dayOfYear
        builtW = w
        builtH = h

        for (p in layerPaths) p.reset()
        accentPath.reset()
        reflectPath.reset()
        focalX = 0f; focalY = 0f; focalW = 0f; focalH = 0f; auxX = 0f; auxY = 0f

        val wf = w.toFloat()
        val hf = h.toFloat()
        val biome = biomeForDay(dayOfYear)
        val baseH = hf * 0.64f

        when (biome) {
            WorldBiome.FIREWATCH_LOOKOUT -> {
                val pPeak = 0.42f + seedRand(dayOfYear, 3) * 0.16f
                buildFractalRidge(layerPaths[0], wf, hf, 0.585f, 0.085f, pPeak, 0.040f, 0f, dayOfYear + 11, 200)
                val r2 = buildFractalRidge(layerPaths[1], wf, hf, 0.605f, 0f, 0.5f, 0.034f, 0.028f, dayOfYear + 29, 160)
                for (t in 0 until 78) {
                    val fx = (t + 0.2f + 0.6f * seedRand(dayOfYear + t, 51)) / 78f
                    val by = r2[(fx * 160).toInt().coerceIn(0, 160)] + 4f
                    val th = hf * (0.016f + 0.020f * seedRand(dayOfYear + t, 53))
                    addFeatheryPine(layerPaths[1], fx * wf, by, th, th * 0.22f, dayOfYear + t + 100)
                }
                val r3 = buildFractalRidge(layerPaths[2], wf, hf, 0.642f, 0f, 0.5f, 0.024f, 0.036f, dayOfYear + 43, 150)
                val towerFx = 0.45f + seedRand(dayOfYear, 59) * 0.10f
                for (t in 0 until 68) {
                    val fx = (t + 0.15f + 0.7f * seedRand(dayOfYear + t, 61)) / 68f
                    if (abs(fx - towerFx) < 0.065f) continue
                    val by = r3[(fx * 150).toInt().coerceIn(0, 150)] + 5f
                    val th = hf * (0.030f + 0.034f * seedRand(dayOfYear + t, 63))
                    addFeatheryPine(layerPaths[2], fx * wf, by, th, th * 0.24f, dayOfYear + t + 200)
                }
                addLookoutTower(layerPaths[2], towerFx * wf, r3[(towerFx * 150).toInt().coerceIn(0, 150)] + 10f, hf * 0.102f)

                val r4 = buildFractalRidge(layerPaths[3], wf, hf, 0.702f, 0f, 0.5f, 0.020f, 0.052f, dayOfYear + 71, 130)
                for (t in 0 until 54) {
                    val fx = (t + 0.1f + 0.8f * seedRand(dayOfYear + t, 79)) / 54f
                    val by = r4[(fx * 130).toInt().coerceIn(0, 130)] + 8f
                    val edgeBoost = 1f + 0.65f * (abs(fx - 0.5f) * 2f).pow(1.4f)
                    val th = hf * (0.042f + 0.044f * seedRand(dayOfYear + t, 83)) * edgeBoost
                    addFeatheryPine(layerPaths[3], fx * wf, by, th, th * 0.25f, dayOfYear + t + 300)
                }
                val r5 = buildFractalRidge(layerPaths[4], wf, hf, 0.768f, 0f, 0.5f, 0.016f, 0.062f, dayOfYear + 97, 110)
                val heroes = arrayOf(0.03f to 0.23f, 0.11f to 0.16f, 0.18f to 0.10f, 0.82f to 0.11f, 0.90f to 0.18f, 0.97f to 0.24f)
                for ((idx, pair) in heroes.withIndex()) {
                    val by = r5[(pair.first * 110).toInt().coerceIn(0, 110)] + 16f
                    val th = hf * pair.second
                    addFeatheryPine(layerPaths[4], pair.first * wf, by, th, th * 0.27f, dayOfYear + 500 + idx * 19)
                }
                for (t in 0 until 28) {
                    val fx = (t + 0.2f + 0.6f * seedRand(dayOfYear + t, 107)) / 28f
                    val by = r5[(fx * 110).toInt().coerceIn(0, 110)] + 10f
                    val th = hf * (0.032f + 0.040f * seedRand(dayOfYear + t, 109))
                    addFeatheryPine(layerPaths[4], fx * wf, by, th, th * 0.26f, dayOfYear + 600 + t)
                }
            }

            WorldBiome.SAND_DUNES -> {
                // Sharp graphic two-tone sunlit vs. shadow dune ridges (exact 0b54cc reference style!)
                val p1x = wf * 0.68f; val p1y = hf * 0.48f
                layerPaths[0].moveTo(0f, hf * 1.15f); layerPaths[0].lineTo(0f, hf * 0.62f)
                layerPaths[0].lineTo(p1x, p1y); layerPaths[0].lineTo(wf, hf * 0.66f); layerPaths[0].lineTo(wf, hf * 1.15f); layerPaths[0].close()
                accentPath.moveTo(p1x, p1y); accentPath.lineTo(wf, hf * 0.56f); accentPath.lineTo(wf, hf * 0.72f)
                accentPath.lineTo(p1x + wf * 0.14f, hf * 0.60f); accentPath.lineTo(p1x + wf * 0.08f, hf * 0.54f); accentPath.close()

                val p2x = wf * 0.12f; val p2y = hf * 0.56f
                layerPaths[1].moveTo(0f, hf * 1.15f); layerPaths[1].lineTo(0f, hf * 0.60f); layerPaths[1].lineTo(p2x, p2y)
                layerPaths[1].quadTo(wf * 0.55f, hf * 0.66f, wf, hf * 0.74f); layerPaths[1].lineTo(wf, hf * 1.15f); layerPaths[1].close()

                // Sunlit golden mid-slope (layerPaths[2])
                layerPaths[2].moveTo(p2x, p2y)
                layerPaths[2].quadTo(wf * 0.55f, hf * 0.66f, wf, hf * 0.74f)
                layerPaths[2].lineTo(wf, hf * 0.85f)
                layerPaths[2].quadTo(wf * 0.58f, hf * 0.80f, wf * 0.18f, hf * 0.76f)
                layerPaths[2].quadTo(wf * 0.28f, hf * 0.68f, p2x, p2y)
                layerPaths[2].close()

                // Warm lower ridge & velvet dark foreground
                layerPaths[3].moveTo(0f, hf * 1.15f); layerPaths[3].lineTo(0f, hf * 0.80f)
                layerPaths[3].lineTo(wf * 0.58f, hf * 0.76f); layerPaths[3].lineTo(wf, hf * 0.82f); layerPaths[3].lineTo(wf, hf * 1.15f); layerPaths[3].close()
                layerPaths[4].moveTo(0f, hf * 1.15f); layerPaths[4].lineTo(0f, hf * 0.86f)
                layerPaths[4].lineTo(wf * 0.48f, hf * 0.89f); layerPaths[4].lineTo(wf, hf * 0.92f); layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
            }

            WorldBiome.SNOWY_ALPENGLOW_LAKE -> {
                val p1 = 0.35f + seedRand(dayOfYear, 7) * 0.30f
                buildFractalRidge(layerPaths[0], wf, hf, 0.61f, 0.115f, p1, 0.044f, 0f, dayOfYear + 13, 200, baseH)
                val rMid = buildFractalRidge(layerPaths[1], wf, hf, 0.64f, 0.135f, 1f - p1 * 0.7f, 0.050f, 0f, dayOfYear + 23, 200, baseH)
                accentPath.moveTo(0f, hf * 0.56f)
                for (i in 0..200) accentPath.lineTo((i / 200f) * wf, rMid[i])
                for (i in 200 downTo 0) {
                    val fx = i / 200f
                    val depth = hf * (0.016f + 0.028f * ((hf * 0.64f - rMid[i]) / (hf * 0.16f)).coerceIn(0f, 1f))
                    accentPath.lineTo(fx * wf, minOf(hf * 0.60f, rMid[i] + depth))
                }
                accentPath.close()
                reflectPath.moveTo(0f, baseH)
                for (i in 0..100) {
                    val fx = i / 100f
                    val mY = rMid[(fx * 200).toInt().coerceIn(0, 200)]
                    reflectPath.lineTo(fx * wf, baseH + (baseH - mY).coerceAtLeast(0f) * 0.68f)
                }
                reflectPath.lineTo(wf, baseH); reflectPath.close()
                val rShore = buildFractalRidge(layerPaths[4], wf, hf, 0.81f, 0f, 0.5f, 0.014f, 0.052f, dayOfYear + 89, 100)
                for ((idx, pair) in arrayOf(0.04f to 0.22f, 0.12f to 0.15f, 0.88f to 0.16f, 0.96f to 0.23f).withIndex()) {
                    val by = rShore[(pair.first * 100).toInt().coerceIn(0, 100)] + 14f
                    addFeatheryPine(layerPaths[4], pair.first * wf, by, hf * pair.second, hf * pair.second * 0.26f, dayOfYear + 400 + idx * 17)
                }
            }

            WorldBiome.EMERALD_SAVANNA -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.61f, 0.024f, 0.38f, 0.014f, 0f, dayOfYear + 15, 140)
                buildFractalRidge(layerPaths[1], wf, hf, 0.645f, 0.012f, 0.65f, 0.010f, 0.012f, dayOfYear + 27, 120)
                val rMid = buildFractalRidge(layerPaths[2], wf, hf, 0.695f, 0f, 0.5f, 0.010f, 0.018f, dayOfYear + 41, 120)
                val a1 = 0.24f + seedRand(dayOfYear, 71) * 0.12f
                val a2 = 0.68f + seedRand(dayOfYear, 73) * 0.14f
                addArchitecturalAcacia(layerPaths[2], a1 * wf, rMid[(a1 * 120).toInt().coerceIn(0, 120)] + 6f, hf * 0.072f, wf * 0.24f, dayOfYear + 1)
                addArchitecturalAcacia(layerPaths[2], a2 * wf, rMid[(a2 * 120).toInt().coerceIn(0, 120)] + 6f, hf * 0.058f, wf * 0.20f, dayOfYear + 2)
                buildFractalRidge(layerPaths[3], wf, hf, 0.76f, 0f, 0.5f, 0.010f, 0.020f, dayOfYear + 67, 100)
                buildFractalRidge(layerPaths[4], wf, hf, 0.83f, 0f, 0.5f, 0.012f, 0.022f, dayOfYear + 91, 100)
            }

            WorldBiome.COASTAL_OCEAN -> {
                val onLeft = (dayOfYear % 2 == 0)
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.072f, onLeft, dayOfYear)
                buildHeadland(layerPaths[1], wf, baseH, hf * 0.042f, !onLeft, dayOfYear + 7)
            }

            WorldBiome.SUNLIT_WOODLAND -> {
                // Firewatch Sunlit Forest Clearing (ZERO prison bars, ZERO shelf trees!)
                val r1 = buildFractalRidge(layerPaths[0], wf, hf, 0.52f, 0f, 0.5f, 0.024f, 0.075f, dayOfYear + 15, 140)
                for (t in 0 until 54) {
                    val fx = (t + 0.2f + 0.6f * seedRand(dayOfYear + t, 21)) / 54f
                    addFeatheryPine(layerPaths[0], fx * wf, r1[(fx * 140).toInt().coerceIn(0, 140)] + 6f, hf * (0.048f + 0.038f * seedRand(dayOfYear + t, 23)), hf * 0.018f, dayOfYear + 100 + t)
                }
                val r2 = buildFractalRidge(layerPaths[2], wf, hf, 0.62f, 0f, 0.5f, 0.020f, 0.085f, dayOfYear + 41, 130)
                for (t in 0 until 44) {
                    val fx = (t + 0.15f + 0.7f * seedRand(dayOfYear + t, 47)) / 44f
                    if (fx in 0.40f..0.60f) continue
                    addFeatheryPine(layerPaths[2], fx * wf, r2[(fx * 130).toInt().coerceIn(0, 130)] + 8f, hf * (0.078f + 0.068f * seedRand(dayOfYear + t, 49)), hf * 0.026f, dayOfYear + 200 + t)
                }
                val r3 = buildFractalRidge(layerPaths[4], wf, hf, 0.76f, 0f, 0.5f, 0.014f, 0.065f, dayOfYear + 73, 100)
                for ((idx, pair) in arrayOf(0.04f to 0.42f, 0.14f to 0.28f, 0.86f to 0.30f, 0.96f to 0.44f).withIndex()) {
                    val by = r3[(pair.first * 100).toInt().coerceIn(0, 100)] + 22f
                    addFeatheryPine(layerPaths[4], pair.first * wf, by, hf * pair.second, hf * pair.second * 0.24f, dayOfYear + 500 + idx * 17)
                }
            }

            WorldBiome.COASTAL_REEDS -> {
                // Distant soft headland on far right only; dark bank strictly sloping from bottom-left
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.038f, false, dayOfYear)
                layerPaths[4].moveTo(0f, hf * 1.15f); layerPaths[4].lineTo(0f, hf * 0.84f)
                layerPaths[4].lineTo(wf * 0.32f, hf * 0.92f); layerPaths[4].lineTo(wf * 0.65f, hf * 0.98f)
                layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
            }

            WorldBiome.CRYSTAL_COVE -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.615f, 0.040f, 0.70f, 0.016f, 0f, dayOfYear + 19, 120, baseH)
                // Asymmetric stepped granite promontory on left + islet on right, both flush at baseH
                val lx = floatArrayOf(0f, 0f, wf * 0.12f, wf * 0.22f, wf * 0.34f, wf * 0.44f, wf * 0.48f, wf * 0.28f)
                val ly = floatArrayOf(baseH + 16f, baseH - hf * 0.095f, baseH - hf * 0.088f, baseH - hf * 0.056f, baseH - hf * 0.042f, baseH - hf * 0.012f, baseH + 4f, baseH + 16f)
                layerPaths[2].moveTo(lx[0], ly[0])
                for (i in 1 until lx.size) layerPaths[2].lineTo(lx[i], ly[i])
                layerPaths[2].close()
                addFeatheryPine(layerPaths[2], wf * 0.07f, baseH - hf * 0.088f, hf * 0.105f, hf * 0.026f, dayOfYear + 311)

                val rx = floatArrayOf(wf * 0.76f, wf * 0.82f, wf * 0.92f, wf, wf)
                val ry = floatArrayOf(baseH + 4f, baseH - hf * 0.032f, baseH - hf * 0.058f, baseH - hf * 0.072f, baseH + 12f)
                layerPaths[2].moveTo(rx[0], ry[0])
                for (i in 1 until rx.size) layerPaths[2].lineTo(rx[i], ry[i])
                layerPaths[2].close()

                // Soft inverted mirror reflection of promontory & islet below baseH
                reflectPath.moveTo(lx[0], baseH)
                for (i in 1 until lx.size) reflectPath.lineTo(lx[i], baseH + (baseH - ly[i]).coerceAtLeast(0f) * 0.55f)
                reflectPath.close()
                reflectPath.moveTo(rx[0], baseH)
                for (i in 1 until rx.size) reflectPath.lineTo(rx[i], baseH + (baseH - ry[i]).coerceAtLeast(0f) * 0.55f)
                reflectPath.close()
            }

            WorldBiome.KYOTO_TORII -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.58f, 0.072f, 0.52f, 0.028f, 0f, dayOfYear + 31, 160, baseH)
                buildFractalRidge(layerPaths[1], wf, hf, 0.615f, 0.032f, 0.22f, 0.020f, 0.020f, dayOfYear + 47, 140, baseH)
                addToriiGate(layerPaths[3], reflectPath, wf * 0.50f, baseH + hf * 0.092f, hf * 0.115f)
                val rShore = buildFractalRidge(layerPaths[4], wf, hf, 0.81f, 0f, 0.5f, 0.012f, 0.048f, dayOfYear + 83, 100)
                addFeatheryPine(layerPaths[4], wf * 0.06f, rShore[6] + 16f, hf * 0.21f, hf * 0.055f, dayOfYear + 701)
                addFeatheryPine(layerPaths[4], wf * 0.94f, rShore[94] + 16f, hf * 0.19f, hf * 0.050f, dayOfYear + 709)
                // Tiered Japanese stone lantern
                val lx = wf * 0.80f; val ly = rShore[80] + 4f; val lh = hf * 0.046f
                layerPaths[4].addRect(lx - wf * 0.008f, ly - lh * 0.55f, lx + wf * 0.008f, ly + 4f, Path.Direction.CW)
                layerPaths[4].addRect(lx - wf * 0.018f, ly - lh * 0.60f, lx + wf * 0.018f, ly - lh * 0.52f, Path.Direction.CW)
                layerPaths[4].moveTo(lx - wf * 0.026f, ly - lh * 0.76f); layerPaths[4].lineTo(lx, ly - lh); layerPaths[4].lineTo(lx + wf * 0.026f, ly - lh * 0.76f); layerPaths[4].close()
                focalX = lx; focalY = ly - lh * 0.68f; focalW = wf * 0.016f; focalH = lh * 0.16f
            }

            WorldBiome.LIGHTHOUSE_CLIFF -> {
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.052f, false, dayOfYear + 5)
                // Seated lower, flush fractal coastal cliff + pines + architectural lighthouse
                layerPaths[3].moveTo(0f, baseH + 4f)
                for (t in 0..40) {
                    val f = t / 40f
                    val y = baseH - (1f - f.pow(2.2f)) * (hf * 0.072f) - fbm1d(f, dayOfYear + 55, 4) * (hf * 0.014f)
                    layerPaths[3].lineTo(f * wf * 0.38f, minOf(baseH + 4f, y))
                }
                layerPaths[3].lineTo(wf * 0.38f, baseH + 4f); layerPaths[3].close()
                addFeatheryPine(layerPaths[3], wf * 0.05f, baseH - hf * 0.068f, hf * 0.10f, hf * 0.026f, dayOfYear + 301)
                addFeatheryPine(layerPaths[3], wf * 0.11f, baseH - hf * 0.064f, hf * 0.08f, hf * 0.022f, dayOfYear + 305)
                addLighthouse(layerPaths[3], wf * 0.22f, baseH - hf * 0.060f, hf * 0.128f)
                buildFractalRidge(layerPaths[4], wf, hf, 0.84f, 0f, 0.5f, 0.014f, 0.042f, dayOfYear + 101, 90)
            }

            WorldBiome.AURORA_FJORD -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.60f, 0.080f, 0.50f, 0.032f, 0f, dayOfYear + 21, 160, baseH)
                // V-walls STRICTLY clamped <= baseH (zero slivers below waterline!)
                layerPaths[2].moveTo(0f, baseH)
                for (i in 0..60) {
                    val t = i / 60f
                    val y = minOf(baseH, hf * (0.42f + t.pow(1.25f) * 0.23f) - fbm1d(t, dayOfYear + 33, 4) * (hf * 0.022f))
                    layerPaths[2].lineTo(t * wf * 0.44f, y)
                }
                layerPaths[2].lineTo(wf * 0.44f, baseH); layerPaths[2].close()
                layerPaths[2].moveTo(wf, baseH)
                for (i in 0..60) {
                    val t = i / 60f
                    val y = minOf(baseH, hf * (0.44f + t.pow(1.25f) * 0.21f) - fbm1d(t, dayOfYear + 49, 4) * (hf * 0.022f))
                    layerPaths[2].lineTo(wf - t * wf * 0.42f, y)
                }
                layerPaths[2].lineTo(wf * 0.58f, baseH); layerPaths[2].close()

                val rShore = buildFractalRidge(layerPaths[4], wf, hf, 0.80f, 0f, 0.5f, 0.014f, 0.048f, dayOfYear + 77, 100)
                val cx = wf * 0.28f; val cy = rShore[28] + 4f; val cw = wf * 0.095f; val ch = hf * 0.036f
                layerPaths[4].addRect(cx - cw * 0.5f, cy - ch, cx + cw * 0.5f, cy + 8f, Path.Direction.CW)
                layerPaths[4].moveTo(cx - cw * 0.62f, cy - ch); layerPaths[4].lineTo(cx, cy - ch * 1.75f); layerPaths[4].lineTo(cx + cw * 0.62f, cy - ch); layerPaths[4].close()
                layerPaths[4].addRect(cx + cw * 0.22f, cy - ch * 1.85f, cx + cw * 0.34f, cy - ch * 1.1f, Path.Direction.CW)
                addFeatheryPine(layerPaths[4], wf * 0.07f, rShore[7] + 16f, hf * 0.22f, hf * 0.056f, dayOfYear + 801)
                addFeatheryPine(layerPaths[4], wf * 0.92f, rShore[92] + 16f, hf * 0.20f, hf * 0.052f, dayOfYear + 809)
                focalX = cx; focalY = cy - ch * 0.52f; focalW = cw * 0.26f; focalH = ch * 0.38f
            }

            WorldBiome.VALLEY_STAG -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.58f, 0.080f, 0.36f, 0.036f, 0f, dayOfYear + 17, 180)
                val r2 = buildFractalRidge(layerPaths[1], wf, hf, 0.625f, 0f, 0.5f, 0.024f, 0.030f, dayOfYear + 37, 150)
                for (t in 0 until 56) {
                    val fx = (t + 0.2f + 0.6f * seedRand(dayOfYear + t, 43)) / 56f
                    if (fx in 0.36f..0.64f) continue
                    val by = r2[(fx * 150).toInt().coerceIn(0, 150)] + 4f
                    addFeatheryPine(layerPaths[1], fx * wf, by, hf * (0.025f + 0.025f * seedRand(dayOfYear + t, 45)), hf * 0.012f, dayOfYear + 250 + t)
                }
                // Organic Pocket Lake polygon (zero glowing oval!)
                for (i in 0 until 48) {
                    val ang = (i / 48.0) * PI * 2.0
                    val rx = wf * 0.24f * (1f + 0.14f * sin(ang * 3.0 + 0.5).toFloat())
                    val ry = hf * 0.034f * (1f + 0.18f * cos(ang * 2.0 - 0.3).toFloat())
                    val lx = wf * 0.56f + cos(ang).toFloat() * rx
                    val ly = hf * 0.725f + sin(ang).toFloat() * ry
                    if (i == 0) accentPath.moveTo(lx, ly) else accentPath.lineTo(lx, ly)
                }
                accentPath.close()
                val r4 = buildFractalRidge(layerPaths[4], wf, hf, 0.755f, 0f, 0.5f, 0.014f, 0.052f, dayOfYear + 63, 120)
                for ((idx, pair) in arrayOf(0.05f to 0.23f, 0.14f to 0.16f, 0.86f to 0.17f, 0.95f to 0.24f).withIndex()) {
                    val by = r4[(pair.first * 120).toInt().coerceIn(0, 120)] + 16f
                    addFeatheryPine(layerPaths[4], pair.first * wf, by, hf * pair.second, hf * pair.second * 0.26f, dayOfYear + 900 + idx * 13)
                }
                addStagSilhouette(layerPaths[4], wf * 0.32f, r4[38] + 4f, hf * 0.072f)
            }

            WorldBiome.CAPPADOCIA_BALLOONS -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.60f, 0f, 0.5f, 0.042f, 0f, dayOfYear + 11, 150)
                val r1 = buildFractalRidge(layerPaths[1], wf, hf, 0.67f, 0f, 0.5f, 0.034f, 0.032f, dayOfYear + 29, 130)
                for ((fx, chf) in arrayOf(0.16f to 0.055f, 0.22f to 0.042f, 0.78f to 0.058f, 0.85f to 0.044f)) {
                    val cx = fx * wf; val cy = r1[(fx * 130).toInt().coerceIn(0, 130)] + 8f; val ch = hf * chf
                    val hw = wf * 0.025f
                    layerPaths[1].moveTo(cx - hw, cy); layerPaths[1].lineTo(cx - hw * 0.65f, cy - ch * 0.72f)
                    layerPaths[1].lineTo(cx - hw * 0.95f, cy - ch * 0.84f); layerPaths[1].lineTo(cx, cy - ch)
                    layerPaths[1].lineTo(cx + hw * 0.95f, cy - ch * 0.84f); layerPaths[1].lineTo(cx + hw * 0.65f, cy - ch * 0.72f)
                    layerPaths[1].lineTo(cx + hw, cy); layerPaths[1].close()
                }
                buildFractalRidge(layerPaths[4], wf, hf, 0.78f, 0f, 0.5f, 0.018f, 0.045f, dayOfYear + 61, 100)
            }

            WorldBiome.ODISHA_BANYAN_GHAT -> {
                // Distant riverbank + Kalinga (Odisha) Rekha-Deula Temple Shikhara spire
                buildFractalRidge(layerPaths[0], wf, hf, 0.61f, 0f, 0.5f, 0.022f, 0f, dayOfYear + 17, 120, baseH)
                val tx = wf * 0.72f; val ty = baseH - hf * 0.015f; val th = hf * 0.105f; val tw = wf * 0.034f
                layerPaths[0].moveTo(tx - tw, ty + 10f); layerPaths[0].lineTo(tx - tw * 0.88f, ty - th * 0.35f)
                layerPaths[0].lineTo(tx - tw * 0.65f, ty - th * 0.68f); layerPaths[0].lineTo(tx - tw * 0.32f, ty - th * 0.86f)
                layerPaths[0].lineTo(tx, ty - th); layerPaths[0].lineTo(tx + tw * 0.32f, ty - th * 0.86f)
                layerPaths[0].lineTo(tx + tw * 0.65f, ty - th * 0.68f); layerPaths[0].lineTo(tx + tw * 0.88f, ty - th * 0.35f)
                layerPaths[0].lineTo(tx + tw, ty + 10f); layerPaths[0].close()
                // Adjacent Jagamohana stepped roof
                layerPaths[0].moveTo(tx - tw * 2.1f, ty + 10f); layerPaths[0].lineTo(tx - tw * 2.1f, ty - th * 0.24f)
                layerPaths[0].lineTo(tx - tw * 1.5f, ty - th * 0.48f); layerPaths[0].lineTo(tx - tw * 0.9f, ty - th * 0.24f)
                layerPaths[0].lineTo(tx - tw * 0.9f, ty + 10f); layerPaths[0].close()

                // Stepped Stone Ghat + Connected Branching Sacred Banyan Tree (zero disconnected blobs!)
                for (st in 0 until 5) {
                    val sy = hf * (0.81f + st * 0.024f)
                    layerPaths[4].addRect(0f, sy, wf * (0.34f + st * 0.07f), hf * 1.15f, Path.Direction.CW)
                }
                layerPaths[4].moveTo(0f, hf * 1.15f); layerPaths[4].lineTo(0f, hf * 0.88f); layerPaths[4].lineTo(wf * 0.55f, hf * 0.92f)
                layerPaths[4].lineTo(wf, hf * 0.88f); layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
                // Massive fluted Banyan trunk connected solidly into spreading primary boughs
                val bx = wf * 0.14f; val by = hf * 0.82f; val crownY = hf * 0.61f
                addLineQuad(layerPaths[4], bx, by + 10f, bx + wf * 0.01f, crownY, wf * 0.042f)
                addLineQuad(layerPaths[4], bx, crownY + hf * 0.06f, wf * 0.04f, crownY - hf * 0.02f, wf * 0.022f)
                addLineQuad(layerPaths[4], bx + wf * 0.01f, crownY + hf * 0.05f, wf * 0.28f, crownY - hf * 0.01f, wf * 0.020f)
                addLineQuad(layerPaths[4], bx + wf * 0.02f, crownY + hf * 0.07f, wf * 0.39f, crownY + hf * 0.03f, wf * 0.014f)
                // Organic multi-faceted leafy Banyan crown dome
                layerPaths[4].moveTo(-wf * 0.04f, crownY + hf * 0.02f)
                for (i in 0..36) {
                    val t = i / 36f
                    val x = -wf * 0.04f + t * wf * 0.50f
                    val dome = sin(t * PI).toFloat().pow(0.62f)
                    val leafBump = 0.86f + 0.18f * sin(t * 15.0 * PI + dayOfYear).toFloat()
                    layerPaths[4].lineTo(x, crownY + hf * 0.03f - dome * hf * 0.14f * leafBump)
                }
                layerPaths[4].lineTo(wf * 0.46f, crownY + hf * 0.04f); layerPaths[4].close()
                // Hanging aerial prop-roots descending into the ghat
                for ((ri, rxf) in floatArrayOf(0.06f, 0.11f, 0.20f, 0.26f, 0.33f, 0.38f).withIndex()) {
                    val rLen = hf * (0.14f + (ri % 3) * 0.035f)
                    addLineQuad(layerPaths[4], rxf * wf, crownY + hf * 0.01f, rxf * wf + wf * 0.004f, crownY + rLen, wf * 0.0025f)
                }
            }

            WorldBiome.HIMALAYAN_MONASTERY -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.56f, 0.14f, 0.44f, 0.044f, 0f, dayOfYear + 19, 180)
                val r1 = buildFractalRidge(layerPaths[1], wf, hf, 0.64f, 0f, 0.5f, 0.030f, 0.050f, dayOfYear + 39, 140)
                val mx = wf * 0.68f; val my = r1[95] + 6f
                layerPaths[1].addRect(mx - wf * 0.070f, my - hf * 0.028f, mx + wf * 0.070f, my + 12f, Path.Direction.CW)
                layerPaths[1].addRect(mx - wf * 0.044f, my - hf * 0.048f, mx + wf * 0.040f, my - hf * 0.024f, Path.Direction.CW)
                layerPaths[1].moveTo(mx - wf * 0.052f, my - hf * 0.048f); layerPaths[1].lineTo(mx, my - hf * 0.060f); layerPaths[1].lineTo(mx + wf * 0.050f, my - hf * 0.048f); layerPaths[1].close()
                layerPaths[1].moveTo(mx + wf * 0.055f, my - hf * 0.028f); layerPaths[1].lineTo(mx + wf * 0.070f, my - hf * 0.048f); layerPaths[1].lineTo(mx + wf * 0.085f, my - hf * 0.028f); layerPaths[1].close()
                focalX = mx; focalY = my - hf * 0.035f; focalW = wf * 0.060f; focalH = hf * 0.008f

                val r2 = buildFractalRidge(layerPaths[4], wf, hf, 0.76f, 0f, 0.5f, 0.016f, 0.080f, dayOfYear + 73, 100)
                val pLy = r2[8] - hf * 0.085f; val pRy = r2[92] - hf * 0.070f
                addLineQuad(layerPaths[4], wf * 0.08f, r2[8] + 10f, wf * 0.08f, pLy, wf * 0.004f)
                addLineQuad(layerPaths[4], wf * 0.92f, r2[92] + 10f, wf * 0.92f, pRy, wf * 0.004f)
                auxX = pLy; auxY = pRy
            }

            WorldBiome.ALPINE_VIADUCT -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.57f, 0.085f, 0.46f, 0.034f, 0f, dayOfYear + 23, 160)
                // 5-Arch Stone Railway Viaduct (solid piers + semicircular spandrel arches!)
                val deckY = hf * 0.64f
                val archW = wf / 5f
                val pierHW = archW * 0.13f
                layerPaths[2].addRect(0f, deckY, wf, deckY + hf * 0.022f, Path.Direction.CW)
                for (p in 0..5) {
                    val px = p * archW
                    layerPaths[2].addRect(px - pierHW, deckY, px + pierHW, hf * 0.90f, Path.Direction.CW)
                }
                // Arched spandrel shoulders between piers
                for (a in 0 until 5) {
                    val ax0 = a * archW + pierHW - 1f
                    val ax1 = (a + 1) * archW - pierHW + 1f
                    val r = (ax1 - ax0) * 0.5f
                    val topA = deckY + hf * 0.020f
                    layerPaths[2].moveTo(ax0, topA)
                    layerPaths[2].lineTo(ax1, topA)
                    layerPaths[2].lineTo(ax1, topA + r)
                    for (k in 0..16) {
                        val ang = (k / 16.0) * PI
                        layerPaths[2].lineTo((ax0 + ax1) * 0.5f + cos(ang).toFloat() * r, topA + r - sin(ang).toFloat() * r)
                    }
                    layerPaths[2].close()
                }
                focalY = deckY
                val r4 = buildFractalRidge(layerPaths[4], wf, hf, 0.76f, 0f, 0.5f, 0.016f, 0.075f, dayOfYear + 89, 100)
                for ((idx, pair) in arrayOf(0.04f to 0.28f, 0.14f to 0.19f, 0.86f to 0.20f, 0.96f to 0.29f).withIndex()) {
                    val by = r4[(pair.first * 100).toInt().coerceIn(0, 100)] + 16f
                    addFeatheryPine(layerPaths[4], pair.first * wf, by, hf * pair.second, hf * pair.second * 0.26f, dayOfYear + 700 + idx * 17)
                }
            }

            WorldBiome.CANYON_WINDMILL -> {
                // Monument Valley flat-topped stepped mesas
                layerPaths[0].moveTo(0f, hf * 1.15f); layerPaths[0].lineTo(0f, hf * 0.64f)
                layerPaths[0].lineTo(wf * 0.14f, hf * 0.64f); layerPaths[0].lineTo(wf * 0.20f, hf * 0.54f)
                layerPaths[0].lineTo(wf * 0.36f, hf * 0.54f); layerPaths[0].lineTo(wf * 0.42f, hf * 0.64f)
                layerPaths[0].lineTo(wf * 0.64f, hf * 0.64f); layerPaths[0].lineTo(wf * 0.69f, hf * 0.56f)
                layerPaths[0].lineTo(wf * 0.84f, hf * 0.56f); layerPaths[0].lineTo(wf * 0.90f, hf * 0.64f)
                layerPaths[0].lineTo(wf, hf * 0.64f); layerPaths[0].lineTo(wf, hf * 1.15f); layerPaths[0].close()

                val r1 = buildFractalRidge(layerPaths[2], wf, hf, 0.70f, 0f, 0.5f, 0.018f, 0.025f, dayOfYear + 31, 100)
                val wx = wf * 0.70f; val wy = r1[70] + 6f; val wh = hf * 0.165f; val topY = wy - wh
                addLineQuad(layerPaths[2], wx - wf * 0.033f, wy, wx - wf * 0.009f, topY, wf * 0.003f)
                addLineQuad(layerPaths[2], wx + wf * 0.033f, wy, wx + wf * 0.009f, topY, wf * 0.003f)
                for (t in 0 until 4) {
                    val f0 = t / 4f; val f1 = (t + 1) / 4f
                    val y0 = wy - wh * f0; val y1 = wy - wh * f1
                    val w0 = wf * (0.033f * (1f - f0) + 0.009f * f0)
                    val w1 = wf * (0.033f * (1f - f1) + 0.009f * f1)
                    addLineQuad(layerPaths[2], wx - w0, y0, wx + w0, y0, wf * 0.002f)
                    addLineQuad(layerPaths[2], wx - w0, y0, wx + w1, y1, wf * 0.0015f)
                    addLineQuad(layerPaths[2], wx + w0, y0, wx - w1, y1, wf * 0.0015f)
                }
                addLineQuad(layerPaths[2], wx, topY, wx - wf * 0.048f, topY - 2f, wf * 0.002f)
                layerPaths[2].moveTo(wx - wf * 0.040f, topY - hf * 0.010f); layerPaths[2].lineTo(wx - wf * 0.068f, topY - hf * 0.008f)
                layerPaths[2].lineTo(wx - wf * 0.064f, topY + hf * 0.006f); layerPaths[2].lineTo(wx - wf * 0.040f, topY + hf * 0.004f); layerPaths[2].close()
                focalX = wx; focalY = topY; focalW = wf * 0.052f

                // Foreground dark floor + 2 Saguaro cacti
                val r2 = buildFractalRidge(layerPaths[4], wf, hf, 0.81f, 0f, 0.5f, 0.012f, 0.032f, dayOfYear + 77, 80)
                for ((sxf, shf) in arrayOf(0.18f to 0.095f, 0.28f to 0.062f)) {
                    val sx = sxf * wf; val sy = r2[(sxf * 80).toInt().coerceIn(0, 80)] + 6f; val sh = hf * shf; val r = wf * 0.009f
                    rect.set(sx - r, sy - sh, sx + r, sy + 4f); layerPaths[4].addRoundRect(rect, r, r, Path.Direction.CW)
                    rect.set(sx - r * 4f, sy - sh * 0.72f, sx - r * 2.4f, sy - sh * 0.36f); layerPaths[4].addRoundRect(rect, r, r, Path.Direction.CW)
                    layerPaths[4].addRect(sx - r * 3.2f, sy - sh * 0.42f, sx, sy - sh * 0.34f, Path.Direction.CW)
                    rect.set(sx + r * 2.4f, sy - sh * 0.82f, sx + r * 4f, sy - sh * 0.48f); layerPaths[4].addRoundRect(rect, r, r, Path.Direction.CW)
                    layerPaths[4].addRect(sx, sy - sh * 0.54f, sx + r * 3.2f, sy - sh * 0.46f, Path.Direction.CW)
                }
            }

            WorldBiome.MISTY_BAMBOO -> {
                // Staggered curved bamboo culms + pointed leaf clusters (ZERO horizontal node stripes!)
                fun buildBambooLayer(path: Path, count: Int, salt: Int, halfW: Float, openCenter: Boolean) {
                    for (i in 0 until count) {
                        val fx = (i + 0.18f + 0.64f * seedRand(dayOfYear + i, salt)) / count
                        if (openCenter && fx in 0.36f..0.64f) continue
                        val bx = fx * wf
                        val lean = (seedRand(dayOfYear + i, salt + 5) - 0.5f) * wf * 0.10f
                        val phaseOffset = seedRand(dayOfYear + i, salt + 13) * 0.11f // Staggered nodes per stalk!
                        val segs = 9
                        for (s in 0 until segs) {
                            val f0 = (s / segs.toFloat() + phaseOffset).coerceAtMost(1.06f)
                            val f1 = ((s + 1) / segs.toFloat() + phaseOffset).coerceAtMost(1.06f)
                            val x0 = bx + lean * f0 + sin(f0 * PI).toFloat() * lean * 0.25f
                            val y0 = hf * (1.04f - f0 * 1.08f)
                            val x1 = bx + lean * f1 + sin(f1 * PI).toFloat() * lean * 0.25f
                            val y1 = hf * (1.04f - f1 * 1.08f)
                            addLineQuad(path, x0, y0, x1, y1, halfW)
                            addLineQuad(path, x1 - halfW * 1.35f, y1, x1 + halfW * 1.35f, y1, halfW * 0.45f)
                            if (s >= 2 && seedRand(dayOfYear + i * 10 + s, salt + 9) > 0.32f) {
                                val side = if ((i + s) % 2 == 0) 1f else -1f
                                for (lf in 0 until 4) {
                                    val ang = (0.35f + lf * 0.28f) * side
                                    val llen = wf * (0.036f + 0.032f * seedRand(dayOfYear + i + s + lf, salt + 11))
                                    val lx2 = x1 + side * cos(abs(ang)) * llen
                                    val ly2 = y1 + sin(abs(ang)) * llen * 0.65f - hf * 0.006f
                                    path.moveTo(x1, y1); path.lineTo((x1 + lx2) * 0.5f, (y1 + ly2) * 0.5f - 3f)
                                    path.lineTo(lx2, ly2); path.lineTo((x1 + lx2) * 0.5f, (y1 + ly2) * 0.5f + 3f); path.close()
                                }
                            }
                        }
                    }
                }
                buildBambooLayer(layerPaths[0], 13, 11, wf * 0.0038f, false)
                buildBambooLayer(layerPaths[2], 10, 31, wf * 0.0065f, true)
                buildBambooLayer(layerPaths[4], 7, 61, wf * 0.010f, true)
                buildFractalRidge(layerPaths[4], wf, hf, 0.85f, 0f, 0.5f, 0.010f, 0.035f, dayOfYear + 93, 60)
            }

            WorldBiome.VOLCANIC_CALDERA -> {
                // Broken volcanic caldera island flush on baseH
                layerPaths[2].moveTo(wf * 0.04f, baseH + 3f); layerPaths[2].lineTo(wf * 0.22f, hf * 0.57f)
                layerPaths[2].lineTo(wf * 0.35f, hf * 0.475f); layerPaths[2].lineTo(wf * 0.40f, hf * 0.460f)
                layerPaths[2].lineTo(wf * 0.44f, hf * 0.478f); layerPaths[2].lineTo(wf * 0.48f, hf * 0.462f)
                layerPaths[2].lineTo(wf * 0.54f, hf * 0.485f); layerPaths[2].lineTo(wf * 0.68f, hf * 0.58f)
                layerPaths[2].lineTo(wf * 0.86f, baseH + 3f); layerPaths[2].close()
                focalX = wf * 0.44f; focalY = hf * 0.468f

                // Sharp foreground basalt sea-stacks
                layerPaths[4].moveTo(0f, hf * 1.15f); layerPaths[4].lineTo(0f, hf * 0.74f); layerPaths[4].lineTo(wf * 0.14f, hf * 0.78f)
                layerPaths[4].lineTo(wf * 0.18f, hf * 0.70f); layerPaths[4].lineTo(wf * 0.24f, hf * 0.84f); layerPaths[4].lineTo(wf * 0.44f, hf * 0.92f)
                layerPaths[4].lineTo(wf, hf * 0.88f); layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
                layerPaths[4].moveTo(wf * 0.82f, hf * 0.84f); layerPaths[4].lineTo(wf * 0.87f, hf * 0.72f)
                layerPaths[4].lineTo(wf * 0.94f, hf * 0.76f); layerPaths[4].lineTo(wf, hf * 0.72f); layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
            }

            WorldBiome.HIGHLAND_LOCH -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.57f, 0.042f, 0.42f, 0.024f, 0f, dayOfYear + 27, 150, baseH)
                // Rocky peninsula + Crumbling Stone Castle Keep (with battlements!) flush at baseH
                layerPaths[2].moveTo(wf * 0.44f, baseH + 3f); layerPaths[2].lineTo(wf * 0.52f, baseH - hf * 0.018f)
                layerPaths[2].lineTo(wf * 0.76f, baseH - hf * 0.022f); layerPaths[2].lineTo(wf, baseH - hf * 0.036f)
                layerPaths[2].lineTo(wf, baseH + 3f); layerPaths[2].close()
                val cx = wf * 0.64f; val cy = baseH - hf * 0.018f; val kw = wf * 0.042f; val kh = hf * 0.070f
                layerPaths[2].addRect(cx - kw, cy - kh, cx + kw * 0.85f, cy + 4f, Path.Direction.CW)
                for (b in 0 until 3) {
                    val bx0 = cx - kw + b * kw * 0.66f
                    layerPaths[2].addRect(bx0, cy - kh - hf * 0.008f, bx0 + kw * 0.34f, cy - kh, Path.Direction.CW)
                }
                layerPaths[2].moveTo(cx + kw * 0.85f, cy - kh * 0.66f); layerPaths[2].lineTo(cx + kw * 2.0f, cy - kh * 0.48f)
                layerPaths[2].lineTo(cx + kw * 2.4f, cy - kh * 0.78f); layerPaths[2].lineTo(cx + kw * 2.9f, cy - kh * 0.70f)
                layerPaths[2].lineTo(cx + kw * 2.9f, cy + 4f); layerPaths[2].lineTo(cx + kw * 0.85f, cy + 4f); layerPaths[2].close()
                // Inverted castle reflection below baseH
                reflectPath.addRect(cx - kw, baseH, cx + kw * 0.85f, baseH + kh * 0.58f, Path.Direction.CW)
                reflectPath.addRect(cx + kw * 0.85f, baseH, cx + kw * 2.9f, baseH + kh * 0.42f, Path.Direction.CW)
                focalX = cx - kw * 0.08f; focalY = cy - kh * 0.52f; focalW = kw * 0.42f; focalH = kh * 0.28f
                buildFractalRidge(layerPaths[4], wf, hf, 0.84f, 0f, 0.5f, 0.012f, 0.048f, dayOfYear + 109, 80)
            }
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

    private fun drawGradientLayer(
        canvas: Canvas, path: Path, topY: Float, botY: Float,
        topCol: Int, botCol: Int, dx: Float, dy: Float, alpha: Int
    ) {
        fillPaint.shader = LinearGradient(0f, topY, 0f, botY, intArrayOf(topCol, botCol), null, Shader.TileMode.CLAMP)
        fillPaint.alpha = alpha.coerceIn(0, 255)
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawPath(path, fillPaint)
        canvas.restore()
        fillPaint.shader = null
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
        val biome = biomeForDay(dayOfYear)
        ensureGeometry(dayOfYear, width, height)

        val horizonY = horizonY(height, lockBlend)
        val baseHorizon = height * 0.64f
        val horizonShiftY = horizonY - baseHorizon
        val midShiftY = horizonShiftY * 0.72f
        val foreShiftY = horizonShiftY * 1.12f

        val isDay = sky.sunAltDeg > -2f
        val isDuskOrNight = sky.sunAltDeg < 2f
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
            sky.moonAltDeg > 0f -> 0.32f * (0.4f + 0.6f * sky.moonIllumination)
            else -> 0.12f
        }
        val windDir = if (weather.windDirDeg in 0f..180f) -1f else 1f
        val steadyWind = weather.windKmh.coerceIn(2f, 60f)
        val fogFactor = ((4500f - weather.visibilityM) / 4000f).coerceIn(0f, 1f)
        val orbX = if (sky.sunAltDeg > -4f) sunX else moonX

        val deepVelvet = if (isDay) ColorUtils.blendARGB(0xFF0A0D18.toInt(), circ.primary, 0.22f) else 0xFF050612.toInt()
        val midTone = ColorUtils.blendARGB(circ.primary, circ.horizonColor, if (isDay) 0.38f else 0.25f)
        val mistColor = if (isDay) ColorUtils.blendARGB(circ.horizonColor, lightColor, 0.45f) else ColorUtils.blendARGB(circ.horizonColor, sky.moonColor, 0.35f)
        val mistRgb = mistColor and 0x00FFFFFF

        when (biome) {
            WorldBiome.FIREWATCH_LOOKOUT -> {
                val c1Top = ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f)
                val c1Bot = ColorUtils.blendARGB(midTone, deepVelvet, 0.25f)
                val c2Top = ColorUtils.blendARGB(midTone, deepVelvet, 0.22f)
                val c2Bot = ColorUtils.blendARGB(midTone, deepVelvet, 0.48f)
                val c3Top = ColorUtils.blendARGB(midTone, deepVelvet, 0.45f)
                val c3Bot = ColorUtils.blendARGB(midTone, deepVelvet, 0.72f)
                val c4Top = ColorUtils.blendARGB(midTone, deepVelvet, 0.68f)
                val c4Bot = ColorUtils.blendARGB(midTone, deepVelvet, 0.90f)
                val c5Top = ColorUtils.blendARGB(deepVelvet, 0xFF03040A.toInt(), 0.45f)

                drawGradientLayer(canvas, layerPaths[0], height * 0.46f, height * 0.74f, c1Top, c1Bot, 0f, midShiftY * 0.45f, masterAlpha)
                val m1A = (masterAlpha * (0.24f + fogFactor * 0.20f)).toInt().coerceIn(0, 170)
                val drift1 = (((timeSec * (0.0022 + steadyWind * 0.00018) * windDir) % 1.0 + 1.0) % 1.0).toFloat()
                drawSoftMist(canvas, (drift1 * 1.4f - 0.2f) * width, horizonY - height * 0.03f, width * 0.85f, height * 0.045f, mistRgb or (m1A shl 24))
                drawGradientLayer(canvas, layerPaths[1], height * 0.50f, height * 0.80f, c2Top, c2Bot, 0f, midShiftY * 0.68f, masterAlpha)
                drawSoftMist(canvas, width * 0.5f, horizonY + height * 0.01f, width * 0.92f, height * 0.038f, mistRgb or ((m1A * 0.8f).toInt() shl 24))
                drawGradientLayer(canvas, layerPaths[2], height * 0.56f, height * 0.86f, c3Top, c3Bot, 0f, midShiftY * 0.88f, masterAlpha)

                val lampStr = ((3f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                if (lampStr > 0.05f && focalW > 0f) {
                    val fy = focalY + midShiftY * 0.88f
                    val flicker = 0.92f + 0.08f * sin(timeSec * 3.2).toFloat()
                    drawSoftMist(canvas, focalX, fy, focalW * 2.4f, focalH * 2.8f, 0x00FFD268 or ((masterAlpha * 0.52f * lampStr * flicker).toInt().coerceIn(0, 255) shl 24))
                    fillPaint.color = 0xFFFFE28A.toInt(); fillPaint.alpha = (masterAlpha * 0.95f * lampStr).toInt()
                    canvas.drawRect(focalX - focalW * 0.5f, fy - focalH * 0.5f, focalX + focalW * 0.5f, fy + focalH * 0.5f, fillPaint)
                }

                val sway4 = sin(timeSec * 0.42).toFloat() * (density * (0.8f + steadyWind * 0.03f)) * windDir
                drawGradientLayer(canvas, layerPaths[3], height * 0.64f, height * 0.94f, c4Top, c4Bot, sway4, foreShiftY * 0.80f, masterAlpha)
                val sway5 = sin(timeSec * 0.52 * 1.414).toFloat() * (density * (1.2f + steadyWind * 0.045f)) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), c5Top, 0xFF03040A.toInt(), sway5, foreShiftY, masterAlpha)
                drawFireflies(canvas, width, height, density, dayOfYear, horizonY, sky, weather, timeSec, masterAlpha)
                if (isDay || alpenglow > 0.1f) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.SAND_DUNES -> {
                val litGold = if (isDay) ColorUtils.blendARGB(0xFFFFC628.toInt(), lightColor, 0.35f) else ColorUtils.blendARGB(0xFF475569.toInt(), sky.moonColor, 0.45f)
                val litCoral = if (isDay) ColorUtils.blendARGB(0xFFE8723E.toInt(), lightColor, 0.30f) else ColorUtils.blendARGB(0xFF1E293B.toInt(), sky.moonColor, 0.25f)
                val shDark = if (isDay) 0xFF162638.toInt() else 0xFF070C16.toInt()
                val shDeep = if (isDay) 0xFF0C1826.toInt() else 0xFF03060C.toInt()

                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, height * 0.78f, shDark, shDeep, 0f, midShiftY * 0.5f, masterAlpha)
                drawGradientLayer(canvas, accentPath, height * 0.48f, height * 0.70f, litGold, litCoral, 0f, midShiftY * 0.5f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.55f, height * 0.88f, shDark, shDeep, 0f, midShiftY * 0.8f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[2], height * 0.56f, height * 0.84f, litGold, litCoral, 0f, midShiftY * 0.8f, masterAlpha)

                // 3 Tiny Travelers walking serenely across the golden dune slope with dynamic sun-opposite shadows!
                val shadowDir = if (orbX >= width * 0.5f) -1f else 1f
                val shadowStretch = (1.4f + abs(orbX - width * 0.5f) / (width * 0.35f)).coerceIn(1.2f, 2.5f)
                val walkBase = (((timeSec * 0.0045) % 1.0) + 1.0) % 1.0
                for (tIdx in 0 until 3) {
                    val u = ((walkBase + tIdx * 0.048) % 1.0).toFloat()
                    if (u !in 0.16f..0.88f) continue
                    val omt = 1f - u
                    val tx = omt * omt * (width * 0.12f) + 2f * omt * u * (width * 0.55f) + u * u * width
                    val ty = omt * omt * (height * 0.56f) + 2f * omt * u * (height * 0.66f) + u * u * (height * 0.74f) + midShiftY * 0.8f + density * 8.5f
                    val figH = density * (7.0f - tIdx * 0.5f)
                    // Dynamic shadow angled away from live sun/moon
                    strokePaint.color = if (isDay) 0xFF6B2818.toInt() else 0xFF040812.toInt()
                    strokePaint.alpha = (masterAlpha * 0.55f).toInt()
                    strokePaint.strokeWidth = density * 1.1f
                    canvas.drawLine(tx, ty, tx + figH * shadowStretch * shadowDir, ty - figH * 0.35f, strokePaint)
                    // Traveler silhouette
                    fillPaint.color = 0xFF261212.toInt()
                    fillPaint.alpha = (masterAlpha * 0.92f).toInt()
                    canvas.drawCircle(tx, ty - figH, density * 1.05f, fillPaint)
                    canvas.drawRect(tx - density * 1.0f, ty - figH + density * 1.2f, tx + density * 1.0f, ty, fillPaint)
                }

                drawGradientLayer(canvas, layerPaths[3], height * 0.76f, height * 0.92f, litCoral, shDark, 0f, foreShiftY * 0.85f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.86f, height.toFloat(), shDark, shDeep, 0f, foreShiftY, masterAlpha)
                if (isDay) drawThermalEagles(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.SNOWY_ALPENGLOW_LAKE -> {
                val rockFarTop = ColorUtils.blendARGB(circ.horizonColor, midTone, 0.40f)
                val rockMidTop = ColorUtils.blendARGB(0xFF334155.toInt(), lightColor, 0.52f * lightStrength)
                drawGradientLayer(canvas, layerPaths[0], height * 0.44f, horizonY, rockFarTop, midTone, 0f, horizonShiftY, (masterAlpha * 0.82f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.44f, horizonY, rockMidTop, deepVelvet, 0f, horizonShiftY, masterAlpha)
                val snowCol = ColorUtils.blendARGB(0xFFE2E8F0.toInt(), lightColor, if (alpenglow > 0.1f) 0.72f else 0.36f)
                drawGradientLayer(canvas, accentPath, height * 0.44f, horizonY - height * 0.04f, snowCol, ColorUtils.blendARGB(snowCol, deepVelvet, 0.70f), 0f, horizonShiftY, (masterAlpha * 0.88f).toInt())

                val lakeTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.28f else 0.72f)
                val lakeBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.65f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(lakeTop, lakeBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                fillPaint.color = ColorUtils.blendARGB(rockMidTop, lakeTop, 0.48f); fillPaint.alpha = (masterAlpha * 0.26f).toInt()
                canvas.save(); canvas.translate(0f, horizonShiftY); canvas.drawPath(reflectPath, fillPaint); canvas.restore()
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.88f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.72f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                if (isDay) drawThermalEagles(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.EMERALD_SAVANNA -> {
                val grassAlbedo = if (alpenglow > 0.25f) 0xFF65A30D.toInt() else 0xFF3F6E1E.toInt()
                val litGrass = ColorUtils.blendARGB(grassAlbedo, lightColor, 0.38f * lightStrength)
                for (l in 0 until 5) {
                    val d = l / 4f
                    val topC = ColorUtils.blendARGB(ColorUtils.blendARGB(circ.horizonColor, litGrass, 0.45f), deepVelvet, d * 0.82f)
                    val botC = ColorUtils.blendARGB(topC, 0xFF050A06.toInt(), 0.45f + d * 0.40f)
                    drawGradientLayer(canvas, layerPaths[l], height * (0.56f + l * 0.05f), height.toFloat(), topC, botC, 0f, midShiftY * (0.4f + d * 0.8f), masterAlpha)
                }
                drawSwayingGrass(canvas, width, height, density, dayOfYear, height * 0.86f + foreShiftY, ColorUtils.blendARGB(litGrass, 0xFF060C08.toInt(), 0.55f), windDir, steadyWind, timeSec, masterAlpha)
                if (isDay) drawThermalEagles(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.COASTAL_OCEAN -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.08f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.25f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.74f).toInt())
                drawGradientLayer(canvas, layerPaths[1], horizonY - height * 0.05f, horizonY + 4f, ColorUtils.blendARGB(midTone, deepVelvet, 0.35f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.82f).toInt())
                drawOceanSurfaceAndLife(canvas, width, height, density, horizonY, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha, showBoat = true)
            }

            WorldBiome.SUNLIT_WOODLAND -> {
                val gMid = ColorUtils.blendARGB(if (isDay) 0xFF2E5C42.toInt() else 0xFF0F2420.toInt(), circ.horizonColor, 0.38f)
                val gDeep = if (isDay) 0xFF0B1F14.toInt() else 0xFF040A08.toInt()
                drawGradientLayer(canvas, layerPaths[0], height * 0.44f, height * 0.78f, ColorUtils.blendARGB(circ.horizonColor, gMid, 0.48f), gMid, 0f, midShiftY * 0.5f, masterAlpha)
                drawSoftMist(canvas, width * 0.5f, height * 0.64f + midShiftY * 0.6f, width * 0.75f, height * 0.06f, mistRgb or ((masterAlpha * (0.28f + fogFactor * 0.20f)).toInt().coerceIn(0, 180) shl 24))
                drawGradientLayer(canvas, layerPaths[2], height * 0.52f, height * 0.88f, gMid, gDeep, 0f, midShiftY * 0.85f, masterAlpha)

                // Soft volumetric diagonal shafts pouring into the glade
                val orbVis = if (isDay) sky.sunAltDeg > -1f else sky.moonAltDeg > 1f
                if (orbVis) {
                    val rayCol = if (isDay) sky.sunCoronaColor else sky.moonColor
                    val rA = (masterAlpha * (if (isDay) 0.14f else 0.08f) * (1f - weather.cloudCoverPct / 140f).coerceIn(0.2f, 1f)).toInt().coerceIn(0, 120)
                    val slant = (width * 0.5f - orbX) * 0.35f + width * 0.06f
                    for (sxF in floatArrayOf(0.36f, 0.49f, 0.62f)) {
                        val sx = width * sxF
                        fillPaint.shader = LinearGradient(sx, height * 0.32f, sx + slant, height * 0.84f, intArrayOf(Color.TRANSPARENT, (rayCol and 0x00FFFFFF) or (rA shl 24), Color.TRANSPARENT), floatArrayOf(0f, 0.40f, 1f), Shader.TileMode.CLAMP)
                        dynPath.reset()
                        dynPath.moveTo(sx - width * 0.055f, height * 0.32f); dynPath.lineTo(sx + width * 0.055f, height * 0.32f)
                        dynPath.lineTo(sx + slant + width * 0.10f, height * 0.84f); dynPath.lineTo(sx + slant - width * 0.10f, height * 0.84f); dynPath.close()
                        canvas.drawPath(dynPath, fillPaint)
                    }
                    fillPaint.shader = null
                }

                val sway5 = sin(timeSec * 0.48).toFloat() * (density * (1.1f + steadyWind * 0.04f)) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.48f, height.toFloat(), gDeep, 0xFF030805.toInt(), sway5 * 0.45f, foreShiftY, masterAlpha)
                val bankY = height * 0.81f + foreShiftY
                drawSwayingGrass(canvas, width, height, density, dayOfYear, bankY, ColorUtils.blendARGB(0xFF143822.toInt(), lightColor, 0.22f * lightStrength), windDir, steadyWind, timeSec, masterAlpha)
                drawWoodlandMushrooms(canvas, width, height, density, dayOfYear, bankY, sky, lightColor, timeSec, masterAlpha)
                if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
                    for (bf in 0 until 2) {
                        val bx = width * (0.40f + bf * 0.20f) + sin(timeSec * (0.25 + bf * 0.06) + bf * 1.9).toFloat() * (width * 0.09f)
                        val by = height * (0.62f + bf * 0.06f) + sin(timeSec * 0.56 + bf * 2.1).toFloat() * (height * 0.022f)
                        drawButterfly(canvas, bx, by, density * (9.5f + bf * 1.5f), if (bf == 0) 0xFFF59E0B.toInt() else 0xFF7DD3FC.toInt(), (masterAlpha * 0.88f * bfVis).toInt(), timeSec, bf * 1.7)
                    }
                }
                if (isDuskOrNight) drawFireflies(canvas, width, height, density, dayOfYear, height * 0.54f, sky, weather, timeSec, masterAlpha, forceNight = true)
            }

            WorldBiome.COASTAL_REEDS -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.05f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.68f).toInt())
                val seaTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.26f else 0.72f)
                val seaBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.70f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(seaTop, seaBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Asymmetrical bottom-left feathery reeds only (right side uncluttered!)
                drawLeftCornerReeds(canvas, width, height, density, dayOfYear, ColorUtils.blendARGB(midTone, deepVelvet, 0.45f), deepVelvet, lightColor, isDay, windDir, steadyWind, timeSec, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.82f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY * 0.45f, masterAlpha)
                if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
                    val bx = width * 0.44f + sin(timeSec * 0.26 + 1.1).toFloat() * (width * 0.09f)
                    val by = height * 0.58f + sin(timeSec * 0.58 + 0.7).toFloat() * (height * 0.024f)
                    drawButterfly(canvas, bx, by, density * 10f, ColorUtils.blendARGB(0xFFF59E0B.toInt(), sky.sunColor, 0.30f), (masterAlpha * 0.88f * bfVis).toInt(), timeSec, 1.2)
                }
            }

            WorldBiome.CRYSTAL_COVE -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.56f, horizonY + 4f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.45f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.72f).toInt())
                val topW = if (isDay) ColorUtils.blendARGB(circ.horizonColor, 0xFF0E7490.toInt(), 0.56f) else 0xFF0B192C.toInt()
                val botW = if (isDay) ColorUtils.blendARGB(0xFF065F46.toInt(), 0xFF0E7490.toInt(), 0.52f) else 0xFF020612.toInt()
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(topW, botW), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Submerged pale-teal stones visible beneath crystal water
                val subCol = if (isDay) 0x000A4A52 else 0x0005141C
                drawSoftMist(canvas, width * 0.30f, height * 0.82f, width * 0.14f, height * 0.048f, subCol or ((masterAlpha * 0.38f).toInt() shl 24))
                drawSoftMist(canvas, width * 0.74f, height * 0.88f, width * 0.15f, height * 0.052f, subCol or ((masterAlpha * 0.42f).toInt() shl 24))

                // Soft mirror reflections of promontory & islet
                fillPaint.color = ColorUtils.blendARGB(deepVelvet, topW, 0.45f); fillPaint.alpha = (masterAlpha * 0.30f).toInt()
                canvas.save(); canvas.translate(0f, horizonShiftY); canvas.drawPath(reflectPath, fillPaint); canvas.restore()

                // Curved underwater caustic ribbons & 5 sleek swimming fish
                val cCol = if (isDay) ColorUtils.blendARGB(0xFF5EEAD4.toInt(), lightColor, 0.35f) else sky.moonColor
                val cA = (masterAlpha * (if (isDay) 0.16f else 0.06f) * lightStrength).toInt().coerceIn(0, 110)
                if (cA > 2) {
                    strokePaint.color = (cCol and 0x00FFFFFF) or (cA shl 24); strokePaint.strokeWidth = density * 1.8f
                    val spd = 0.015 + (steadyWind / 450.0).coerceIn(0.0, 0.025)
                    for (c in 0 until 5) {
                        val prog = (((timeSec * spd * windDir) + c * 0.20) % 1.0 + 1.0) % 1.0
                        val cx = (prog.toFloat() * 1.2f - 0.1f) * width; val cy = height * (0.74f + (c % 3) * 0.065f)
                        val wave = sin(timeSec * 0.9 + c * 1.4).toFloat() * (height * 0.012f)
                        dynPath.reset(); dynPath.moveTo(cx - width * 0.14f, cy - wave); dynPath.quadTo(cx, cy + wave, cx + width * 0.14f, cy - wave * 0.6f)
                        canvas.drawPath(dynPath, strokePaint)
                    }
                }
                drawCoveFish(canvas, width, height, density, botW, isDay, timeSec, masterAlpha)
                val rockTop = ColorUtils.blendARGB(0xFF64748B.toInt(), lightColor, 0.38f * lightStrength)
                drawGradientLayer(canvas, layerPaths[2], horizonY - height * 0.10f, horizonY + 18f, rockTop, deepVelvet, 0f, horizonShiftY, masterAlpha)
                if (isDay) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.KYOTO_TORII -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, horizonY, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, horizonShiftY, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.54f, horizonY, ColorUtils.blendARGB(midTone, deepVelvet, 0.30f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                val lakeTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.25f else 0.70f)
                val lakeBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.72f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(lakeTop, lakeBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.90f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                val toriiCol = if (isDay) ColorUtils.blendARGB(0xFF991B1B.toInt(), deepVelvet, 0.48f) else ColorUtils.blendARGB(0xFF450A0A.toInt(), deepVelvet, 0.65f)
                // Soft inverted Torii water reflection
                fillPaint.color = toriiCol; fillPaint.alpha = (masterAlpha * 0.28f).toInt()
                canvas.save(); canvas.translate(0f, horizonShiftY); canvas.drawPath(reflectPath, fillPaint); canvas.restore()
                drawGradientLayer(canvas, layerPaths[3], horizonY - height * 0.04f, horizonY + height * 0.12f, toriiCol, deepVelvet, 0f, horizonShiftY, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.74f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)

                val lanternStr = ((3f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                if (lanternStr > 0.05f && focalW > 0f) {
                    val fy = focalY + foreShiftY
                    drawSoftMist(canvas, focalX, fy, focalW * 2.6f, focalH * 3.0f, 0x00FBBF24 or ((masterAlpha * 0.55f * lanternStr).toInt().coerceIn(0, 255) shl 24))
                    fillPaint.color = 0xFFFEF08A.toInt(); fillPaint.alpha = (masterAlpha * 0.92f * lanternStr).toInt()
                    canvas.drawRect(focalX - focalW * 0.4f, fy - focalH * 0.4f, focalX + focalW * 0.4f, fy + focalH * 0.4f, fillPaint)
                }
                val petalCol = if (dayOfYear in 60..170) 0xFFFBCFE8.toInt() else 0xFFF59E0B.toInt()
                fillPaint.color = petalCol
                for (p in 0 until 7) {
                    val px = ((((seedRand(dayOfYear + p, 311) + timeSec * (0.012 + steadyWind * 0.0005) * windDir) % 1.15) + 1.15) % 1.15 - 0.07).toFloat() * width
                    val py = ((((seedRand(dayOfYear + p, 313) + timeSec * 0.018) % 0.75) + 0.15)).toFloat() * height
                    fillPaint.alpha = (masterAlpha * (if (isDay) 0.58f else 0.25f)).toInt()
                    val pr = density * (2.6f + (p % 2) * 1.0f)
                    rect.set(px - pr, py - pr * 0.55f, px + pr, py + pr * 0.55f); canvas.drawOval(rect, fillPaint)
                }
            }

            WorldBiome.LIGHTHOUSE_CLIFF -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.06f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.68f).toInt())
                drawOceanSurfaceAndLife(canvas, width, height, density, horizonY, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha, showBoat = false)

                val beamStr = ((2f - sky.sunAltDeg) / 6f).coerceIn(0f, 1f)
                if (beamStr > 0.05f && focalW > 0f) {
                    val lx = focalX; val ly = focalY + horizonShiftY
                    val sweep = sin(timeSec * 0.52).toFloat()
                    val beamLen = width * (0.65f + 0.22f * abs(sweep))
                    val dir = if (sweep >= 0f) 1f else -1f
                    val beamA = (masterAlpha * 0.24f * beamStr * (0.45f + 0.55f * abs(sweep))).toInt().coerceIn(0, 160)
                    fillPaint.shader = LinearGradient(lx, ly, lx + beamLen * dir, ly - height * 0.06f, intArrayOf(0x00FEF08A or (beamA shl 24), 0x00FEF08A or ((beamA / 3) shl 24), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    dynPath.reset(); dynPath.moveTo(lx, ly - focalH * 0.32f); dynPath.lineTo(lx + beamLen * dir, ly - height * 0.13f)
                    dynPath.lineTo(lx + beamLen * dir, ly + height * 0.05f); dynPath.lineTo(lx, ly + focalH * 0.32f); dynPath.close()
                    canvas.drawPath(dynPath, fillPaint); fillPaint.shader = null
                    drawSoftMist(canvas, lx, ly, focalW * 2.4f, focalH * 2.4f, 0x00FEF08A or ((masterAlpha * 0.62f * beamStr).toInt() shl 24))
                }
                drawGradientLayer(canvas, layerPaths[3], horizonY - height * 0.22f, horizonY + 8f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.78f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.AURORA_FJORD -> {
                val auroraStr = ((-2f - sky.sunAltDeg) / 8f).coerceIn(0f, 1f)
                if (auroraStr > 0.05f && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    for (a in 0 until 4) {
                        val ax = width * (0.18f + a * 0.22f) + sin(timeSec * (0.22 + a * 0.05) + a * 1.4).toFloat() * (width * 0.08f)
                        val ay = height * (0.20f + (a % 2) * 0.06f)
                        val colRgb = if (a % 2 == 0) 0x0034D399 else 0x0038BDF8
                        val aA = (masterAlpha * 0.22f * auroraStr * (0.7f + 0.3f * sin(timeSec * 0.4 + a).toFloat())).toInt().coerceIn(0, 140)
                        drawSoftMist(canvas, ax, ay, width * 0.22f, height * 0.14f, colRgb or (aA shl 24))
                    }
                }
                drawGradientLayer(canvas, layerPaths[0], height * 0.46f, horizonY, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.38f), midTone, 0f, horizonShiftY, masterAlpha)
                val fjordWaterTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.32f else 0.72f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(fjordWaterTop, 0xFF03060E.toInt()), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.88f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)
                drawGradientLayer(canvas, layerPaths[2], height * 0.42f, horizonY + 4f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.74f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)

                val winStr = ((4f - sky.sunAltDeg) / 8f).coerceIn(0f, 1f)
                if (focalW > 0f) {
                    val fy = focalY + foreShiftY
                    if (winStr > 0.05f) {
                        drawSoftMist(canvas, focalX, fy, focalW * 2.8f, focalH * 2.8f, 0x00FBBF24 or ((masterAlpha * 0.50f * winStr).toInt() shl 24))
                        fillPaint.color = 0xFFFEF08A.toInt(); fillPaint.alpha = (masterAlpha * 0.94f * winStr).toInt()
                        canvas.drawRect(focalX - focalW * 0.5f, fy - focalH * 0.5f, focalX + focalW * 0.5f, fy + focalH * 0.5f, fillPaint)
                    }
                    val chimX = focalX + focalW * 1.15f; val chimY = fy - focalH * 2.4f
                    val smokeRgb = if (isDay) 0x00E2E8F0 else 0x0094A3B8
                    for (sm in 0 until 5) {
                        val sp = (((timeSec * 0.14) + sm * 0.20) % 1.0).toFloat()
                        drawSoftMist(canvas, chimX + sp * (width * 0.045f * windDir) + sin(timeSec * 0.9 + sm).toFloat() * (density * 3f), chimY - sp * (height * 0.065f), density * (4f + sp * 9f), density * (3f + sp * 6f), smokeRgb or ((masterAlpha * 0.24f * sin(sp * PI).toFloat()).toInt().coerceIn(0, 120) shl 24))
                    }
                }
            }

            WorldBiome.VALLEY_STAG -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, height * 0.78f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, midShiftY * 0.45f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.54f, height * 0.84f, ColorUtils.blendARGB(midTone, deepVelvet, 0.36f), deepVelvet, 0f, midShiftY * 0.72f, masterAlpha)
                val lakeTop = ColorUtils.blendARGB(circ.horizonColor, lightColor, 0.55f)
                val lakeBot = ColorUtils.blendARGB(midTone, deepVelvet, 0.35f)
                drawGradientLayer(canvas, accentPath, height * 0.69f, height * 0.76f, lakeTop, lakeBot, 0f, midShiftY * 0.85f, masterAlpha)
                drawSoftMist(canvas, width * 0.53f, height * 0.725f + midShiftY * 0.85f, width * 0.10f, height * 0.015f, (lightColor and 0x00FFFFFF) or ((masterAlpha * 0.52f).toInt() shl 24))
                val sway5 = sin(timeSec * 0.45).toFloat() * (density * (0.9f + steadyWind * 0.03f)) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), sway5 * 0.4f, foreShiftY, masterAlpha)
                drawFireflies(canvas, width, height, density, dayOfYear, horizonY, sky, weather, timeSec, masterAlpha)
                if (isDay || alpenglow > 0.1f) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.CAPPADOCIA_BALLOONS -> {
                // 4 drifting hot-air balloons with warm pulsing internal burners
                val bBaseX = floatArrayOf(0.24f, 0.62f, 0.78f, 0.42f)
                val bBaseY = floatArrayOf(0.34f, 0.28f, 0.41f, 0.44f)
                val bRad = floatArrayOf(0.044f, 0.032f, 0.038f, 0.025f)
                for (b in 0 until 4) {
                    val drift = (((bBaseX[b] + timeSec * (0.0022 + b * 0.0004) * windDir) % 1.24 + 1.24) % 1.24 - 0.12).toFloat()
                    val bx = drift * width
                    val by = height * bBaseY[b] + sin(timeSec * (0.45 + b * 0.12) + b * 1.7).toFloat() * (height * 0.012f)
                    val r = width * bRad[b]
                    val burner = (((sin(timeSec * (0.65 + b * 0.2) + b * 2.4) + 1.0) * 0.5).pow(3.0)).toFloat()
                    if (burner > 0.15f) {
                        drawSoftMist(canvas, bx, by + r * 0.6f, r * 1.5f, r * 1.2f, 0x00FFBE5A or ((masterAlpha * 0.45f * burner).toInt() shl 24))
                    }
                    fillPaint.color = if (b % 2 == 0) ColorUtils.blendARGB(midTone, deepVelvet, 0.65f) else ColorUtils.blendARGB(midTone, deepVelvet, 0.40f)
                    fillPaint.alpha = (masterAlpha * 0.92f).toInt()
                    rect.set(bx - r, by - r, bx + r, by + r * 0.85f); canvas.drawOval(rect, fillPaint)
                    dynPath.reset(); dynPath.moveTo(bx - r * 0.72f, by + r * 0.42f); dynPath.lineTo(bx + r * 0.72f, by + r * 0.42f)
                    dynPath.lineTo(bx + r * 0.26f, by + r * 1.12f); dynPath.lineTo(bx - r * 0.26f, by + r * 1.12f); dynPath.close()
                    canvas.drawPath(dynPath, fillPaint)
                    canvas.drawRect(bx - r * 0.20f, by + r * 1.38f, bx + r * 0.20f, by + r * 1.62f, fillPaint)
                    if (burner > 0.2f) {
                        fillPaint.color = 0xFFFFD66E.toInt(); fillPaint.alpha = (masterAlpha * 0.85f * burner).toInt()
                        rect.set(bx - r * 0.30f, by + r * 0.58f, bx + r * 0.30f, by + r * 1.02f); canvas.drawOval(rect, fillPaint)
                    }
                }
                drawGradientLayer(canvas, layerPaths[0], height * 0.52f, height * 0.80f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.40f), midTone, 0f, midShiftY * 0.55f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.60f, height * 0.90f, ColorUtils.blendARGB(midTone, deepVelvet, 0.42f), deepVelvet, 0f, midShiftY * 0.85f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.74f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.ODISHA_BANYAN_GHAT -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.50f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.25f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                val rivTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.26f else 0.70f)
                val rivBot = ColorUtils.blendARGB(circ.primary, 0xFF06050E.toInt(), 0.74f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(rivTop, rivBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.92f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Tiny glowing Diyas (oil lamps) floating serenely downstream at dusk & night!
                val diyaStr = ((6f - sky.sunAltDeg) / 9f).coerceIn(0.25f, 1f)
                for (d in 0 until 6) {
                    val prog = (((0.38 + d * 0.14 + timeSec * (0.0055 + d * 0.0008)) % 0.75) + 0.35).toFloat()
                    val dx = prog * width
                    val dy = horizonY + height * (0.08f + (d % 4) * 0.042f) + sin(timeSec * 0.9 + d * 1.5).toFloat() * (density * 2.5f)
                    val sc = 0.85f + (d % 3) * 0.22f
                    val flick = 0.88f + 0.12f * sin(timeSec * 4.2 + d * 2.1).toFloat()
                    drawSoftMist(canvas, dx, dy, density * 9f * sc, density * 5f * sc, 0x00FFB43C or ((masterAlpha * 0.55f * diyaStr * flick).toInt().coerceIn(0, 255) shl 24))
                    drawSoftMist(canvas, dx, dy + density * 4f * sc, density * 5f * sc, density * 7f * sc, 0x00FF9632 or ((masterAlpha * 0.32f * diyaStr).toInt().coerceIn(0, 255) shl 24))
                    fillPaint.color = 0xFFFFF4B4.toInt(); fillPaint.alpha = (masterAlpha * 0.95f * diyaStr).toInt()
                    canvas.drawCircle(dx, dy, density * 1.6f * sc, fillPaint)
                }
                drawGradientLayer(canvas, layerPaths[4], height * 0.48f, height.toFloat(), ColorUtils.blendARGB(deepVelvet, 0xFF140C1E.toInt(), 0.45f), 0xFF040308.toInt(), 0f, foreShiftY * 0.6f, masterAlpha)
            }

            WorldBiome.HIMALAYAN_MONASTERY -> {
                val peakTop = ColorUtils.blendARGB(0xFFE2E8F0.toInt(), lightColor, 0.35f)
                drawGradientLayer(canvas, layerPaths[0], height * 0.38f, height * 0.72f, peakTop, midTone, 0f, midShiftY * 0.45f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.54f, height * 0.88f, ColorUtils.blendARGB(midTone, deepVelvet, 0.45f), deepVelvet, 0f, midShiftY * 0.80f, masterAlpha)
                // Warm prayer-hall windows at dusk/night
                val winStr = ((4f - sky.sunAltDeg) / 8f).coerceIn(0f, 1f)
                if (winStr > 0.05f && focalW > 0f) {
                    val fy = focalY + midShiftY * 0.80f
                    drawSoftMist(canvas, focalX, fy, focalW * 1.1f, focalH * 3.5f, 0x00FFC85A or ((masterAlpha * 0.48f * winStr).toInt() shl 24))
                    fillPaint.color = 0xFFFFE88C.toInt(); fillPaint.alpha = (masterAlpha * 0.94f * winStr).toInt()
                    for (wIdx in 0 until 4) {
                        val wx = focalX - focalW * 0.38f + wIdx * (focalW * 0.25f)
                        canvas.drawRect(wx - density * 1.5f, fy - focalH * 0.5f, wx + density * 1.5f, fy + focalH * 0.5f, fillPaint)
                    }
                }
                drawGradientLayer(canvas, layerPaths[4], height * 0.68f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)

                // Wind-fluttering Tibetan prayer flags strung across the saddle
                val pLx = width * 0.08f; val pLy = auxX + foreShiftY
                val pRx = width * 0.92f; val pRy = auxY + foreShiftY
                strokePaint.color = 0xFF121624.toInt(); strokePaint.alpha = (masterAlpha * 0.85f).toInt(); strokePaint.strokeWidth = density * 1.2f
                dynPath.reset(); dynPath.moveTo(pLx, pLy)
                dynPath.quadTo(width * 0.5f, (pLy + pRy) * 0.5f + height * 0.095f, pRx, pRy)
                canvas.drawPath(dynPath, strokePaint)
                val fCols = intArrayOf(0xFF3882D2.toInt(), 0xFFECF2F8.toInt(), 0xFFDA4444.toInt(), 0xFF2E9E5C.toInt(), 0xFFF2C438.toInt())
                val fFreq = 1.6 + (steadyWind / 22.0).coerceIn(0.0, 2.2)
                for (k in 1..13) {
                    val t = k / 14f; val omt = 1f - t
                    val rx = omt * omt * pLx + 2f * omt * t * (width * 0.5f) + t * t * pRx
                    val ry = omt * omt * pLy + 2f * omt * t * ((pLy + pRy) * 0.5f + height * 0.095f) + t * t * pRy
                    val fl = sin(timeSec * fFreq + k * 0.7).toFloat() * (density * (2.0f + steadyWind * 0.08f)) * windDir
                    fillPaint.color = if (isDay) fCols[k % 5] else ColorUtils.blendARGB(fCols[k % 5], 0xFF0F172A.toInt(), 0.55f)
                    fillPaint.alpha = (masterAlpha * 0.85f).toInt()
                    val fw = density * 5.2f; val fh = density * 6.8f
                    dynPath.reset(); dynPath.moveTo(rx, ry + 1f); dynPath.lineTo(rx + fw, ry + 1.5f)
                    dynPath.lineTo(rx + fw + fl, ry + fh); dynPath.lineTo(rx + fl, ry + fh); dynPath.close()
                    canvas.drawPath(dynPath, fillPaint)
                }
            }

            WorldBiome.ALPINE_VIADUCT -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.46f, height * 0.76f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, midShiftY * 0.45f, masterAlpha)
                drawSoftMist(canvas, width * 0.5f, height * 0.66f + midShiftY * 0.6f, width * 0.88f, height * 0.045f, mistRgb or ((masterAlpha * (0.24f + fogFactor * 0.18f)).toInt().coerceIn(0, 165) shl 24))
                drawGradientLayer(canvas, layerPaths[2], height * 0.62f, height * 0.90f, ColorUtils.blendARGB(midTone, deepVelvet, 0.50f), deepVelvet, 0f, midShiftY * 0.80f, masterAlpha)

                // Serene warm-lit train gliding across the viaduct every ~45 seconds!
                val trainProg = ((timeSec / 45.0) % 1.0).toFloat()
                if (trainProg < 0.72f) {
                    val trX = (trainProg / 0.72f * 1.45f - 0.35f) * width
                    val deckY = focalY + midShiftY * 0.80f
                    val carW = width * 0.072f; val carH = height * 0.012f
                    val glowStr = ((5f - sky.sunAltDeg) / 8f).coerceIn(0.25f, 1f)
                    drawSoftMist(canvas, trX + carW * 2f, deckY - carH * 0.5f, carW * 2.6f, carH * 1.8f, 0x00FFCE60 or ((masterAlpha * 0.35f * glowStr).toInt() shl 24))
                    for (car in 0 until 4) {
                        val cx0 = trX + car * (carW * 1.08f)
                        fillPaint.color = 0xFF0A0E18.toInt(); fillPaint.alpha = masterAlpha
                        canvas.drawRect(cx0, deckY - carH, cx0 + carW, deckY, fillPaint)
                        fillPaint.color = 0xFFFFE080.toInt(); fillPaint.alpha = (masterAlpha * 0.92f * glowStr).toInt()
                        for (wIdx in 0 until 4) {
                            val wx0 = cx0 + carW * (0.10f + wIdx * 0.22f)
                            canvas.drawRect(wx0, deckY - carH * 0.72f, wx0 + carW * 0.14f, deckY - carH * 0.28f, fillPaint)
                        }
                    }
                    // Headlight glow ahead of engine
                    drawSoftMist(canvas, trX + carW * 4.5f, deckY - carH * 0.5f, carW * 0.9f, carH * 0.9f, 0x00FFF0AF or ((masterAlpha * 0.42f * glowStr).toInt() shl 24))
                }
                drawGradientLayer(canvas, layerPaths[4], height * 0.66f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.CANYON_WINDMILL -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.52f, height * 0.78f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.38f), midTone, 0f, midShiftY * 0.50f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[2], height * 0.52f, height * 0.90f, ColorUtils.blendARGB(midTone, deepVelvet, 0.48f), deepVelvet, 0f, midShiftY * 0.80f, masterAlpha)
                // Spinning 8-blade windmill rotor paced strictly by Rourkela's live steadyWind!
                val wx = focalX; val wy = focalY + midShiftY * 0.80f; val rr = focalW
                val rotSpeed = 0.25 + (steadyWind / 25.0).coerceIn(0.0, 1.8)
                val baseAng = (timeSec * rotSpeed * windDir) % (2.0 * PI)
                val bladeCol = ColorUtils.blendARGB(midTone, deepVelvet, 0.62f)
                strokePaint.color = bladeCol; strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = density * 1.4f
                canvas.drawCircle(wx, wy, rr, strokePaint)
                fillPaint.color = bladeCol; fillPaint.alpha = masterAlpha
                for (b in 0 until 8) {
                    val ang = baseAng + b * (PI / 4.0)
                    dynPath.reset(); dynPath.moveTo(wx, wy)
                    dynPath.lineTo(wx + cos(ang - 0.14).toFloat() * rr, wy + sin(ang - 0.14).toFloat() * rr)
                    dynPath.lineTo(wx + cos(ang).toFloat() * rr, wy + sin(ang).toFloat() * rr); dynPath.close()
                    canvas.drawPath(dynPath, fillPaint)
                }
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                if (isDay) drawThermalEagles(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.MISTY_BAMBOO -> {
                val bMid = ColorUtils.blendARGB(if (isDay) 0xFF285842.toInt() else 0xFF0D201A.toInt(), circ.horizonColor, 0.35f)
                val bDeep = if (isDay) 0xFF0A1D14.toInt() else 0xFF040A08.toInt()
                val sw0 = sin(timeSec * 0.38).toFloat() * (density * (0.9f + steadyWind * 0.03f)) * windDir
                val sw2 = sin(timeSec * 0.48 + 1.2).toFloat() * (density * (1.6f + steadyWind * 0.05f)) * windDir
                val sw4 = sin(timeSec * 0.56 + 2.4).toFloat() * (density * (2.2f + steadyWind * 0.07f)) * windDir
                drawGradientLayer(canvas, layerPaths[0], 0f, height.toFloat(), ColorUtils.blendARGB(circ.horizonColor, bMid, 0.52f), bMid, sw0, midShiftY * 0.35f, (masterAlpha * 0.60f).toInt())
                drawSoftMist(canvas, width * 0.5f, height * 0.64f, width * 0.92f, height * 0.14f, mistRgb or ((masterAlpha * (0.28f + fogFactor * 0.22f)).toInt().coerceIn(0, 185) shl 24))
                drawGradientLayer(canvas, layerPaths[2], 0f, height.toFloat(), bMid, bDeep, sw2, midShiftY * 0.65f, (masterAlpha * 0.85f).toInt())
                drawGradientLayer(canvas, layerPaths[4], 0f, height.toFloat(), bDeep, 0xFF030805.toInt(), sw4, foreShiftY * 0.5f, masterAlpha)
                if (isDuskOrNight) drawFireflies(canvas, width, height, density, dayOfYear, height * 0.48f, sky, weather, timeSec, masterAlpha, forceNight = true)
            }

            WorldBiome.VOLCANIC_CALDERA -> {
                val emberStr = ((8f - sky.sunAltDeg) / 14f).coerceIn(0.20f, 1f)
                val vx = focalX; val vy = focalY + horizonShiftY
                // Crater ember glow & thin steam plume drifting downwind
                val pulse = 0.86f + 0.14f * sin(timeSec * 0.85).toFloat()
                drawSoftMist(canvas, vx, vy, width * 0.11f, height * 0.028f, 0x00FF6E2A or ((masterAlpha * 0.56f * emberStr * pulse).toInt().coerceIn(0, 255) shl 24))
                val steamRgb = if (isDay) 0x00CBD5E1 else 0x00B48084
                for (sm in 0 until 6) {
                    val sp = (((timeSec * (0.045 + steadyWind * 0.0012)) + sm / 6.0) % 1.0).toFloat()
                    drawSoftMist(canvas, vx + sp * (width * 0.22f * windDir), vy - height * (0.018f + sp * 0.10f), width * (0.035f + sp * 0.075f), height * (0.014f + sp * 0.025f), steamRgb or ((masterAlpha * 0.28f * sin(sp * PI).toFloat()).toInt().coerceIn(0, 130) shl 24))
                }
                drawGradientLayer(canvas, layerPaths[2], height * 0.45f, horizonY + 4f, ColorUtils.blendARGB(midTone, deepVelvet, 0.62f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                val seaTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.34f else 0.70f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(seaTop, 0xFF06040C.toInt()), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.90f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)
                drawSoftMist(canvas, vx, horizonY + height * 0.10f, width * 0.07f, height * 0.09f, 0x00FF7638 or ((masterAlpha * 0.34f * emberStr * pulse).toInt().coerceIn(0, 160) shl 24))
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), deepVelvet, 0xFF030308.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.HIGHLAND_LOCH -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, horizonY, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.38f), midTone, 0f, horizonShiftY, masterAlpha)
                val mDrift = (((timeSec * (0.0020 + steadyWind * 0.00018) * windDir) % 1.0 + 1.0) % 1.0).toFloat()
                drawSoftMist(canvas, (mDrift * 1.4f - 0.2f) * width, horizonY - height * 0.01f, width * 0.85f, height * 0.030f, mistRgb or ((masterAlpha * (0.28f + fogFactor * 0.22f)).toInt().coerceIn(0, 180) shl 24))
                val lochTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.28f else 0.72f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(lochTop, 0xFF050812.toInt()), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.90f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Soft inverted castle reflection + castle keep silhouette
                fillPaint.color = deepVelvet; fillPaint.alpha = (masterAlpha * 0.28f).toInt()
                canvas.save(); canvas.translate(0f, horizonShiftY); canvas.drawPath(reflectPath, fillPaint); canvas.restore()
                drawGradientLayer(canvas, layerPaths[2], horizonY - height * 0.12f, horizonY + 4f, ColorUtils.blendARGB(midTone, deepVelvet, 0.62f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                // Sky-lit arched ruin window cut-through
                fillPaint.color = circ.horizonColor; fillPaint.alpha = (masterAlpha * 0.72f).toInt()
                val wy = focalY + horizonShiftY
                rect.set(focalX - focalW * 0.5f, wy - focalH * 0.5f, focalX + focalW * 0.5f, wy + focalH * 0.5f)
                canvas.drawRoundRect(rect, focalW * 0.5f, focalW * 0.5f, fillPaint)
                drawGradientLayer(canvas, layerPaths[4], height * 0.80f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                if (isDay) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }
        }
    }

    private fun drawOceanSurfaceAndLife(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float,
        sky: SkySnapshot, weather: WeatherState, orbX: Float, isDay: Boolean,
        windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int, showBoat: Boolean
    ) {
        val shoreY = h * (0.85f + 0.025f * ((horizonY - h * 0.64f) / (h * 0.05f)) - sky.tideFrac * 0.018f)
        drawWaterShimmer(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

        val isBiolum = !isDay && sky.moonIllumination < 0.08f && weather.sky == SkyWeather.CLEAR
        val swellRgb = if (isDay) 0x00E0F2FE else (if (isBiolum) 0x002DD4BF else 0x0094A3B8)
        val foamRgb = if (isDay) 0x00F8FAFC else (if (isBiolum) 0x005EEAD4 else 0x00CBD5E1)
        val surfEnergy = (0.76f + (steadyWind / 36f) + (sky.tideFrac * 0.15f)).coerceIn(0.72f, 1.45f)
        val waveSpeed = 0.024 + (steadyWind / 800.0).coerceIn(0.0, 0.015)
        val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)

        val tideBreath = 0.85f + 0.15f * sin(timeSec * 0.45).toFloat()
        drawSoftMist(canvas, w * 0.5f, shoreY + h * 0.004f, w * 0.78f, h * 0.011f, foamRgb or ((masterAlpha * (if (isDay) 0.14f else 0.09f) * tideBreath).toInt().coerceIn(0, 255) shl 24))

        for (wIdx in 0 until 3) {
            val waveProg = (((timeSec * waveSpeed) + wIdx / 3.0) % 1.0).toFloat()
            val persp = waveProg * sqrt(waveProg)
            val wy = horizonY + seaSpan * (0.34f + persp * 0.66f)
            val env = sin(waveProg * PI).toFloat()
            val driftX = sin(timeSec * 0.16 * 1.618 + wIdx * 2.1).toFloat() * (w * 0.05f)
            val cx = w * (0.44f + (wIdx % 2) * 0.12f) + driftX
            val halfW = w * (0.30f + 0.12f * waveProg)
            val glowAlpha = (masterAlpha * env * (if (isDay) 0.17f else 0.10f) * surfEnergy).toInt().coerceIn(0, 255)
            if (glowAlpha > 2) drawSoftMist(canvas, cx, wy, halfW, h * (0.006f + 0.008f * waveProg), swellRgb or (glowAlpha shl 24))
            if (waveProg > 0.66f) {
                val washEnv = sin((((waveProg - 0.66f) / 0.34f).coerceIn(0f, 1f)) * PI).toFloat()
                val washAlpha = (masterAlpha * washEnv * (if (isDay) 0.22f else 0.12f) * surfEnergy).toInt().coerceIn(0, 255)
                if (washAlpha > 2) drawSoftMist(canvas, cx, shoreY + h * 0.009f * washEnv, halfW * 1.12f, h * (0.008f + 0.008f * washEnv), foamRgb or (washAlpha shl 24))
            }
        }

        if (showBoat) {
            val boatSpeed = (0.0018 + (steadyWind / 60.0).coerceIn(0.0, 1.0) * 0.0010) * windDir
            val boatX = ((((0.20f + (timeSec * boatSpeed).toFloat()) % 1.16f) + 1.16f) % 1.16f - 0.08f) * w
            val boatY = horizonY + h * 0.013f + sin(timeSec * 1.1).toFloat() * (density * 1.4f)
            fillPaint.color = if (isDay) 0xFF0F172A.toInt() else 0xFF020617.toInt()
            fillPaint.alpha = (masterAlpha * 0.62f).toInt()
            val bw = w * 0.025f; val bh = w * 0.021f
            rect.set(boatX - bw * 0.5f, boatY, boatX + bw * 0.5f, boatY + density * 2.2f)
            canvas.drawRoundRect(rect, density, density, fillPaint)
            dynPath.reset(); dynPath.moveTo(boatX - bw * 0.05f, boatY); dynPath.lineTo(boatX - bw * 0.05f, boatY - bh)
            dynPath.lineTo(boatX + bw * 0.42f * windDir, boatY - density * 1.5f); dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }
        if (sky.sunAltDeg > -2f) drawBirds(canvas, w, h, density, windDir, timeSec, masterAlpha)
    }

    // Asymmetrical Bottom-Left 2-Layer Feathery Reeds (right side & sea uncluttered!)
    private fun drawLeftCornerReeds(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        midCol: Int, foreCol: Int, lightColor: Int, isDay: Boolean, windDir: Float, steadyWind: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val swayFreq = 0.68 + (steadyWind / 48.0).coerceIn(0.0, 0.42)
        val windLean = (steadyWind / 55f) * w * 0.045f * windDir
        for (pass in 0..1) {
            val count = if (pass == 0) 9 else 11
            val stemC = if (pass == 0) midCol else foreCol
            val plumeC = if (pass == 0) ColorUtils.blendARGB(midCol, lightColor, 0.35f) else ColorUtils.blendARGB(foreCol, lightColor, if (isDay) 0.25f else 0.10f)
            val hScale = if (pass == 0) 1.06f else 0.90f
            for (r in 0 until count) {
                val salt = if (pass == 0) 101 else 201
                val bx = w * (-0.03f + 0.33f * seedRand(dayOfYear + r, salt))
                val by = h * 1.02f
                val rh = h * (0.28f + 0.26f * seedRand(dayOfYear + r, salt + 3)) * hScale
                val arch = w * (0.14f + 0.15f * seedRand(dayOfYear + r, salt + 7))
                val sway = sin(timeSec * swayFreq + r * 0.72 + pass).toFloat() * (density * (6.0f + steadyWind * 0.18f)) + windLean
                val tipX = bx + arch + sway
                val tipY = by - rh
                val ctrlX = bx + (arch + sway) * 0.32f
                val ctrlY = by - rh * 0.62f

                strokePaint.color = stemC; strokePaint.alpha = (masterAlpha * (if (pass == 0) 0.72f else 0.92f)).toInt()
                strokePaint.strokeWidth = density * (if (pass == 0) 1.1f else 1.5f)
                dynPath.reset(); dynPath.moveTo(bx, by); dynPath.quadTo(ctrlX, ctrlY, tipX, tipY); canvas.drawPath(dynPath, strokePaint)

                // Dense 22-bristle sweeping feathery plume along upper 30%
                strokePaint.color = plumeC; strokePaint.strokeWidth = density * 0.75f
                for (b in 0 until 22) {
                    val t = 0.70f + (b / 22f) * 0.30f; val omt = 1f - t
                    val px = omt * omt * bx + 2f * omt * t * ctrlX + t * t * tipX
                    val py = omt * omt * by + 2f * omt * t * ctrlY + t * t * tipY
                    val env = sin(((t - 0.70f) / 0.30f) * PI).toFloat()
                    val blen = h * (0.018f + 0.022f * env)
                    val side = if (b % 2 == 0) 1f else -1f
                    canvas.drawLine(px, py, px + arch * 0.20f + side * blen * 0.36f, py - blen * 0.74f, strokePaint)
                }
            }
        }
    }

    private fun drawSwayingGrass(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        baseY: Float, color: Int, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val swayFreq = 0.72 + (steadyWind / 48.0).coerceIn(0.0, 0.42)
        val windLean = (steadyWind / 60f) * density * 9f * windDir
        strokePaint.color = color; strokePaint.alpha = (masterAlpha * 0.90f).toInt(); strokePaint.strokeWidth = density * 1.4f
        for (b in 0 until 30) {
            val bx = w * (0.14f + 0.72f * seedRand(dayOfYear + b, 131))
            val by = baseY + h * (0.005f + 0.055f * seedRand(dayOfYear + b, 137))
            val bh = h * (0.032f + 0.032f * seedRand(dayOfYear + b, 149))
            val sway = sin(timeSec * swayFreq + b * 0.65).toFloat() * (density * (3.2f + steadyWind * 0.12f)) + windLean
            dynPath.reset(); dynPath.moveTo(bx, by); dynPath.quadTo(bx + sway * 0.35f, by - bh * 0.55f, bx + sway, by - bh)
            canvas.drawPath(dynPath, strokePaint)
        }
    }

    private fun drawWoodlandMushrooms(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        bankY: Float, sky: SkySnapshot, lightColor: Int, timeSec: Double, masterAlpha: Int
    ) {
        val nightGlow = ((-sky.sunAltDeg - 0.5f) / 6f).coerceIn(0f, 1f)
        for (m in 0 until 4) {
            val mx = w * (0.32f + m * 0.12f)
            val my = bankY + h * (0.008f + 0.018f * (m % 2))
            val capR = density * (4.2f + 1.6f * (m % 2))
            if (nightGlow > 0.05f) {
                val breath = 0.78f + 0.22f * sin(timeSec * 1.15 + m * 1.4).toFloat()
                drawSoftMist(canvas, mx, my - capR * 0.9f, capR * 3.8f, capR * 2.8f, 0x002DD4BF or ((masterAlpha * 0.54f * nightGlow * breath).toInt().coerceIn(0, 255) shl 24))
            }
            strokePaint.color = if (nightGlow > 0.3f) 0xFF99F6E4.toInt() else 0xFFD6D3D1.toInt()
            strokePaint.alpha = (masterAlpha * 0.85f).toInt(); strokePaint.strokeWidth = density * 1.4f
            canvas.drawLine(mx, my, mx + density * 0.5f, my - capR * 1.1f, strokePaint)
            fillPaint.color = if (nightGlow > 0.25f) ColorUtils.blendARGB(0xFFB45309.toInt(), 0xFF2DD4BF.toInt(), nightGlow) else ColorUtils.blendARGB(0xFFD28034.toInt(), lightColor, 0.25f)
            fillPaint.alpha = (masterAlpha * 0.92f).toInt()
            rect.set(mx - capR, my - capR * 1.75f, mx + capR, my - capR * 0.55f)
            canvas.drawArc(rect, 180f, 180f, true, fillPaint)
        }
    }

    private fun drawCoveFish(canvas: Canvas, w: Int, h: Int, density: Float, botWater: Int, isDay: Boolean, timeSec: Double, masterAlpha: Int) {
        fillPaint.color = if (isDay) ColorUtils.blendARGB(0xFF062C33.toInt(), botWater, 0.35f) else 0xFF020617.toInt()
        fillPaint.alpha = (masterAlpha * (if (isDay) 0.56f else 0.30f)).toInt()
        for (f in 0 until 5) {
            val dir = if (f % 2 == 0) 1f else -1f
            val speed = 0.015 + (f % 3) * 0.004
            val fx = (((((0.15 + f * 0.19) + timeSec * speed * dir) % 1.20) + 1.20) % 1.20 - 0.10).toFloat() * w
            val fy = h * (0.76f + (f % 4) * 0.042f) + sin(timeSec * 0.8 + f * 1.7).toFloat() * (h * 0.012f)
            val len = density * (10f + (f % 3) * 2.5f)
            val tailWag = sin(timeSec * 4.5 + f * 1.3).toFloat() * (len * 0.24f)
            dynPath.reset(); dynPath.moveTo(fx + len * 0.55f * dir, fy)
            dynPath.quadTo(fx, fy - len * 0.26f, fx - len * 0.45f * dir, fy + tailWag * 0.3f)
            dynPath.lineTo(fx - len * 0.76f * dir, fy - len * 0.24f + tailWag)
            dynPath.lineTo(fx - len * 0.76f * dir, fy + len * 0.24f + tailWag)
            dynPath.lineTo(fx - len * 0.45f * dir, fy + tailWag * 0.3f)
            dynPath.quadTo(fx, fy + len * 0.26f, fx + len * 0.55f * dir, fy); dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }
    }

    private fun drawFireflies(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        baseY: Float, sky: SkySnapshot, weather: WeatherState,
        timeSec: Double, masterAlpha: Int, forceNight: Boolean = false
    ) {
        val active = if (forceNight) (sky.sunAltDeg < 0.5f) else (weather.tempC >= 16f && sky.sunAltDeg in -12f..0.5f && weather.windKmh < 16f)
        if (!active || weather.sky == SkyWeather.RAIN || weather.sky == SkyWeather.THUNDERSTORM) return
        for (f in 0 until 8) {
            val pulse = ((sin(timeSec * (1.1 + f * 0.17) + f * 2.3) + 1.0) * 0.5).toFloat()
            val glow = pulse * pulse * pulse
            if (glow > 0.08f) {
                val fx = w * (0.14f + 0.72f * seedRand(dayOfYear + f, 191)) + sin(timeSec * 0.32 + f).toFloat() * (w * 0.032f)
                val fy = baseY + h * (0.04f + 0.20f * seedRand(dayOfYear + f, 193)) + cos(timeSec * 0.41 + f).toFloat() * (h * 0.018f)
                drawSoftMist(canvas, fx, fy, density * 6.5f, density * 6.5f, 0x00FDE047 or ((masterAlpha * 0.62f * glow).toInt().coerceIn(0, 255) shl 24))
            }
        }
    }

    private fun drawButterfly(canvas: Canvas, bx: Float, by: Float, wingSpan: Float, color: Int, alpha: Int, timeSec: Double, phase: Double) {
        val flutter = abs(sin(timeSec * 4.4 + phase)).toFloat().coerceAtLeast(0.22f)
        val ww = wingSpan * flutter
        fillPaint.color = color; fillPaint.alpha = alpha.coerceIn(0, 255)
        dynPath.reset()
        dynPath.moveTo(bx, by); dynPath.quadTo(bx - ww * 1.05f, by - wingSpan * 0.95f, bx - ww * 0.85f, by - wingSpan * 0.05f); dynPath.close()
        dynPath.moveTo(bx, by + wingSpan * 0.05f); dynPath.quadTo(bx - ww * 0.80f, by + wingSpan * 0.65f, bx - ww * 0.25f, by + wingSpan * 0.52f); dynPath.close()
        dynPath.moveTo(bx, by); dynPath.quadTo(bx + ww * 1.05f, by - wingSpan * 0.95f, bx + ww * 0.85f, by - wingSpan * 0.05f); dynPath.close()
        dynPath.moveTo(bx, by + wingSpan * 0.05f); dynPath.quadTo(bx + ww * 0.80f, by + wingSpan * 0.65f, bx + ww * 0.25f, by + wingSpan * 0.52f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
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
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.24f, w * 0.085f * windScale, seaSpan * 0.30f, shimRgb or ((masterAlpha * (if (isDay) 0.22f else 0.16f) * lumBoost * shimBreath).toInt().coerceIn(0, 255) shl 24))
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.56f, w * 0.155f * windScale, seaSpan * 0.44f, shimRgb or ((masterAlpha * (if (isDay) 0.12f else 0.09f) * lumBoost * shimBreath).toInt().coerceIn(0, 255) shl 24))
        for (gIdx in 0 until 4) {
            val fracY = 0.12f + gIdx * 0.19f
            val glintVis = ((sin(timeSec * (0.45 + gIdx * 0.12) * 1.618 + gIdx * 1.9) + 1.0) * 0.5).toFloat()
            val spreadX = sin(timeSec * 0.28 * 1.4142 + gIdx * 1.7).toFloat() * (w * (0.018f + 0.022f * fracY) * windScale)
            val gA = (masterAlpha * (0.14f - 0.07f * fracY) * lumBoost * (0.45f + 0.55f * glintVis)).toInt().coerceIn(0, 255)
            if (gA > 2) drawSoftMist(canvas, orbX + spreadX, horizonY + seaSpan * fracY, w * (0.038f + 0.032f * fracY) * windScale, seaSpan * (0.045f + 0.035f * fracY), shimRgb or (gA shl 24))
        }
        if (weather.sky == SkyWeather.RAIN || weather.sky == SkyWeather.THUNDERSTORM) {
            strokePaint.strokeWidth = density * 0.8f; strokePaint.color = 0xFFBAE6FD.toInt()
            for (rp in 0 until 4) {
                val hRp = abs(sin(rp * 12.9898 + 1.3)).toFloat()
                val rpProg = (((timeSec * (0.50 + hRp * 0.20)) + rp * 0.25 + hRp * 0.31) % 1.0).toFloat()
                val rx = w * (0.12f + 0.76f * hRp); val ry = horizonY + seaSpan * (0.22f + 0.54f * abs(sin(rp * 78.233)).toFloat())
                val rad = w * (0.01f + rpProg * 0.035f)
                strokePaint.alpha = (masterAlpha * 0.24f * (1f - rpProg)).toInt().coerceIn(0, 255)
                rect.set(rx - rad, ry - rad * 0.28f, rx + rad, ry + rad * 0.28f); canvas.drawOval(rect, strokePaint)
            }
        }
    }

    private fun drawThermalEagles(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, timeSec: Double, masterAlpha: Int) {
        strokePaint.strokeWidth = density * 1.35f; strokePaint.color = 0xFF1E293B.toInt()
        for (e in 0 until 2) {
            val ex = w * (0.34f + e * 0.32f) + sin(timeSec * (0.16 + e * 0.04) + e * 2.0).toFloat() * (w * 0.14f) * windDir
            val ey = h * (0.24f + e * 0.08f) + cos(timeSec * 0.22 + e * 1.4).toFloat() * (h * 0.018f)
            val span = w * (0.022f - e * 0.004f); val glide = sin(timeSec * 0.55 + e * 1.7).toFloat() * (span * 0.16f)
            strokePaint.alpha = (masterAlpha * (0.48f - e * 0.10f)).toInt().coerceIn(0, 255)
            canvas.drawLine(ex - span, ey - glide, ex, ey + span * 0.15f, strokePaint)
            canvas.drawLine(ex, ey + span * 0.15f, ex + span, ey - glide, strokePaint)
        }
    }

    private fun drawBirds(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, timeSec: Double, masterAlpha: Int) {
        strokePaint.strokeWidth = density * 1.05f; strokePaint.color = 0xFF0F172A.toInt()
        for (s in 0 until 4) {
            val sx = ((((0.18f + s * 0.06f + (timeSec * 0.009 * windDir).toFloat()) % 1.25f) + 1.25f) % 1.25f - 0.12f) * w
            val sy = h * (0.26f + (s % 2) * 0.025f + (s / 2) * 0.020f) + sin(timeSec * 1.4 + s * 1.1).toFloat() * (h * 0.009f)
            val span = w * (0.013f - s * 0.0012f); val flap = sin(timeSec * 2.0 + s * 1.4).toFloat() * (span * 0.36f)
            strokePaint.alpha = (masterAlpha * 0.44f).toInt().coerceIn(0, 255)
            canvas.drawLine(sx - span, sy - flap, sx, sy + span * 0.2f, strokePaint)
            canvas.drawLine(sx, sy + span * 0.2f, sx + span, sy - flap, strokePaint)
        }
    }
}
