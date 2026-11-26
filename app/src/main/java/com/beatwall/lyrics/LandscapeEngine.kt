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
    VALLEY_STAG("Valley Lake & Stag")
}

/**
 * Pure-Vector Firewatch Minimalist Atmospheric Landscape Engine (v9.0.0).
 * - Zero WebP bitmaps, zero RuntimeShaders, zero Random dice rolls, zero per-frame steady allocations.
 * - Pre-builds 5 multi-octave fractal & 16-branch feathery conifer layers ONCE per dayOfYear/size.
 * - Dynamically grades all 5 depth layers from live astronomical sky colors (dawn/noon/sunset/midnight).
 * - Steady weather.windKmh pacing (never oscillating gusts) + Lock<->Home 3D parallax.
 */
class LandscapeEngine {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    private var cachedMistColor = 0
    private var cachedMistShader: RadialGradient? = null

    private val layerPaths = Array(5) { Path() }
    private val accentPath = Path()
    private val reflectPath = Path()
    private val dynPath = Path()

    // Architectural light anchor points computed once in ensureGeometry
    private var focalX = 0f
    private var focalY = 0f
    private var focalW = 0f
    private var focalH = 0f

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
            WorldBiome.VALLEY_STAG -> 1.22f
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

    // 14-to-20 branch irregular drooping Firewatch conifer silhouette
    private fun addFeatheryPine(path: Path, cx: Float, baseY: Float, treeH: Float, maxHalfW: Float, seed: Int) {
        val tw = maxOf(1.2f, maxHalfW * 0.11f)
        path.moveTo(cx - tw, baseY + 6f)
        path.lineTo(cx - tw * 0.25f, baseY - treeH * 0.96f)
        path.lineTo(cx + tw * 0.25f, baseY - treeH * 0.96f)
        path.lineTo(cx + tw, baseY + 6f)
        path.close()

        // Needle-sharp spire tip
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
            for (sIdx in 0..1) {
                val side = if (sIdx == 0) -1f else 1f
                val jitter = 0.72f + 0.56f * seedRand(seed + t * 13, if (sIdx == 0) 3 else 7)
                val bw = maxHalfW * env * jitter
                val droop = treeH * (0.045f - frac * 0.025f) * (0.8f + 0.4f * seedRand(seed + t, 11))
                path.moveTo(cx, yAtt - thick * 0.5f)
                path.lineTo(cx + side * bw * 0.55f, yAtt + droop * 0.35f - thick * 0.25f)
                path.lineTo(cx + side * bw, yAtt + droop)
                path.lineTo(cx + side * bw * 0.65f, yAtt + droop * 0.95f + thick * 0.45f)
                path.lineTo(cx + side * bw * 0.28f, yAtt + droop * 0.65f + thick * 0.65f)
                path.lineTo(cx, yAtt + thick * 0.95f)
                path.close()
            }
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

    // Architectural Firewatch Lookout Tower (angled legs, 4-tier X-trusses, balcony, hip roof)
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

        val tiers = 4
        for (i in 0 until tiers) {
            val f0 = i / tiers.toFloat()
            val f1 = (i + 1) / tiers.toFloat()
            val y0 = baseY - (baseY - cabY) * f0
            val y1 = baseY - (baseY - cabY) * f1
            val w0 = botW * (1f - f0) + topW * f0
            val w1 = botW * (1f - f1) + topW * f1
            addLineQuad(path, tx - w0 * 0.5f, y0, tx + w0 * 0.5f, y0, braceHW)
            addLineQuad(path, tx - w0 * 0.5f, y0, tx + w1 * 0.5f, y1, braceHW)
            addLineQuad(path, tx + w0 * 0.5f, y0, tx - w1 * 0.5f, y1, braceHW)
        }

        // Balcony deck & railing
        path.addRect(tx - balW * 0.5f, cabY - 2.5f, tx + balW * 0.5f, cabY + 2.5f, Path.Direction.CW)
        val railY = cabY - cabH * 0.36f
        addLineQuad(path, tx - balW * 0.5f, railY, tx + balW * 0.5f, railY, braceHW)
        for (p in 0..6) {
            val px = tx - balW * 0.5f + balW * (p / 6f)
            addLineQuad(path, px, cabY, px, railY, braceHW * 0.7f)
        }

        // Cabin lower wall & window mullions
        path.addRect(tx - cabW * 0.5f, cabY - cabH * 0.42f, tx + cabW * 0.5f, cabY, Path.Direction.CW)
        path.addRect(tx - cabW * 0.5f, cabY - cabH, tx - cabW * 0.38f, cabY, Path.Direction.CW)
        path.addRect(tx + cabW * 0.38f, cabY - cabH, tx + cabW * 0.5f, cabY, Path.Direction.CW)
        addLineQuad(path, tx, cabY - cabH, tx, cabY, braceHW)

        // Pitched hip roof
        val roofW = balW * 1.14f
        val roofY = cabY - cabH
        path.moveTo(tx - roofW * 0.5f, roofY + 3f)
        path.lineTo(tx, roofY - towerH * 0.13f)
        path.lineTo(tx + roofW * 0.5f, roofY + 3f)
        path.close()

        focalX = tx
        focalY = cabY - cabH * 0.68f
        focalW = cabW * 0.74f
        focalH = cabH * 0.46f
    }

    // Architectural Japanese Torii Gate
    private fun addToriiGate(path: Path, tx: Float, baseY: Float, gateH: Float) {
        val span = gateH * 0.78f
        val pillarW = gateH * 0.085f
        val topY = baseY - gateH
        // Two gently battered pillars
        addLineQuad(path, tx - span * 0.36f, baseY + 6f, tx - span * 0.33f, topY + gateH * 0.12f, pillarW * 0.5f)
        addLineQuad(path, tx + span * 0.36f, baseY + 6f, tx + span * 0.33f, topY + gateH * 0.12f, pillarW * 0.5f)
        // Curved upper Kasagi lintel (swept upturned ends)
        path.moveTo(tx - span * 0.62f, topY - gateH * 0.05f)
        path.quadTo(tx, topY + gateH * 0.08f, tx + span * 0.62f, topY - gateH * 0.05f)
        path.lineTo(tx + span * 0.56f, topY + gateH * 0.10f)
        path.quadTo(tx, topY + gateH * 0.17f, tx - span * 0.56f, topY + gateH * 0.10f)
        path.close()
        // Lower Nuki crossbeam
        path.addRect(tx - span * 0.48f, topY + gateH * 0.24f, tx + span * 0.48f, topY + gateH * 0.31f, Path.Direction.CW)
        // Center Gakuzuka strut
        path.addRect(tx - pillarW * 0.45f, topY + gateH * 0.10f, tx + pillarW * 0.45f, topY + gateH * 0.27f, Path.Direction.CW)
    }

    // Architectural Coastal Lighthouse
    private fun addLighthouse(path: Path, lx: Float, baseY: Float, lhH: Float) {
        val botW = lhH * 0.24f
        val topW = lhH * 0.14f
        val deckY = baseY - lhH * 0.76f
        path.moveTo(lx - botW * 0.5f, baseY + 8f)
        path.lineTo(lx - topW * 0.5f, deckY)
        path.lineTo(lx + topW * 0.5f, deckY)
        path.lineTo(lx + botW * 0.5f, baseY + 8f)
        path.close()
        // Gallery deck
        path.addRect(lx - topW * 0.85f, deckY - 3f, lx + topW * 0.85f, deckY + 3f, Path.Direction.CW)
        // Lantern room pillars
        val lanTop = deckY - lhH * 0.14f
        addLineQuad(path, lx - topW * 0.48f, deckY, lx - topW * 0.48f, lanTop, 1.5f)
        addLineQuad(path, lx + topW * 0.48f, deckY, lx + topW * 0.48f, lanTop, 1.5f)
        // Domed cupola + finial
        path.moveTo(lx - topW * 0.65f, lanTop)
        path.quadTo(lx, lanTop - lhH * 0.13f, lx + topW * 0.65f, lanTop)
        path.close()
        addLineQuad(path, lx, lanTop - lhH * 0.08f, lx, lanTop - lhH * 0.16f, 1.2f)

        focalX = lx
        focalY = (deckY + lanTop) * 0.5f
        focalW = topW * 0.9f
        focalH = lhH * 0.12f
    }

    // Quiet Standing Stag Silhouette (Row 2 Firewatch classic)
    private fun addStagSilhouette(path: Path, sx: Float, baseY: Float, sH: Float) {
        // Slim legs
        addLineQuad(path, sx - sH * 0.24f, baseY + 3f, sx - sH * 0.20f, baseY - sH * 0.44f, sH * 0.025f)
        addLineQuad(path, sx - sH * 0.14f, baseY + 3f, sx - sH * 0.12f, baseY - sH * 0.44f, sH * 0.024f)
        addLineQuad(path, sx + sH * 0.16f, baseY + 3f, sx + sH * 0.14f, baseY - sH * 0.42f, sH * 0.025f)
        addLineQuad(path, sx + sH * 0.26f, baseY + 3f, sx + sH * 0.22f, baseY - sH * 0.42f, sH * 0.024f)
        // Torso
        path.moveTo(sx - sH * 0.26f, baseY - sH * 0.42f)
        path.quadTo(sx, baseY - sH * 0.56f, sx + sH * 0.26f, baseY - sH * 0.40f)
        path.quadTo(sx, baseY - sH * 0.34f, sx - sH * 0.26f, baseY - sH * 0.42f)
        path.close()
        // Proud neck & head facing right toward lake
        path.moveTo(sx + sH * 0.14f, baseY - sH * 0.46f)
        path.lineTo(sx + sH * 0.28f, baseY - sH * 0.78f)
        path.lineTo(sx + sH * 0.40f, baseY - sH * 0.76f)
        path.lineTo(sx + sH * 0.34f, baseY - sH * 0.68f)
        path.lineTo(sx + sH * 0.26f, baseY - sH * 0.42f)
        path.close()
        // Sweeping antlers
        val ax = sx + sH * 0.28f
        val ay = baseY - sH * 0.78f
        addLineQuad(path, ax, ay, ax - sH * 0.06f, ay - sH * 0.24f, sH * 0.014f)
        addLineQuad(path, ax - sH * 0.04f, ay - sH * 0.14f, ax + sH * 0.06f, ay - sH * 0.22f, sH * 0.011f)
        addLineQuad(path, ax + sH * 0.04f, ay, ax + sH * 0.14f, ay - sH * 0.22f, sH * 0.014f)
        addLineQuad(path, ax + sH * 0.10f, ay - sH * 0.12f, ax + sH * 0.20f, ay - sH * 0.18f, sH * 0.011f)
    }

    // Faceted angular granite boulder cut completely flat at waterline (zero ovals!)
    private fun addFacetedBoulder(path: Path, cx: Float, waterY: Float, halfW: Float, rockH: Float, seed: Int) {
        val pts = 9
        path.moveTo(cx - halfW, waterY + 2f)
        for (i in 0..pts) {
            val t = i / pts.toFloat()
            val x = cx - halfW + t * halfW * 2f
            val arch = sin(t * PI).toFloat().pow(0.72f)
            val facet = 0.80f + 0.32f * seedRand(seed + i * 7, 17)
            val y = waterY - arch * rockH * facet
            path.lineTo(x, y)
        }
        path.lineTo(cx + halfW, waterY + 2f)
        path.close()
    }

    // Multi-branch architectural Acacia silhouette (zero mushroom ovals!)
    private fun addArchitecturalAcacia(path: Path, tx: Float, baseY: Float, treeH: Float, spanW: Float, seed: Int) {
        val lean = (seedRand(seed, 5) - 0.5f) * spanW * 0.18f
        val forkY = baseY - treeH * 0.46f
        val tw = maxOf(2f, treeH * 0.042f)
        addLineQuad(path, tx, baseY + 6f, tx + lean * 0.5f, forkY, tw)
        // 3 spreading branches
        val bTargets = floatArrayOf(-0.34f, 0.04f, 0.36f)
        val bHeights = floatArrayOf(0.92f, 1.0f, 0.86f)
        for (b in 0 until 3) {
            val bx = tx + lean + spanW * bTargets[b]
            val by = baseY - treeH * bHeights[b]
            addLineQuad(path, tx + lean * 0.5f, forkY, bx, by, tw * 0.55f)
            // Flat layered irregular foliage crown (faceted jagged canopy polygon)
            val cw = spanW * (0.28f + 0.08f * (1 - abs(b - 1)))
            val ch = treeH * 0.14f
            path.moveTo(bx - cw, by + ch * 0.25f)
            path.lineTo(bx - cw * 0.78f, by - ch * 0.45f)
            path.lineTo(bx - cw * 0.30f, by - ch * 0.72f)
            path.lineTo(bx + cw * 0.22f, by - ch * 0.65f)
            path.lineTo(bx + cw * 0.76f, by - ch * 0.38f)
            path.lineTo(bx + cw, by + ch * 0.22f)
            path.close()
        }
    }

    private fun buildFractalRidge(
        path: Path, w: Float, h: Float, baseYFrac: Float,
        centerPeakFrac: Float, peakPos: Float, cragScale: Float,
        bowlFrac: Float, seed: Int, steps: Int = 180
    ): FloatArray {
        val ys = FloatArray(steps + 1)
        path.moveTo(-w * 0.02f, h * 1.15f)
        for (i in 0..steps) {
            val fx = i / steps.toFloat()
            val peak = (1f - abs(fx - peakPos) / 0.34f).coerceIn(0f, 1f).pow(1.45f) * centerPeakFrac
            val shL = (1f - abs(fx - 0.14f) / 0.26f).coerceIn(0f, 1f).pow(1.6f) * (centerPeakFrac * 0.52f)
            val shR = (1f - abs(fx - 0.86f) / 0.28f).coerceIn(0f, 1f).pow(1.6f) * (centerPeakFrac * 0.58f)
            val bowl = sin(fx * PI).toFloat() * bowlFrac
            val crag = fbm1d(fx, seed, octaves = 5) * cragScale
            val y = h * (baseYFrac + bowl - peak - shL - shR - crag)
            ys[i] = y
            path.lineTo(fx * w, y)
        }
        path.lineTo(w * 1.02f, h * 1.15f)
        path.close()
        return ys
    }

    private fun ensureGeometry(dayOfYear: Int, w: Int, h: Int) {
        if (builtDay == dayOfYear && builtW == w && builtH == h) return
        builtDay = dayOfYear
        builtW = w
        builtH = h

        for (p in layerPaths) p.reset()
        accentPath.reset()
        reflectPath.reset()
        focalX = 0f; focalY = 0f; focalW = 0f; focalH = 0f

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
                val tBase = r3[(towerFx * 150).toInt().coerceIn(0, 150)] + 10f
                addLookoutTower(layerPaths[2], towerFx * wf, tBase, hf * 0.102f)

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
                val baseFracs = floatArrayOf(0.58f, 0.63f, 0.69f, 0.76f, 0.84f)
                val amps = floatArrayOf(0.026f, 0.034f, 0.042f, 0.048f, 0.042f)
                val freqs = floatArrayOf(1.9f, 1.55f, 1.25f, 1.05f, 0.85f)
                for (l in 0 until 5) {
                    val p = seedRand(dayOfYear, 1 + l * 5) * 6.28f
                    val path = layerPaths[l]
                    path.moveTo(0f, hf * 1.15f)
                    for (i in 0..80) {
                        val fx = i / 80f
                        val wave = sin(fx * freqs[l] * PI + p) * 0.68 + sin(fx * freqs[l] * 2.1 * PI + p * 0.6) * 0.32
                        path.lineTo(fx * wf, hf * baseFracs[l] - wave.toFloat() * (hf * amps[l]))
                    }
                    path.lineTo(wf, hf * 1.15f)
                    path.close()
                }
                // Subtle ruined desert stone arch silhouette on Layer 2
                val ax = wf * (0.28f + seedRand(dayOfYear, 41) * 0.44f)
                val ay = hf * 0.675f
                val aw = wf * 0.075f
                val ah = hf * 0.052f
                addLineQuad(layerPaths[2], ax - aw * 0.5f, ay + 12f, ax - aw * 0.42f, ay - ah, wf * 0.010f)
                addLineQuad(layerPaths[2], ax + aw * 0.5f, ay + 12f, ax + aw * 0.42f, ay - ah * 0.85f, wf * 0.010f)
                addLineQuad(layerPaths[2], ax - aw * 0.52f, ay - ah, ax + aw * 0.48f, ay - ah * 0.88f, wf * 0.008f)
            }

            WorldBiome.SNOWY_ALPENGLOW_LAKE -> {
                val p1 = 0.35f + seedRand(dayOfYear, 7) * 0.30f
                buildFractalRidge(layerPaths[0], wf, hf, 0.61f, 0.115f, p1, 0.044f, 0f, dayOfYear + 13, 200)
                val rMid = buildFractalRidge(layerPaths[1], wf, hf, 0.64f, 0.135f, 1f - p1 * 0.7f, 0.050f, 0f, dayOfYear + 23, 200)
                // Snow facet highlights on upper peaks
                accentPath.moveTo(0f, hf * 0.56f)
                for (i in 0..200) accentPath.lineTo((i / 200f) * wf, rMid[i])
                for (i in 200 downTo 0) {
                    val fx = i / 200f
                    val depth = hf * (0.016f + 0.028f * ((hf * 0.64f - rMid[i]) / (hf * 0.16f)).coerceIn(0f, 1f))
                    accentPath.lineTo(fx * wf, minOf(hf * 0.60f, rMid[i] + depth))
                }
                accentPath.close()
                // Inverted mountain reflection in mirror lake
                reflectPath.moveTo(0f, baseH)
                for (i in 0..100) {
                    val fx = i / 100f
                    val mY = rMid[(fx * 200).toInt().coerceIn(0, 200)]
                    reflectPath.lineTo(fx * wf, baseH + (baseH - mY).coerceAtLeast(0f) * 0.68f)
                }
                reflectPath.lineTo(wf, baseH)
                reflectPath.close()
                // Foreground dark pine-framed lake shore
                val rShore = buildFractalRidge(layerPaths[4], wf, hf, 0.80f, 0f, 0.5f, 0.014f, 0.055f, dayOfYear + 89, 100)
                val heroes = arrayOf(0.04f to 0.22f, 0.12f to 0.15f, 0.88f to 0.16f, 0.96f to 0.23f)
                for ((idx, pair) in heroes.withIndex()) {
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
                // 3 depth layers of slender organic woodland trunks + feathery upper boughs + mossy clearing bank
                buildWoodlandTrunks(layerPaths[0], wf, hf, dayOfYear, 31, count = 7, minW = 0.008f, maxW = 0.014f, openCenter = false)
                buildWoodlandTrunks(layerPaths[2], wf, hf, dayOfYear, 53, count = 6, minW = 0.016f, maxW = 0.024f, openCenter = true)
                val rBank = buildFractalRidge(layerPaths[4], wf, hf, 0.79f, 0f, 0.5f, 0.014f, 0.038f, dayOfYear + 79, 100)
                val edgeSlots = floatArrayOf(0.05f, 0.16f, 0.84f, 0.95f)
                for (i in edgeSlots.indices) {
                    val cx = edgeSlots[i] * wf
                    val curve = (if (i < 2) 1f else -1f) * wf * 0.022f
                    val tw = wf * (0.026f + 0.008f * seedRand(dayOfYear + i, 83))
                    addOrganicTrunk(layerPaths[4], cx, hf * 1.05f, -hf * 0.04f, tw * 0.72f, tw * 0.34f, curve)
                    // Feathery upper canopy boughs arching into top corners
                    val dir = if (i < 2) 1f else -1f
                    for (b in 0 until 4) {
                        val by = hf * (0.03f + b * 0.055f)
                        val blen = wf * (0.28f - b * 0.04f) * dir
                        addLineQuad(layerPaths[4], cx + curve * 0.8f, by, cx + curve * 0.8f + blen, by + hf * 0.025f, wf * 0.006f)
                        // Pine needle sprigs along bough
                        for (sp in 1..6) {
                            val t = sp / 6f
                            val sx = cx + curve * 0.8f + blen * t
                            val sy = by + hf * 0.025f * t
                            addFeatheryPine(layerPaths[4], sx, sy + hf * 0.035f, hf * (0.045f - b * 0.005f), wf * 0.025f, dayOfYear + i * 20 + b * 7 + sp)
                        }
                    }
                }
                // Keep rBank referenced cleanly via mossy floor
                if (rBank.isNotEmpty()) {
                    focalY = rBank[50]
                }
            }

            WorldBiome.COASTAL_REEDS -> {
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.045f, dayOfYear % 2 == 1, dayOfYear)
                buildFractalRidge(layerPaths[4], wf, hf, 0.84f, 0f, 0.5f, 0.012f, 0.042f, dayOfYear + 111, 80)
            }

            WorldBiome.CRYSTAL_COVE -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.62f, 0.032f, 0.44f, 0.018f, 0f, dayOfYear + 19, 140)
                // Faceted granite boulders cut flat at baseH (waterline)
                val bPositions = floatArrayOf(0.08f, 0.22f, 0.38f, 0.54f, 0.72f, 0.88f)
                for (i in bPositions.indices) {
                    val cx = (bPositions[i] + (seedRand(dayOfYear + i, 131) - 0.5f) * 0.05f) * wf
                    val hw = wf * (0.075f + 0.045f * seedRand(dayOfYear + i, 137))
                    val rh = hf * (0.042f + 0.034f * seedRand(dayOfYear + i, 139))
                    addFacetedBoulder(layerPaths[2], cx, baseH, hw, rh, dayOfYear + i * 13)
                    // Soft inverted reflection below baseH
                    addFacetedBoulder(reflectPath, cx, baseH, hw * 0.96f, -rh * 0.55f, dayOfYear + i * 13)
                }
                // 2 foreground corner rocks
                addFacetedBoulder(layerPaths[4], wf * 0.12f, hf * 0.88f, wf * 0.14f, hf * 0.050f, dayOfYear + 211)
                addFacetedBoulder(layerPaths[4], wf * 0.88f, hf * 0.90f, wf * 0.15f, hf * 0.048f, dayOfYear + 223)
            }

            WorldBiome.KYOTO_TORII -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.58f, 0.075f, 0.52f, 0.032f, 0f, dayOfYear + 31, 180)
                buildFractalRidge(layerPaths[1], wf, hf, 0.615f, 0.035f, 0.22f, 0.022f, 0.020f, dayOfYear + 47, 150)
                // Iconic Torii gate standing in the calm mirror lake
                val gx = wf * 0.50f
                val gy = baseH + hf * 0.095f
                addToriiGate(layerPaths[3], gx, gy, hf * 0.115f)
                // Foreground dark pine-framed shore + stone lantern on right
                val rShore = buildFractalRidge(layerPaths[4], wf, hf, 0.82f, 0f, 0.5f, 0.012f, 0.050f, dayOfYear + 83, 100)
                addFeatheryPine(layerPaths[4], wf * 0.06f, rShore[6] + 16f, hf * 0.21f, hf * 0.055f, dayOfYear + 701)
                addFeatheryPine(layerPaths[4], wf * 0.94f, rShore[94] + 16f, hf * 0.19f, hf * 0.050f, dayOfYear + 709)
                // Tiny stone lantern silhouette on right shore
                val lx = wf * 0.80f
                val ly = rShore[80] + 4f
                val lh = hf * 0.042f
                addLineQuad(layerPaths[4], lx, ly, lx, ly - lh * 0.65f, wf * 0.006f)
                layerPaths[4].moveTo(lx - wf * 0.024f, ly - lh * 0.72f)
                layerPaths[4].lineTo(lx, ly - lh)
                layerPaths[4].lineTo(lx + wf * 0.024f, ly - lh * 0.72f)
                layerPaths[4].close()
                focalX = lx; focalY = ly - lh * 0.64f; focalW = wf * 0.016f; focalH = lh * 0.22f
            }

            WorldBiome.LIGHTHOUSE_CLIFF -> {
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.050f, false, dayOfYear + 5)
                // Dramatic stepped coastal cliff on left + tall architectural lighthouse
                val cliffPath = layerPaths[3]
                cliffPath.moveTo(0f, hf * 1.15f)
                cliffPath.lineTo(0f, baseH - hf * 0.075f)
                cliffPath.lineTo(wf * 0.18f, baseH - hf * 0.082f)
                cliffPath.lineTo(wf * 0.29f, baseH - hf * 0.068f)
                cliffPath.lineTo(wf * 0.35f, baseH + hf * 0.010f)
                cliffPath.lineTo(wf * 0.42f, baseH + hf * 0.025f)
                cliffPath.lineTo(0f, baseH + hf * 0.025f)
                cliffPath.close()
                addLighthouse(layerPaths[3], wf * 0.23f, baseH - hf * 0.078f, hf * 0.135f)
                buildFractalRidge(layerPaths[4], wf, hf, 0.84f, 0f, 0.5f, 0.016f, 0.045f, dayOfYear + 101, 90)
            }

            WorldBiome.AURORA_FJORD -> {
                // Steep left & right V-shaped Nordic fjord walls framing a deep mirror inlet
                buildFractalRidge(layerPaths[0], wf, hf, 0.60f, 0.085f, 0.50f, 0.036f, 0f, dayOfYear + 21, 180)
                // Left fjord wall
                layerPaths[2].moveTo(0f, hf * 1.15f)
                layerPaths[2].lineTo(0f, hf * 0.44f)
                for (i in 0..60) {
                    val t = i / 60f
                    val x = t * wf * 0.44f
                    val y = hf * (0.44f + t.pow(1.25f) * 0.24f) - fbm1d(t, dayOfYear + 33, 4) * (hf * 0.022f)
                    layerPaths[2].lineTo(x, y)
                }
                layerPaths[2].lineTo(wf * 0.44f, baseH + 4f)
                layerPaths[2].lineTo(0f, baseH + 4f)
                layerPaths[2].close()
                // Right fjord wall
                layerPaths[2].moveTo(wf, hf * 1.15f)
                layerPaths[2].lineTo(wf, hf * 0.46f)
                for (i in 0..60) {
                    val t = i / 60f
                    val x = wf - t * wf * 0.42f
                    val y = hf * (0.46f + t.pow(1.25f) * 0.22f) - fbm1d(t, dayOfYear + 49, 4) * (hf * 0.022f)
                    layerPaths[2].lineTo(x, y)
                }
                layerPaths[2].lineTo(wf * 0.58f, baseH + 4f)
                layerPaths[2].lineTo(wf, baseH + 4f)
                layerPaths[2].close()

                // Foreground timber shoreline + cozy Nordic cabin with glowing window & chimney
                val rShore = buildFractalRidge(layerPaths[4], wf, hf, 0.81f, 0f, 0.5f, 0.014f, 0.048f, dayOfYear + 77, 100)
                val cx = wf * 0.28f
                val cy = rShore[28] + 4f
                val cw = wf * 0.095f
                val ch = hf * 0.036f
                layerPaths[4].addRect(cx - cw * 0.5f, cy - ch, cx + cw * 0.5f, cy + 8f, Path.Direction.CW)
                layerPaths[4].moveTo(cx - cw * 0.62f, cy - ch)
                layerPaths[4].lineTo(cx, cy - ch * 1.75f)
                layerPaths[4].lineTo(cx + cw * 0.62f, cy - ch)
                layerPaths[4].close()
                // Chimney
                layerPaths[4].addRect(cx + cw * 0.22f, cy - ch * 1.85f, cx + cw * 0.34f, cy - ch * 1.1f, Path.Direction.CW)
                addFeatheryPine(layerPaths[4], wf * 0.07f, rShore[7] + 16f, hf * 0.22f, hf * 0.056f, dayOfYear + 801)
                addFeatheryPine(layerPaths[4], wf * 0.92f, rShore[92] + 16f, hf * 0.20f, hf * 0.052f, dayOfYear + 809)
                focalX = cx; focalY = cy - ch * 0.52f; focalW = cw * 0.22f; focalH = ch * 0.38f
            }

            WorldBiome.VALLEY_STAG -> {
                // Classic Row-2 Firewatch: layered peaks dipping into a glowing pocket lake + stag silhouette!
                buildFractalRidge(layerPaths[0], wf, hf, 0.59f, 0.075f, 0.32f, 0.036f, 0f, dayOfYear + 17, 180)
                val r2 = buildFractalRidge(layerPaths[1], wf, hf, 0.635f, 0f, 0.5f, 0.026f, 0.030f, dayOfYear + 37, 150)
                for (t in 0 until 60) {
                    val fx = (t + 0.2f + 0.6f * seedRand(dayOfYear + t, 43)) / 60f
                    if (fx in 0.36f..0.64f) continue
                    val by = r2[(fx * 150).toInt().coerceIn(0, 150)] + 4f
                    val th = hf * (0.024f + 0.026f * seedRand(dayOfYear + t, 45))
                    addFeatheryPine(layerPaths[1], fx * wf, by, th, th * 0.23f, dayOfYear + 250 + t)
                }
                val r4 = buildFractalRidge(layerPaths[4], wf, hf, 0.77f, 0f, 0.5f, 0.015f, 0.058f, dayOfYear + 63, 120)
                val heroes = arrayOf(0.05f to 0.23f, 0.14f to 0.16f, 0.86f to 0.17f, 0.95f to 0.24f)
                for ((idx, pair) in heroes.withIndex()) {
                    val by = r4[(pair.first * 120).toInt().coerceIn(0, 120)] + 16f
                    addFeatheryPine(layerPaths[4], pair.first * wf, by, hf * pair.second, hf * pair.second * 0.26f, dayOfYear + 900 + idx * 13)
                }
                // Quiet stag standing on the left shore looking toward the glowing pocket lake
                val stagX = wf * 0.32f
                val stagY = r4[38] + 4f
                addStagSilhouette(layerPaths[4], stagX, stagY, hf * 0.068f)
            }
        }
    }

    private fun buildHeadland(path: Path, w: Float, yBase: Float, cliffH: Float, onLeft: Boolean, day: Int) {
        val span = 0.28f + seedRand(day, 99) * 0.12f
        val steps = 36
        path.moveTo(if (onLeft) 0f else w * (1f - span), yBase + 4f)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val fx = if (onLeft) t * span else (1f - span) + t * span
            val profile = if (onLeft) (1f - t) else t
            val crag = 0.82f + 0.24f * fbm1d(t, day + 5, 3)
            val y = yBase - (profile * profile) * cliffH * crag
            path.lineTo(fx * w, y)
        }
        path.lineTo(if (onLeft) w * span else w, yBase + 4f)
        path.close()
    }

    private fun addOrganicTrunk(path: Path, cx: Float, botY: Float, topY: Float, baseHW: Float, topHW: Float, curveX: Float) {
        val midY = (botY + topY) * 0.5f
        val rootFlare = baseHW * 1.7f
        path.moveTo(cx - rootFlare, botY)
        path.quadTo(cx - baseHW, botY - (botY - topY) * 0.12f, cx + curveX * 0.5f - baseHW * 0.85f, midY)
        path.quadTo(cx + curveX - topHW, topY + (botY - topY) * 0.15f, cx + curveX - topHW, topY)
        path.lineTo(cx + curveX + topHW, topY)
        path.quadTo(cx + curveX + topHW, topY + (botY - topY) * 0.15f, cx + curveX * 0.5f + baseHW * 0.85f, midY)
        path.quadTo(cx + baseHW, botY - (botY - topY) * 0.12f, cx + rootFlare, botY)
        path.close()
    }

    private fun buildWoodlandTrunks(
        path: Path, w: Float, h: Float, day: Int, salt: Int,
        count: Int, minW: Float, maxW: Float, openCenter: Boolean
    ) {
        for (i in 0 until count) {
            var fx = (i + 0.22f + 0.56f * seedRand(day + i, salt + 3)) / count
            if (openCenter && fx in 0.36f..0.64f) {
                fx = if (fx < 0.5f) 0.26f - (0.5f - fx) * 0.45f else 0.74f + (fx - 0.5f) * 0.45f
            }
            val cx = fx * w
            val curve = (seedRand(day + i, salt + 7) - 0.5f) * (w * 0.032f)
            val tw = w * (minW + (maxW - minW) * seedRand(day + i, salt + 11))
            addOrganicTrunk(path, cx, h * 0.96f, -h * 0.04f, tw * 0.60f, tw * 0.28f, curve)
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

    // Firewatch 5-step luminous atmospheric depth layer helper
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
        val midShiftY = (horizonY - baseHorizon) * 0.72f
        val foreShiftY = (horizonY - baseHorizon) * 1.15f

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

        // Unified Firewatch 5-step monochromatic hue ramp derived from live sky
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

                drawGradientLayer(canvas, layerPaths[0], height * 0.46f, height * 0.74f, c1Top, c1Bot, 0f, midShiftY * 0.40f, masterAlpha)
                val m1A = (masterAlpha * (0.24f + fogFactor * 0.20f)).toInt().coerceIn(0, 170)
                val drift1 = (((timeSec * (0.0022 + steadyWind * 0.00018) * windDir) % 1.0 + 1.0) % 1.0).toFloat()
                drawSoftMist(canvas, (drift1 * 1.4f - 0.2f) * width, horizonY - height * 0.03f, width * 0.85f, height * 0.045f, mistRgb or (m1A shl 24))

                drawGradientLayer(canvas, layerPaths[1], height * 0.50f, height * 0.80f, c2Top, c2Bot, 0f, midShiftY * 0.62f, masterAlpha)
                drawSoftMist(canvas, width * 0.5f, horizonY + height * 0.01f, width * 0.92f, height * 0.038f, mistRgb or ((m1A * 0.8f).toInt() shl 24))

                drawGradientLayer(canvas, layerPaths[2], height * 0.56f, height * 0.86f, c3Top, c3Bot, 0f, midShiftY * 0.85f, masterAlpha)

                // Warm Firewatch Lookout Tower cabin lamp wakes automatically at dusk & night!
                val lampStr = ((3f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                if (lampStr > 0.05f && focalW > 0f) {
                    val fy = focalY + midShiftY * 0.85f
                    val flicker = 0.92f + 0.08f * sin(timeSec * 3.2).toFloat()
                    val haloA = (masterAlpha * 0.52f * lampStr * flicker).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, focalX, fy, focalW * 2.4f, focalH * 2.8f, 0x00FFD268 or (haloA shl 24))
                    fillPaint.color = 0xFFFFE28A.toInt()
                    fillPaint.alpha = (masterAlpha * 0.95f * lampStr).toInt()
                    canvas.drawRect(focalX - focalW * 0.5f, fy - focalH * 0.5f, focalX + focalW * 0.5f, fy + focalH * 0.5f, fillPaint)
                }

                val sway4 = sin(timeSec * 0.42).toFloat() * (density * (0.8f + steadyWind * 0.03f)) * windDir
                drawGradientLayer(canvas, layerPaths[3], height * 0.64f, height * 0.94f, c4Top, c4Bot, sway4, foreShiftY * 0.78f, masterAlpha)

                val sway5 = sin(timeSec * 0.52 * 1.414).toFloat() * (density * (1.2f + steadyWind * 0.045f)) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), c5Top, 0xFF03040A.toInt(), sway5, foreShiftY, masterAlpha)

                drawFireflies(canvas, width, height, density, dayOfYear, horizonY, sky, weather, timeSec, masterAlpha)
                if (isDay || alpenglow > 0.1f) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.SAND_DUNES -> {
                val sandAlbedo = 0xFFE59A3A.toInt()
                val litSand = ColorUtils.blendARGB(sandAlbedo, lightColor, 0.45f)
                val xTilt = ((sunX / width.toFloat()) - 0.5f) * (width * 0.35f)
                for (l in 0 until 5) {
                    val depth = l / 4f
                    val topC = ColorUtils.blendARGB(ColorUtils.blendARGB(circ.horizonColor, litSand, 0.55f), deepVelvet, depth * 0.78f)
                    val botC = ColorUtils.blendARGB(topC, deepVelvet, 0.42f + depth * 0.35f)
                    fillPaint.shader = LinearGradient(
                        width * 0.5f + xTilt, height * (0.55f + l * 0.06f),
                        width * 0.5f - xTilt, height.toFloat(),
                        intArrayOf(topC, botC), null, Shader.TileMode.CLAMP
                    )
                    fillPaint.alpha = masterAlpha
                    canvas.save()
                    canvas.translate(0f, midShiftY * (0.35f + depth * 0.85f))
                    canvas.drawPath(layerPaths[l], fillPaint)
                    canvas.restore()
                    fillPaint.shader = null
                }
                val wispAlpha = (masterAlpha * (0.08f + steadyWind / 200f + fogFactor * 0.08f)).toInt().coerceIn(0, 120)
                for (s in 0 until 3) {
                    val prog = (((timeSec * (0.0045 + steadyWind * 0.00025) * windDir) + s * 0.33) % 1.0 + 1.0) % 1.0
                    drawSoftMist(canvas, (prog.toFloat() * 1.3f - 0.15f) * width, horizonY + height * (0.04f + s * 0.08f), width * 0.30f, height * 0.018f, (litSand and 0x00FFFFFF) or (wispAlpha shl 24))
                }
                if (isDay) drawThermalEagles(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.SNOWY_ALPENGLOW_LAKE -> {
                val rockFarTop = ColorUtils.blendARGB(circ.horizonColor, midTone, 0.40f)
                val rockMidTop = ColorUtils.blendARGB(0xFF334155.toInt(), lightColor, 0.52f * lightStrength)
                drawGradientLayer(canvas, layerPaths[0], height * 0.44f, horizonY, rockFarTop, midTone, 0f, midShiftY * 0.55f, (masterAlpha * 0.82f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.44f, horizonY, rockMidTop, deepVelvet, 0f, midShiftY, masterAlpha)
                val snowCol = ColorUtils.blendARGB(0xFFE2E8F0.toInt(), lightColor, if (alpenglow > 0.1f) 0.72f else 0.36f)
                drawGradientLayer(canvas, accentPath, height * 0.44f, horizonY - height * 0.04f, snowCol, ColorUtils.blendARGB(snowCol, deepVelvet, 0.70f), 0f, midShiftY, (masterAlpha * 0.88f).toInt())

                // Mirror Lake below horizonY
                val lakeTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.28f else 0.72f)
                val lakeBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.65f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(lakeTop, lakeBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha
                canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint)
                fillPaint.shader = null

                fillPaint.color = ColorUtils.blendARGB(rockMidTop, lakeTop, 0.48f)
                fillPaint.alpha = (masterAlpha * 0.26f).toInt()
                canvas.save(); canvas.translate(0f, midShiftY); canvas.drawPath(reflectPath, fillPaint); canvas.restore()
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
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.08f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.25f), deepVelvet, 0f, midShiftY * 0.5f, (masterAlpha * 0.74f).toInt())
                drawGradientLayer(canvas, layerPaths[1], horizonY - height * 0.05f, horizonY + 4f, ColorUtils.blendARGB(midTone, deepVelvet, 0.35f), deepVelvet, 0f, midShiftY * 0.6f, (masterAlpha * 0.82f).toInt())
                drawOceanSurfaceAndLife(canvas, width, height, density, horizonY, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha, showBoat = true)
            }

            WorldBiome.SUNLIT_WOODLAND -> {
                // Seamless atmospheric forest haze (dissolves any hard horizon line)
                val hazeTop = if (isDay) ColorUtils.blendARGB(circ.horizonColor, 0xFF1D3F2A.toInt(), 0.40f) else 0xFF0A1622.toInt()
                fillPaint.shader = LinearGradient(0f, height * 0.42f, 0f, height.toFloat(), intArrayOf(Color.TRANSPARENT, hazeTop, deepVelvet), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha
                canvas.drawRect(0f, height * 0.42f, width.toFloat(), height.toFloat(), fillPaint)
                fillPaint.shader = null

                val swayFar = sin(timeSec * 0.36).toFloat() * (density * (0.6f + steadyWind * 0.02f)) * windDir
                val swayMid = sin(timeSec * 0.46 + 1.1).toFloat() * (density * (1.1f + steadyWind * 0.035f)) * windDir
                drawGradientLayer(canvas, layerPaths[0], 0f, height.toFloat(), ColorUtils.blendARGB(midTone, circ.horizonColor, 0.50f), deepVelvet, swayFar, midShiftY * 0.35f, (masterAlpha * 0.42f).toInt())
                drawSoftMist(canvas, width * 0.5f, height * 0.70f, width * 0.95f, height * 0.14f, mistRgb or ((masterAlpha * (0.22f + fogFactor * 0.20f)).toInt().coerceIn(0, 170) shl 24))
                drawGradientLayer(canvas, layerPaths[2], 0f, height.toFloat(), ColorUtils.blendARGB(midTone, deepVelvet, 0.48f), deepVelvet, swayMid, midShiftY * 0.65f, (masterAlpha * 0.74f).toInt())

                // Soft multi-layered feathered diagonal light shafts (zero UFO cone!)
                val orbVis = if (isDay) sky.sunAltDeg > -1f else sky.moonAltDeg > 1f
                if (orbVis) {
                    val oy = (if (isDay) sunY else moonY).coerceIn(height * 0.05f, height * 0.28f)
                    val cloudScale = (1f - weather.cloudCoverPct / 135f).coerceIn(0.20f, 1f)
                    val moonScale = if (isDay) 1f else (0.30f + 0.70f * sky.moonIllumination.coerceIn(0f, 1f))
                    val rayCol = if (isDay) sky.sunCoronaColor else sky.moonColor
                    val rA = (masterAlpha * (if (isDay) 0.11f else 0.07f) * cloudScale * moonScale).toInt().coerceIn(0, 120)
                    val slant = (width * 0.5f - orbX) * 0.36f + windDir * width * 0.04f
                    val starts = floatArrayOf(0.22f, 0.38f, 0.52f, 0.66f, 0.80f)
                    for (r in starts.indices) {
                        val sx = width * starts[r]
                        for (pass in 0..1) {
                            val halfW = width * (if (pass == 0) 0.075f else 0.036f)
                            val pA = if (pass == 0) (rA * 0.55f).toInt() else rA
                            fillPaint.shader = LinearGradient(
                                sx, oy, sx + slant, height * 0.88f,
                                intArrayOf(Color.TRANSPARENT, (rayCol and 0x00FFFFFF) or (pA shl 24), Color.TRANSPARENT),
                                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
                            )
                            dynPath.reset()
                            dynPath.moveTo(sx - halfW * 0.7f, oy)
                            dynPath.lineTo(sx + halfW * 0.7f, oy)
                            dynPath.lineTo(sx + slant + halfW * 1.4f, height * 0.88f)
                            dynPath.lineTo(sx + slant - halfW * 1.4f, height * 0.88f)
                            dynPath.close()
                            canvas.drawPath(dynPath, fillPaint)
                        }
                    }
                    fillPaint.shader = null
                }

                // Foreground framing trunks, pine boughs & mossy clearing bank
                val swayFore = sin(timeSec * 0.54 + 2.0).toFloat() * (density * (1.4f + steadyWind * 0.045f)) * windDir
                drawGradientLayer(canvas, layerPaths[4], 0f, height.toFloat(), ColorUtils.blendARGB(deepVelvet, 0xFF0A1D12.toInt(), 0.45f), 0xFF030508.toInt(), swayFore * 0.35f, foreShiftY * 0.4f, masterAlpha)

                // Lush swaying ferns, grass & day->night bioluminescent mushrooms above dock
                val bankY = height * 0.81f + foreShiftY * 0.4f
                val grassCol = ColorUtils.blendARGB(0xFF143822.toInt(), lightColor, 0.25f * lightStrength)
                drawSwayingGrass(canvas, width, height, density, dayOfYear, bankY, grassCol, windDir, steadyWind, timeSec, masterAlpha)
                drawWoodlandMushrooms(canvas, width, height, density, dayOfYear, bankY, sky, lightColor, timeSec, masterAlpha)

                if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
                    for (bf in 0 until 2) {
                        val bx = width * (0.34f + bf * 0.30f) + sin(timeSec * (0.25 + bf * 0.06) + bf * 1.9).toFloat() * (width * 0.10f)
                        val by = height * (0.58f + bf * 0.08f) + sin(timeSec * 0.56 + bf * 2.1).toFloat() * (height * 0.025f)
                        val col = if (bf == 0) ColorUtils.blendARGB(0xFFF59E0B.toInt(), sky.sunColor, 0.30f) else 0xFF7DD3FC.toInt()
                        drawButterfly(canvas, bx, by, density * (9.5f + bf * 1.5f), col, (masterAlpha * 0.88f * bfVis).toInt(), timeSec, bf * 1.7)
                    }
                }
                if (isDuskOrNight) drawFireflies(canvas, width, height, density, dayOfYear, height * 0.54f, sky, weather, timeSec, masterAlpha, forceNight = true)
            }

            WorldBiome.COASTAL_REEDS -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.06f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, midShiftY * 0.5f, (masterAlpha * 0.68f).toInt())
                val seaTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.30f else 0.72f)
                val seaBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.70f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(seaTop, seaBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha
                canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint)
                fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Dark foreground shore bank + gracefully arching multi-stroke feathery reed silhouettes!
                drawGradientLayer(canvas, layerPaths[4], height * 0.78f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY * 0.5f, masterAlpha)
                drawFeatheryVectorReeds(canvas, width, height, density, dayOfYear, deepVelvet, lightColor, isDay, windDir, steadyWind, timeSec, masterAlpha)

                if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
                    for (bf in 0 until 2) {
                        val bx = width * (0.28f + bf * 0.44f) + sin(timeSec * (0.26 + bf * 0.05) + bf * 2.1).toFloat() * (width * 0.09f)
                        val by = height * (0.62f + bf * 0.07f) + sin(timeSec * 0.58 + bf * 1.8).toFloat() * (height * 0.024f)
                        val col = if (bf == 0) ColorUtils.blendARGB(0xFFF59E0B.toInt(), sky.sunColor, 0.30f) else 0xFFBAE6FD.toInt()
                        drawButterfly(canvas, bx, by, density * (9.5f + bf * 1.5f), col, (masterAlpha * 0.88f * bfVis).toInt(), timeSec, bf * 1.5)
                    }
                }
            }

            WorldBiome.CRYSTAL_COVE -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.56f, horizonY + 4f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.45f), deepVelvet, 0f, midShiftY * 0.45f, (masterAlpha * 0.72f).toInt())
                val topW = if (isDay) ColorUtils.blendARGB(circ.horizonColor, 0xFF0E7490.toInt(), 0.58f) else 0xFF0B192C.toInt()
                val botW = if (isDay) ColorUtils.blendARGB(0xFF065F46.toInt(), 0xFF0E7490.toInt(), 0.52f) else 0xFF020612.toInt()
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(topW, botW), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha
                canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint)
                fillPaint.shader = null

                // Soft mirror reflections of faceted boulders
                fillPaint.color = ColorUtils.blendARGB(deepVelvet, topW, 0.45f)
                fillPaint.alpha = (masterAlpha * 0.28f).toInt()
                canvas.save(); canvas.translate(0f, midShiftY); canvas.drawPath(reflectPath, fillPaint); canvas.restore()
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Graceful curved underwater caustic ribbons
                val cCol = if (isDay) ColorUtils.blendARGB(0xFF5EEAD4.toInt(), lightColor, 0.35f) else sky.moonColor
                val cA = (masterAlpha * (if (isDay) 0.14f else 0.06f) * lightStrength).toInt().coerceIn(0, 110)
                if (cA > 2) {
                    strokePaint.color = (cCol and 0x00FFFFFF) or (cA shl 24)
                    strokePaint.strokeWidth = density * 2.0f
                    val spd = 0.015 + (steadyWind / 450.0).coerceIn(0.0, 0.025)
                    for (c in 0 until 6) {
                        val prog = (((timeSec * spd * windDir) + c * 0.17) % 1.0 + 1.0) % 1.0
                        val cx = (prog.toFloat() * 1.2f - 0.1f) * width
                        val cy = height * (0.74f + (c % 3) * 0.065f)
                        val wave = sin(timeSec * 0.9 + c * 1.4).toFloat() * (height * 0.014f)
                        dynPath.reset()
                        dynPath.moveTo(cx - width * 0.16f, cy - wave)
                        dynPath.quadTo(cx, cy + wave, cx + width * 0.16f, cy - wave * 0.6f)
                        canvas.drawPath(dynPath, strokePaint)
                    }
                }

                // 5 sleek fish swimming through the cove shallows
                drawCoveFish(canvas, width, height, density, botW, isDay, timeSec, masterAlpha)

                // Crisp faceted boulders cut flat at horizonY
                val rockTop = ColorUtils.blendARGB(0xFF94A3B8.toInt(), lightColor, 0.45f * lightStrength)
                drawGradientLayer(canvas, layerPaths[2], horizonY - height * 0.08f, horizonY + 6f, rockTop, deepVelvet, 0f, midShiftY, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.80f, height.toFloat(), ColorUtils.blendARGB(rockTop, deepVelvet, 0.45f), 0xFF030508.toInt(), 0f, foreShiftY * 0.5f, masterAlpha)
                if (isDay) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.KYOTO_TORII -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, horizonY, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, midShiftY * 0.45f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.54f, horizonY, ColorUtils.blendARGB(midTone, deepVelvet, 0.30f), deepVelvet, 0f, midShiftY * 0.65f, masterAlpha)
                val lakeTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.25f else 0.70f)
                val lakeBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.72f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(lakeTop, lakeBot), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha
                canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint)
                fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.90f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                // Architectural Torii Gate standing in the water
                val toriiCol = if (isDay) ColorUtils.blendARGB(0xFF991B1B.toInt(), deepVelvet, 0.52f) else ColorUtils.blendARGB(0xFF450A0A.toInt(), deepVelvet, 0.68f)
                drawGradientLayer(canvas, layerPaths[3], horizonY - height * 0.04f, horizonY + height * 0.12f, toriiCol, deepVelvet, 0f, midShiftY * 0.85f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.74f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)

                // Warm stone lantern glow at dusk/night
                val lanternStr = ((3f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                if (lanternStr > 0.05f && focalW > 0f) {
                    val fy = focalY + foreShiftY
                    val haloA = (masterAlpha * 0.55f * lanternStr).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, focalX, fy, focalW * 2.6f, focalH * 3.0f, 0x00FBBF24 or (haloA shl 24))
                    fillPaint.color = 0xFFFEF08A.toInt()
                    fillPaint.alpha = (masterAlpha * 0.92f * lanternStr).toInt()
                    canvas.drawRect(focalX - focalW * 0.4f, fy - focalH * 0.4f, focalX + focalW * 0.4f, fy + focalH * 0.4f, fillPaint)
                }

                // Slow wind-drifted sakura petals (spring/day) or amber leaves
                val petalCol = if (dayOfYear in 60..170) 0xFFFBCFE8.toInt() else 0xFFF59E0B.toInt()
                fillPaint.color = petalCol
                for (p in 0 until 7) {
                    val px = ((((seedRand(dayOfYear + p, 311) + timeSec * (0.012 + steadyWind * 0.0005) * windDir) % 1.15) + 1.15) % 1.15 - 0.07).toFloat() * width
                    val py = ((((seedRand(dayOfYear + p, 313) + timeSec * 0.018) % 0.75) + 0.15)).toFloat() * height
                    fillPaint.alpha = (masterAlpha * (if (isDay) 0.58f else 0.25f)).toInt()
                    val pr = density * (2.6f + (p % 2) * 1.0f)
                    rect.set(px - pr, py - pr * 0.55f, px + pr, py + pr * 0.55f)
                    canvas.drawOval(rect, fillPaint)
                }
            }

            WorldBiome.LIGHTHOUSE_CLIFF -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.06f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, midShiftY * 0.5f, (masterAlpha * 0.68f).toInt())
                drawOceanSurfaceAndLife(canvas, width, height, density, horizonY, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha, showBoat = false)

                // Rotating volumetric Lighthouse beam at dusk & night!
                val beamStr = ((2f - sky.sunAltDeg) / 6f).coerceIn(0f, 1f)
                if (beamStr > 0.05f && focalW > 0f) {
                    val lx = focalX
                    val ly = focalY + midShiftY * 0.75f
                    val sweep = sin(timeSec * 0.55).toFloat()
                    val beamLen = width * (0.65f + 0.20f * abs(sweep))
                    val dir = if (sweep >= 0f) 1f else -1f
                    val beamAlpha = (masterAlpha * 0.24f * beamStr * (0.45f + 0.55f * abs(sweep))).toInt().coerceIn(0, 160)
                    fillPaint.shader = LinearGradient(
                        lx, ly, lx + beamLen * dir, ly - height * 0.08f,
                        intArrayOf((0x00FEF08A) or (beamAlpha shl 24), Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP
                    )
                    dynPath.reset()
                    dynPath.moveTo(lx, ly - focalH * 0.35f)
                    dynPath.lineTo(lx + beamLen * dir, ly - height * 0.14f)
                    dynPath.lineTo(lx + beamLen * dir, ly + height * 0.06f)
                    dynPath.lineTo(lx, ly + focalH * 0.35f)
                    dynPath.close()
                    canvas.drawPath(dynPath, fillPaint)
                    fillPaint.shader = null
                    drawSoftMist(canvas, lx, ly, focalW * 2.4f, focalH * 2.4f, 0x00FEF08A or ((masterAlpha * 0.60f * beamStr).toInt() shl 24))
                }

                drawGradientLayer(canvas, layerPaths[3], horizonY - height * 0.24f, height.toFloat(), ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, midShiftY * 0.75f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.78f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.AURORA_FJORD -> {
                // Shimmering Aurora Borealis curtains in upper sky at night!
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
                drawGradientLayer(canvas, layerPaths[0], height * 0.46f, horizonY, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.38f), midTone, 0f, midShiftY * 0.45f, masterAlpha)
                val fjordWaterTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.32f else 0.72f)
                fillPaint.shader = LinearGradient(0f, horizonY, 0f, height.toFloat(), intArrayOf(fjordWaterTop, 0xFF03060E.toInt()), null, Shader.TileMode.CLAMP)
                fillPaint.alpha = masterAlpha
                canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint)
                fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.88f, sky, weather, orbX, isDay, steadyWind, timeSec, masterAlpha)

                drawGradientLayer(canvas, layerPaths[2], height * 0.42f, horizonY + 8f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, midShiftY * 0.70f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.74f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)

                // Cozy cabin glowing window + thin chimney smoke curling downwind
                val winStr = ((4f - sky.sunAltDeg) / 8f).coerceIn(0f, 1f)
                if (focalW > 0f) {
                    val fy = focalY + foreShiftY
                    if (winStr > 0.05f) {
                        drawSoftMist(canvas, focalX, fy, focalW * 2.8f, focalH * 2.8f, 0x00FBBF24 or ((masterAlpha * 0.50f * winStr).toInt() shl 24))
                        fillPaint.color = 0xFFFEF08A.toInt()
                        fillPaint.alpha = (masterAlpha * 0.94f * winStr).toInt()
                        canvas.drawRect(focalX - focalW * 0.5f, fy - focalH * 0.5f, focalX + focalW * 0.5f, fy + focalH * 0.5f, fillPaint)
                    }
                    // Thin chimney smoke puffs rising gently downwind
                    val chimX = focalX + focalW * 1.25f
                    val chimY = fy - focalH * 2.4f
                    val smokeRgb = (if (isDay) 0x00E2E8F0 else 0x0094A3B8)
                    for (sm in 0 until 5) {
                        val sp = (((timeSec * 0.14) + sm * 0.20) % 1.0).toFloat()
                        val sx = chimX + sp * (width * 0.045f * windDir) + sin(timeSec * 0.9 + sm).toFloat() * (density * 3f)
                        val sy = chimY - sp * (height * 0.065f)
                        val sA = (masterAlpha * 0.24f * sin(sp * PI).toFloat()).toInt().coerceIn(0, 120)
                        drawSoftMist(canvas, sx, sy, density * (4f + sp * 9f), density * (3f + sp * 6f), smokeRgb or (sA shl 24))
                    }
                }
            }

            WorldBiome.VALLEY_STAG -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, height * 0.78f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, midShiftY * 0.45f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[1], height * 0.54f, height * 0.84f, ColorUtils.blendARGB(midTone, deepVelvet, 0.36f), deepVelvet, 0f, midShiftY * 0.72f, masterAlpha)

                // Glowing mountain-saddle pocket lake catching live sky & sun/moon glow!
                val pocketCol = if (isDay) ColorUtils.blendARGB(circ.horizonColor, lightColor, 0.55f) else ColorUtils.blendARGB(circ.horizonColor, sky.moonColor, 0.40f)
                drawSoftMist(canvas, width * 0.54f, height * 0.74f + midShiftY * 0.8f, width * 0.19f, height * 0.028f, (pocketCol and 0x00FFFFFF) or ((masterAlpha * 0.82f).toInt() shl 24))
                drawSoftMist(canvas, width * 0.54f, height * 0.74f + midShiftY * 0.8f, width * 0.11f, height * 0.014f, (lightColor and 0x00FFFFFF) or ((masterAlpha * 0.55f).toInt() shl 24))

                val sway5 = sin(timeSec * 0.45).toFloat() * (density * (0.9f + steadyWind * 0.03f)) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), sway5 * 0.4f, foreShiftY, masterAlpha)
                drawFireflies(canvas, width, height, density, dayOfYear, horizonY, sky, weather, timeSec, masterAlpha)
                if (isDay || alpenglow > 0.1f) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
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
            dynPath.reset()
            dynPath.moveTo(boatX - bw * 0.05f, boatY)
            dynPath.lineTo(boatX - bw * 0.05f, boatY - bh)
            dynPath.lineTo(boatX + bw * 0.42f * windDir, boatY - density * 1.5f)
            dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }

        if (sky.sunAltDeg > -2f) {
            drawBirds(canvas, w, h, density, windDir, timeSec, masterAlpha)
        }
    }

    private fun drawFeatheryVectorReeds(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        stemCol: Int, lightColor: Int, isDay: Boolean, windDir: Float, steadyWind: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val swayFreq = 0.68 + (steadyWind / 48.0).coerceIn(0.0, 0.42)
        val windLean = (steadyWind / 55f) * w * 0.055f * windDir
        val plumeCol = if (isDay) ColorUtils.blendARGB(stemCol, lightColor, 0.48f) else ColorUtils.blendARGB(stemCol, 0xFF64748B.toInt(), 0.25f)

        for (r in 0 until 18) {
            val sideLeft = (r % 2 == 0)
            val rxFrac = if (sideLeft) (0.02f + 0.34f * seedRand(dayOfYear + r, 211)) else (0.64f + 0.34f * seedRand(dayOfYear + r, 211))
            val bx = rxFrac * w
            val by = h * (0.98f + 0.04f * seedRand(dayOfYear + r, 213))
            val reedH = h * (0.26f + 0.18f * seedRand(dayOfYear + r, 219))
            val archDir = if (sideLeft) 1f else -1f
            val naturalArch = w * (0.04f + 0.04f * seedRand(dayOfYear + r, 221)) * archDir
            val sway = sin(timeSec * swayFreq + r * 0.72).toFloat() * (density * (6.5f + steadyWind * 0.20f)) + windLean * 0.8f
            val tipX = bx + naturalArch + sway
            val tipY = by - reedH

            strokePaint.color = stemCol
            strokePaint.alpha = (masterAlpha * 0.90f).toInt()
            strokePaint.strokeWidth = density * (1.3f + 0.5f * seedRand(dayOfYear + r, 227))
            dynPath.reset()
            dynPath.moveTo(bx, by)
            dynPath.quadTo(bx + (naturalArch + sway) * 0.35f, by - reedH * 0.58f, tipX, tipY)
            canvas.drawPath(dynPath, strokePaint)

            // Fine multi-stroke feathery sea-oat wisps (zero ovals!)
            strokePaint.color = plumeCol
            strokePaint.alpha = (masterAlpha * (if (isDay) 0.78f else 0.45f)).toInt()
            strokePaint.strokeWidth = density * 0.85f
            for (f in 0 until 10) {
                val t = 0.76f + (f / 10f) * 0.24f
                val omt = 1f - t
                val cx = omt * omt * bx + 2f * omt * t * (bx + (naturalArch + sway) * 0.35f) + t * t * tipX
                val cy = omt * omt * by + 2f * omt * t * (by - reedH * 0.58f) + t * t * tipY
                val wispLen = density * (10f * (1f - (f / 11f) * 0.65f))
                val ang = (if (f % 2 == 0) -0.45f else 0.45f) + (sway / (w * 0.15f))
                canvas.drawLine(cx, cy, cx + sin(ang) * wispLen + windDir * density * 2f, cy - cos(ang) * wispLen, strokePaint)
            }
        }
    }

    private fun drawSwayingGrass(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        baseY: Float, color: Int, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val swayFreq = 0.72 + (steadyWind / 48.0).coerceIn(0.0, 0.42)
        val windLean = (steadyWind / 60f) * density * 9f * windDir
        strokePaint.color = color
        strokePaint.alpha = (masterAlpha * 0.90f).toInt()
        strokePaint.strokeWidth = density * 1.5f
        for (b in 0 until 32) {
            val bx = w * (0.03f + 0.94f * seedRand(dayOfYear + b, 131))
            val by = baseY + h * (0.005f + 0.06f * seedRand(dayOfYear + b, 137))
            val bh = h * (0.042f + 0.038f * seedRand(dayOfYear + b, 149))
            val sway = sin(timeSec * swayFreq + b * 0.65).toFloat() * (density * (3.2f + steadyWind * 0.12f)) + windLean
            dynPath.reset()
            dynPath.moveTo(bx, by)
            dynPath.quadTo(bx + sway * 0.35f, by - bh * 0.55f, bx + sway, by - bh)
            canvas.drawPath(dynPath, strokePaint)
        }
    }

    private fun drawWoodlandMushrooms(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        bankY: Float, sky: SkySnapshot, lightColor: Int, timeSec: Double, masterAlpha: Int
    ) {
        val nightGlow = ((-sky.sunAltDeg - 0.5f) / 6f).coerceIn(0f, 1f)
        for (m in 0 until 5) {
            val mx = w * (0.18f + 0.64f * seedRand(dayOfYear + m, 157))
            val my = bankY + h * (0.008f + 0.032f * seedRand(dayOfYear + m, 163))
            val capR = density * (4.5f + 2.0f * seedRand(dayOfYear + m, 167))
            if (nightGlow > 0.05f) {
                val breath = 0.78f + 0.22f * sin(timeSec * 1.15 + m * 1.4).toFloat()
                drawSoftMist(canvas, mx, my - capR * 0.9f, capR * 3.8f, capR * 2.8f, 0x002DD4BF or ((masterAlpha * 0.54f * nightGlow * breath).toInt().coerceIn(0, 255) shl 24))
            }
            strokePaint.color = if (nightGlow > 0.3f) 0xFF99F6E4.toInt() else 0xFFD6D3D1.toInt()
            strokePaint.alpha = (masterAlpha * 0.85f).toInt()
            strokePaint.strokeWidth = density * 1.5f
            canvas.drawLine(mx, my, mx + density * 0.5f, my - capR * 1.1f, strokePaint)
            fillPaint.color = if (nightGlow > 0.25f) ColorUtils.blendARGB(0xFFB45309.toInt(), 0xFF2DD4BF.toInt(), nightGlow) else ColorUtils.blendARGB(0xFF92400E.toInt(), lightColor, 0.30f)
            fillPaint.alpha = (masterAlpha * 0.92f).toInt()
            rect.set(mx - capR, my - capR * 1.75f, mx + capR, my - capR * 0.55f)
            canvas.drawArc(rect, 180f, 180f, true, fillPaint)
        }
    }

    private fun drawCoveFish(
        canvas: Canvas, w: Int, h: Int, density: Float, botWater: Int,
        isDay: Boolean, timeSec: Double, masterAlpha: Int
    ) {
        fillPaint.color = if (isDay) ColorUtils.blendARGB(0xFF062C33.toInt(), botWater, 0.35f) else 0xFF020617.toInt()
        fillPaint.alpha = (masterAlpha * (if (isDay) 0.56f else 0.30f)).toInt()
        for (f in 0 until 5) {
            val dir = if (f % 2 == 0) 1f else -1f
            val speed = 0.015 + (f % 3) * 0.004
            val fx = (((((0.15 + f * 0.19) + timeSec * speed * dir) % 1.20) + 1.20) % 1.20 - 0.10).toFloat() * w
            val fy = h * (0.76f + (f % 4) * 0.042f) + sin(timeSec * 0.8 + f * 1.7).toFloat() * (h * 0.012f)
            val len = density * (10f + (f % 3) * 2.5f)
            val tailWag = sin(timeSec * 4.5 + f * 1.3).toFloat() * (len * 0.24f)
            dynPath.reset()
            dynPath.moveTo(fx + len * 0.55f * dir, fy)
            dynPath.quadTo(fx, fy - len * 0.26f, fx - len * 0.45f * dir, fy + tailWag * 0.3f)
            dynPath.lineTo(fx - len * 0.76f * dir, fy - len * 0.24f + tailWag)
            dynPath.lineTo(fx - len * 0.76f * dir, fy + len * 0.24f + tailWag)
            dynPath.lineTo(fx - len * 0.45f * dir, fy + tailWag * 0.3f)
            dynPath.quadTo(fx, fy + len * 0.26f, fx + len * 0.55f * dir, fy)
            dynPath.close()
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

    private fun drawButterfly(
        canvas: Canvas, bx: Float, by: Float, wingSpan: Float,
        color: Int, alpha: Int, timeSec: Double, phase: Double
    ) {
        val flutter = abs(sin(timeSec * 4.4 + phase)).toFloat().coerceAtLeast(0.22f)
        val ww = wingSpan * flutter
        fillPaint.color = color
        fillPaint.alpha = alpha.coerceIn(0, 255)
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

    private fun drawThermalEagles(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, timeSec: Double, masterAlpha: Int) {
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

    private fun drawBirds(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, timeSec: Double, masterAlpha: Int) {
        strokePaint.strokeWidth = density * 1.05f
        strokePaint.color = 0xFF0F172A.toInt()
        for (s in 0 until 4) {
            val sx = ((((0.18f + s * 0.06f + (timeSec * 0.009 * windDir).toFloat()) % 1.25f) + 1.25f) % 1.25f - 0.12f) * w
            val sy = h * (0.26f + (s % 2) * 0.025f + (s / 2) * 0.020f) + sin(timeSec * 1.4 + s * 1.1).toFloat() * (h * 0.009f)
            val span = w * (0.013f - s * 0.0012f)
            val flap = sin(timeSec * 2.0 + s * 1.4).toFloat() * (span * 0.36f)
            strokePaint.alpha = (masterAlpha * 0.44f).toInt().coerceIn(0, 255)
            canvas.drawLine(sx - span, sy - flap, sx, sy + span * 0.2f, strokePaint)
            canvas.drawLine(sx, sy + span * 0.2f, sx + span, sy - flap, strokePaint)
        }
    }
}
