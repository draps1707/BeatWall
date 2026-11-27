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
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt

enum class WorldBiome(val title: String) {
    FIREWATCH_LOOKOUT("Firewatch Lookout"),
    SAND_DUNES("Sand Dunes"),
    SNOWY_ALPENGLOW_LAKE("Snowy Peaks & Lake"),
    EMERALD_SAVANNA("Emerald Savanna"),
    COASTAL_OCEAN("Coastal Ocean"),
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
    VOLCANIC_CALDERA("Volcanic Caldera"),
    HIGHLAND_LOCH("Highland Loch & Ruin"),
    LAVENDER_PROVENCE("Lavender Provence"),
    SANTORINI_CLIFFSIDE("Santorini Cliffside")
}

/**
 * Pure-Vector Firewatch Minimalist 20-World Landscape Engine (v10.0.0).
 * - Zero WebP bitmaps, zero RuntimeShaders, zero Random dice rolls, zero per-frame steady allocations.
 * - Pre-builds multi-octave fractal ridges, solid-wound 16-branch feathery conifers, and architectural
 *   silhouettes ONCE per dayOfYear/size.
 * - Exact waterline anchoring (full horizonShiftY) so zero headlands, cliffs, or walls ever float.
 */
class LandscapeEngine {

    // Cache enum constants once: Kotlin enum values() creates a new array per call.
    private val biomeValues = WorldBiome.values()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    // Bounded cache for the per-layer vertical gradients. drawForeground is called
    // continuously; the palette and layer bounds usually remain unchanged between
    // circadian updates, so reusing shaders avoids repeated LinearGradient objects.
    private val gradientShaderCache = arrayOfNulls<LinearGradient>(96)
    private val gradientTopYCache = FloatArray(96) { Float.NaN }
    private val gradientBottomYCache = FloatArray(96) { Float.NaN }
    private val gradientTopColorCache = IntArray(96)
    private val gradientBottomColorCache = IntArray(96)
    private var gradientCacheCursor = 0

    // Bounded mist shader cache keyed by its ARGB color (the radial shape is fixed).
    // The shader local matrix is updated per draw so one cached shader can serve
    // multiple mist positions and ellipse sizes without allocating new gradients.
    private val mistShaderCache = arrayOfNulls<RadialGradient>(64)
    private val mistShaderColorCache = IntArray(64)
    private var mistShaderCacheCursor = 0

    // Cache the two multi-stop animated light effects by their exact geometry and colors.
    private val effectLinearCache = arrayOfNulls<LinearGradient>(12)
    private val effectX0Cache = FloatArray(12) { Float.NaN }
    private val effectY0Cache = FloatArray(12) { Float.NaN }
    private val effectX1Cache = FloatArray(12) { Float.NaN }
    private val effectY1Cache = FloatArray(12) { Float.NaN }
    private val effectColor0Cache = IntArray(12)
    private val effectColor1Cache = IntArray(12)
    private val effectColor2Cache = IntArray(12)
    private val effectPosition1Cache = FloatArray(12) { Float.NaN }
    private val effectPosition2Cache = FloatArray(12) { Float.NaN }
    private var effectLinearCacheCursor = 0

    private val layerPaths = Array(5) { Path().apply { fillType = Path.FillType.WINDING } }
    private val accentPath = Path().apply { fillType = Path.FillType.WINDING }
    private val reflectPath = Path().apply { fillType = Path.FillType.WINDING }
    private val dynPath = Path().apply { fillType = Path.FillType.WINDING }
    // Shared empty sample result for ridge builds whose height samples are not consumed.
    private val emptyRidgeSamples = FloatArray(0)
    private val midRidgeCache = FloatArray(121)

    // Reusable immutable layout tables. Keeping these outside drawForeground avoids
    // allocating temporary arrays on every wallpaper frame.
    private val woodlandLightShaftX = floatArrayOf(0.36f, 0.49f, 0.62f)
    private val cypressBaseX = floatArrayOf(0.51f, 0.545f, 0.71f, 0.75f)
    private val cypressScale = floatArrayOf(0.85f, 1.02f, 0.92f, 0.76f)
    private val balloonBaseX = floatArrayOf(0.20f, 0.43f, 0.64f, 0.78f, 0.50f)
    private val balloonBaseY = floatArrayOf(0.46f, 0.25f, 0.43f, 0.37f, 0.33f)
    private val balloonRadii = floatArrayOf(0.032f, 0.048f, 0.022f, 0.038f, 0.028f)
    // Reused geometry-generation constants: avoid short-lived arrays when a scene rebuilds.
    private val forestClusterZones = floatArrayOf(0.10f, 0.34f, 0.63f, 0.89f)
    private val acaciaBranchTargets = floatArrayOf(-0.34f, 0.04f, 0.36f)
    private val acaciaBranchHeights = floatArrayOf(0.92f, 1.0f, 0.86f)
    private val banyanRootPositions = floatArrayOf(0.06f, 0.11f, 0.20f, 0.26f, 0.33f, 0.38f)

    private val butterflyPalette = intArrayOf(
        0xFFF59E0B.toInt(), 0xFFFDE047.toInt(), 0xFF7DD3FC.toInt(),
        0xFFF8FAFC.toInt(), 0xFFEA7B4B.toInt()
    )
    private val prayerFlagColors = intArrayOf(
        0xFF3882D2.toInt(), 0xFFECF2F8.toInt(), 0xFFDA4444.toInt(),
        0xFF2E9E5C.toInt(), 0xFFF2C438.toInt()
    )

    private var focalX = 0f
    private var focalY = 0f
    private var focalW = 0f
    private var focalH = 0f
    private var auxX = 0f
    private var auxY = 0f

    private var builtDay = -1
    private var builtW = 0
    private var builtH = 0

    private var cachedDeckSeed = Int.MIN_VALUE
    private val shuffledDeck = IntArray(biomeValues.size) { it }
    private val prevCycleDeck = IntArray(biomeValues.size) { it }

    private fun fillDeterministicDeck(target: IntArray, seed: Int) {
        val n = target.size
        for (i in 0 until n) target[i] = i
        var s = (seed * 1103515245 + 12345)
        for (i in n - 1 downTo 1) {
            s = (s * 1664525 + 1013904223)
            val j = ((s ushr 1) % (i + 1))
            val tmp = target[i]
            target[i] = target[j]
            target[j] = tmp
        }
    }

    /**
     * Deterministic shuffled 20-day deck for Live Daily Rotation:
     * guarantees every world appears once per 20-day cycle in a fresh order,
     * prevents back-to-back repeats across cycle boundaries, and preserves
     * a unique terrain seed per cycle. Manual dropdown previews (1..20)
     * bypass this and map 1:1 to WorldBiome ordinals.
     */
    fun shuffledLiveBiomeDay(year: Int, dayOfYear: Int): Int {
        val n = biomeValues.size
        val zeroDay = (dayOfYear - 1).coerceAtLeast(0)
        val cycle = zeroDay / n
        val pos = zeroDay % n
        val deckSeed = year * 37 + cycle
        if (cachedDeckSeed != deckSeed) {
            cachedDeckSeed = deckSeed
            fillDeterministicDeck(shuffledDeck, deckSeed)
            val prevSeed = if (cycle > 0) year * 37 + (cycle - 1) else (year - 1) * 37 + (365 / n)
            fillDeterministicDeck(prevCycleDeck, prevSeed)
            if (shuffledDeck[0] == prevCycleDeck[n - 1] && n > 1) {
                val tmp = shuffledDeck[0]
                shuffledDeck[0] = shuffledDeck[1]
                shuffledDeck[1] = tmp
            }
        }
        return shuffledDeck[pos] + 1 + cycle * n
    }

    fun biomeForDay(dayOfYear: Int): WorldBiome {
        val idx = ((dayOfYear - 1).coerceAtLeast(0)) % biomeValues.size
        return biomeValues[idx]
    }

    fun horizonY(height: Int, lockBlend: Float): Float {
        return height * (0.64f + 0.05f * lockBlend)
    }

    fun skyClarityBoost(dayOfYear: Int): Float {
        return when (biomeForDay(dayOfYear)) {
            WorldBiome.FIREWATCH_LOOKOUT, WorldBiome.SAND_DUNES,
            WorldBiome.SNOWY_ALPENGLOW_LAKE, WorldBiome.AURORA_FJORD,
            WorldBiome.VALLEY_STAG, WorldBiome.HIMALAYAN_MONASTERY,
            WorldBiome.ALPINE_VIADUCT, WorldBiome.SANTORINI_CLIFFSIDE -> 1.22f
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

    // Deterministic spatial clustering for forests: candidates concentrate around several
    // soft cluster centers, leaving naturally wider gaps between groups. Evaluated only
    // during cached geometry generation; no per-frame work or allocations.
    private fun forestClusterDensity(xNorm: Float, seed: Int): Float {
        var density = 0f
        // Uneven anchor spacing avoids a visible four-beat rhythm across the screen.
        // Each anchor drifts deterministically, while variable widths create a mix of
        // compact groves and broader woodland bands instead of identical clusters.
        for (i in forestClusterZones.indices) {
            val jitter = (seedRand(seed + i * 37, 211) - 0.5f) * 0.13f
            val center = (forestClusterZones[i] + jitter).coerceIn(0.055f, 0.945f)
            val width = 0.085f + 0.060f * seedRand(seed + i * 53, 223)
            val d = (xNorm - center) / width
            val contribution = 0.88f / (1f + d * d * 1.65f)
            density = maxOf(density, contribution)
        }
        // A restrained edge falloff avoids trees crowding the screen boundaries.
        val edgeDistance = minOf(xNorm, 1f - xNorm).coerceIn(0f, 0.12f)
        val edgeFactor = 0.72f + 0.28f * (edgeDistance / 0.12f)
        return density * edgeFactor
    }

    // Soft cluster boundaries: retain dense cluster cores, but probabilistically taper
    // candidate trees at the fringes instead of producing a hard density cutoff.
    // Deterministic candidate seeds keep the cached landscape stable across rebuilds.
    private fun shouldPlaceForestTree(xNorm: Float, clusterSeed: Int, candidateSeed: Int, threshold: Float): Boolean {
        val density = forestClusterDensity(xNorm, clusterSeed)
        val chance = ((density - threshold) / (0.88f - threshold)).coerceIn(0f, 1f)
        return seedRand(candidateSeed, 307) < chance
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
            // Small deterministic spacing irregularity breaks the repeated, machine-even tiers.
            val tierJitter = (seedRand(seed + t * 23, 29) - 0.5f) * 0.018f
            val tierFrac = (frac + tierJitter).coerceIn(0f, 1f)
            val yAtt = baseY - treeH * (0.10f + tierFrac * 0.84f)
            val env = (1f - frac * 0.90f).pow(0.85f)
            val thick = maxOf(1.4f, treeH * 0.030f * (1f - frac * 0.55f))
            // Gentle crown lean differentiates silhouettes while keeping the base anchored.
            val tierCx = cx + treeH * 0.012f * sin(frac * PI.toFloat() * 0.85f + seedRand(seed, 31) * 6.28318f)

            // Right branch (CW)
            val jitR = 0.72f + 0.56f * seedRand(seed + t * 13, 7)
            val bwR = maxHalfW * env * jitR
            val drR = treeH * (0.045f - frac * 0.025f) * (0.8f + 0.4f * seedRand(seed + t, 11))
            path.moveTo(tierCx, yAtt - thick * 0.5f)
            path.lineTo(tierCx + bwR * 0.55f, yAtt + drR * 0.35f - thick * 0.25f)
            path.lineTo(tierCx + bwR, yAtt + drR)
            path.lineTo(tierCx + bwR * 0.65f, yAtt + drR * 0.95f + thick * 0.45f)
            path.lineTo(tierCx + bwR * 0.28f, yAtt + drR * 0.65f + thick * 0.65f)
            path.lineTo(tierCx, yAtt + thick * 0.95f)
            path.close()

            // Left branch (also CW so WINDING fill never cancels trunk!)
            val jitL = 0.72f + 0.56f * seedRand(seed + t * 13, 3)
            val bwL = maxHalfW * env * jitL
            val drL = treeH * (0.045f - frac * 0.025f) * (0.8f + 0.4f * seedRand(seed + t, 17))
            path.moveTo(tierCx, yAtt + thick * 0.95f)
            path.lineTo(tierCx - bwL * 0.28f, yAtt + drL * 0.65f + thick * 0.65f)
            path.lineTo(tierCx - bwL * 0.65f, yAtt + drL * 0.95f + thick * 0.45f)
            path.lineTo(tierCx - bwL, yAtt + drL)
            path.lineTo(tierCx - bwL * 0.55f, yAtt + drL * 0.35f - thick * 0.25f)
            path.lineTo(tierCx, yAtt - thick * 0.5f)
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
        for (b in acaciaBranchTargets.indices) {
            val bx = tx + lean + spanW * acaciaBranchTargets[b]
            val by = baseY - treeH * acaciaBranchHeights[b]
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
        bowlFrac: Float, seed: Int, steps: Int = 180, clampMaxY: Float = h * 1.15f, captureYs: Boolean = true
    ): FloatArray {
        val ys = if (captureYs) FloatArray(steps + 1) else emptyRidgeSamples
        path.moveTo(-w * 0.02f, clampMaxY)
        // Seeded geological asymmetry: peak shoulders vary subtly between ridges,
        // avoiding the repeated symmetric "generated mountain" silhouette.
        val peakWidth = 0.31f + seedRand(seed, 301) * 0.07f
        val leftShoulderCenter = 0.10f + seedRand(seed, 302) * 0.07f
        val rightShoulderCenter = 0.82f + seedRand(seed, 303) * 0.08f
        val leftShoulderWidth = 0.23f + seedRand(seed, 304) * 0.08f
        val rightShoulderWidth = 0.24f + seedRand(seed, 305) * 0.08f
        val peakExponent = 1.30f + seedRand(seed, 306) * 0.35f
        val shoulderExponent = 1.45f + seedRand(seed, 307) * 0.35f
        val broadUndulationPhase = seedRand(seed, 308) * (2.0 * PI).toFloat()
        val broadUndulationScale = cragScale * (0.10f + seedRand(seed, 309) * 0.08f)
        for (i in 0..steps) {
            val fx = i / steps.toFloat()
            val peak = (1f - abs(fx - peakPos) / peakWidth).coerceIn(0f, 1f).pow(peakExponent) * centerPeakFrac
            val shL = (1f - abs(fx - leftShoulderCenter) / leftShoulderWidth).coerceIn(0f, 1f).pow(shoulderExponent) * (centerPeakFrac * 0.48f)
            val shR = (1f - abs(fx - rightShoulderCenter) / rightShoulderWidth).coerceIn(0f, 1f).pow(shoulderExponent) * (centerPeakFrac * 0.56f)
            val bowl = sin(fx * PI).toFloat() * bowlFrac
            // Broad geological roll + existing fractal crags: large forms remain dominant.
            val broadRoll = sin(fx * (2.0 * PI).toFloat() + broadUndulationPhase) * broadUndulationScale
            val crag = fbm1d(fx, seed, octaves = 5) * cragScale
            val y = minOf(clampMaxY, h * (baseYFrac + bowl + broadRoll - peak - shL - shR - crag))
            if (captureYs) ys[i] = y
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
            // Softer coastal shoulder: a broad contour variation is layered over
            // the anchored cliff profile, while small crags remain subordinate.
            val shoulder = profile.pow(1.78f)
            val broadContour = sin(t * PI).toFloat() * sin(t * PI * 2.0 + seedRand(day, 98) * PI).toFloat() * 0.055f
            val crag = 0.90f + 0.16f * fbm1d(t, day + 5, 3)
            val contourScale = (shoulder + broadContour * profile).coerceAtLeast(0f)
            val y = yBase - contourScale * cliffH * crag
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
                buildFractalRidge(layerPaths[0], wf, hf, 0.585f, 0.085f, pPeak, 0.040f, 0f, dayOfYear + 11, 200, captureYs = false)
                val r2 = buildFractalRidge(layerPaths[1], wf, hf, 0.605f, 0f, 0.5f, 0.034f, 0.028f, dayOfYear + 29, 160)
                for (t in 0 until 78) {
                    val fx = (t + 0.2f + 0.6f * seedRand(dayOfYear + t, 51)) / 78f
                    if (!shouldPlaceForestTree(fx, dayOfYear + 17, dayOfYear + t + 51, 0.34f)) continue
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
                // Sweeping, erosion-like dune contours: broad curves with restrained
                // deterministic variation instead of hard polygon corners.
                val crestX = wf * (0.64f + 0.06f * seedRand(dayOfYear, 3))
                val crestY = hf * (0.475f + 0.018f * seedRand(dayOfYear, 5))
                val leftShoulderY = hf * (0.615f + 0.012f * seedRand(dayOfYear, 7))
                val rightShoulderY = hf * (0.655f + 0.015f * seedRand(dayOfYear, 9))

                layerPaths[0].moveTo(0f, hf * 1.15f)
                layerPaths[0].lineTo(0f, leftShoulderY)
                layerPaths[0].quadTo(wf * 0.30f, hf * 0.565f, crestX, crestY)
                layerPaths[0].quadTo(wf * 0.82f, hf * 0.555f, wf, rightShoulderY)
                layerPaths[0].lineTo(wf, hf * 1.15f); layerPaths[0].close()

                // Thin, softly curved sun-facing contour aligned to the main crest.
                accentPath.moveTo(crestX, crestY)
                accentPath.quadTo(wf * 0.83f, hf * 0.535f, wf, hf * 0.565f)
                accentPath.quadTo(wf * 0.91f, hf * 0.705f, wf * 0.73f, hf * 0.685f)
                accentPath.quadTo(wf * 0.68f, hf * 0.625f, crestX + wf * 0.075f, hf * 0.565f)
                accentPath.close()

                val secondaryX = wf * (0.10f + 0.035f * seedRand(dayOfYear, 11))
                val secondaryY = hf * (0.565f + 0.012f * seedRand(dayOfYear, 13))
                layerPaths[1].moveTo(0f, hf * 1.15f); layerPaths[1].lineTo(0f, hf * 0.605f)
                layerPaths[1].quadTo(secondaryX * 0.42f, hf * 0.585f, secondaryX, secondaryY)
                layerPaths[1].quadTo(wf * 0.46f, hf * 0.665f, wf, hf * 0.735f)
                layerPaths[1].lineTo(wf, hf * 1.15f); layerPaths[1].close()

                // Golden slope follows the secondary ridge; its lower edge is a broad curve.
                layerPaths[2].moveTo(secondaryX, secondaryY)
                layerPaths[2].quadTo(wf * 0.46f, hf * 0.665f, wf, hf * 0.735f)
                layerPaths[2].lineTo(wf, hf * 0.835f)
                layerPaths[2].quadTo(wf * 0.64f, hf * 0.805f, wf * 0.26f, hf * 0.755f)
                layerPaths[2].quadTo(wf * 0.30f, hf * 0.685f, secondaryX, secondaryY)
                layerPaths[2].close()

                // Low rolling dune and a quiet foreground trough.
                layerPaths[3].moveTo(0f, hf * 1.15f); layerPaths[3].lineTo(0f, hf * 0.805f)
                layerPaths[3].quadTo(wf * 0.30f, hf * 0.785f, wf * 0.57f, hf * 0.765f)
                layerPaths[3].quadTo(wf * 0.82f, hf * 0.785f, wf, hf * 0.825f)
                layerPaths[3].lineTo(wf, hf * 1.15f); layerPaths[3].close()
                layerPaths[4].moveTo(0f, hf * 1.15f); layerPaths[4].lineTo(0f, hf * 0.865f)
                layerPaths[4].quadTo(wf * 0.40f, hf * 0.885f, wf * 0.56f, hf * 0.875f)
                layerPaths[4].quadTo(wf * 0.79f, hf * 0.895f, wf, hf * 0.925f)
                layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
            }

            WorldBiome.SNOWY_ALPENGLOW_LAKE -> {
                val p1 = 0.35f + seedRand(dayOfYear, 7) * 0.30f
                buildFractalRidge(layerPaths[0], wf, hf, 0.61f, 0.115f, p1, 0.044f, 0f, dayOfYear + 13, 200, baseH, captureYs = false)
                val rMid = buildFractalRidge(layerPaths[1], wf, hf, 0.64f, 0.135f, 1f - p1 * 0.7f, 0.050f, 0f, dayOfYear + 23, 200, baseH)
                // Smooth slope-averaged snow cap so sub-crag slivers never leave dark gaps
                accentPath.moveTo(0f, rMid[0])
                for (i in 0..200) accentPath.lineTo((i / 200f) * wf, rMid[i])
                for (i in 200 downTo 0) {
                    val fx = i / 200f
                    val i0 = (i - 4).coerceAtLeast(0)
                    val i1 = (i + 4).coerceAtMost(200)
                    val smoothY = (rMid[i0] + rMid[i] + rMid[i1]) / 3f
                    val elev = ((hf * 0.64f - smoothY) / (hf * 0.16f)).coerceIn(0f, 1f)
                    val depth = hf * (0.015f + 0.030f * elev)
                    accentPath.lineTo(fx * wf, minOf(hf * 0.605f, rMid[i] + depth))
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
                // Layer 0: Far flat-topped African table mesa / kopje
                val mBase = hf * 0.61f
                layerPaths[0].moveTo(0f, hf); layerPaths[0].lineTo(0f, mBase + hf * 0.018f)
                layerPaths[0].lineTo(wf * 0.34f, mBase + hf * 0.014f); layerPaths[0].lineTo(wf * 0.48f, mBase + hf * 0.004f)
                layerPaths[0].lineTo(wf * 0.56f, mBase - hf * 0.046f); layerPaths[0].lineTo(wf * 0.80f, mBase - hf * 0.048f)
                layerPaths[0].lineTo(wf * 0.87f, mBase - hf * 0.002f); layerPaths[0].lineTo(wf, mBase + hf * 0.008f)
                layerPaths[0].lineTo(wf, hf); layerPaths[0].close()

                // Layer 1: Sweeping savanna plain + smooth S-curved watering-hole ribbon in accentPath
                buildFractalRidge(layerPaths[1], wf, hf, 0.64f, 0.010f, 0.45f, 0.008f, 0f, dayOfYear + 15, 120, captureYs = false)
                accentPath.moveTo(wf * 0.08f, hf * 0.678f)
                for (i in 0..32) {
                    val t = i / 32f
                    val x = wf * (0.08f + t * 0.70f)
                    val cy = hf * 0.678f + sin(t * PI * 1.4).toFloat() * (hf * 0.006f) + t * (hf * 0.008f)
                    val hh = sin(t * PI).toFloat().pow(0.7f) * (hf * (0.0045f + 0.0025f * sin(t * PI * 2.0).toFloat()))
                    accentPath.lineTo(x, cy - hh)
                }
                for (i in 32 downTo 0) {
                    val t = i / 32f
                    val x = wf * (0.08f + t * 0.70f)
                    val cy = hf * 0.678f + sin(t * PI * 1.4).toFloat() * (hf * 0.006f) + t * (hf * 0.008f)
                    val hh = sin(t * PI).toFloat().pow(0.7f) * (hf * (0.0045f + 0.0025f * sin(t * PI * 2.0).toFloat()))
                    accentPath.lineTo(x, cy + hh)
                }
                accentPath.close()

                // Layer 3: Midground emerald ridge + 2 iconic V-branched umbrella acacias
                val rMid = buildFractalRidge(layerPaths[3], wf, hf, 0.74f, 0.014f, 0.52f, 0.010f, 0f, dayOfYear + 27, 120)
                val midLeftY = rMid[(0.25f * 120).toInt().coerceIn(0, 120)] + 4f
                val midSmallY = rMid[(0.66f * 120).toInt().coerceIn(0, 120)] + 3f
                addAcaciaTree(layerPaths[3], wf * 0.25f, midLeftY, hf * 0.115f, dayOfYear + 101)
                addAcaciaTree(layerPaths[3], wf * 0.66f, midSmallY, hf * 0.062f, dayOfYear + 109)
                for (i in 0..120) midRidgeCache[i] = rMid[i]
                focalX = wf * 0.44f; focalY = rMid[53]

                // Layer 4: Foreground dark silhouette ridge + framing right acacia + corner grass
                val rFg = buildFractalRidge(layerPaths[4], wf, hf, 0.87f, 0.011f, 0.4f, 0.006f, 0f, dayOfYear + 45, 100)
                val fgTreeY = rFg[(0.86f * 100).toInt().coerceIn(0, 100)] + 8f
                addAcaciaTree(layerPaths[4], wf * 0.86f, fgTreeY, hf * 0.175f, dayOfYear + 211)
            }

            WorldBiome.COASTAL_OCEAN -> {
                // Distant right headland in layerPaths[0], left coastal cape + sea stack in layerPaths[1]
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.032f, false, dayOfYear + 5)
                buildHeadland(layerPaths[1], wf, baseH, hf * 0.058f, true, dayOfYear + 11)
                layerPaths[1].moveTo(wf * 0.42f, baseH + 3f); layerPaths[1].lineTo(wf * 0.435f, baseH - hf * 0.016f)
                layerPaths[1].lineTo(wf * 0.458f, baseH - hf * 0.018f); layerPaths[1].lineTo(wf * 0.472f, baseH + 3f); layerPaths[1].close()

                // Sweeping sandy cove shoreline in layerPaths[3], rocky coastal cliff + pine in layerPaths[4]
                layerPaths[3].moveTo(0f, hf); layerPaths[3].lineTo(0f, hf * 0.82f)
                for (i in 0..40) {
                    val nx = i / 40f
                    val sy = hf * 0.815f + sin(nx * PI * 1.15 + 0.4).toFloat() * (hf * 0.018f) - nx * (hf * 0.016f)
                    layerPaths[3].lineTo(nx * wf, sy)
                }
                layerPaths[3].lineTo(wf, hf); layerPaths[3].close()

                layerPaths[4].moveTo(0f, hf); layerPaths[4].lineTo(0f, hf * 0.79f)
                layerPaths[4].lineTo(wf * 0.13f, hf * 0.81f); layerPaths[4].lineTo(wf * 0.24f, hf * 0.87f)
                layerPaths[4].lineTo(wf * 0.33f, hf * 0.94f); layerPaths[4].lineTo(wf * 0.40f, hf); layerPaths[4].close()
                layerPaths[4].moveTo(wf * 0.75f, hf); layerPaths[4].lineTo(wf * 0.83f, hf * 0.91f)
                layerPaths[4].lineTo(wf * 0.92f, hf * 0.88f); layerPaths[4].lineTo(wf, hf * 0.87f)
                layerPaths[4].lineTo(wf, hf); layerPaths[4].close()
                // Pine rendered dynamically in drawForeground for smooth wind sway on locked cliff
                focalX = wf * 0.13f; focalY = hf * 0.815f
            }

            WorldBiome.COASTAL_REEDS -> {
                // Distant soft headland on far right only; dark bank strictly sloping from bottom-left
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.038f, false, dayOfYear)
                layerPaths[4].moveTo(0f, hf * 1.15f); layerPaths[4].lineTo(0f, hf * 0.84f)
                layerPaths[4].lineTo(wf * 0.32f, hf * 0.92f); layerPaths[4].lineTo(wf * 0.65f, hf * 0.98f)
                layerPaths[4].lineTo(wf, hf * 1.15f); layerPaths[4].close()
            }

            WorldBiome.CRYSTAL_COVE -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.615f, 0.040f, 0.70f, 0.016f, 0f, dayOfYear + 19, 120, baseH, captureYs = false)
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
                buildFractalRidge(layerPaths[0], wf, hf, 0.60f, 0.058f, 0.48f, 0.022f, 0f, dayOfYear + 11, 160, baseH, captureYs = false)
                buildFractalRidge(layerPaths[1], wf, hf, 0.625f, 0.032f, 0.65f, 0.014f, 0f, dayOfYear + 29, 140, baseH, captureYs = false)
                val tx = wf * 0.53f; val ty = baseH + hf * 0.075f; val ts = hf * 0.145f
                addToriiGate(layerPaths[2], tx, ty, ts, flipY = false)
                addToriiGate(reflectPath, tx, ty, ts, flipY = true)
                // Sakura bough + attached blossoms rendered dynamically with wind sway in drawForeground
                // Bottom-left stone bank + tōrō lantern
                layerPaths[4].moveTo(0f, hf); layerPaths[4].lineTo(0f, hf * 0.84f)
                layerPaths[4].lineTo(wf * 0.24f, hf * 0.865f); layerPaths[4].lineTo(wf * 0.36f, hf * 0.94f)
                layerPaths[4].lineTo(wf * 0.44f, hf); layerPaths[4].close()
                val lx = wf * 0.16f; val ly = hf * 0.855f
                addStoneLantern(layerPaths[4], lx, ly, hf * 0.062f)
                focalX = lx; focalY = ly - hf * 0.043f; focalW = hf * 0.009f; focalH = hf * 0.008f
                auxX = tx; auxY = ty
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
                // Match the lighthouse and nearby pines to the same local cliff profile.
                fun cliffYAt(f: Float): Float = baseH - (1f - f.pow(2.2f)) * (hf * 0.072f) - fbm1d(f, dayOfYear + 55, 4) * (hf * 0.014f)
                val lighthouseF = 0.22f / 0.38f
                val lighthouseBaseY = minOf(baseH + 4f, cliffYAt(lighthouseF))
                val pineLeftBase = minOf(baseH + 4f, cliffYAt(0.05f / 0.38f))
                val pineRightBase = minOf(baseH + 4f, cliffYAt(0.11f / 0.38f))
                addFeatheryPine(layerPaths[3], wf * 0.05f, pineLeftBase + hf * 0.003f, hf * 0.10f, hf * 0.026f, dayOfYear + 301)
                addFeatheryPine(layerPaths[3], wf * 0.11f, pineRightBase + hf * 0.003f, hf * 0.08f, hf * 0.022f, dayOfYear + 305)
                addLighthouse(layerPaths[3], wf * 0.22f, lighthouseBaseY + hf * 0.008f, hf * 0.128f)
                buildFractalRidge(layerPaths[4], wf, hf, 0.84f, 0f, 0.5f, 0.014f, 0.042f, dayOfYear + 101, 90, captureYs = false)
            }

            WorldBiome.AURORA_FJORD -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.60f, 0.080f, 0.50f, 0.032f, 0f, dayOfYear + 21, 160, baseH, captureYs = false)
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
                buildFractalRidge(layerPaths[0], wf, hf, 0.56f, 0.048f, 0.55f, 0.022f, 0f, dayOfYear + 13, 150, captureYs = false)
                val rMidFar = buildFractalRidge(layerPaths[1], wf, hf, 0.66f, 0.036f, 0.72f, 0.018f, 0f, dayOfYear + 31, 130)
                for (px in arrayOf(0.74f, 0.80f, 0.86f, 0.92f)) {
                    val py = rMidFar[(px * 130).toInt().coerceIn(0, 130)] + 4f
                    addFeatheryPine(layerPaths[1], px * wf, py, hf * 0.045f, hf * 0.012f, dayOfYear + (px * 100).toInt())
                }
                val rOverlook = buildFractalRidge(layerPaths[2], wf, hf, 0.745f, 0.026f, 0.46f, 0.012f, 0f, dayOfYear + 53, 120)
                for ((idx, px) in arrayOf(0.10f, 0.19f, 0.27f).withIndex()) {
                    val py = rOverlook[(px * 120).toInt().coerceIn(0, 120)] + 6f
                    val th = hf * (0.072f - idx * 0.012f)
                    addFeatheryPine(layerPaths[2], px * wf, py, th, th * 0.26f, dayOfYear + 200 + idx * 11)
                }
                for (i in 0..120) midRidgeCache[i] = rOverlook[i]
                focalX = wf * 0.47f; focalY = rOverlook[56] + 1f
                val rFg = buildFractalRidge(layerPaths[4], wf, hf, 0.88f, 0.014f, 0.4f, 0.008f, 0f, dayOfYear + 89, 90)
                for ((idx, pair) in arrayOf(0.06f to 0.095f, 0.86f to 0.080f, 0.94f to 0.100f).withIndex()) {
                    val py = rFg[(pair.first * 90).toInt().coerceIn(0, 90)] + 8f
                    addFeatheryPine(layerPaths[4], pair.first * wf, py, hf * pair.second, hf * pair.second * 0.26f, dayOfYear + 310 + idx * 17)
                }
            }

            WorldBiome.CAPPADOCIA_BALLOONS -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.64f, 0.018f, 0.45f, 0.010f, 0f, dayOfYear + 17, 120, captureYs = false)
                buildFractalRidge(layerPaths[1], wf, hf, 0.72f, 0.020f, 0.62f, 0.012f, 0f, dayOfYear + 37, 110, captureYs = false)
                val rChim = buildFractalRidge(layerPaths[2], wf, hf, 0.80f, 0.014f, 0.35f, 0.008f, 0f, dayOfYear + 59, 100)
                val chimSpecs = arrayOf(
                    floatArrayOf(0.09f, 0.10f, 0.102f, -0.3f),
                    floatArrayOf(0.18f, 0.078f, 0.081f, 0.2f),
                    floatArrayOf(0.26f, 0.059f, 0.060f, -0.1f),
                    floatArrayOf(0.73f, 0.070f, 0.073f, -0.2f),
                    floatArrayOf(0.83f, 0.104f, 0.110f, 0.15f),
                    floatArrayOf(0.92f, 0.081f, 0.085f, -0.25f)
                )
                for (cs in chimSpecs) {
                    val cy = rChim[(cs[0] * 100).toInt().coerceIn(0, 100)] + 6f
                    addFairyChimney(layerPaths[2], accentPath, wf * cs[0], cy, wf * cs[1], hf * cs[2], cs[3])
                }
                val wy = rChim[(0.83f * 100).toInt().coerceIn(0, 100)]
                focalX = wf * 0.83f; focalY = wy - hf * 0.024f
                buildFractalRidge(layerPaths[4], wf, hf, 0.90f, 0.010f, 0.5f, 0.006f, 0f, dayOfYear + 97, 80, captureYs = false)
            }

            WorldBiome.ODISHA_BANYAN_GHAT -> {
                // Far riverbank + Kalinga Rekha Deula + Pidha Jagamohana temple
                layerPaths[0].moveTo(0f, baseH + 2f); layerPaths[0].lineTo(0f, baseH - hf * 0.012f)
                layerPaths[0].lineTo(wf * 0.28f, baseH - hf * 0.016f); layerPaths[0].lineTo(wf * 0.56f, baseH - hf * 0.010f)
                layerPaths[0].lineTo(wf * 0.82f, baseH - hf * 0.018f); layerPaths[0].lineTo(wf, baseH - hf * 0.014f)
                layerPaths[0].lineTo(wf, baseH + 2f); layerPaths[0].close()
                addKalingaTemple(layerPaths[0], wf * 0.79f, baseH - hf * 0.014f, hf * 0.082f)

                // Stepped stone ghat terrace on bottom-left descending into the river
                layerPaths[4].moveTo(0f, hf); layerPaths[4].lineTo(0f, hf * 0.72f)
                layerPaths[4].lineTo(wf * 0.24f, hf * 0.72f); layerPaths[4].lineTo(wf * 0.28f, hf * 0.76f)
                layerPaths[4].lineTo(wf * 0.33f, hf * 0.80f); layerPaths[4].lineTo(wf * 0.38f, hf * 0.84f)
                layerPaths[4].lineTo(wf * 0.44f, hf * 0.88f); layerPaths[4].lineTo(wf * 0.50f, hf * 0.93f)
                layerPaths[4].lineTo(wf * 0.56f, hf); layerPaths[4].close()
                // Ancient Banyan tree with gnarled limbs & hanging aerial prop roots
                addBanyanTreeAndRoots(layerPaths[4], wf * 0.12f, hf * 0.72f, hf * 0.155f)
            }

            WorldBiome.HIMALAYAN_MONASTERY -> {
                // Organic snow-draped Himalayan range + snow crest in accentPath
                val r1 = buildFractalRidge(layerPaths[0], wf, hf, 0.50f, 0.042f, 0.44f, 0.018f, 0f, dayOfYear + 19, 140)
                accentPath.moveTo(0f, r1[0])
                for (i in 0..140) accentPath.lineTo((i / 140f) * wf, r1[i])
                for (i in 140 downTo 0) {
                    val fx = i / 140f
                    accentPath.lineTo(fx * wf, r1[i] + hf * (0.014f + 0.010f * sin(fx * 6.0).toFloat()))
                }
                accentPath.close()
                buildFractalRidge(layerPaths[1], wf, hf, 0.62f, 0.032f, 0.58f, 0.015f, 0f, dayOfYear + 41, 120, captureYs = false)

                // Steep right Himalayan gorge cliff (multi-toned gompa drawn dynamically in drawForeground)
                layerPaths[3].moveTo(wf * 0.34f, hf)
                for (i in 0..40) {
                    val nx = i / 40f
                    val x = wf * (0.34f + nx * 0.66f)
                    val rise = -sin(minOf(1f, nx * 1.65f) * PI * 0.5).toFloat() * (hf * 0.36f)
                    val crag = sin(nx * 14.0).toFloat() * (hf * 0.005f) + cos(nx * 29.0).toFloat() * (hf * 0.002f)
                    layerPaths[3].lineTo(x, hf * 0.96f + rise + crag)
                }
                layerPaths[3].lineTo(wf, hf); layerPaths[3].close()
                focalX = wf * 0.78f; focalY = hf * 0.602f

                // Foreground overlook ridge cached in midRidgeCache for wind-swaying deodar pines & prayer poles
                val rFg = buildFractalRidge(layerPaths[4], wf, hf, 0.82f, 0.018f, 0.25f, 0.012f, 0f, dayOfYear + 73, 120)
                for (i in 0..120) midRidgeCache[i] = rFg[i]
                auxX = sampleMidRidgeY(0.24f); auxY = sampleMidRidgeY(0.45f)
            }

            WorldBiome.ALPINE_VIADUCT -> {
                val r1 = buildFractalRidge(layerPaths[0], wf, hf, 0.49f, 0.040f, 0.42f, 0.018f, 0f, dayOfYear + 23, 140)
                accentPath.moveTo(0f, r1[0])
                for (i in 0..140) accentPath.lineTo((i / 140f) * wf, r1[i])
                for (i in 140 downTo 0) {
                    val fx = i / 140f
                    accentPath.lineTo(fx * wf, r1[i] + hf * (0.013f + 0.009f * sin(fx * 6.0).toFloat()))
                }
                accentPath.close()
                buildFractalRidge(layerPaths[1], wf, hf, 0.59f, 0.028f, 0.58f, 0.014f, 0f, dayOfYear + 47, 120, captureYs = false)
                val deckY = hf * 0.61f
                focalY = deckY
                addLandwasserViaduct(layerPaths[2], wf, hf, deckY)
                // Steep gorge walls anchoring both ends + spruces
                layerPaths[4].moveTo(0f, hf); layerPaths[4].lineTo(0f, hf * 0.56f); layerPaths[4].lineTo(wf * 0.08f, hf * 0.60f)
                layerPaths[4].lineTo(wf * 0.14f, hf * 0.72f); layerPaths[4].lineTo(wf * 0.24f, hf * 0.84f)
                layerPaths[4].lineTo(wf * 0.38f, hf * 0.94f); layerPaths[4].lineTo(wf * 0.48f, hf); layerPaths[4].close()
                layerPaths[4].moveTo(wf * 0.56f, hf); layerPaths[4].lineTo(wf * 0.70f, hf * 0.91f)
                layerPaths[4].lineTo(wf * 0.84f, hf * 0.75f); layerPaths[4].lineTo(wf * 0.91f, hf * 0.60f)
                layerPaths[4].lineTo(wf, hf * 0.56f); layerPaths[4].lineTo(wf, hf); layerPaths[4].close()
            }

            WorldBiome.CANYON_WINDMILL -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.65f, 0.010f, 0.45f, 0.006f, 0f, dayOfYear + 31, 100, captureYs = false)
                addCanyonButte(layerPaths[0], wf * 0.52f, hf * 0.66f, wf * 0.18f, hf * 0.092f)
                addCanyonButte(layerPaths[0], wf * 0.78f, hf * 0.66f, wf * 0.22f, hf * 0.110f)
                buildFractalRidge(layerPaths[1], wf, hf, 0.73f, 0.014f, 0.55f, 0.008f, 0f, dayOfYear + 53, 100, captureYs = false)
                addCanyonButte(layerPaths[1], wf * 0.14f, hf * 0.74f, wf * 0.16f, hf * 0.075f)

                val rRim = buildFractalRidge(layerPaths[3], wf, hf, 0.81f, 0.010f, 0.35f, 0.006f, 0f, dayOfYear + 71, 100)
                val wmX = wf * 0.30f; val wmY = rRim[30]
                focalX = wmX; focalY = wmY - hf * 0.132f; auxX = wmY
                addRanchWindmillTowerAndTank(layerPaths[3], wmX, wmY, hf * 0.132f)

                val rFg = buildFractalRidge(layerPaths[4], wf, hf, 0.89f, 0.010f, 0.5f, 0.005f, 0f, dayOfYear + 97, 80)
                addSaguaro(layerPaths[4], wf * 0.82f, rFg[66] + 4f, hf * 0.064f)
                addSaguaro(layerPaths[4], wf * 0.91f, rFg[73] + 4f, hf * 0.045f)
            }

            WorldBiome.VOLCANIC_CALDERA -> {
                // Sweeping broad-shouldered stratovolcano
                val vBaseY = hf * 0.62f; val vPeakY = hf * 0.37f
                focalX = wf * 0.50f; focalY = vPeakY; auxY = vBaseY
                layerPaths[2].moveTo(0f, vBaseY + 12f)
                for (i in 0..48) {
                    val u = i / 48f
                    val dist = abs(u - 0.5f) * 2f
                    val elev = kotlin.math.exp(-dist * 1.95f) * (vBaseY - vPeakY)
                    val edgeBlend = max(0f, 1f - dist.pow(3.2f))
                    val craterDip = max(0f, 1f - (dist / 0.055f).pow(2f)) * (hf * 0.006f)
                    val crag = sin(u * 28.0).toFloat() * (hf * 0.0025f) + cos(u * 47.0).toFloat() * (hf * 0.0012f)
                    layerPaths[2].lineTo(u * wf, vBaseY - elev * edgeBlend + craterDip + crag)
                }
                layerPaths[2].lineTo(wf, vBaseY + 12f); layerPaths[2].close()
                // Sunlit left slope facet
                accentPath.moveTo(0f, vBaseY + 12f)
                for (i in 0..24) {
                    val u = i / 48f
                    val dist = abs(u - 0.5f) * 2f
                    val elev = kotlin.math.exp(-dist * 1.95f) * (vBaseY - vPeakY)
                    val edgeBlend = max(0f, 1f - dist.pow(3.2f))
                    val craterDip = max(0f, 1f - (dist / 0.055f).pow(2f)) * (hf * 0.006f)
                    val crag = sin(u * 28.0).toFloat() * (hf * 0.0025f) + cos(u * 47.0).toFloat() * (hf * 0.0012f)
                    accentPath.lineTo(u * wf, vBaseY - elev * edgeBlend + craterDip + crag)
                }
                accentPath.lineTo(wf * 0.45f, vBaseY * 0.80f); accentPath.lineTo(wf * 0.34f, vBaseY + 12f); accentPath.close()

                // Dark basalt lava plain below vBaseY
                layerPaths[4].moveTo(0f, hf); layerPaths[4].lineTo(0f, vBaseY)
                layerPaths[4].lineTo(wf * 0.35f, vBaseY + hf * 0.006f); layerPaths[4].lineTo(wf * 0.72f, vBaseY - hf * 0.004f)
                layerPaths[4].lineTo(wf, vBaseY + hf * 0.004f); layerPaths[4].lineTo(wf, hf); layerPaths[4].close()
            }

            WorldBiome.HIGHLAND_LOCH -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.51f, 0.024f, 0.45f, 0.016f, 0f, dayOfYear + 27, 130, baseH, captureYs = false)
                buildFractalRidge(layerPaths[1], wf, hf, 0.54f, 0.018f, 0.65f, 0.012f, 0f, dayOfYear + 43, 110, baseH, captureYs = false)
                // Low rocky castle promontory jutting from left-center shore + Kilchurn ruin
                val promY = baseH + hf * 0.010f
                layerPaths[2].moveTo(0f, baseH + hf * 0.038f); layerPaths[2].lineTo(0f, promY)
                layerPaths[2].lineTo(wf * 0.16f, baseH + hf * 0.006f); layerPaths[2].lineTo(wf * 0.34f, promY)
                layerPaths[2].lineTo(wf * 0.42f, baseH + hf * 0.024f); layerPaths[2].lineTo(wf * 0.28f, baseH + hf * 0.036f); layerPaths[2].close()
                val casX = wf * 0.23f; val casY = promY
                focalX = casX; focalY = casY
                // Foreground heather knolls (castle ruin & organic swaying Scots pine drawn dynamically)
                layerPaths[4].moveTo(wf * 0.44f, hf); layerPaths[4].lineTo(wf * 0.62f, hf * 0.91f)
                layerPaths[4].lineTo(wf * 0.78f, hf * 0.855f); layerPaths[4].lineTo(wf, hf * 0.835f)
                layerPaths[4].lineTo(wf, hf); layerPaths[4].close()
                layerPaths[4].moveTo(0f, hf); layerPaths[4].lineTo(0f, hf * 0.89f)
                layerPaths[4].lineTo(wf * 0.16f, hf * 0.93f); layerPaths[4].lineTo(wf * 0.30f, hf); layerPaths[4].close()
            }

            WorldBiome.LAVENDER_PROVENCE -> {
                buildFractalRidge(layerPaths[0], wf, hf, 0.56f, 0.018f, 0.4f, 0.010f, 0f, dayOfYear + 13, 100, captureYs = false)
                // Rounded hilltop crowned by Provençal stone mas
                layerPaths[1].moveTo(0f, hf)
                for (i in 0..80) {
                    val nx = i / 80f
                    val hill = -kotlin.math.exp(-((nx - 0.62f) / 0.26f).pow(2f)) * (hf * 0.035f)
                    layerPaths[1].lineTo(nx * wf, hf * 0.64f + hill + sin(nx * 5.0).toFloat() * (hf * 0.006f))
                }
                layerPaths[1].lineTo(wf, hf); layerPaths[1].close()
                val hillTopY = hf * 0.64f - hf * 0.035f + sin(0.62 * 5.0).toFloat() * (hf * 0.006f)
                focalX = wf * 0.62f; focalY = hillTopY
                // Base soil field (multi-toned stone mas drawn dynamically with terracotta roof)
                val fieldTop = hf * 0.655f
                layerPaths[3].moveTo(0f, hf); layerPaths[3].lineTo(0f, fieldTop + hf * 0.018f)
                layerPaths[3].lineTo(wf * 0.58f, fieldTop); layerPaths[3].lineTo(wf, fieldTop + hf * 0.014f)
                layerPaths[3].lineTo(wf, hf); layerPaths[3].close()
            }

            WorldBiome.SANTORINI_CLIFFSIDE -> {
                buildHeadland(layerPaths[0], wf, baseH, hf * 0.025f, true, dayOfYear + 9)
                // Craggy stepped volcanic caldera cliff on right
                layerPaths[3].moveTo(wf * 0.38f, hf); layerPaths[3].lineTo(wf * 0.43f, hf * 0.91f)
                layerPaths[3].lineTo(wf * 0.45f, hf * 0.85f); layerPaths[3].lineTo(wf * 0.50f, hf * 0.78f)
                layerPaths[3].lineTo(wf * 0.52f, hf * 0.70f); layerPaths[3].lineTo(wf * 0.56f, hf * 0.62f)
                layerPaths[3].lineTo(wf * 0.60f, hf * 0.57f); layerPaths[3].lineTo(wf * 0.68f, hf * 0.52f)
                layerPaths[3].lineTo(wf * 0.78f, hf * 0.47f); layerPaths[3].lineTo(wf * 0.90f, hf * 0.44f)
                layerPaths[3].lineTo(wf, hf * 0.43f); layerPaths[3].lineTo(wf, hf); layerPaths[3].close()
                // Whitewashed Cycladic terraces & 3 cobalt-blue dome chapels built in accentPath/layerPaths[2]
                addSantoriniVillage(layerPaths[2], accentPath, wf, hf)
            }
        }
    }

    private fun drawSoftMist(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, colWithAlpha: Int) {
        // Mist opacity breathes with weather/light, often changing by a single
        // alpha value each frame. Quantize the cache key and shader stops to 8-step
        // alpha buckets (maximum 4/255 error) so animation reuses cached shaders.
        val quantizedRgb = quantizeShaderColor(colWithAlpha) and 0x00FFFFFF
        val rawAlpha = Color.alpha(colWithAlpha)
        val quantizedAlpha = (((rawAlpha + 4) / 8) * 8).coerceAtMost(255)
        val shaderColor = quantizedRgb or (quantizedAlpha shl 24)
        var cacheIndex = -1
        for (i in mistShaderCache.indices) {
            if (mistShaderCache[i] != null && mistShaderColorCache[i] == shaderColor) {
                cacheIndex = i
                break
            }
        }
        val sh = if (cacheIndex >= 0) {
            mistShaderCache[cacheIndex]!!
        } else {
            cacheIndex = mistShaderCacheCursor
            mistShaderCacheCursor = (mistShaderCacheCursor + 1) % mistShaderCache.size
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(shaderColor, (shaderColor and 0x00FFFFFF) or (((Color.alpha(shaderColor) * 0.35f).toInt()) shl 24), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            ).also {
                mistShaderColorCache[cacheIndex] = shaderColor
                mistShaderCache[cacheIndex] = it
            }
        }
        shaderMatrix.reset()
        shaderMatrix.setScale(rx, ry)
        shaderMatrix.postTranslate(cx, cy)
        sh.setLocalMatrix(shaderMatrix)
        val previousColor = mistPaint.color
        val previousAlpha = mistPaint.alpha
        val previousStyle = mistPaint.style
        val previousShader = mistPaint.shader
        val previousStrokeWidth = mistPaint.strokeWidth
        val previousStrokeCap = mistPaint.strokeCap
        try {
            // Mist is a soft filled oval, independent of prior Paint configuration.
            mistPaint.style = Paint.Style.FILL
            mistPaint.shader = sh
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
            canvas.drawOval(rect, mistPaint)
        } finally {
            // Shared Paint state must not leak into a later landscape layer.
            mistPaint.color = previousColor
            mistPaint.alpha = previousAlpha
            mistPaint.style = previousStyle
            mistPaint.shader = previousShader
            mistPaint.strokeWidth = previousStrokeWidth
            mistPaint.strokeCap = previousStrokeCap
        }
    }

    // Circadian palette colors evolve smoothly but can change by a few RGB values
    // every frame. Exact-color shader keys therefore churn the bounded cache.
    // Quantize RGB to 8-value steps (maximum 4/255 channel error); preserve alpha.
    // This is visually negligible while making nearby palette frames share shaders.
    private fun quantizeShaderColor(color: Int): Int {
        val a = color and 0xFF000000.toInt()
        val r = ((((color ushr 16) and 0xFF) + 4) / 8 * 8).coerceAtMost(255)
        val g = ((((color ushr 8) and 0xFF) + 4) / 8 * 8).coerceAtMost(255)
        val b = (((color and 0xFF) + 4) / 8 * 8).coerceAtMost(255)
        return a or (r shl 16) or (g shl 8) or b
    }

    private fun getCachedVerticalGradient(topY: Float, botY: Float, topCol: Int, botCol: Int): LinearGradient {
        val cachedTopCol = quantizeShaderColor(topCol)
        val cachedBotCol = quantizeShaderColor(botCol)
        for (i in gradientShaderCache.indices) {
            if (gradientShaderCache[i] != null && gradientTopYCache[i] == topY &&
                gradientBottomYCache[i] == botY && gradientTopColorCache[i] == cachedTopCol &&
                gradientBottomColorCache[i] == cachedBotCol
            ) return gradientShaderCache[i]!!
        }
        val cacheIndex = gradientCacheCursor
        gradientCacheCursor = (gradientCacheCursor + 1) % gradientShaderCache.size
        return LinearGradient(0f, topY, 0f, botY, intArrayOf(cachedTopCol, cachedBotCol), null, Shader.TileMode.CLAMP)
            .also {
                gradientShaderCache[cacheIndex] = it
                gradientTopYCache[cacheIndex] = topY
                gradientBottomYCache[cacheIndex] = botY
                gradientTopColorCache[cacheIndex] = cachedTopCol
                gradientBottomColorCache[cacheIndex] = cachedBotCol
            }
    }

    private fun getCachedThreeStopGradient(
        x0: Float, y0: Float, x1: Float, y1: Float,
        color0: Int, color1: Int, color2: Int, position1: Float, position2: Float
    ): LinearGradient {
        // Animated shaft/beam endpoints move by fractions of a pixel between frames.
        // Exact-coordinate caching misses almost every frame; quantize only the
        // shader endpoints to a small 4 px grid while leaving the drawn geometry
        // continuous. This keeps the cache useful without visible coarse stepping.
        val qx0 = (x0 / 4f).roundToInt() * 4f
        val qy0 = (y0 / 4f).roundToInt() * 4f
        val qx1 = (x1 / 4f).roundToInt() * 4f
        val qy1 = (y1 / 4f).roundToInt() * 4f
        for (i in effectLinearCache.indices) {
            if (effectLinearCache[i] != null && effectX0Cache[i] == qx0 && effectY0Cache[i] == qy0 &&
                effectX1Cache[i] == qx1 && effectY1Cache[i] == qy1 &&
                effectColor0Cache[i] == color0 && effectColor1Cache[i] == color1 &&
                effectColor2Cache[i] == color2 && effectPosition1Cache[i] == position1 &&
                effectPosition2Cache[i] == position2
            ) return effectLinearCache[i]!!
        }
        val cacheIndex = effectLinearCacheCursor
        effectLinearCacheCursor = (effectLinearCacheCursor + 1) % effectLinearCache.size
        return LinearGradient(
            qx0, qy0, qx1, qy1, intArrayOf(color0, color1, color2),
            floatArrayOf(0f, position1, position2), Shader.TileMode.CLAMP
        ).also {
            effectLinearCache[cacheIndex] = it
            effectX0Cache[cacheIndex] = qx0; effectY0Cache[cacheIndex] = qy0
            effectX1Cache[cacheIndex] = qx1; effectY1Cache[cacheIndex] = qy1
            effectColor0Cache[cacheIndex] = color0; effectColor1Cache[cacheIndex] = color1
            effectColor2Cache[cacheIndex] = color2
            effectPosition1Cache[cacheIndex] = position1; effectPosition2Cache[cacheIndex] = position2
        }
    }

    private fun drawGradientLayer(
        canvas: Canvas, path: Path, topY: Float, botY: Float,
        topCol: Int, botCol: Int, dx: Float, dy: Float, alpha: Int
    ) {
        val shader = getCachedVerticalGradient(topY, botY, topCol, botCol)
        val previousColor = fillPaint.color
        val previousAlpha = fillPaint.alpha
        val previousStyle = fillPaint.style
        val previousShader = fillPaint.shader
        val previousStrokeWidth = fillPaint.strokeWidth
        val previousStrokeCap = fillPaint.strokeCap
        canvas.save()
        try {
            // Gradient geometry is always filled; don't inherit an earlier helper's
            // style or stroke configuration through the shared Paint instance.
            fillPaint.style = Paint.Style.FILL
            fillPaint.shader = shader
            fillPaint.alpha = alpha.coerceIn(0, 255)
            canvas.translate(dx, dy)
            canvas.drawPath(path, fillPaint)
        } finally {
            // Paint restoration must still run if Canvas restoration itself fails.
            try {
                canvas.restore()
            } finally {
                fillPaint.color = previousColor
                fillPaint.alpha = previousAlpha
                fillPaint.style = previousStyle
                fillPaint.shader = previousShader
                fillPaint.strokeWidth = previousStrokeWidth
                fillPaint.strokeCap = previousStrokeCap
            }
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
        // Wallpaper surfaces can briefly report zero dimensions during lifecycle
        // transitions. Skip safely rather than building degenerate cached geometry.
        // Also skip fully transparent frames: they are common during fade/lifecycle
        // transitions and otherwise still perform geometry, shader lookup, and draw work.
        if (width <= 0 || height <= 0 || density <= 0f || masterAlpha <= 0) return
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
        // Meteorological direction is the direction wind comes FROM (0° north,
        // 90° east). Convert to horizontal screen motion; calm weather remains calm.
        val windDir = -sin(Math.toRadians(weather.windDirDeg.toDouble())).toFloat()
        val steadyWind = weather.windKmh.coerceIn(0f, 60f)
        // Smooth fog response: haze should build gradually with reduced visibility,
        // rather than changing linearly and feeling like a uniform overlay switch.
        val fogRaw = ((4500f - weather.visibilityM) / 4000f).coerceIn(0f, 1f)
        val fogFactor = fogRaw * fogRaw * (3f - 2f * fogRaw)
        val orbX = if (sky.sunAltDeg > -4f) sunX else moonX

        val deepVelvet = if (isDay) ColorUtils.blendARGB(0xFF0A0D18.toInt(), circ.primary, 0.22f) else 0xFF050612.toInt()
        val midTone = ColorUtils.blendARGB(circ.primary, circ.horizonColor, if (isDay) 0.38f else 0.25f)
        val mistColor = if (isDay) ColorUtils.blendARGB(circ.horizonColor, lightColor, 0.45f) else ColorUtils.blendARGB(circ.horizonColor, sky.moonColor, 0.35f)
        val mistRgb = mistColor and 0x00FFFFFF

        // The vertical-gradient cache is keyed globally and uses a persistent
        // replacement cursor so different biomes retain useful shader entries.
        // A single boundary protects shared Paint state from every biome branch,
        // including exceptional draw paths. Primitive snapshots avoid per-frame allocations.
        val frameFillColor = fillPaint.color
        val frameFillAlpha = fillPaint.alpha
        val frameFillStyle = fillPaint.style
        val frameFillShader = fillPaint.shader
        val frameFillStrokeWidth = fillPaint.strokeWidth
        val frameFillStrokeCap = fillPaint.strokeCap
        val frameStrokeColor = strokePaint.color
        val frameStrokeAlpha = strokePaint.alpha
        val frameStrokeStyle = strokePaint.style
        val frameStrokeShader = strokePaint.shader
        val frameStrokeWidth = strokePaint.strokeWidth
        val frameStrokeCap = strokePaint.strokeCap
        val frameMistColor = mistPaint.color
        val frameMistAlpha = mistPaint.alpha
        val frameMistStyle = mistPaint.style
        val frameMistShader = mistPaint.shader
        val frameMistStrokeWidth = mistPaint.strokeWidth
        val frameMistStrokeCap = mistPaint.strokeCap
        try { when (biome) {
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
                val m1A = (masterAlpha * (0.16f + fogFactor * 0.28f)).toInt().coerceIn(0, 170)
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

                // Living multi-harmonic forest wind sway (smooth at 120Hz, scaled by weather.windKmh)
                val windMotion = (0.28f + (steadyWind / 28f).coerceIn(0f, 1f) * 0.72f)
                val sway4 = (sin(timeSec * 0.55) * 0.7 + sin(timeSec * 1.23 + 0.9) * 0.3).toFloat() * (density * (1.8f + steadyWind * 0.06f)) * windMotion * windDir
                drawGradientLayer(canvas, layerPaths[3], height * 0.64f, height * 0.94f, c4Top, c4Bot, sway4, foreShiftY * 0.80f, masterAlpha)
                val sway5 = (sin(timeSec * 0.68 + 1.4) * 0.7 + cos(timeSec * 1.47) * 0.3).toFloat() * (density * (2.6f + steadyWind * 0.085f)) * windMotion * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), c5Top, 0xFF03040A.toInt(), sway5, foreShiftY, masterAlpha)
                // Gentle daytime sunlit forest pollen motes drifting in the breeze
                if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    fillPaint.color = lightColor; fillPaint.shader = null
                    for (m in 0 until 12) {
                        val mPhase = (((timeSec * (0.012 + (m % 4) * 0.004) * windDir + m * 0.19) % 1.0 + 1.0) % 1.0).toFloat()
                        val mx = mPhase * width
                        val my = height * (0.58f + (m % 5) * 0.06f) + sin(timeSec * (0.7 + m * 0.11) + m * 1.7).toFloat() * density * 8f
                        val ma = (masterAlpha * (0.16f + 0.14f * sin(timeSec * 1.1 + m * 2.1).toFloat())).toInt().coerceIn(0, 95)
                        fillPaint.alpha = ma
                        canvas.drawCircle(mx, my, density * (0.9f + (m % 3) * 0.35f), fillPaint)
                    }
                }
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

                if (max(steadyWind, weather.windGustsKmh * 0.45f) >= 8f && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    drawDuneDust(canvas, width, height, density, windDir, steadyWind, weather.windGustsKmh, timeSec, masterAlpha)
                }

                // Unpredictable 2..5 Human Travelers climbing up or descending down the golden dune crest
                val shadowDir = if (orbX >= width * 0.5f) -1f else 1f
                val lightAlt = if (sky.sunAltDeg > -4f) sky.sunAltDeg else sky.moonAltDeg
                val lowAngleStretch = (1.0f - (lightAlt / 55f).coerceIn(0f, 1f)) * 0.75f
                val lateralStretch = (abs(orbX - width * 0.5f) / (width * 0.35f)).coerceIn(0f, 1f)
                val shadowStretch = (1.15f + lowAngleStretch + lateralStretch * 0.55f).coerceIn(1.15f, 2.45f)
                val shadowAlphaFactor = (0.12f + 0.68f * (lightStrength / 0.85f).coerceIn(0f, 1f))
                val lapRate = 0.0072
                val rawLap = timeSec * lapRate
                val lapId = kotlin.math.floor(rawLap).toInt()
                val lapFrac = (rawLap - lapId).toFloat()
                val groupCount = 2 + (seedRand(lapId + dayOfYear, 17) * 4f).toInt().coerceIn(0, 3)
                val climbingUp = seedRand(lapId + dayOfYear, 29) > 0.5f
                val moveDir = if (climbingUp) -1f else 1f
                for (tIdx in 0 until groupCount) {
                    val spacing = 0.042f + seedRand(lapId * 7 + tIdx, 43) * 0.026f
                    val rawU = lapFrac - tIdx * spacing
                    if (rawU !in 0.08f..0.92f) continue
                    val u = if (climbingUp) (1f - rawU) else rawU
                    val omt = 1f - u
                    val tx = omt * omt * (width * 0.10f) + 2f * omt * u * (width * 0.54f) + u * u * width
                    val ty = omt * omt * (height * 0.56f) + 2f * omt * u * (height * 0.66f) + u * u * (height * 0.74f) + midShiftY * 0.8f + density * 8.5f
                    val figH = density * (9.2f - (tIdx % 3) * 0.7f + seedRand(lapId + tIdx, 53) * 0.8f)
                    val gaitSpeed = 3.2 + (tIdx % 3) * 0.55 + seedRand(lapId + tIdx, 61) * 0.45
                    val gaitPhase = timeSec * gaitSpeed + tIdx * 2.17
                    drawHumanTraveler(canvas, tx, ty, figH, density, moveDir, shadowDir, shadowStretch, shadowAlphaFactor, gaitPhase, isDay, masterAlpha)
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
                val snowCol = ColorUtils.blendARGB(if (isDay) 0xFFF1F5F9.toInt() else 0xFF94A3B8.toInt(), lightColor, if (alpenglow > 0.15f) 0.75f else 0.35f)
                drawGradientLayer(canvas, accentPath, height * 0.42f, horizonY * 0.96f, snowCol, ColorUtils.blendARGB(snowCol, midTone, 0.35f), 0f, horizonShiftY, (masterAlpha * 0.92f).toInt())
                val waterTop = ColorUtils.blendARGB(circ.horizonColor, midTone, 0.25f)
                val waterBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.70f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), waterTop, waterBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawGradientLayer(canvas, reflectPath, horizonY, horizonY + height * 0.16f, ColorUtils.blendARGB(midTone, circ.horizonColor, 0.5f), waterTop, 0f, horizonShiftY, (masterAlpha * 0.38f).toInt())
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.85f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
                val mDrift = (((timeSec * (0.0020 + steadyWind * 0.00014) * windDir) % 1.0 + 1.0) % 1.0).toFloat()
                drawSoftMist(canvas, (mDrift * 1.2f - 0.1f) * width, horizonY, width * 0.92f, height * 0.034f, mistRgb or ((masterAlpha * (0.15f + fogFactor * 0.28f)).toInt() shl 24))
                val pineSway = (sin(timeSec * 0.62) * 0.7 + cos(timeSec * 1.38) * 0.3).toFloat() * density * (1.8f + steadyWind * 0.06f) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.72f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), pineSway, foreShiftY, masterAlpha)
                drawSnowFlurries(canvas, width, height, density, windDir, steadyWind, weather.windGustsKmh, timeSec, masterAlpha)
            }

            WorldBiome.EMERALD_SAVANNA -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.54f, height * 0.72f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, horizonShiftY * 0.7f, (masterAlpha * 0.72f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.60f, height * 0.80f, ColorUtils.blendARGB(midTone, 0xFF4D7C5F.toInt(), if (isDay) 0.45f else 0.15f), midTone, 0f, midShiftY * 0.55f, masterAlpha)
                // Reflective watering-hole ribbon
                val poolCol = ColorUtils.blendARGB(circ.horizonColor, lightColor, 0.40f)
                drawGradientLayer(canvas, accentPath, height * 0.66f, height * 0.70f, poolCol, ColorUtils.blendARGB(poolCol, midTone, 0.4f), 0f, midShiftY * 0.55f, (masterAlpha * 0.72f).toInt())
                val acaciaSwayMid = sin(timeSec * 0.58).toFloat() * density * (1.2f + steadyWind * 0.045f) * windDir
                val acaciaSwayFg = sin(timeSec * 0.72 + 1.1).toFloat() * density * (2.0f + steadyWind * 0.07f) * windDir
                drawGradientLayer(canvas, layerPaths[3], height * 0.62f, height * 0.90f, ColorUtils.blendARGB(midTone, deepVelvet, 0.48f), deepVelvet, acaciaSwayMid, midShiftY * 0.85f, masterAlpha)
                // Alternating living savanna wildlife: walking/grazing giraffes, elephant, or bounding antelopes
                val wildCol = ColorUtils.blendARGB(midTone, deepVelvet, 0.55f)
                drawSavannaWildlife(canvas, width, height, midShiftY * 0.85f, dayOfYear, wildCol, masterAlpha, timeSec)

                drawGradientLayer(canvas, layerPaths[4], height * 0.70f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), acaciaSwayFg, foreShiftY, masterAlpha)
                drawSwayingGrass(canvas, width, height, density, dayOfYear, height * 0.87f + foreShiftY, deepVelvet, windDir, steadyWind, timeSec, masterAlpha)
                if (isDay || alpenglow > 0.1f) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.COASTAL_OCEAN -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.04f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.25f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.65f).toInt())
                drawGradientLayer(canvas, layerPaths[1], horizonY - height * 0.06f, horizonY + 4f, ColorUtils.blendARGB(midTone, deepVelvet, 0.42f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.86f).toInt())
                val seaTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.22f else 0.68f)
                val seaBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.68f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), seaTop, seaBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.82f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
                drawUnpredictableSailboat(canvas, width, height, horizonY, density, dayOfYear, isDay, windDir, steadyWind, timeSec, masterAlpha)
                // Sandy cove & sea-foam edge
                val sandCol = if (isDay) ColorUtils.blendARGB(0xFFD4BC94.toInt(), lightColor, 0.22f) else ColorUtils.blendARGB(0xFF142234.toInt(), sky.moonColor, 0.20f)
                drawGradientLayer(canvas, layerPaths[3], height * 0.79f, height.toFloat(), sandCol, ColorUtils.blendARGB(sandCol, deepVelvet, 0.45f), 0f, foreShiftY * 0.7f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.72f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                val pSway = (sin(timeSec * 0.72) * 0.7 + cos(timeSec * 1.54) * 0.3).toFloat() * density * (2.2f + steadyWind * 0.075f) * windDir
                drawSwayingCoastalPine(canvas, focalX, focalY + foreShiftY, height * 0.105f, pSway, deepVelvet, masterAlpha)
                if (isDay || alpenglow > 0.1f) drawBirds(canvas, width, height, density, windDir, timeSec, masterAlpha)
            }

            WorldBiome.COASTAL_REEDS -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.05f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.68f).toInt())
                val seaTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.26f else 0.72f)
                val seaBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.70f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), seaTop, seaBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)

                // Asymmetrical bottom-left feathery reeds only (right side uncluttered!)
                drawLeftCornerReeds(canvas, width, height, density, dayOfYear, ColorUtils.blendARGB(midTone, deepVelvet, 0.45f), deepVelvet, lightColor, isDay, windDir, steadyWind, timeSec, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.82f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY * 0.45f, masterAlpha)
                if (isDay && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    val bfVis = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
                    val bfCount = 3 + ((dayOfYear + (timeSec / 45.0).toInt()) % 3)
                    for (bf in 0 until bfCount) {
                        val cyc = ((timeSec * (0.045 + bf * 0.011) + bf * 0.37) % 1.0 + 1.0) % 1.0
                        // Perch on a left-side reed tip during cyc 0.68..0.92, roam marsh/water otherwise
                        val perchX = width * (0.14f + (bf % 4) * 0.068f)
                        val perchY = height * (0.63f + (bf % 3) * 0.055f)
                        val roamX = width * (0.14f + 0.72f * (0.5f + 0.5f * sin(timeSec * (0.19 + bf * 0.05) + bf * 2.1).toFloat()))
                        val roamY = height * (0.61f + 0.21f * (0.5f + 0.5f * sin(timeSec * (0.31 + bf * 0.07) + bf * 1.4).toFloat()))
                        val perchBlend = when {
                            cyc < 0.62 -> 0f
                            cyc < 0.70 -> ((cyc - 0.62) / 0.08).toFloat()
                            cyc < 0.90 -> 1f
                            cyc < 0.98 -> (1.0 - (cyc - 0.90) / 0.08).toFloat()
                            else -> 0f
                        }
                        val bx = roamX * (1f - perchBlend) + perchX * perchBlend
                        val by = roamY * (1f - perchBlend) + perchY * perchBlend
                        val wingSpan = density * (8.2f + (bf % 3) * 1.6f)
                        val col = ColorUtils.blendARGB(butterflyPalette[(bf + dayOfYear) % butterflyPalette.size], sky.sunColor, 0.22f)
                        // Slow wing-breathing when perched, lively flutter when flying
                        val wingTime = if (perchBlend > 0.85f) timeSec * 0.16 else timeSec
                        drawButterfly(canvas, bx, by, wingSpan, col, (masterAlpha * 0.88f * bfVis).toInt(), wingTime, bf * 1.85)
                    }
                }
            }

            WorldBiome.CRYSTAL_COVE -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.56f, horizonY + 4f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.45f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.72f).toInt())
                val topW = if (isDay) ColorUtils.blendARGB(circ.horizonColor, 0xFF0E7490.toInt(), 0.56f) else 0xFF0B192C.toInt()
                val botW = if (isDay) ColorUtils.blendARGB(0xFF065F46.toInt(), 0xFF0E7490.toInt(), 0.52f) else 0xFF020612.toInt()
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), topW, botW)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)

                // Submerged pale-teal stones visible beneath crystal water
                val subCol = if (isDay) 0x000A4A52 else 0x0005141C
                drawSoftMist(canvas, width * 0.30f, height * 0.82f, width * 0.14f, height * 0.048f, subCol or ((masterAlpha * 0.38f).toInt() shl 24))
                drawSoftMist(canvas, width * 0.74f, height * 0.88f, width * 0.15f, height * 0.052f, subCol or ((masterAlpha * 0.42f).toInt() shl 24))

                // Soft mirror reflections of promontory & islet
                val reflectionPreviousColor = fillPaint.color
                val reflectionPreviousAlpha = fillPaint.alpha
                val reflectionPreviousStyle = fillPaint.style
                val reflectionPreviousShader = fillPaint.shader
                val reflectionPreviousStrokeWidth = fillPaint.strokeWidth
                val reflectionPreviousStrokeCap = fillPaint.strokeCap
                canvas.save()
                try {
                    fillPaint.color = ColorUtils.blendARGB(deepVelvet, topW, 0.45f); fillPaint.alpha = (masterAlpha * 0.30f).toInt()
                    fillPaint.style = Paint.Style.FILL
                    fillPaint.shader = null
                    canvas.translate(0f, horizonShiftY)
                    canvas.drawPath(reflectPath, fillPaint)
                } finally {
                    try {
                        canvas.restore()
                    } finally {
                        fillPaint.color = reflectionPreviousColor
                        fillPaint.alpha = reflectionPreviousAlpha
                        fillPaint.style = reflectionPreviousStyle
                        fillPaint.shader = reflectionPreviousShader
                        fillPaint.strokeWidth = reflectionPreviousStrokeWidth
                        fillPaint.strokeCap = reflectionPreviousStrokeCap
                    }
                }

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
                drawGradientLayer(canvas, layerPaths[0], height * 0.50f, horizonY + 4f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, horizonShiftY * 0.8f, (masterAlpha * 0.72f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.56f, horizonY + 4f, midTone, deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.86f).toInt())
                val lakeTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.24f else 0.70f)
                val lakeBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.72f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), lakeTop, lakeBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.92f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
                // Silky Torii reflection + solid vermilion Torii gate
                val toriiCol = ColorUtils.blendARGB(0xFFBC3028.toInt(), deepVelvet, if (isDay) 0.12f else 0.55f)
                drawGradientLayer(canvas, reflectPath, horizonY, horizonY + height * 0.16f, toriiCol, lakeBot, 0f, horizonShiftY, (masterAlpha * 0.28f).toInt())
                drawGradientLayer(canvas, layerPaths[2], horizonY - height * 0.12f, horizonY + height * 0.08f, toriiCol, ColorUtils.blendARGB(toriiCol, deepVelvet, 0.35f), 0f, horizonShiftY, masterAlpha)
                // Subtle shimmering water ripples around the submerged Torii pillar bases
                val tyLive = auxY + horizonShiftY
                strokePaint.color = if (isDay) 0xFFE8F4FA.toInt() else 0xFF94A3B8.toInt()
                strokePaint.alpha = (masterAlpha * 0.36f).toInt(); strokePaint.strokeWidth = density * 1.1f
                val rWave = sin(timeSec * 1.1).toFloat() * density * 3f
                canvas.drawLine(auxX - height * 0.055f + rWave, tyLive + density * 1.2f, auxX - height * 0.020f + rWave, tyLive + density * 1.2f, strokePaint)
                canvas.drawLine(auxX + height * 0.020f - rWave, tyLive + density * 1.2f, auxX + height * 0.055f - rWave, tyLive + density * 1.2f, strokePaint)
                canvas.drawLine(auxX - height * 0.036f - rWave * 0.5f, tyLive + density * 6.5f, auxX + height * 0.036f - rWave * 0.5f, tyLive + density * 6.5f, strokePaint)

                // Attached swaying sakura bough + clustered blooms + drifting petals
                drawSwayingSakuraBough(canvas, width, height, density, deepVelvet, isDay, windDir, steadyWind, timeSec, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.80f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                // Tōrō stone lantern flicker at dusk/night
                val lanternStr = ((4f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                if (lanternStr > 0.05f && focalW > 0f) {
                    val ly = focalY + foreShiftY
                    val fl = 0.90f + 0.10f * sin(timeSec * 4.1).toFloat()
                    drawSoftMist(canvas, focalX, ly, focalW * 5.2f, focalH * 5.2f, 0x00FFD268 or ((masterAlpha * 0.52f * lanternStr * fl).toInt() shl 24))
                    fillPaint.color = 0xFFFFD878.toInt(); fillPaint.alpha = (masterAlpha * 0.92f * lanternStr).toInt()
                    canvas.drawRect(focalX - focalW * 0.5f, ly - focalH * 0.5f, focalX + focalW * 0.5f, ly + focalH * 0.5f, fillPaint)
                }
            }

            WorldBiome.LIGHTHOUSE_CLIFF -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.06f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.68f).toInt())
                drawOceanSurfaceAndLife(canvas, width, height, density, horizonY, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha, showBoat = false)

                val beamStr = ((2f - sky.sunAltDeg) / 6f).coerceIn(0f, 1f)
                if (beamStr > 0.05f && focalW > 0f) {
                    val lx = focalX; val ly = focalY + horizonShiftY
                    val rotAngle = timeSec * 0.65
                    val lateral = sin(rotAngle).toFloat()
                    val facingViewer = max(0f, cos(rotAngle).toFloat()).pow(2.2f)
                    val cloudAttenuation = (1f - weather.cloudCoverPct.coerceIn(0, 100) / 100f * 0.25f)
                    val fogDiffusion = fogFactor.coerceIn(0f, 1f)
                    val absLat = abs(lateral)
                    // Smooth 360-degree Fresnel beam: shrinks smoothly to 0 as it sweeps across center (zero snapping!)
                    if (absLat > 0.04f) {
                        val dir = if (lateral >= 0f) 1f else -1f
                        val beamLen = width * 0.94f * absLat.pow(0.78f) * (1f - fogDiffusion * 0.20f)
                        val tipY = ly - height * (0.045f * absLat)
                        val outerA = (masterAlpha * 0.20f * beamStr * cloudAttenuation * absLat.pow(0.65f)).toInt().coerceIn(0, 120)
                        val innerA = (masterAlpha * 0.34f * beamStr * cloudAttenuation * absLat.pow(0.75f) * (1f - fogDiffusion * 0.38f)).toInt().coerceIn(0, 165)
                        // Outer diffused atmospheric cone
                        val hwOut = focalH * (0.36f + fogDiffusion * 0.22f)
                        fillPaint.shader = getCachedThreeStopGradient(lx, ly, lx + beamLen * dir, tipY, 0x00FEF08A or (outerA shl 24), 0x00FEF08A or ((outerA / 3) shl 24), Color.TRANSPARENT, 0.52f, 1f)
                        dynPath.reset(); dynPath.moveTo(lx, ly - hwOut); dynPath.lineTo(lx + beamLen * dir, tipY - height * 0.105f * absLat)
                        dynPath.lineTo(lx + beamLen * dir, tipY + height * 0.065f * absLat); dynPath.lineTo(lx, ly + hwOut); dynPath.close()
                        canvas.drawPath(dynPath, fillPaint)
                        // Tighter bright Fresnel core ray
                        val hwIn = focalH * 0.16f
                        fillPaint.shader = getCachedThreeStopGradient(lx, ly, lx + beamLen * 0.88f * dir, tipY, 0x00FFFBEB or (innerA shl 24), 0x00FEF08A or ((innerA / 3) shl 24), Color.TRANSPARENT, 0.45f, 1f)
                        dynPath.reset(); dynPath.moveTo(lx, ly - hwIn); dynPath.lineTo(lx + beamLen * 0.88f * dir, tipY - height * 0.038f * absLat)
                        dynPath.lineTo(lx + beamLen * 0.88f * dir, tipY + height * 0.022f * absLat); dynPath.lineTo(lx, ly + hwIn); dynPath.close()
                        canvas.drawPath(dynPath, fillPaint); fillPaint.shader = null
                    }
                    // Fresnel lens flare in lantern room (brightens smoothly as beam faces camera)
                    val flareScale = 1.0f + facingViewer * 1.15f + fogDiffusion * 0.45f
                    val flareAlpha = (masterAlpha * (0.58f + facingViewer * 0.42f) * beamStr).toInt().coerceIn(0, 255)
                    drawSoftMist(canvas, lx, ly, focalW * 2.2f * flareScale, focalH * 2.2f * flareScale, 0x00FEF08A or (flareAlpha shl 24))
                    fillPaint.color = 0xFFFFFBEB.toInt(); fillPaint.alpha = (masterAlpha * (0.78f + facingViewer * 0.22f) * beamStr).toInt().coerceIn(0, 255)
                    canvas.drawCircle(lx, ly, focalW * (0.42f + facingViewer * 0.25f), fillPaint)
                }
                drawGradientLayer(canvas, layerPaths[3], horizonY - height * 0.22f, horizonY + 8f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                drawGradientLayer(canvas, layerPaths[4], height * 0.78f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.AURORA_FJORD -> {
                val auroraStr = ((-2f - sky.sunAltDeg) / 8f).coerceIn(0f, 1f)
                // Aurora visibility responds to the atmosphere: overcast skies and
                // poor visibility soften the curtains instead of leaving them vivid.
                val auroraCloudFactor = (1f - weather.cloudCoverPct.coerceIn(0, 100) / 100f * 0.78f)
                val auroraVisibilityFactor = 0.55f + 0.45f * (1f - fogFactor)
                val auroraAtmosphereFactor = auroraCloudFactor * auroraVisibilityFactor
                if (auroraStr > 0.05f && weather.sky != SkyWeather.RAIN && weather.sky != SkyWeather.THUNDERSTORM) {
                    for (a in 0 until 4) {
                        val ax = width * (0.18f + a * 0.22f) + sin(timeSec * (0.22 + a * 0.05) + a * 1.4).toFloat() * (width * 0.08f)
                        val ay = height * (0.20f + (a % 2) * 0.06f)
                        val colRgb = if (a % 2 == 0) 0x0034D399 else 0x0038BDF8
                        val aA = (masterAlpha * 0.22f * auroraStr * auroraAtmosphereFactor * (0.7f + 0.3f * sin(timeSec * 0.4 + a).toFloat())).toInt().coerceIn(0, 140)
                        drawSoftMist(canvas, ax, ay, width * 0.22f, height * 0.14f, colRgb or (aA shl 24))
                    }
                }
                drawGradientLayer(canvas, layerPaths[0], height * 0.46f, horizonY, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.38f), midTone, 0f, horizonShiftY, masterAlpha)
                val fjordWaterTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.32f else 0.72f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), fjordWaterTop, 0xFF03060E.toInt())
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.88f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
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
                drawGradientLayer(canvas, layerPaths[0], height * 0.48f, height * 0.72f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.75f).toInt())
                val mDrift = (((timeSec * 0.0020 * windDir) % 1.0 + 1.0) % 1.0).toFloat()
                drawSoftMist(canvas, (mDrift * 1.2f - 0.1f) * width, height * 0.62f + horizonShiftY, width * 0.85f, height * 0.055f, mistRgb or ((masterAlpha * 0.32f).toInt() shl 24))
                drawGradientLayer(canvas, layerPaths[1], height * 0.56f, height * 0.80f, midTone, ColorUtils.blendARGB(midTone, deepVelvet, 0.45f), 0f, midShiftY * 0.5f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[2], height * 0.66f, height * 0.92f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, midShiftY * 0.82f, masterAlpha)
                // Living Stag: walks onto overlook, pauses & moves head, then strolls or breaks into a run
                drawLivingStag(canvas, width, height, midShiftY * 0.82f, dayOfYear, ColorUtils.blendARGB(midTone, deepVelvet, 0.58f), masterAlpha, timeSec)
                val stagPineSway = sin(timeSec * 0.64).toFloat() * density * (1.8f + steadyWind * 0.06f) * windDir
                drawGradientLayer(canvas, layerPaths[4], height * 0.78f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), stagPineSway, foreShiftY, masterAlpha)
            }

            WorldBiome.CAPPADOCIA_BALLOONS -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.56f, height * 0.78f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.40f), midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.78f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.64f, height * 0.88f, midTone, ColorUtils.blendARGB(midTone, deepVelvet, 0.42f), 0f, midShiftY * 0.5f, masterAlpha)
                // 5 Staggered hot-air balloons with positive-wrapped wind drift + asynchronous bob + burner glow
                val burnerStr = ((4f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                for (b in balloonBaseX.indices) {
                    val rawX = timeSec * (0.0038 + b * 0.0009 + steadyWind * 0.00012) * windDir + balloonBaseX[b]
                    val wrappedX = (((rawX % 1.18) + 1.18) % 1.18 - 0.09).toFloat()
                    val bobY = sin(timeSec * (0.52 + b * 0.13) + b * 1.8).toFloat() * height * 0.011f
                    val bx = wrappedX * width
                    val by = height * balloonBaseY[b] + bobY + midShiftY * 0.4f
                    val br = width * balloonRadii[b]
                    drawAnimatedBalloon(canvas, bx, by, br, b, isDay, burnerStr, deepVelvet, timeSec, masterAlpha)
                }
                drawGradientLayer(canvas, layerPaths[2], height * 0.68f, height * 0.94f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, midShiftY * 0.8f, masterAlpha)
                drawGradientLayer(canvas, accentPath, height * 0.68f, height * 0.90f, ColorUtils.blendARGB(midTone, lightColor, 0.25f), deepVelvet, 0f, midShiftY * 0.8f, masterAlpha)
                if (burnerStr > 0.05f) {
                    fillPaint.color = 0xFFFFCE66.toInt(); fillPaint.alpha = (masterAlpha * 0.88f * burnerStr).toInt()
                    val wy = focalY + midShiftY * 0.8f
                    canvas.drawRect(focalX - density * 2f, wy, focalX + density * 2f, wy + density * 3f, fillPaint)
                }
                drawGradientLayer(canvas, layerPaths[4], height * 0.84f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.ODISHA_BANYAN_GHAT -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.09f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.82f).toInt())
                val riverTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.24f else 0.70f)
                val riverBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.72f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), riverTop, riverBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.94f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
                // Gentle horizontal river current ripples across the calm water
                val ripCol = if (isDay) 0xFFD8F0F2.toInt() else 0xFF7B9AB8.toInt()
                strokePaint.color = ripCol; strokePaint.strokeWidth = density * 0.95f
                for (r in 0 until 7) {
                    val rPhase = (((timeSec * (0.014 + (r % 3) * 0.004) + r * 0.17) % 1.0 + 1.0) % 1.0).toFloat()
                    val rx = width * (0.36f + rPhase * 0.62f)
                    val ry = height * (0.64f + r * 0.042f) + sin(timeSec * 0.9 + r).toFloat() * density * 0.9f
                    val rLen = width * (0.045f + (r % 3) * 0.024f)
                    strokePaint.alpha = (masterAlpha * 0.24f * sin(rPhase * PI).toFloat().coerceIn(0f, 1f)).toInt()
                    canvas.drawLine(rx - rLen, ry, rx + rLen, ry, strokePaint)
                }

                // Gliding wooden nauka boat with gentle river drift, pitch & water ripples
                val boatU = (((timeSec * 0.0085) % 1.0 + 1.0) % 1.0).toFloat()
                val nx = width * (0.52f + 0.36f * (0.5f + 0.5f * sin(boatU * PI * 2.0).toFloat()))
                val ny = height * 0.775f + sin(timeSec * 1.15).toFloat() * density * 1.8f
                val bPitch = sin(timeSec * 1.35).toFloat() * 2.2f
                val bRip = ((timeSec * 0.45) % 1.0).toFloat()
                strokePaint.color = ripCol; strokePaint.alpha = (masterAlpha * 0.32f * (1f - bRip)).toInt(); strokePaint.strokeWidth = density * 1.0f
                rect.set(nx - density * (14f + bRip * 12f), ny + density * 0.5f, nx + density * (14f + bRip * 12f), ny + density * (4.5f + bRip * 3.5f))
                canvas.drawOval(rect, strokePaint)
                canvas.save(); canvas.rotate(bPitch, nx, ny)
                fillPaint.color = deepVelvet; fillPaint.alpha = masterAlpha
                dynPath.reset(); dynPath.moveTo(nx - density * 15f, ny - density * 2.2f); dynPath.lineTo(nx - density * 9f, ny + density * 2.4f)
                dynPath.lineTo(nx + density * 9f, ny + density * 2.4f); dynPath.lineTo(nx + density * 16f, ny - density * 2.8f); dynPath.close()
                canvas.drawPath(dynPath, fillPaint)
                canvas.restore()

                drawGradientLayer(canvas, layerPaths[4], height * 0.56f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                // Wind-swaying hanging Banyan aerial prop roots
                val bxRoot = width * 0.12f; val byRoot = height * 0.72f + foreShiftY; val bhRoot = height * 0.155f
                strokePaint.color = deepVelvet; strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = density * 1.4f
                for ((rIdx, rf) in banyanRootPositions.withIndex()) {
                    val rx = bxRoot + bhRoot * (rf - 0.10f) * 2.15f
                    val rTop = byRoot - bhRoot * 0.64f
                    val rBot = if (rIdx % 3 == 2) byRoot - bhRoot * 0.18f else byRoot + density * 2f
                    val rSway = sin(timeSec * (0.9 + rIdx * 0.14) + rIdx * 1.3).toFloat() * density * (1.8f + steadyWind * 0.06f) * windDir
                    dynPath.reset(); dynPath.moveTo(rx, rTop)
                    dynPath.quadTo(rx + rSway, (rTop + rBot) * 0.5f, rx + (if (rIdx % 3 == 2) rSway * 1.4f else 0f), rBot)
                    canvas.drawPath(dynPath, strokePaint)
                }

                // Warm flickering diyas on ghat steps & drifting gently on the river with soft ripples
                val diyaStr = ((4f - sky.sunAltDeg) / 7f).coerceIn(0.18f, 1f)
                for (dIdx in 0 until 4) {
                    val isWater = dIdx >= 2
                    val dx = if (isWater) width * (0.56f + (dIdx - 2) * 0.10f) + sin(timeSec * 0.35 + dIdx * 1.7).toFloat() * density * 10f else width * (0.26f + dIdx * 0.10f)
                    val dy = if (isWater) height * (0.855f + (dIdx - 2) * 0.042f) + sin(timeSec * 0.95 + dIdx).toFloat() * density * 1.4f else height * (0.755f + dIdx * 0.08f) + foreShiftY
                    if (isWater) {
                        val dRip = ((timeSec * 0.50 + dIdx * 0.37) % 1.0).toFloat()
                        strokePaint.color = 0xFFFFD268.toInt(); strokePaint.alpha = (masterAlpha * 0.28f * diyaStr * (1f - dRip)).toInt(); strokePaint.strokeWidth = density * 0.85f
                        rect.set(dx - density * (4f + dRip * 8f), dy - density * 1f, dx + density * (4f + dRip * 8f), dy + density * (2.5f + dRip * 2.5f))
                        canvas.drawOval(rect, strokePaint)
                    }
                    val fl = 0.85f + 0.15f * sin(timeSec * 4.5 + dIdx * 2.1).toFloat()
                    drawSoftMist(canvas, dx, dy, density * 13f, density * 13f, 0x00FFD268 or ((masterAlpha * 0.50f * diyaStr * fl).toInt() shl 24))
                    fillPaint.color = 0xFFFFD268.toInt(); fillPaint.alpha = (masterAlpha * 0.95f * diyaStr).toInt()
                    canvas.drawCircle(dx, dy, density * 1.9f, fillPaint)
                }
            }

            WorldBiome.HIMALAYAN_MONASTERY -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.40f, height * 0.65f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.30f), midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.84f).toInt())
                val snowCol = ColorUtils.blendARGB(if (isDay) 0xFFF1F5F9.toInt() else 0xFF94A3B8.toInt(), lightColor, if (alpenglow > 0.15f) 0.72f else 0.30f)
                drawGradientLayer(canvas, accentPath, height * 0.40f, height * 0.56f, snowCol, midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.90f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.54f, height * 0.75f, midTone, deepVelvet, 0f, midShiftY * 0.5f, (masterAlpha * 0.88f).toInt())
                val mistDrift = sin(timeSec * 0.14).toFloat() * width * 0.045f
                drawSoftMist(canvas, width * 0.5f + mistDrift, height * 0.68f + midShiftY * 0.6f, width * 0.95f, height * 0.055f, mistRgb or ((masterAlpha * 0.34f).toInt() shl 24))
                drawGradientLayer(canvas, layerPaths[3], height * 0.56f, height.toFloat(), ColorUtils.blendARGB(midTone, deepVelvet, 0.52f), deepVelvet, 0f, midShiftY * 0.8f, masterAlpha)

                // Layered multi-toned Tibetan gompa with crimson roof friezes, golden pagoda spire & glowing windows
                drawTibetanMonastery(canvas, focalX, focalY + midShiftY * 0.8f, height * 0.060f, density, isDay, lightColor, deepVelvet, sky.sunAltDeg, masterAlpha)

                // Foreground overlook ridge + wind-swaying Himalayan deodar pines
                drawGradientLayer(canvas, layerPaths[4], height * 0.76f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                val windAmp = 1f + (steadyWind / 22f).coerceIn(0f, 1.8f)
                val p1Sway = (sin(timeSec * 0.85) * 0.7 + cos(timeSec * 1.72) * 0.3).toFloat() * density * 3.0f * windAmp
                val p2Sway = (sin(timeSec * 0.98 + 1.4) * 0.7 + cos(timeSec * 1.95 + 0.6) * 0.3).toFloat() * density * 2.4f * windAmp
                val p3Sway = (sin(timeSec * 0.78 + 2.7) * 0.7 + cos(timeSec * 1.58 + 1.1) * 0.3).toFloat() * density * 2.8f * windAmp
                drawSwayingDeodarPine(canvas, width * 0.08f, sampleMidRidgeY(0.08f) + foreShiftY + density * 4f, height * 0.086f, p1Sway, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.16f, sampleMidRidgeY(0.16f) + foreShiftY + density * 4f, height * 0.066f, p2Sway, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.93f, sampleMidRidgeY(0.93f) + foreShiftY + density * 4f, height * 0.082f, p3Sway, deepVelvet, masterAlpha)

                // Wooden prayer poles planted into foreground ridge + fluttering Lungta prayer flags
                drawAnimatedPrayerFlags(
                    canvas,
                    width * 0.24f, auxX + foreShiftY, height * 0.046f,
                    width * 0.45f, auxY + foreShiftY, height * 0.040f,
                    density, deepVelvet, steadyWind, timeSec, masterAlpha
                )
            }

            WorldBiome.ALPINE_VIADUCT -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.40f, height * 0.65f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.30f), midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.84f).toInt())
                val snowCol = ColorUtils.blendARGB(if (isDay) 0xFFF1F5F9.toInt() else 0xFF94A3B8.toInt(), lightColor, if (alpenglow > 0.15f) 0.72f else 0.30f)
                drawGradientLayer(canvas, accentPath, height * 0.40f, height * 0.56f, snowCol, midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.90f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.52f, height * 0.72f, midTone, deepVelvet, 0f, midShiftY * 0.5f, (masterAlpha * 0.88f).toInt())
                val gorgeMistDrift = sin(timeSec * 0.13).toFloat() * width * 0.055f
                drawSoftMist(canvas, width * 0.5f + gorgeMistDrift, height * 0.76f + midShiftY * 0.6f, width * 0.95f, height * 0.065f, mistRgb or ((masterAlpha * 0.36f).toInt() shl 24))
                val stoneCol = ColorUtils.blendARGB(if (isDay) 0xFFB0AAA2.toInt() else 0xFF2C3A50.toInt(), lightColor, 0.22f)
                drawGradientLayer(canvas, layerPaths[2], focalY, height * 0.96f, stoneCol, deepVelvet, 0f, midShiftY * 0.82f, masterAlpha)
                // Gliding 3-car Swiss red express train across the viaduct
                drawGlidingAlpineTrain(canvas, width, height, density, focalY + midShiftY * 0.82f, sky.sunAltDeg, timeSec, masterAlpha)
                // Gorge walls + 6 wind-swaying Alpine spruces
                drawGradientLayer(canvas, layerPaths[4], height * 0.54f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                val windAmp = 1f + (steadyWind / 22f).coerceIn(0f, 1.8f)
                val s0 = (sin(timeSec * 0.82) * 0.7 + cos(timeSec * 1.65) * 0.3).toFloat() * density * 2.6f * windAmp
                val s1 = (sin(timeSec * 0.94 + 1.1) * 0.7 + cos(timeSec * 1.88 + 0.5) * 0.3).toFloat() * density * 2.3f * windAmp
                val s2 = (sin(timeSec * 0.76 + 2.2) * 0.7 + cos(timeSec * 1.54 + 1.3) * 0.3).toFloat() * density * 2.8f * windAmp
                val s3 = (sin(timeSec * 0.88 + 3.4) * 0.7 + cos(timeSec * 1.74 + 2.1) * 0.3).toFloat() * density * 2.5f * windAmp
                val s4 = (sin(timeSec * 0.80 + 4.5) * 0.7 + cos(timeSec * 1.62 + 0.8) * 0.3).toFloat() * density * 2.7f * windAmp
                val s5 = (sin(timeSec * 0.91 + 5.3) * 0.7 + cos(timeSec * 1.80 + 1.9) * 0.3).toFloat() * density * 2.9f * windAmp
                drawSwayingDeodarPine(canvas, width * 0.05f, height * 0.59f + foreShiftY + density * 3f, height * 0.075f, s0, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.11f, height * 0.72f + foreShiftY + density * 3f, height * 0.068f, s1, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.19f, height * 0.83f + foreShiftY + density * 3f, height * 0.082f, s2, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.94f, height * 0.59f + foreShiftY + density * 3f, height * 0.072f, s3, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.87f, height * 0.74f + foreShiftY + density * 3f, height * 0.078f, s4, deepVelvet, masterAlpha)
                drawSwayingDeodarPine(canvas, width * 0.78f, height * 0.88f + foreShiftY + density * 3f, height * 0.086f, s5, deepVelvet, masterAlpha)
            }

            WorldBiome.CANYON_WINDMILL -> {
                if (isDay) drawThermalEagles(canvas, width, height, density, windDir, timeSec, masterAlpha)
                drawGradientLayer(canvas, layerPaths[0], height * 0.54f, height * 0.72f, ColorUtils.blendARGB(circ.horizonColor, 0xFFB27E6E.toInt(), 0.45f), midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.85f).toInt())
                // Drifting desert dust / sunlit canyon haze scaled by weather.windKmh
                val dustSpeed = 0.010 + (steadyWind / 26.0).coerceIn(0.0, 1.8) * 0.016
                val dustU = (((timeSec * dustSpeed * windDir) % 1.0 + 1.0) % 1.0).toFloat()
                val dustRgb = if (isDay) (ColorUtils.blendARGB(circ.horizonColor, 0xFFE6B58A.toInt(), 0.45f) and 0x00FFFFFF) else mistRgb
                drawSoftMist(canvas, width * (0.25f + dustU * 0.50f), height * 0.71f + midShiftY * 0.55f, width * 0.88f, height * 0.055f, dustRgb or ((masterAlpha * if (isDay) 0.24f else 0.14f).toInt() shl 24))
                drawGradientLayer(canvas, layerPaths[1], height * 0.62f, height * 0.82f, ColorUtils.blendARGB(midTone, 0xFF80463E.toInt(), if (isDay) 0.55f else 0.15f), deepVelvet, 0f, midShiftY * 0.55f, masterAlpha)
                drawGradientLayer(canvas, layerPaths[3], height * 0.66f, height * 0.94f, ColorUtils.blendARGB(midTone, deepVelvet, 0.55f), deepVelvet, 0f, midShiftY * 0.85f, masterAlpha)
                // Spinning 12-blade turbine rotor scaled by weather.windKmh
                drawSpinningWindmillRotor(canvas, focalX, focalY + midShiftY * 0.85f, height * 0.035f, deepVelvet, steadyWind, timeSec, masterAlpha)
                val lampStr = ((4f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                if (lampStr > 0.05f) {
                    val lx = focalX + height * 0.032f; val ly = auxX + midShiftY * 0.85f - height * 0.020f
                    val flicker = (0.84f + 0.10f * sin(timeSec * 7.3) + 0.06f * cos(timeSec * 13.7)).toFloat()
                    drawSoftMist(canvas, lx, ly, density * (13f + flicker * 3f), density * (13f + flicker * 3f), 0x00FFD268 or ((masterAlpha * 0.52f * lampStr * flicker).toInt() shl 24))
                    fillPaint.color = 0xFFFFD875.toInt(); fillPaint.alpha = (masterAlpha * 0.94f * lampStr * flicker).toInt()
                    canvas.drawCircle(lx, ly, density * (1.5f + flicker * 0.45f), fillPaint)
                }
                drawGradientLayer(canvas, layerPaths[4], height * 0.82f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
            }

            WorldBiome.VOLCANIC_CALDERA -> {
                // Smooth circadian transition: daytime grey-white smoke plumes -> dusk/night breathing lava glow
                val lavaStr = ((4f - sky.sunAltDeg) / 7f).coerceIn(0f, 1f)
                val pulse = (0.84f + 0.16f * sin(timeSec * 1.35)).toFloat()
                val cy = focalY + horizonShiftY
                // Rising wind-drifting volcanic smoke & ash plumes behind crater rim
                drawVolcanicSmokePlume(canvas, width, height, density, focalX, cy, isDay, lavaStr, lightColor, windDir, steadyWind, timeSec, masterAlpha)
                if (lavaStr > 0.02f) {
                    drawSoftMist(canvas, focalX, cy - height * 0.014f, width * 0.26f, height * 0.15f, 0x00FF5E1A or ((masterAlpha * 0.76f * pulse * lavaStr).toInt() shl 24))
                }
                drawGradientLayer(canvas, layerPaths[2], cy, auxY + horizonShiftY + 12f, ColorUtils.blendARGB(midTone, 0xFF2A2E38.toInt(), 0.55f), 0xFF0E121C.toInt(), 0f, horizonShiftY, masterAlpha)
                drawGradientLayer(canvas, accentPath, cy, auxY + horizonShiftY + 12f, ColorUtils.blendARGB(lightColor, 0xFF4A4E5A.toInt(), 0.45f), 0xFF1C202E.toInt(), 0f, horizonShiftY, (masterAlpha * 0.75f).toInt())
                drawGradientLayer(canvas, layerPaths[4], auxY + horizonShiftY, height.toFloat(), 0xFF141620.toInt(), 0xFF04050A.toInt(), 0f, foreShiftY, masterAlpha)
                // Cooled dark basalt channel by day; sinuous glowing molten lava river + wind-drifting embers at dusk/night
                drawGlowingLavaRiverAndEmbers(canvas, width, height, density, focalX, cy, auxY + horizonShiftY, foreShiftY, lavaStr, pulse, windDir, steadyWind, timeSec, masterAlpha)
            }

            WorldBiome.HIGHLAND_LOCH -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.44f, horizonY + 4f, ColorUtils.blendARGB(circ.horizonColor, midTone, 0.35f), midTone, 0f, horizonShiftY * 0.7f, (masterAlpha * 0.78f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.50f, horizonY + 4f, midTone, deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.88f).toInt())
                // Drifting Highland glen mist veil separating the castle ruin cleanly from the dark hills behind it
                val glenMistDrift = sin(timeSec * 0.12).toFloat() * width * 0.06f
                drawSoftMist(canvas, width * 0.36f + glenMistDrift, horizonY - height * 0.012f, width * 0.72f, height * 0.058f, mistRgb or ((masterAlpha * if (isDay) 0.46f else 0.28f).toInt() shl 24))
                val lochTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.24f else 0.70f)
                val lochBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.72f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), lochTop, lochBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.92f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
                // Gentle drifting horizontal loch ripples
                val ripCol = ColorUtils.blendARGB(lightColor, 0xFFDCE6F2.toInt(), if (isDay) 0.45f else 0.18f)
                strokePaint.color = ripCol; strokePaint.strokeWidth = density * 0.95f
                for (r in 0 until 7) {
                    val rPhase = (((timeSec * (0.016 + (r % 3) * 0.004) * windDir + r * 0.17) % 1.0 + 1.0) % 1.0).toFloat()
                    val rx = width * (0.08f + rPhase * 0.84f)
                    val ry = horizonY + height * (0.07f + r * 0.045f) + sin(timeSec * 0.8 + r).toFloat() * density * 0.8f
                    val rLen = width * (0.045f + (r % 3) * 0.025f)
                    strokePaint.alpha = (masterAlpha * 0.22f * sin(rPhase * PI).toFloat().coerceIn(0f, 1f)).toInt()
                    canvas.drawLine(rx - rLen, ry, rx + rLen, ry, strokePaint)
                }
                // Soft mirrored castle reflection on the loch below the promontory
                val casBaseY = focalY + horizonShiftY
                val casH = height * 0.056f
                fillPaint.color = deepVelvet; fillPaint.alpha = (masterAlpha * 0.20f).toInt()
                for (band in 0 until 5) {
                    val byRef = casBaseY + height * 0.032f + band * density * 4.2f
                    val wob = sin(timeSec * 1.4 + band * 1.2).toFloat() * density * 2.5f
                    val halfW = casH * (0.48f - band * 0.06f)
                    canvas.drawRect(focalX - halfW + wob, byRef, focalX + halfW + wob, byRef + density * 2.0f, fillPaint)
                }
                // Rocky promontory + high-contrast weathered Kilchurn castle ruin
                drawGradientLayer(canvas, layerPaths[2], horizonY - height * 0.02f, horizonY + height * 0.05f, ColorUtils.blendARGB(midTone, deepVelvet, 0.56f), deepVelvet, 0f, horizonShiftY, masterAlpha)
                drawHighlandCastleRuin(canvas, focalX, casBaseY, casH, density, isDay, lightColor, deepVelvet, sky.sunAltDeg, timeSec, masterAlpha)
                // Foreground heather knolls + organic wind-swaying Highland Scots pine
                drawGradientLayer(canvas, layerPaths[4], height * 0.76f, height.toFloat(), deepVelvet, 0xFF03040A.toInt(), 0f, foreShiftY, masterAlpha)
                val pineSway = (sin(timeSec * 0.78) * 0.7 + cos(timeSec * 1.56) * 0.3).toFloat() * density * (2.6f + steadyWind * 0.08f)
                drawSwayingScotsPine(canvas, width * 0.86f, height * 0.854f + foreShiftY + density * 3f, height * 0.102f, pineSway, deepVelvet, masterAlpha)
            }

            WorldBiome.LAVENDER_PROVENCE -> {
                drawGradientLayer(canvas, layerPaths[0], height * 0.50f, height * 0.66f, ColorUtils.blendARGB(circ.horizonColor, 0xFF9A8AB8.toInt(), 0.35f), midTone, 0f, horizonShiftY * 0.6f, (masterAlpha * 0.78f).toInt())
                drawGradientLayer(canvas, layerPaths[1], height * 0.56f, height * 0.72f, ColorUtils.blendARGB(midTone, 0xFF746898.toInt(), 0.45f), deepVelvet, 0f, midShiftY * 0.5f, masterAlpha)
                // Multi-toned Provençal stone mas (sunlit limestone, shaded gable, terracotta roof & glowing windows)
                drawProvenceMas(canvas, focalX, focalY + midShiftY * 0.5f, height * 0.038f, density, isDay, lightColor, deepVelvet, sky.sunAltDeg, masterAlpha)
                // Swaying Mediterranean cypresses flanking the stone mas
                val cypCol = ColorUtils.blendARGB(deepVelvet, 0xFF1A362E.toInt(), if (isDay) 0.45f else 0.15f)
                val sway = (sin(timeSec * 0.85) * 0.72 + cos(timeSec * 1.70) * 0.28).toFloat() * density * (1.8f + steadyWind * 0.06f) * windDir
                for (idx in cypressBaseX.indices) {
                    drawSwayingCypress(canvas, width * cypressBaseX[idx], focalY + midShiftY * 0.5f + height * 0.01f, height * 0.072f * cypressScale[idx], sway * (0.8f + idx * 0.1f), cypCol, masterAlpha)
                }
                // Base soil + graceful S-curved sunlit/shadowed lavender rows with breeze shimmer
                drawGradientLayer(canvas, layerPaths[3], height * 0.65f, height.toFloat(), ColorUtils.blendARGB(deepVelvet, 0xFF342246.toInt(), 0.48f), 0xFF0A0816.toInt(), 0f, foreShiftY * 0.7f, masterAlpha)
                drawLavenderRows(canvas, width, height, density, midShiftY * 0.5f, isDay, lightColor, deepVelvet, steadyWind, timeSec, masterAlpha)
            }

            WorldBiome.SANTORINI_CLIFFSIDE -> {
                drawGradientLayer(canvas, layerPaths[0], horizonY - height * 0.03f, horizonY + 4f, ColorUtils.blendARGB(midTone, lightColor, 0.22f), deepVelvet, 0f, horizonShiftY, (masterAlpha * 0.70f).toInt())
                val seaTop = ColorUtils.blendARGB(circ.horizonColor, deepVelvet, if (isDay) 0.20f else 0.68f)
                val seaBot = ColorUtils.blendARGB(circ.primary, 0xFF040710.toInt(), 0.72f)
                fillPaint.shader = getCachedVerticalGradient(horizonY, height.toFloat(), seaTop, seaBot)
                fillPaint.alpha = masterAlpha; canvas.drawRect(0f, horizonY, width.toFloat(), height.toFloat(), fillPaint); fillPaint.shader = null
                drawWaterShimmer(canvas, width, height, density, horizonY, height * 0.92f, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)
                // Gliding Aegean sailboat with swell pitch & wake ripples (naturally occluded behind caldera cliff)
                drawUnpredictableSailboat(canvas, width, height, horizonY, density, dayOfYear, isDay, windDir, steadyWind, timeSec, masterAlpha)
                // Layered volcanic caldera cliff with warm rust-ochre rock bands
                val cliffShift = midShiftY * 0.75f
                val cliffTopCol = ColorUtils.blendARGB(midTone, if (isDay) 0xFF72443A.toInt() else 0xFF282230.toInt(), if (isDay) 0.48f else 0.35f)
                drawGradientLayer(canvas, layerPaths[3], height * 0.43f, height.toFloat(), cliffTopCol, deepVelvet, 0f, cliffShift, masterAlpha)
                // Subtle volcanic rock strata facets along the steep cliff face
                strokePaint.color = ColorUtils.blendARGB(cliffTopCol, lightColor, 0.25f)
                strokePaint.alpha = (masterAlpha * if (isDay) 0.26f else 0.14f).toInt()
                strokePaint.strokeWidth = density * 2.2f
                canvas.drawLine(width * 0.46f, height * 0.85f + cliffShift, width * 0.96f, height * 0.72f + cliffShift, strokePaint)
                canvas.drawLine(width * 0.52f, height * 0.74f + cliffShift, width * 0.98f, height * 0.61f + cliffShift, strokePaint)

                // Whitewashed Cycladic terraces + 3 cobalt-blue chapel domes
                val wallCol = if (isDay) 0xFFF7F5F0.toInt() else ColorUtils.blendARGB(0xFF5E6C88.toInt(), lightColor, 0.25f)
                val domeCol = if (isDay) 0xFF1A58B0.toInt() else 0xFF183668.toInt()
                drawGradientLayer(canvas, layerPaths[2], height * 0.42f, height * 0.66f, wallCol, ColorUtils.blendARGB(wallCol, deepVelvet, 0.26f), 0f, cliffShift, masterAlpha)
                drawGradientLayer(canvas, accentPath, height * 0.42f, height * 0.56f, domeCol, ColorUtils.blendARGB(domeCol, deepVelvet, 0.24f), 0f, cliffShift, masterAlpha)

                // Crisp white Cycladic crosses atop all 3 cobalt domes + bougainvillea terrace accents + rotating Oia windmill
                drawSantoriniDetails(canvas, width, height, density, cliffShift, isDay, lightColor, deepVelvet, sky.sunAltDeg, steadyWind, timeSec, masterAlpha)
            }
        }
        } finally {
            fillPaint.color = frameFillColor
            fillPaint.alpha = frameFillAlpha
            fillPaint.style = frameFillStyle
            fillPaint.shader = frameFillShader
            fillPaint.strokeWidth = frameFillStrokeWidth
            fillPaint.strokeCap = frameFillStrokeCap
            strokePaint.color = frameStrokeColor
            strokePaint.alpha = frameStrokeAlpha
            strokePaint.style = frameStrokeStyle
            strokePaint.shader = frameStrokeShader
            strokePaint.strokeWidth = frameStrokeWidth
            strokePaint.strokeCap = frameStrokeCap
            mistPaint.color = frameMistColor
            mistPaint.alpha = frameMistAlpha
            mistPaint.style = frameMistStyle
            mistPaint.shader = frameMistShader
            mistPaint.strokeWidth = frameMistStrokeWidth
            mistPaint.strokeCap = frameMistStrokeCap
        }

    }

    private fun drawOceanSurfaceAndLife(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float,
        sky: SkySnapshot, weather: WeatherState, orbX: Float, isDay: Boolean,
        windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int, showBoat: Boolean
    ) {
        val shoreY = h * (0.85f + 0.025f * ((horizonY - h * 0.64f) / (h * 0.05f)) - sky.tideFrac * 0.018f)
        drawWaterShimmer(canvas, w, h, density, horizonY, shoreY, sky, weather, orbX, isDay, windDir, steadyWind, timeSec, masterAlpha)

        val isBiolum = !isDay && sky.moonIllumination < 0.08f && weather.sky == SkyWeather.CLEAR
        val swellRgb = if (isDay) 0x00E0F2FE else (if (isBiolum) 0x002DD4BF else 0x0094A3B8)
        val foamRgb = if (isDay) 0x00F8FAFC else (if (isBiolum) 0x005EEAD4 else 0x00CBD5E1)
        // Ocean swell responds to gusts as well as sustained wind. Blend gusts
        // conservatively so brief bursts add texture without making waves jitter.
        val effectiveSeaWind = max(steadyWind, weather.windGustsKmh * 0.45f).coerceIn(0f, 70f)
        val surfEnergy = (0.76f + (effectiveSeaWind / 36f) + (sky.tideFrac * 0.15f).coerceIn(0f, 0.15f)).coerceIn(0.72f, 1.45f)
        val waveSpeed = 0.024 + (effectiveSeaWind / 800.0).coerceIn(0.0, 0.015)
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
            // The boat shares fillPaint with other landscape layers. Isolate its
            // state so the following biome/foreground draw is not affected.
            val oldFillColor = fillPaint.color
            val oldFillAlpha = fillPaint.alpha
            val oldFillStyle = fillPaint.style
            val oldFillShader = fillPaint.shader
            val oldFillWidth = fillPaint.strokeWidth
            val oldFillCap = fillPaint.strokeCap
            try {
                fillPaint.shader = null
                fillPaint.style = Paint.Style.FILL
                val boatSpeed = (0.0018 + (steadyWind / 60.0).coerceIn(0.0, 1.0) * 0.0010) * windDir.toDouble()
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
            } finally {
                fillPaint.color = oldFillColor
                fillPaint.alpha = oldFillAlpha
                fillPaint.style = oldFillStyle
                fillPaint.shader = oldFillShader
                fillPaint.strokeWidth = oldFillWidth
                fillPaint.strokeCap = oldFillCap
            }
        }
        if (sky.sunAltDeg > -2f) drawBirds(canvas, w, h, density, windDir, timeSec, masterAlpha)
    }

    // Asymmetrical Bottom-Left 2-Layer Feathery Reeds (right side & sea uncluttered!)
    private fun drawLeftCornerReeds(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        midCol: Int, foreCol: Int, lightColor: Int, isDay: Boolean, windDir: Float, steadyWind: Float,
        timeSec: Double, masterAlpha: Int
    ) {
        val oldColor = strokePaint.color
        val oldAlpha = strokePaint.alpha
        val oldWidth = strokePaint.strokeWidth
        val oldCap = strokePaint.strokeCap
        val oldStyle = strokePaint.style
        val oldShader = strokePaint.shader
        try {
            strokePaint.shader = null
            strokePaint.style = Paint.Style.STROKE
            val swayFreq = 0.68 + (steadyWind / 48.0).coerceIn(0.0, 0.42)
            val windMotion = (0.10f + (steadyWind / 35f).coerceIn(0f, 1f) * 0.90f)
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
                    val sway = sin(timeSec * swayFreq + r * 0.72 + pass).toFloat() * (density * (6.0f + steadyWind * 0.18f)) * windMotion + windLean
                    val tipX = bx + arch + sway
                    val tipY = by - rh
                    val ctrlX = bx + (arch + sway) * 0.32f
                    val ctrlY = by - rh * 0.62f
                    strokePaint.color = stemC; strokePaint.alpha = (masterAlpha * (if (pass == 0) 0.72f else 0.92f)).toInt()
                    strokePaint.strokeWidth = density * (if (pass == 0) 1.1f else 1.5f)
                    dynPath.reset(); dynPath.moveTo(bx, by); dynPath.quadTo(ctrlX, ctrlY, tipX, tipY); canvas.drawPath(dynPath, strokePaint)
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
        } finally {
            strokePaint.color = oldColor; strokePaint.alpha = oldAlpha; strokePaint.strokeWidth = oldWidth
            strokePaint.strokeCap = oldCap; strokePaint.style = oldStyle; strokePaint.shader = oldShader
        }
    }


    private fun drawSwayingGrass(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        baseY: Float, color: Int, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int,
        bladeCount: Int = 30
    ) {
        val oldColor = strokePaint.color; val oldAlpha = strokePaint.alpha; val oldWidth = strokePaint.strokeWidth
        val oldCap = strokePaint.strokeCap; val oldStyle = strokePaint.style; val oldShader = strokePaint.shader
        try {
            strokePaint.shader = null; strokePaint.style = Paint.Style.STROKE
            val swayFreq = 0.72 + (steadyWind / 48.0).coerceIn(0.0, 0.42)
            val windMotion = (0.10f + (steadyWind / 35f).coerceIn(0f, 1f) * 0.90f)
            val windLean = (steadyWind / 60f) * density * 9f * windDir
            strokePaint.color = color; strokePaint.alpha = (masterAlpha * 0.90f).toInt(); strokePaint.strokeWidth = density * 1.4f
            for (b in 0 until bladeCount) {
                val bx = w * (0.10f + 0.80f * seedRand(dayOfYear + b, 131))
                val by = baseY + h * (0.005f + 0.055f * seedRand(dayOfYear + b, 137))
                val bh = h * (0.032f + 0.032f * seedRand(dayOfYear + b, 149))
                val sway = sin(timeSec * swayFreq + b * 0.65).toFloat() * (density * (3.2f + steadyWind * 0.12f)) * windMotion + windLean
                dynPath.reset(); dynPath.moveTo(bx, by); dynPath.quadTo(bx + sway * 0.35f, by - bh * 0.55f, bx + sway, by - bh)
                canvas.drawPath(dynPath, strokePaint)
            }
        } finally {
            strokePaint.color = oldColor; strokePaint.alpha = oldAlpha; strokePaint.strokeWidth = oldWidth
            strokePaint.strokeCap = oldCap; strokePaint.style = oldStyle; strokePaint.shader = oldShader
        }
    }


    private fun drawWoodlandMushrooms(
        canvas: Canvas, w: Int, h: Int, density: Float, dayOfYear: Int,
        bankY: Float, sky: SkySnapshot, lightColor: Int, timeSec: Double, masterAlpha: Int
    ) {
        val oldStrokeColor = strokePaint.color; val oldStrokeAlpha = strokePaint.alpha; val oldStrokeWidth = strokePaint.strokeWidth
        val oldStrokeCap = strokePaint.strokeCap; val oldStrokeStyle = strokePaint.style; val oldStrokeShader = strokePaint.shader
        val oldFillColor = fillPaint.color; val oldFillAlpha = fillPaint.alpha; val oldFillStyle = fillPaint.style; val oldFillShader = fillPaint.shader
        val oldFillWidth = fillPaint.strokeWidth; val oldFillCap = fillPaint.strokeCap
        try {
            strokePaint.shader = null; strokePaint.style = Paint.Style.STROKE
            fillPaint.shader = null; fillPaint.style = Paint.Style.FILL
            val nightGlow = ((-sky.sunAltDeg - 0.5f) / 6f).coerceIn(0f, 1f)
            for (m in 0 until 4) {
                val mx = w * (0.32f + m * 0.12f)
                val my = bankY + h * (0.008f + 0.018f * (m % 2))
                val capR = density * (4.2f + 1.6f * (m % 2)
                )
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
        } finally {
            strokePaint.color = oldStrokeColor; strokePaint.alpha = oldStrokeAlpha; strokePaint.strokeWidth = oldStrokeWidth
            strokePaint.strokeCap = oldStrokeCap; strokePaint.style = oldStrokeStyle; strokePaint.shader = oldStrokeShader
            fillPaint.color = oldFillColor; fillPaint.alpha = oldFillAlpha; fillPaint.style = oldFillStyle; fillPaint.shader = oldFillShader
            fillPaint.strokeWidth = oldFillWidth; fillPaint.strokeCap = oldFillCap
        }
    }


    private fun drawCoveFish(canvas: Canvas, w: Int, h: Int, density: Float, botWater: Int, isDay: Boolean, timeSec: Double, masterAlpha: Int) {
        val oldColor = fillPaint.color; val oldAlpha = fillPaint.alpha; val oldStyle = fillPaint.style; val oldShader = fillPaint.shader
        val oldWidth = fillPaint.strokeWidth; val oldCap = fillPaint.strokeCap
        try {
            fillPaint.shader = null; fillPaint.style = Paint.Style.FILL
            fillPaint.color = if (isDay) ColorUtils.blendARGB(0xFF062C33.toInt(), botWater, 0.35f) else 0xFF020617.toInt()
            fillPaint.alpha = (masterAlpha * (if (isDay) 0.56f else 0.30f)).toInt()
            for (f in 0 until 6) {
                // Independent crossing cycle per fish: unpredictable direction, depth, and dart speed
                val baseSpeed = 0.016 + (f % 3) * 0.0065 + (f / 3) * 0.0045
                val rawT = timeSec * baseSpeed + f * 1.618
                val lap = kotlin.math.floor(rawT).toInt()
                val u = (rawT - lap).toFloat()
                // Smooth occasional dart burst without jumping backward
                val dartU = (u + 0.035f * sin(u * PI * 3.0 + f * 1.7).toFloat()).coerceIn(0f, 1f)
                val dir = if (seedRand(lap + f * 13, 29) > 0.5f) 1f else -1f
                val fx = if (dir > 0f) (-0.08f + dartU * 1.16f) * w else (1.08f - dartU * 1.16f) * w
                val baseDepth = 0.71f + seedRand(lap + f * 17, 47) * 0.21f
                val curveY = sin(u * PI * (1.4 + (f % 2) * 0.6) + f * 2.1).toFloat() * (h * 0.024f)
                val fy = h * baseDepth + curveY
                val len = density * (9.0f + seedRand(lap + f * 7, 19) * 5.2f)
                val tailRate = 5.2 + (f % 3) * 1.4
                val tailWag = sin(timeSec * tailRate + f * 1.3).toFloat() * (len * 0.26f)
                val edgeFade = (sin(u * PI).toFloat().coerceIn(0f, 1f)).pow(0.30f)
                fillPaint.alpha = (masterAlpha * (if (isDay) 0.58f else 0.32f) * edgeFade).toInt()
                dynPath.reset(); dynPath.moveTo(fx + len * 0.55f * dir, fy)
                dynPath.quadTo(fx, fy - len * 0.26f, fx - len * 0.45f * dir, fy + tailWag * 0.3f)
                dynPath.lineTo(fx - len * 0.76f * dir, fy - len * 0.24f + tailWag)
                dynPath.lineTo(fx - len * 0.76f * dir, fy + len * 0.24f + tailWag)
                dynPath.lineTo(fx - len * 0.45f * dir, fy + tailWag * 0.3f)
                dynPath.quadTo(fx, fy + len * 0.26f, fx + len * 0.55f * dir, fy); dynPath.close()
                canvas.drawPath(dynPath, fillPaint)
                // Occasional subtle surface ripple when a fish rises near the upper shallows
                if (baseDepth < 0.77f && u in 0.25f..0.75f) {
                    val rProg = ((timeSec * 0.55 + f * 0.31) % 1.0).toFloat()
                    strokePaint.color = if (isDay) 0xFFD8F6FA.toInt() else 0xFF7DD3FC.toInt()
                    strokePaint.alpha = (masterAlpha * 0.26f * (1f - rProg) * edgeFade).toInt()
                    strokePaint.strokeWidth = density * 0.9f
                    rect.set(fx - len * (0.6f + rProg * 1.4f), fy - len * (0.18f + rProg * 0.38f), fx + len * (0.6f + rProg * 1.4f), fy + len * (0.18f + rProg * 0.38f))
                    canvas.drawOval(rect, strokePaint)
                }
            }
        } finally {
            fillPaint.color = oldColor; fillPaint.alpha = oldAlpha; fillPaint.style = oldStyle; fillPaint.shader = oldShader
            fillPaint.strokeWidth = oldWidth; fillPaint.strokeCap = oldCap
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
        val previousColor = fillPaint.color
        val previousAlpha = fillPaint.alpha
        val previousStyle = fillPaint.style
        val previousShader = fillPaint.shader
        val previousWidth = fillPaint.strokeWidth
        val previousCap = fillPaint.strokeCap
        try {
            fillPaint.shader = null
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = color
            fillPaint.alpha = alpha.coerceIn(0, 255)
            dynPath.reset()
            dynPath.moveTo(bx, by); dynPath.quadTo(bx - ww * 1.05f, by - wingSpan * 0.95f, bx - ww * 0.85f, by - wingSpan * 0.05f); dynPath.close()
            dynPath.moveTo(bx, by + wingSpan * 0.05f); dynPath.quadTo(bx - ww * 0.80f, by + wingSpan * 0.65f, bx - ww * 0.25f, by + wingSpan * 0.52f); dynPath.close()
            dynPath.moveTo(bx, by); dynPath.quadTo(bx + ww * 1.05f, by - wingSpan * 0.95f, bx + ww * 0.85f, by - wingSpan * 0.05f); dynPath.close()
            dynPath.moveTo(bx, by + wingSpan * 0.05f); dynPath.quadTo(bx + ww * 0.80f, by + wingSpan * 0.65f, bx + ww * 0.25f, by + wingSpan * 0.52f); dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        } finally {
            fillPaint.color = previousColor
            fillPaint.alpha = previousAlpha
            fillPaint.style = previousStyle
            fillPaint.shader = previousShader
            fillPaint.strokeWidth = previousWidth
            fillPaint.strokeCap = previousCap
        }
    }

    private fun drawWaterShimmer(
        canvas: Canvas, w: Int, h: Int, density: Float, horizonY: Float, shoreY: Float,
        sky: SkySnapshot, weather: WeatherState, orbX: Float, isDay: Boolean,
        windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)
        val shimRgb = if (sky.sunAltDeg > -4f) (sky.sunColor and 0x00FFFFFF) else (sky.moonColor and 0x00FFFFFF)
        val lumBoost = if (sky.sunAltDeg > -4f) 1.0f else (0.55f + 0.45f * sky.moonIllumination.coerceIn(0f, 1f))
        val effectiveWind = max(steadyWind, weather.windGustsKmh * 0.35f).coerceAtLeast(0f)
        val windScale = (0.75f + (effectiveWind / 28f)).coerceIn(0.75f, 1.8f)
        // Precipitation roughens the surface and suppresses polished sun/moon glints.
        // Gusts influence drift without making calm-weather animation jittery.
        val rainGlintFactor = when (weather.sky) {
            SkyWeather.RAIN -> (0.62f - weather.precipMm.coerceIn(0f, 8f) * 0.035f).coerceIn(0.34f, 0.62f)
            SkyWeather.THUNDERSTORM -> 0.28f
            else -> 1f
        }
        val windPhase = timeSec * (effectiveWind * 0.0012) * windDir.toDouble()
        val shimBreath = 0.90f + 0.10f * sin(timeSec * 0.45).toFloat()
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.24f, w * 0.085f * windScale, seaSpan * 0.30f, shimRgb or ((masterAlpha * (if (isDay) 0.22f else 0.16f) * lumBoost * shimBreath * rainGlintFactor).toInt().coerceIn(0, 255) shl 24))
        drawSoftMist(canvas, orbX, horizonY + seaSpan * 0.56f, w * 0.155f * windScale, seaSpan * 0.44f, shimRgb or ((masterAlpha * (if (isDay) 0.12f else 0.09f) * lumBoost * shimBreath * rainGlintFactor).toInt().coerceIn(0, 255) shl 24))
        for (gIdx in 0 until 4) {
            val fracY = 0.12f + gIdx * 0.19f
            val glintVis = ((sin(timeSec * (0.45 + gIdx * 0.12) * 1.618 + gIdx * 1.9) + 1.0) * 0.5).toFloat()
            val spreadX = sin(windPhase + gIdx * 0.72).toFloat() * (w * (0.018f + 0.022f * fracY) * windScale)
            val gA = (masterAlpha * (0.14f - 0.07f * fracY) * lumBoost * rainGlintFactor * (0.45f + 0.55f * glintVis)).toInt().coerceIn(0, 255)
            if (gA > 2) drawSoftMist(canvas, orbX + spreadX, horizonY + seaSpan * fracY, w * (0.038f + 0.032f * fracY) * windScale, seaSpan * (0.045f + 0.035f * fracY), shimRgb or (gA shl 24))
        }
        if (weather.sky == SkyWeather.RAIN || weather.sky == SkyWeather.THUNDERSTORM) {
            val previousColor = strokePaint.color
            val previousAlpha = strokePaint.alpha
            val previousWidth = strokePaint.strokeWidth
            val previousCap = strokePaint.strokeCap
            val previousStyle = strokePaint.style
            val previousShader = strokePaint.shader
            try {
                strokePaint.shader = null
                strokePaint.style = Paint.Style.STROKE
                strokePaint.strokeCap = Paint.Cap.ROUND
                strokePaint.strokeWidth = density * 0.8f
                strokePaint.color = 0xFFBAE6FD.toInt()
                // Make rain ripples respond to precipitation instead of using a
                // fixed intensity for drizzle and heavy storms alike.
                val rippleStrength = when (weather.sky) {
                    SkyWeather.RAIN -> (0.12f + weather.precipMm.coerceIn(0f, 8f) * 0.035f).coerceIn(0.12f, 0.40f)
                    SkyWeather.THUNDERSTORM -> 0.44f
                    else -> 0f
                }
                for (rp in 0 until 4) {
                    val hRp = abs(sin(rp * 12.9898 + 1.3)).toFloat()
                    val rpProg = (((timeSec * (0.50 + hRp * 0.20)) + rp * 0.25 + hRp * 0.31) % 1.0).toFloat()
                    val rx = w * (0.12f + 0.76f * hRp); val ry = horizonY + seaSpan * (0.22f + 0.54f * abs(sin(rp * 78.233)).toFloat())
                    val rad = w * (0.01f + rpProg * 0.035f)
                    strokePaint.alpha = (masterAlpha * rippleStrength * (1f - rpProg)).toInt().coerceIn(0, 255)
                    rect.set(rx - rad, ry - rad * 0.28f, rx + rad, ry + rad * 0.28f); canvas.drawOval(rect, strokePaint)
                }
            } finally {
                strokePaint.color = previousColor
                strokePaint.alpha = previousAlpha
                strokePaint.strokeWidth = previousWidth
                strokePaint.strokeCap = previousCap
                strokePaint.style = previousStyle
                strokePaint.shader = previousShader
            }
        }
    }

    private fun drawDuneDust(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, steadyWind: Float, gustWind: Float, timeSec: Double, masterAlpha: Int) {
        val count = 14
        // Brief gusts can lift sand even when the sustained wind is below threshold.
        // Blend conservatively and clamp to keep the particle drift stable.
        val wind = max(steadyWind, gustWind * 0.45f).coerceIn(8f, 55f)
        val driftSpeed = 0.018 + wind * 0.00055
        val visibility = ((wind - 8f) / 42f).coerceIn(0f, 1f)
        val oldColor = strokePaint.color
        val oldAlpha = strokePaint.alpha
        val oldWidth = strokePaint.strokeWidth
        val oldCap = strokePaint.strokeCap
        val oldStyle = strokePaint.style
        val oldShader = strokePaint.shader
        try {
            strokePaint.shader = null
            strokePaint.style = Paint.Style.STROKE
            strokePaint.color = 0xFFFFE0A3.toInt()
            strokePaint.strokeWidth = (density * 0.55f).coerceAtLeast(0.65f)
            strokePaint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until count) {
                val seed = i * 29.41f + 7.9f
                val baseX = (abs(sin(seed * 1.37f)) * 1.08f - 0.04f) * w
                val baseY = 0.50f + abs(sin(seed * 2.11f)) * 0.30f
                val phase = ((timeSec * driftSpeed + i * 0.137) % 1.0 + 1.0) % 1.0
                val x = ((baseX + phase.toFloat() * w * 0.16f * windDir) % (w * 1.12f) + w * 1.12f) % (w * 1.12f) - w * 0.06f
                val y = baseY * h + sin(timeSec * 0.7 + seed).toFloat() * density * 2.2f
                val alpha = (masterAlpha * (0.10f + visibility * 0.16f) * (0.45f + (i % 4) * 0.12f)).toInt().coerceIn(0, 70)
                strokePaint.alpha = alpha
                val length = density * (1.4f + visibility * 2.8f)
                canvas.drawLine(x, y, x + length * windDir, y + length * 0.22f, strokePaint)
            }
        } finally {
            strokePaint.color = oldColor
            strokePaint.alpha = oldAlpha
            strokePaint.strokeWidth = oldWidth
            strokePaint.strokeCap = oldCap
            strokePaint.style = oldStyle
            strokePaint.shader = oldShader
        }
    }

    private fun drawSnowFlurries(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, steadyWind: Float, gustWind: Float, timeSec: Double, masterAlpha: Int) {
        val flakeCount = 24
        val effectiveWind = max(steadyWind, gustWind * 0.50f).coerceIn(0f, 55f)
        val driftSpeed = (0.018 + effectiveWind * 0.00055) * windDir
        val oldColor = strokePaint.color
        val oldAlpha = strokePaint.alpha
        val oldWidth = strokePaint.strokeWidth
        val oldCap = strokePaint.strokeCap
        val oldStyle = strokePaint.style
        val oldShader = strokePaint.shader
        try {
            strokePaint.shader = null
            strokePaint.style = Paint.Style.FILL
            strokePaint.color = 0xFFEAF4FF.toInt()
            for (i in 0 until flakeCount) {
                val depth = 0.45f + (i % 4) * 0.22f // 0.45 (far) .. 1.11 (near)
                val fallSpeed = (0.028 + (i % 5) * 0.0075) * depth
                val phaseY = (((timeSec * fallSpeed + i * 0.193) % 1.0) + 1.0) % 1.0
                val y = (phaseY.toFloat() * 1.08f - 0.04f) * h
                val baseX = seedRand(i + 17, 53)
                val flutter = sin(timeSec * (0.65 + (i % 4) * 0.16) + i * 2.13).toFloat() * (0.016f * depth)
                val xNorm = ((((baseX + (timeSec * driftSpeed * depth).toFloat() + flutter) % 1.12f) + 1.12f) % 1.12f) - 0.06f
                val x = xNorm * w
                val fadeEnv = (sin(phaseY * PI).toFloat().coerceIn(0f, 1f)).pow(0.38f)
                val radius = density * (0.65f + (i % 3) * 0.42f) * depth
                val alpha = (masterAlpha * (0.20f + depth * 0.24f) * fadeEnv).toInt().coerceIn(0, 165)
                strokePaint.alpha = alpha
                canvas.drawCircle(x, y, radius, strokePaint)
            }
        } finally {
            strokePaint.color = oldColor
            strokePaint.alpha = oldAlpha
            strokePaint.strokeWidth = oldWidth
            strokePaint.strokeCap = oldCap
            strokePaint.style = oldStyle
            strokePaint.shader = oldShader
        }
    }

    private fun drawThermalEagles(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, timeSec: Double, masterAlpha: Int) {
        val oldColor = strokePaint.color
        val oldAlpha = strokePaint.alpha
        val oldWidth = strokePaint.strokeWidth
        val oldCap = strokePaint.strokeCap
        val oldStyle = strokePaint.style
        val oldShader = strokePaint.shader
        try {
            strokePaint.shader = null
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = density * 1.35f; strokePaint.color = 0xFF1E293B.toInt()
            for (e in 0 until 3) {
                val speed = 0.011 + e * 0.0045
                val dir = if (e % 2 == 0) windDir else -windDir
                val rawT = timeSec * speed + e * 1.73
                val lap = kotlin.math.floor(rawT).toInt()
                val frac = (rawT - lap).toFloat()
                if (frac > 0.84f) continue
                val u = frac / 0.84f
                val ex = if (dir >= 0f) (-0.08f + u * 1.16f) * w else (1.08f - u * 1.16f) * w
                val alt = 0.16f + seedRand(lap + e * 23, 41) * 0.20f
                val ey = h * alt + sin(u * PI * 1.5 + e * 1.4).toFloat() * (h * 0.028f)
                val span = w * (0.020f + seedRand(lap + e * 11, 19) * 0.007f)
                val glide = sin(timeSec * (0.75 + e * 0.2) + e * 1.7).toFloat() * (span * 0.18f)
                val edgeFade = (sin(u * PI).toFloat().coerceIn(0f, 1f)).pow(0.35f)
                strokePaint.alpha = (masterAlpha * 0.48f * edgeFade).toInt().coerceIn(0, 255)
                canvas.drawLine(ex - span, ey - glide, ex, ey + span * 0.15f, strokePaint)
                canvas.drawLine(ex, ey + span * 0.15f, ex + span, ey - glide, strokePaint)
            }
        } finally {
            strokePaint.color = oldColor
            strokePaint.alpha = oldAlpha
            strokePaint.strokeWidth = oldWidth
            strokePaint.strokeCap = oldCap
            strokePaint.style = oldStyle
            strokePaint.shader = oldShader
        }
    }

    private fun drawBirds(canvas: Canvas, w: Int, h: Int, density: Float, windDir: Float, timeSec: Double, masterAlpha: Int) {
        val oldColor = strokePaint.color
        val oldAlpha = strokePaint.alpha
        val oldWidth = strokePaint.strokeWidth
        val oldCap = strokePaint.strokeCap
        val oldStyle = strokePaint.style
        val oldShader = strokePaint.shader
        try {
            strokePaint.shader = null
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = density * 1.05f; strokePaint.color = 0xFF0F172A.toInt()
            for (s in 0 until 5) {
                // Independent per-bird speed, direction, and crossing cycle so birds never bunch in one spot
                val speed = 0.014 + (s % 3) * 0.0065 + (s / 3) * 0.0045
                val dir = if (s % 2 == 0) windDir else -windDir
                val rawT = timeSec * speed + s * 1.37
                val lap = kotlin.math.floor(rawT).toInt()
                val frac = (rawT - lap).toFloat()
                // Only show bird during active crossing window (0..0.82), creating natural unpredictable pauses
                if (frac > 0.82f) continue
                val u = frac / 0.82f
                val sx = if (dir >= 0f) (-0.08f + u * 1.16f) * w else (1.08f - u * 1.16f) * w
                val lapAlt = 0.14f + seedRand(lap + s * 19, 31) * 0.26f
                val arc = sin(u * PI * (1.2 + (s % 2) * 0.5) + s * 1.3).toFloat() * (h * 0.032f)
                val sy = h * lapAlt + arc + cos(timeSec * 0.85 + s * 2.1).toFloat() * (h * 0.008f)
                val span = w * (0.011f + seedRand(lap + s * 7, 17) * 0.007f)
                // Alternate active wingbeats with brief soaring glides
                val glideEnv = ((sin(timeSec * 0.65 + s * 2.3) + 0.35).coerceIn(0.0, 1.0)).toFloat()
                val flap = sin(timeSec * (4.6 + s * 0.55) + s * 1.4).toFloat() * (span * 0.42f) * glideEnv
                val edgeFade = (sin(u * PI).toFloat().coerceIn(0f, 1f)).pow(0.35f)
                strokePaint.alpha = (masterAlpha * 0.46f * edgeFade).toInt().coerceIn(0, 255)
                canvas.drawLine(sx - span, sy - flap, sx, sy + span * 0.18f, strokePaint)
                canvas.drawLine(sx, sy + span * 0.18f, sx + span, sy - flap, strokePaint)
            }
        } finally {
            strokePaint.color = oldColor
            strokePaint.alpha = oldAlpha
            strokePaint.strokeWidth = oldWidth
            strokePaint.strokeCap = oldCap
            strokePaint.style = oldStyle
            strokePaint.shader = oldShader
        }
    }

    private fun addAcaciaTree(path: Path, bx: Float, by: Float, h: Float, seed: Int) {
        val forkY = by - h * 0.52f
        val topY = by - h
        path.moveTo(bx - h * 0.055f, by + 4f); path.lineTo(bx - h * 0.025f, forkY)
        path.lineTo(bx + h * 0.025f, forkY); path.lineTo(bx + h * 0.055f, by + 4f); path.close()
        for (i in acaciaBranchTargets.indices) {
            val tx = bx + h * acaciaBranchTargets[i]
            val ty = topY + h * (1f - acaciaBranchHeights[i]) * 0.35f
            val bw = h * 0.022f
            path.moveTo(bx - bw, forkY); path.lineTo(tx - bw * 0.6f, ty)
            path.lineTo(tx + bw * 0.6f, ty); path.lineTo(bx + bw, forkY); path.close()
            rect.set(tx - h * 0.32f, ty - h * 0.09f, tx + h * 0.32f, ty + h * 0.06f)
            path.addOval(rect, Path.Direction.CW)
        }
        rect.set(bx - h * 0.30f, topY - h * 0.12f, bx + h * 0.30f, topY + h * 0.05f)
        path.addOval(rect, Path.Direction.CW)
    }

    private fun addCoastalPine(path: Path, bx: Float, by: Float, h: Float) {
        path.moveTo(bx - h * 0.05f, by + 4f); path.lineTo(bx + h * 0.06f, by - h * 0.76f)
        path.lineTo(bx + h * 0.11f, by - h * 0.76f); path.lineTo(bx + h * 0.05f, by + 4f); path.close()
        rect.set(bx - h * 0.38f, by - h * 0.76f, bx + h * 0.02f, by - h * 0.52f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx - h * 0.16f, by - h * 0.94f, bx + h * 0.32f, by - h * 0.66f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx + h * 0.12f, by - h * 0.88f, bx + h * 0.56f, by - h * 0.64f); path.addOval(rect, Path.Direction.CW)
    }

    private fun addWoodlandTrunk(path: Path, bx: Float, by: Float, topY: Float, bw: Float, tw: Float, lean: Float) {
        path.moveTo(bx - bw * 0.5f, by); path.lineTo(bx + lean - tw * 0.5f, topY)
        path.lineTo(bx + lean + tw * 0.5f, topY); path.lineTo(bx + bw * 0.5f, by); path.close()
    }

    private fun addFernFrond(path: Path, bx: Float, by: Float, len: Float, dir: Float) {
        path.moveTo(bx - 2f, by)
        path.quadTo(bx + dir * len * 0.45f, by - len * 0.55f, bx + dir * len * 0.85f, by - len * 0.25f)
        path.quadTo(bx + dir * len * 0.45f, by - len * 0.35f, bx + 2f, by); path.close()
    }

    private fun addToriiGate(path: Path, cx: Float, baseY: Float, h: Float, flipY: Boolean) {
        val d = if (flipY) 1f else -1f
        for (side in intArrayOf(-1, 1)) {
            path.moveTo(cx + side * h * 0.32f, baseY)
            path.lineTo(cx + side * h * 0.28f, baseY + d * h * 0.85f)
            path.lineTo(cx + side * h * 0.21f, baseY + d * h * 0.85f)
            path.lineTo(cx + side * h * 0.24f, baseY); path.close()
        }
        path.addRect(cx - h * 0.44f, minOf(baseY + d * h * 0.62f, baseY + d * h * 0.70f), cx + h * 0.44f, max(baseY + d * h * 0.62f, baseY + d * h * 0.70f), Path.Direction.CW)
        path.moveTo(cx - h * 0.58f, baseY + d * h * 0.96f); path.quadTo(cx, baseY + d * h * 0.86f, cx + h * 0.58f, baseY + d * h * 0.96f)
        path.lineTo(cx + h * 0.54f, baseY + d * h * 0.86f); path.quadTo(cx, baseY + d * h * 0.79f, cx - h * 0.54f, baseY + d * h * 0.86f); path.close()
    }

    private fun addStoneLantern(path: Path, cx: Float, baseY: Float, h: Float) {
        path.addRect(cx - h * 0.08f, baseY - h * 0.52f, cx + h * 0.08f, baseY + 4f, Path.Direction.CW)
        path.addRect(cx - h * 0.16f, baseY - h * 0.78f, cx + h * 0.16f, baseY - h * 0.52f, Path.Direction.CW)
        path.moveTo(cx - h * 0.30f, baseY - h * 0.78f); path.lineTo(cx, baseY - h); path.lineTo(cx + h * 0.30f, baseY - h * 0.78f); path.close()
    }

    private fun addFairyChimney(body: Path, lit: Path, cx: Float, by: Float, w: Float, h: Float, tilt: Float) {
        val hw = w * 0.5f; val nx = cx + tilt * w * 0.14f
        body.moveTo(cx - hw, by + 8f); body.lineTo(cx - hw * 0.54f, by - h * 0.56f)
        body.lineTo(nx - hw * 0.24f, by - h * 0.83f); body.lineTo(nx + hw * 0.24f, by - h * 0.83f)
        body.lineTo(cx + hw * 0.50f, by - h * 0.52f); body.lineTo(cx + hw, by + 8f); body.close()
        body.moveTo(nx - hw * 0.40f, by - h * 0.81f); body.lineTo(nx, by - h * 1.02f)
        body.lineTo(nx + hw * 0.42f, by - h * 0.81f); body.close()

        lit.moveTo(cx - hw, by + 8f); lit.lineTo(cx - hw * 0.54f, by - h * 0.56f)
        lit.lineTo(nx - hw * 0.24f, by - h * 0.83f); lit.lineTo(nx - hw * 0.02f, by - h * 0.83f)
        lit.lineTo(cx - hw * 0.24f, by + 8f); lit.close()
    }

    private fun addKalingaTemple(path: Path, cx: Float, by: Float, h: Float) {
        path.moveTo(cx - h * 0.22f, by + 4f); path.lineTo(cx - h * 0.19f, by - h * 0.55f)
        path.quadTo(cx - h * 0.12f, by - h * 0.88f, cx, by - h)
        path.quadTo(cx + h * 0.12f, by - h * 0.88f, cx + h * 0.19f, by - h * 0.55f)
        path.lineTo(cx + h * 0.22f, by + 4f); path.close()
        val jx = cx - h * 0.30f
        path.moveTo(jx - h * 0.16f, by + 4f); path.lineTo(jx, by - h * 0.50f); path.lineTo(jx + h * 0.16f, by + 4f); path.close()
    }

    private fun addBanyanTreeAndRoots(path: Path, bx: Float, by: Float, h: Float) {
        // Broad gnarled trunk + buttress flare
        path.moveTo(bx - h * 0.15f, by + 6f); path.lineTo(bx - h * 0.09f, by - h * 0.42f)
        path.lineTo(bx - h * 0.11f, by - h * 0.62f); path.lineTo(bx + h * 0.11f, by - h * 0.62f)
        path.lineTo(bx + h * 0.10f, by - h * 0.40f); path.lineTo(bx + h * 0.16f, by + 6f); path.close()
        // Organic 7-lobe clustered Banyan crown
        rect.set(bx - h * 0.62f, by - h * 0.96f, bx + h * 0.05f, by - h * 0.58f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx - h * 0.38f, by - h * 1.12f, bx + h * 0.35f, by - h * 0.66f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx - h * 0.05f, by - h * 1.20f, bx + h * 0.68f, by - h * 0.72f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx + h * 0.26f, by - h * 1.08f, bx + h * 0.95f, by - h * 0.66f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx + h * 0.52f, by - h * 0.95f, bx + h * 1.18f, by - h * 0.60f); path.addOval(rect, Path.Direction.CW)
        rect.set(bx + h * 0.10f, by - h * 0.92f, bx + h * 0.72f, by - h * 0.56f); path.addOval(rect, Path.Direction.CW)
    }

    private fun addTibetanMonastery(path: Path, cx: Float, by: Float, h: Float) {
        val yb = by + h * 0.35f
        path.addRect(cx - h * 0.80f, by - h * 0.32f, cx + h * 0.10f, yb, Path.Direction.CW)
        path.addRect(cx - h * 0.34f, by - h * 0.72f, cx + h * 0.40f, yb, Path.Direction.CW)
        path.addRect(cx - h * 0.16f, by - h * 0.94f, cx + h * 0.24f, by - h * 0.72f, Path.Direction.CW)
        path.moveTo(cx - h * 0.20f, by - h * 0.94f); path.lineTo(cx + h * 0.04f, by - h * 1.10f); path.lineTo(cx + h * 0.28f, by - h * 0.94f); path.close()
        path.addRect(cx + h * 0.36f, by - h * 0.48f, cx + h * 0.76f, yb, Path.Direction.CW)
    }

    private fun addLandwasserViaduct(path: Path, wf: Float, hf: Float, deckY: Float) {
        val xStart = wf * 0.05f; val xEnd = wf * 0.95f
        val span = (xEnd - xStart) / 6f; val pierHw = span * 0.10f; val archR = span * 0.5f - pierHw
        val springY = deckY + hf * 0.036f; val botY = hf * 0.95f
        path.addRect(xStart, deckY, xEnd, deckY + hf * 0.012f, Path.Direction.CW)
        for (i in 0 until 6) {
            val cx = xStart + (i + 0.5f) * span
            path.moveTo(cx - span * 0.5f, deckY + hf * 0.012f); path.lineTo(cx + span * 0.5f, deckY + hf * 0.012f)
            path.lineTo(cx + span * 0.5f, springY)
            for (k in 0..12) {
                val ang = PI * (k / 12.0)
                path.lineTo(cx + (cos(ang) * archR).toFloat(), springY - (sin(ang) * archR).toFloat())
            }
            path.lineTo(cx - span * 0.5f, springY); path.close()
        }
        for (i in 0..6) {
            val px = xStart + i * span
            path.moveTo(px - pierHw, deckY + hf * 0.012f); path.lineTo(px + pierHw, deckY + hf * 0.012f)
            path.lineTo(px + pierHw * 1.55f, botY); path.lineTo(px - pierHw * 1.55f, botY); path.close()
        }
    }

    private fun addCanyonButte(path: Path, cx: Float, by: Float, w: Float, h: Float) {
        val hw = w * 0.5f
        path.moveTo(cx - hw, by + 8f); path.lineTo(cx - hw * 0.64f, by - h * 0.35f)
        path.lineTo(cx - hw * 0.34f, by - h); path.lineTo(cx + hw * 0.30f, by - h)
        path.lineTo(cx + hw * 0.66f, by - h * 0.32f); path.lineTo(cx + hw, by + 8f); path.close()
    }

    private fun addRanchWindmillTowerAndTank(path: Path, cx: Float, by: Float, h: Float) {
        val yb = by + h * 0.10f; val topY = by - h; val bw = h * 0.18f; val tw = h * 0.04f
        path.moveTo(cx - bw, yb); path.lineTo(cx - tw, topY); path.lineTo(cx - tw + 3f, topY); path.lineTo(cx - bw + 3f, yb); path.close()
        path.moveTo(cx + bw, yb); path.lineTo(cx + tw, topY); path.lineTo(cx + tw - 3f, topY); path.lineTo(cx + bw - 3f, yb); path.close()
        path.addRect(cx - 1.5f, topY, cx + 1.5f, yb, Path.Direction.CW)
        // Tail vane
        path.moveTo(cx + h * 0.18f, topY - h * 0.02f); path.lineTo(cx + h * 0.34f, topY - h * 0.10f)
        path.lineTo(cx + h * 0.36f, topY + h * 0.04f); path.close()
        // Stock water tank
        val tx = cx + h * 0.34f; val twT = h * 0.12f
        path.addRect(tx - twT, by - h * 0.38f, tx + twT, by - h * 0.18f, Path.Direction.CW)
        path.addRect(tx - twT * 0.7f, by - h * 0.18f, tx - twT * 0.55f, yb, Path.Direction.CW)
        path.addRect(tx + twT * 0.55f, by - h * 0.18f, tx + twT * 0.7f, yb, Path.Direction.CW)
    }

    private fun addSaguaro(path: Path, cx: Float, by: Float, h: Float) {
        val r = h * 0.08f
        rect.set(cx - r, by - h, cx + r, by + 8f); path.addRoundRect(rect, r, r, Path.Direction.CW)
        rect.set(cx - r * 3.5f, by - h * 0.80f, cx - r * 1.8f, by - h * 0.48f); path.addRoundRect(rect, r * 0.7f, r * 0.7f, Path.Direction.CW)
        path.addRect(cx - r * 3.0f, by - h * 0.56f, cx, by - h * 0.46f, Path.Direction.CW)
        rect.set(cx + r * 1.8f, by - h * 0.68f, cx + r * 3.5f, by - h * 0.36f); path.addRoundRect(rect, r * 0.7f, r * 0.7f, Path.Direction.CW)
        path.addRect(cx, by - h * 0.44f, cx + r * 3.0f, by - h * 0.34f, Path.Direction.CW)
    }

    private fun addHighlandCastle(path: Path, cx: Float, by: Float, h: Float) {
        path.addRect(cx - h * 0.46f, by - h * 0.84f, cx - h * 0.08f, by + 6f, Path.Direction.CW)
        path.addRect(cx - h * 0.08f, by - h * 0.48f, cx + h * 0.42f, by + 6f, Path.Direction.CW)
        path.moveTo(cx + h * 0.04f, by - h * 0.48f); path.lineTo(cx + h * 0.24f, by - h * 0.72f); path.lineTo(cx + h * 0.42f, by - h * 0.48f); path.close()
        path.addRect(cx + h * 0.38f, by - h * 0.62f, cx + h * 0.54f, by + 6f, Path.Direction.CW)
    }

    private fun addProvenceMas(path: Path, cx: Float, by: Float, h: Float) {
        path.addRect(cx - h * 0.70f, by - h * 0.60f, cx + h * 0.85f, by + 6f, Path.Direction.CW)
        path.moveTo(cx - h * 0.78f, by - h * 0.60f); path.lineTo(cx - h * 0.10f, by - h); path.lineTo(cx + h * 0.55f, by - h * 0.60f); path.close()
    }

    private fun addSantoriniVillage(walls: Path, domes: Path, wf: Float, hf: Float) {
        val cubes = arrayOf(
            floatArrayOf(0.62f, 0.570f, 0.08f, 0.025f), floatArrayOf(0.69f, 0.538f, 0.09f, 0.029f),
            floatArrayOf(0.77f, 0.495f, 0.10f, 0.029f), floatArrayOf(0.85f, 0.468f, 0.09f, 0.027f),
            floatArrayOf(0.57f, 0.622f, 0.08f, 0.023f), floatArrayOf(0.64f, 0.602f, 0.09f, 0.025f),
            floatArrayOf(0.72f, 0.578f, 0.10f, 0.027f), floatArrayOf(0.81f, 0.548f, 0.10f, 0.027f),
            floatArrayOf(0.60f, 0.652f, 0.08f, 0.021f), floatArrayOf(0.68f, 0.635f, 0.09f, 0.021f)
        )
        for (c in cubes) {
            walls.addRect(wf * c[0], hf * c[1] - hf * c[3], wf * (c[0] + c[2]), hf * c[1], Path.Direction.CW)
        }
        val chapelSpecs = arrayOf(
            Triple(0.67f, 0.530f, 0.030f), Triple(0.80f, 0.468f, 0.036f), Triple(0.90f, 0.518f, 0.028f)
        )
        for (ch in chapelSpecs) {
            val cx = wf * ch.first; val by = hf * ch.second; val w = wf * ch.third * 1.8f; val h = hf * ch.third * 0.72f
            walls.addRect(cx - w * 0.5f, by - h, cx + w * 0.5f, by + 4f, Path.Direction.CW)
            rect.set(cx - w * 0.42f, by - h - w * 0.42f, cx + w * 0.42f, by - h + w * 0.42f)
            domes.addArc(rect, 180f, 180f)
        }
    }

    private fun drawHumanTraveler(
        canvas: Canvas, tx: Float, ty: Float, figH: Float, density: Float,
        moveDir: Float, shadowDir: Float, shadowStretch: Float, shadowAlphaFactor: Float,
        gaitPhase: Double, isDay: Boolean, masterAlpha: Int
    ) {
        // Sun-opposite shadow
        strokePaint.color = if (isDay) 0xFF6B2818.toInt() else 0xFF040812.toInt()
        strokePaint.alpha = (masterAlpha * shadowAlphaFactor).toInt().coerceIn(0, masterAlpha)
        strokePaint.strokeWidth = density * 1.25f
        canvas.drawLine(tx, ty, tx + figH * shadowStretch * shadowDir, ty + figH * 0.10f, strokePaint)

        val stride = sin(gaitPhase).toFloat() * figH * 0.25f
        val bob = abs(cos(gaitPhase).toFloat()) * figH * 0.04f
        val armSwing = -sin(gaitPhase).toFloat() * figH * 0.20f
        val lean = moveDir * figH * 0.07f
        val hipY = ty - figH * 0.43f - bob
        val shoulderY = ty - figH * 0.78f - bob
        val headY = ty - figH * 0.91f - bob

        fillPaint.color = 0xFF261212.toInt(); fillPaint.alpha = (masterAlpha * 0.95f).toInt()
        strokePaint.color = 0xFF261212.toInt(); strokePaint.alpha = (masterAlpha * 0.95f).toInt()
        // Head + desert scarf/hood & small pack on back
        canvas.drawCircle(tx + lean, headY, figH * 0.105f, fillPaint)
        canvas.drawCircle(tx - lean * 1.2f, shoulderY + figH * 0.14f, figH * 0.11f, fillPaint)
        // Forward-leaning torso
        strokePaint.strokeWidth = figH * 0.17f
        canvas.drawLine(tx, hipY, tx + lean, shoulderY, strokePaint)
        // Striding legs (front & back)
        strokePaint.strokeWidth = figH * 0.095f
        canvas.drawLine(tx, hipY, tx - stride, ty, strokePaint)
        canvas.drawLine(tx, hipY, tx + stride, ty, strokePaint)
        // Two swinging arms + angled walking staff
        strokePaint.strokeWidth = figH * 0.075f
        canvas.drawLine(tx + lean, shoulderY, tx - armSwing * 0.9f, hipY + figH * 0.04f, strokePaint)
        val handX = tx + lean + moveDir * figH * 0.16f + armSwing * 0.6f
        val handY = hipY - figH * 0.04f
        canvas.drawLine(tx + lean, shoulderY, handX, handY, strokePaint)
        strokePaint.strokeWidth = density * 0.65f
        canvas.drawLine(handX + moveDir * figH * 0.05f, shoulderY - figH * 0.10f, handX + moveDir * figH * 0.12f, ty, strokePaint)
    }

    private fun sampleMidRidgeY(xNorm: Float): Float {
        val f = (xNorm.coerceIn(0f, 1f)) * 120f
        val i0 = f.toInt().coerceIn(0, 119)
        val frac = f - i0
        return midRidgeCache[i0] * (1f - frac) + midRidgeCache[i0 + 1] * frac
    }

    private fun drawSavannaWildlife(canvas: Canvas, w: Int, h: Int, shiftY: Float, dayOfYear: Int, col: Int, masterAlpha: Int, timeSec: Double) {
        val cycleRate = 0.014
        val rawCycle = timeSec * cycleRate
        val lap = kotlin.math.floor(rawCycle).toInt()
        val frac = (rawCycle - lap).toFloat()
        val species = ((lap + dayOfYear) % 3 + 3) % 3
        val dir = if (seedRand(lap + dayOfYear, 37) > 0.5f) 1f else -1f

        when (species) {
            0 -> {
                // Asynchronous Giraffe pair: walk at varied speed, stop to graze grass, lift head, walk on
                for (g in 0 until 2) {
                    val offset = g * 0.11f
                    val uRaw = ((frac - offset).coerceIn(0f, 1f))
                    if (frac < offset || frac > 0.88f + offset * 0.5f) continue
                    // Smooth stop-and-graze window: walks 0..0.34, pauses & grazes 0.34..0.60, walks 0.60..1.0
                    val pStart = 0.30f + g * 0.08f
                    val pEnd = pStart + 0.26f
                    val walkU: Float
                    val grazeAmt: Float
                    val isWalking: Boolean
                    if (uRaw < pStart) {
                        walkU = (uRaw / pStart) * 0.44f
                        grazeAmt = 0f; isWalking = true
                    } else if (uRaw < pEnd) {
                        walkU = 0.44f
                        val gp = (uRaw - pStart) / (pEnd - pStart)
                        grazeAmt = sin(gp * PI).toFloat().coerceIn(0f, 1f)
                        isWalking = false
                    } else {
                        walkU = 0.44f + ((uRaw - pEnd) / (1f - pEnd)) * 0.56f
                        grazeAmt = 0f; isWalking = true
                    }
                    val xNorm = if (dir > 0f) (0.06f + walkU * 0.88f) else (0.94f - walkU * 0.88f)
                    val gx = xNorm * w
                    val gy = sampleMidRidgeY(xNorm) + shiftY + 1f
                    val gh = h * (if (g == 0) 0.056f else 0.042f)
                    val gait = if (isWalking) timeSec * (3.0 + g * 0.7) + g * 1.9 else 0.0
                    drawWalkingGiraffe(canvas, gx, gy, gh, dir, gait, grazeAmt, col, masterAlpha, timeSec + g * 2.1)
                }
            }
            1 -> {
                // African Elephant striding steadily across the savanna ridge
                if (frac in 0.04f..0.96f) {
                    val u = (frac - 0.04f) / 0.92f
                    val xNorm = if (dir > 0f) (-0.06f + u * 1.12f) else (1.06f - u * 1.12f)
                    val ex = xNorm * w
                    val ey = sampleMidRidgeY(xNorm.coerceIn(0f, 1f)) + shiftY + 2f
                    drawWalkingElephant(canvas, ex, ey, h * 0.044f, dir, timeSec * 2.1, col, masterAlpha)
                }
            }
            else -> {
                // Pair of graceful antelopes trotting & occasionally bounding across the ridge
                for (a in 0 until 2) {
                    val rawU = frac * 1.25f - a * 0.09f
                    if (rawU !in 0.0f..1.0f) continue
                    val xNorm = if (dir > 0f) (-0.05f + rawU * 1.10f) else (1.05f - rawU * 1.10f)
                    val ax = xNorm * w
                    val bound = max(0f, sin(timeSec * (4.2 + a * 0.6) + a * 1.7).toFloat()) * (h * 0.008f)
                    val ay = sampleMidRidgeY(xNorm.coerceIn(0f, 1f)) + shiftY - bound
                    drawAntelope(canvas, ax, ay, h * (0.026f - a * 0.003f), dir, timeSec * (5.2 + a * 0.6), col, masterAlpha)
                }
            }
        }
    }

    private fun drawWalkingGiraffe(canvas: Canvas, x: Float, y: Float, h: Float, dir: Float, gait: Double, grazeAmt: Float, col: Int, masterAlpha: Int, timeSec: Double) {
        val stride = sin(gait).toFloat() * h * 0.14f
        val headBob = sin(timeSec * 1.4).toFloat() * h * 0.025f
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        strokePaint.color = col; strokePaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(x - h * 0.18f * dir, y - h * 0.34f)
        dynPath.lineTo(x + h * 0.16f * dir, y - h * 0.46f)
        dynPath.lineTo(x + h * 0.20f * dir, y - h * 0.32f)
        dynPath.lineTo(x - h * 0.14f * dir, y - h * 0.24f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        // Neck bends smoothly forward & down to grass when grazeAmt -> 1f
        val nx = x + (h * 0.32f + grazeAmt * h * 0.22f) * dir
        val ny = y - h * 0.96f + grazeAmt * h * 0.84f + headBob
        strokePaint.strokeWidth = h * 0.062f
        canvas.drawLine(x + h * 0.14f * dir, y - h * 0.42f, nx, ny, strokePaint)
        canvas.drawLine(nx, ny, nx + h * 0.11f * dir, ny + h * 0.04f, strokePaint)
        // 4 striding legs
        strokePaint.strokeWidth = h * 0.034f
        canvas.drawLine(x - h * 0.15f * dir, y - h * 0.26f, x - h * 0.16f * dir - stride, y + 3f, strokePaint)
        canvas.drawLine(x - h * 0.08f * dir, y - h * 0.26f, x - h * 0.07f * dir + stride, y + 3f, strokePaint)
        canvas.drawLine(x + h * 0.12f * dir, y - h * 0.34f, x + h * 0.12f * dir + stride, y + 3f, strokePaint)
        canvas.drawLine(x + h * 0.17f * dir, y - h * 0.34f, x + h * 0.18f * dir - stride, y + 3f, strokePaint)
    }

    private fun drawWalkingElephant(canvas: Canvas, x: Float, y: Float, h: Float, dir: Float, gait: Double, col: Int, masterAlpha: Int) {
        val stride = sin(gait).toFloat() * h * 0.14f
        val trunkSwing = sin(gait * 0.7).toFloat() * h * 0.10f
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        strokePaint.color = col; strokePaint.alpha = masterAlpha
        // Massive domed back & head
        rect.set(x - h * 0.52f, y - h * 0.92f, x + h * 0.44f, y - h * 0.28f)
        canvas.drawOval(rect, fillPaint)
        canvas.drawCircle(x + h * 0.42f * dir, y - h * 0.68f, h * 0.26f, fillPaint)
        // Trunk & subtle tusk
        strokePaint.strokeWidth = h * 0.11f
        canvas.drawLine(x + h * 0.60f * dir, y - h * 0.62f, x + h * 0.68f * dir + trunkSwing, y - h * 0.20f, strokePaint)
        // Pillar legs
        strokePaint.strokeWidth = h * 0.13f
        canvas.drawLine(x - h * 0.32f * dir, y - h * 0.38f, x - h * 0.34f * dir - stride, y + 3f, strokePaint)
        canvas.drawLine(x - h * 0.14f * dir, y - h * 0.38f, x - h * 0.12f * dir + stride, y + 3f, strokePaint)
        canvas.drawLine(x + h * 0.18f * dir, y - h * 0.38f, x + h * 0.16f * dir + stride, y + 3f, strokePaint)
        canvas.drawLine(x + h * 0.34f * dir, y - h * 0.38f, x + h * 0.36f * dir - stride, y + 3f, strokePaint)
    }

    private fun drawAntelope(canvas: Canvas, x: Float, y: Float, h: Float, dir: Float, gait: Double, col: Int, masterAlpha: Int) {
        val stride = sin(gait).toFloat() * h * 0.28f
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        strokePaint.color = col; strokePaint.alpha = masterAlpha
        rect.set(x - h * 0.38f, y - h * 0.56f, x + h * 0.38f, y - h * 0.32f)
        canvas.drawOval(rect, fillPaint)
        strokePaint.strokeWidth = h * 0.09f
        canvas.drawLine(x + h * 0.26f * dir, y - h * 0.48f, x + h * 0.44f * dir, y - h * 0.86f, strokePaint)
        strokePaint.strokeWidth = h * 0.045f
        canvas.drawLine(x + h * 0.42f * dir, y - h * 0.86f, x + h * 0.28f * dir, y - h * 1.12f, strokePaint)
        canvas.drawLine(x - h * 0.26f * dir, y - h * 0.36f, x - h * 0.30f * dir - stride, y + 2f, strokePaint)
        canvas.drawLine(x + h * 0.24f * dir, y - h * 0.36f, x + h * 0.28f * dir + stride, y + 2f, strokePaint)
    }

    private fun drawSailboatMinimal(canvas: Canvas, x: Float, baseY: Float, density: Float, isDay: Boolean, timeSec: Double, masterAlpha: Int) {
        val bob = sin(timeSec * 1.15).toFloat() * density * 1.4f
        val y = baseY + bob
        fillPaint.color = if (isDay) 0xFF183A54.toInt() else 0xFF0A1424.toInt()
        fillPaint.alpha = masterAlpha
        dynPath.reset(); dynPath.moveTo(x - density * 9f, y); dynPath.lineTo(x + density * 10f, y)
        dynPath.lineTo(x + density * 6f, y + density * 3f); dynPath.lineTo(x - density * 6f, y + density * 3f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        fillPaint.color = if (isDay) 0xFFF6FAFC.toInt() else 0xFF94A3B8.toInt()
        dynPath.reset(); dynPath.moveTo(x - density * 0.6f, y - density); dynPath.lineTo(x - density * 0.6f, y - density * 19f)
        dynPath.lineTo(x - density * 7.5f, y - density * 1.8f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        dynPath.reset(); dynPath.moveTo(x + density * 1f, y - density); dynPath.lineTo(x + density * 1f, y - density * 14f)
        dynPath.lineTo(x + density * 7f, y - density * 1.5f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
    }

    private fun drawUnpredictableSailboat(
        canvas: Canvas, w: Int, h: Int, horizonY: Float, density: Float,
        dayOfYear: Int, isDay: Boolean, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val sailSpeed = 0.011 + (steadyWind / 45.0).coerceIn(0.0, 0.6) * 0.008
        val rawT = timeSec * sailSpeed
        val lap = kotlin.math.floor(rawT).toInt()
        val u = (rawT - lap).toFloat()
        val dir = if (seedRand(lap + dayOfYear, 23) > 0.5f) 1f else -1f
        val bx = if (dir > 0f) (-0.08f + u * 1.16f) * w else (1.08f - u * 1.16f) * w
        val depthRow = 0.055f + seedRand(lap + dayOfYear, 41) * 0.075f
        val swellY = (sin(timeSec * 1.25 + lap) * 0.65 + cos(timeSec * 2.15) * 0.35).toFloat() * density * 2.2f
        val by = horizonY + h * depthRow + swellY
        val rollDeg = (sin(timeSec * 1.4 + lap) * 2.8 + cos(timeSec * 0.85) * 1.4).toFloat()
        val scale = (0.78f + depthRow * 3.0f) * density

        // Soft trailing wake ripple behind hull
        strokePaint.color = if (isDay) 0xFFE2F4FA.toInt() else 0xFF94A3B8.toInt()
        strokePaint.alpha = (masterAlpha * 0.36f).toInt()
        strokePaint.strokeWidth = density * 1.1f
        canvas.drawLine(bx - dir * scale * 8f, by + scale * 3.0f, bx - dir * scale * 26f, by + scale * 3.2f, strokePaint)

        canvas.save()
        canvas.rotate(rollDeg, bx, by)
        fillPaint.color = if (isDay) 0xFF183A54.toInt() else 0xFF0A1424.toInt()
        fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(bx - scale * 10f, by); dynPath.lineTo(bx + scale * 11f, by)
        dynPath.lineTo(bx + scale * 6.5f, by + scale * 3.2f); dynPath.lineTo(bx - scale * 6.5f, by + scale * 3.2f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        fillPaint.color = if (isDay) 0xFFF6FAFC.toInt() else 0xFF94A3B8.toInt()
        dynPath.reset()
        dynPath.moveTo(bx - dir * scale * 0.6f, by - scale); dynPath.lineTo(bx - dir * scale * 0.6f, by - scale * 20f)
        dynPath.lineTo(bx - dir * scale * 8.5f, by - scale * 1.8f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        dynPath.reset()
        dynPath.moveTo(bx + dir * scale * 1.0f, by - scale); dynPath.lineTo(bx + dir * scale * 1.0f, by - scale * 15f)
        dynPath.lineTo(bx + dir * scale * 7.5f, by - scale * 1.5f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        canvas.restore()
    }

    private fun drawSwayingCoastalPine(canvas: Canvas, bx: Float, by: Float, h: Float, sway: Float, col: Int, masterAlpha: Int) {
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(bx - h * 0.05f, by + 4f)
        dynPath.quadTo(bx + h * 0.03f, by - h * 0.38f, bx + h * 0.06f + sway, by - h * 0.76f)
        dynPath.lineTo(bx + h * 0.11f + sway, by - h * 0.76f)
        dynPath.quadTo(bx + h * 0.06f, by - h * 0.38f, bx + h * 0.05f, by + 4f); dynPath.close()
        rect.set(bx - h * 0.38f + sway, by - h * 0.76f, bx + h * 0.02f + sway, by - h * 0.52f); dynPath.addOval(rect, Path.Direction.CW)
        rect.set(bx - h * 0.16f + sway * 1.15f, by - h * 0.94f, bx + h * 0.32f + sway * 1.15f, by - h * 0.66f); dynPath.addOval(rect, Path.Direction.CW)
        rect.set(bx + h * 0.12f + sway * 1.05f, by - h * 0.88f, bx + h * 0.56f + sway * 1.05f, by - h * 0.64f); dynPath.addOval(rect, Path.Direction.CW)
        canvas.drawPath(dynPath, fillPaint)
    }

    private fun drawSwayingSakuraBough(
        canvas: Canvas, w: Int, h: Int, density: Float, branchCol: Int,
        isDay: Boolean, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val sway = (sin(timeSec * 0.82) * 0.7 + cos(timeSec * 1.65) * 0.3).toFloat() * density * (2.5f + steadyWind * 0.08f)
        val x0 = w + 6f; val y0 = h * 0.065f
        val x1 = w * 0.87f; val y1 = h * 0.098f + sway * 0.35f
        val x2 = w * 0.78f; val y2 = h * 0.122f + sway * 0.70f
        val x3 = w * 0.70f; val y3 = h * 0.148f + sway
        val tx1 = w * 0.82f; val ty1 = h * 0.055f + sway * 0.50f
        val tx2 = w * 0.75f; val ty2 = h * 0.185f + sway * 0.85f

        strokePaint.color = branchCol; strokePaint.alpha = masterAlpha
        strokePaint.strokeWidth = density * 2.6f
        canvas.drawLine(x0, y0, x1, y1, strokePaint)
        strokePaint.strokeWidth = density * 1.9f
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
        strokePaint.strokeWidth = density * 1.3f
        canvas.drawLine(x2, y2, x3, y3, strokePaint)
        canvas.drawLine(x1, y1, tx1, ty1, strokePaint)
        canvas.drawLine(x2, y2, tx2, ty2, strokePaint)

        // Clustered blossoms anchored directly onto the branch & sub-twig nodes
        val bloomMain = if (isDay) 0xFFEEAABB.toInt() else 0xFF906E90.toInt()
        val bloomLight = if (isDay) 0xFFFCD6E0.toInt() else 0xFFB694B8.toInt()
        fillPaint.alpha = (masterAlpha * 0.94f).toInt()
        for (n in 0 until 6) {
            val cx = when (n) { 0 -> x1; 1 -> x2; 2 -> x3; 3 -> tx1; 4 -> tx2; else -> (x0 + x1) * 0.5f }
            val cy = when (n) { 0 -> y1; 1 -> y2; 2 -> y3; 3 -> ty1; 4 -> ty2; else -> (y0 + y1) * 0.5f }
            for (k in 0 until 6) {
                val ang = k * 1.0472 + n * 0.6
                val dist = density * (2.6f + (k % 3) * 2.2f)
                val bx = cx + cos(ang).toFloat() * dist
                val by = cy + sin(ang).toFloat() * dist * 0.78f
                fillPaint.color = if (k % 2 == 0) bloomLight else bloomMain
                canvas.drawCircle(bx, by, density * (2.4f + (k % 2) * 0.8f), fillPaint)
            }
        }

        // Delicate separate petals drifting gently down from the branch tips
        fillPaint.color = bloomLight
        for (p in 0 until 5) {
            val prog = (((timeSec * (0.065 + p * 0.014) + p * 0.23) % 1.0) + 1.0) % 1.0
            val pf = prog.toFloat()
            val px = x3 + (p * density * 12f) - pf * w * 0.24f + sin(timeSec * 1.8 + p * 2.1).toFloat() * density * 9f
            val py = y3 + pf * h * 0.30f
            fillPaint.alpha = (masterAlpha * 0.85f * sin(pf * PI).toFloat().coerceIn(0f, 1f)).toInt()
            canvas.drawCircle(px, py, density * 1.6f, fillPaint)
        }
    }

    private fun drawLivingStag(canvas: Canvas, w: Int, h: Int, shiftY: Float, dayOfYear: Int, col: Int, masterAlpha: Int, timeSec: Double) {
        val rawC = timeSec * 0.016
        val lap = kotlin.math.floor(rawC).toInt()
        val frac = (rawC - lap).toFloat()
        val dir = if (seedRand(lap + dayOfYear, 19) > 0.5f) 1f else -1f
        val runsAfterPause = seedRand(lap + dayOfYear, 43) > 0.42f

        val uPos: Float
        val gaitFreq: Double
        val strideAmp: Float
        val isPaused: Boolean
        val isRunning: Boolean
        if (frac < 0.28f) {
            // Walk onto the overlook shelf
            uPos = (frac / 0.28f) * 0.48f
            gaitFreq = 3.2; strideAmp = 0.16f; isPaused = false; isRunning = false
        } else if (frac < 0.62f) {
            // Stand alert on overlook, gently turning & lifting head
            uPos = 0.48f
            gaitFreq = 0.0; strideAmp = 0f; isPaused = true; isRunning = false
        } else {
            // Either break into a brisk run or stroll smoothly across
            val rem = (frac - 0.62f) / 0.38f
            if (runsAfterPause) {
                val runU = (rem * 1.45f).coerceIn(0f, 1f)
                uPos = 0.48f + runU * 0.60f
                gaitFreq = 7.4; strideAmp = 0.30f; isPaused = false; isRunning = runU < 1f
            } else {
                uPos = 0.48f + rem * 0.58f
                gaitFreq = 3.5; strideAmp = 0.18f; isPaused = false; isRunning = false
            }
        }
        val xNorm = if (dir > 0f) (-0.04f + uPos * 1.08f) else (1.04f - uPos * 1.08f)
        if (xNorm !in -0.06f..1.06f) return

        val sh = h * 0.068f
        val x = xNorm * w
        val runHop = if (isRunning) abs(sin(timeSec * 7.4).toFloat()) * sh * 0.14f else 0f
        val y = sampleMidRidgeY(xNorm.coerceIn(0f, 1f)) + shiftY + 1f - runHop
        val stride = if (isPaused) 0f else sin(timeSec * gaitFreq).toFloat() * sh * strideAmp
        val headLift = if (isPaused) sin(timeSec * 0.95).toFloat() * sh * 0.08f else 0f
        val headNod = if (isPaused) cos(timeSec * 1.35).toFloat() * sh * 0.05f else 0f

        fillPaint.color = col; fillPaint.alpha = masterAlpha
        strokePaint.color = col; strokePaint.alpha = masterAlpha
        // Torso
        dynPath.reset()
        dynPath.moveTo(x - sh * 0.28f * dir, y - sh * 0.38f)
        dynPath.lineTo(x + sh * 0.14f * dir, y - sh * 0.44f)
        dynPath.lineTo(x + sh * 0.22f * dir, y - sh * 0.32f)
        dynPath.lineTo(x - sh * 0.24f * dir, y - sh * 0.25f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        // 4 striding / bounding legs
        strokePaint.strokeWidth = sh * 0.038f
        canvas.drawLine(x - sh * 0.24f * dir, y - sh * 0.26f, x - sh * 0.26f * dir - stride, y + 3f, strokePaint)
        canvas.drawLine(x - sh * 0.16f * dir, y - sh * 0.26f, x - sh * 0.17f * dir + stride * 0.7f, y + 3f, strokePaint)
        canvas.drawLine(x + sh * 0.14f * dir, y - sh * 0.30f, x + sh * 0.13f * dir + stride, y + 3f, strokePaint)
        canvas.drawLine(x + sh * 0.19f * dir, y - sh * 0.30f, x + sh * 0.23f * dir - stride * 0.7f, y + 3f, strokePaint)
        // Neck, head & branched antlers
        val hx = x + sh * 0.25f * dir + headNod * dir
        val hy = y - sh * 0.66f - headLift
        strokePaint.strokeWidth = sh * 0.075f
        canvas.drawLine(x + sh * 0.14f * dir, y - sh * 0.40f, hx, hy, strokePaint)
        canvas.drawLine(hx, hy, hx + sh * 0.14f * dir, hy + sh * 0.04f + headNod * 0.4f, strokePaint)
        strokePaint.strokeWidth = sh * 0.025f
        canvas.drawLine(hx, hy - sh * 0.02f, hx - sh * 0.10f * dir, hy - sh * 0.28f, strokePaint)
        canvas.drawLine(hx, hy - sh * 0.02f, hx + sh * 0.04f * dir, hy - sh * 0.30f, strokePaint)
        canvas.drawLine(hx - sh * 0.04f * dir, hy - sh * 0.14f, hx + sh * 0.12f * dir, hy - sh * 0.18f, strokePaint)
    }

    private fun drawAnimatedBalloon(canvas: Canvas, cx: Float, cy: Float, r: Float, idx: Int, isDay: Boolean, burnerStr: Float, darkCol: Int, timeSec: Double, masterAlpha: Int) {
        val mainCol = when (idx % 4) {
            0 -> if (isDay) 0xFFD4523E.toInt() else 0xFF9E3A36.toInt()
            1 -> if (isDay) 0xFF36809E.toInt() else 0xFF304866.toInt()
            2 -> if (isDay) 0xFFE09444.toInt() else 0xFF865236.toInt()
            else -> if (isDay) 0xFF765686.toInt() else 0xFF483458.toInt()
        }
        val stripeCol = if (isDay) 0xFFF4C67E.toInt() else 0xFFE2AE68.toInt()
        if (burnerStr > 0.05f) {
            val fl = 0.85f + 0.15f * sin(timeSec * 4.2 + idx * 1.7).toFloat()
            drawSoftMist(canvas, cx, cy + r * 0.8f, r * 1.9f, r * 1.9f, 0x00FFC65C or ((masterAlpha * 0.45f * burnerStr * fl).toInt() shl 24))
        }
        fillPaint.color = mainCol; fillPaint.alpha = masterAlpha
        dynPath.reset(); dynPath.moveTo(cx - r, cy)
        rect.set(cx - r, cy - r, cx + r, cy + r); dynPath.arcTo(rect, 180f, 180f)
        dynPath.lineTo(cx + r * 0.24f, cy + r * 1.35f); dynPath.lineTo(cx - r * 0.24f, cy + r * 1.35f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        fillPaint.color = stripeCol
        dynPath.reset(); dynPath.moveTo(cx - r * 0.42f, cy)
        rect.set(cx - r * 0.42f, cy - r, cx + r * 0.42f, cy + r); dynPath.arcTo(rect, 180f, 180f)
        dynPath.lineTo(cx + r * 0.12f, cy + r * 1.35f); dynPath.lineTo(cx - r * 0.12f, cy + r * 1.35f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        strokePaint.color = darkCol; strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = max(1f, r * 0.045f)
        canvas.drawLine(cx - r * 0.18f, cy + r * 1.35f, cx - r * 0.22f, cy + r * 1.62f, strokePaint)
        canvas.drawLine(cx + r * 0.18f, cy + r * 1.35f, cx + r * 0.22f, cy + r * 1.62f, strokePaint)
        if (burnerStr > 0.05f) {
            fillPaint.color = 0xFFFFE082.toInt(); fillPaint.alpha = (masterAlpha * 0.92f * burnerStr).toInt()
            canvas.drawCircle(cx, cy + r * 1.40f, r * 0.16f, fillPaint)
        }
        fillPaint.color = darkCol; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx - r * 0.24f, cy + r * 1.60f, cx + r * 0.24f, cy + r * 1.94f, fillPaint)
    }

    private fun drawTibetanMonastery(
        canvas: Canvas, cx: Float, by: Float, h: Float, density: Float,
        isDay: Boolean, lightColor: Int, deepVelvet: Int, sunAlt: Float, masterAlpha: Int
    ) {
        val yb = by + h * 0.34f
        val wallLight = if (isDay) ColorUtils.blendARGB(0xFFF3ECE2.toInt(), lightColor, 0.22f)
                        else ColorUtils.blendARGB(0xFF566882.toInt(), lightColor, 0.24f)
        val wallShade = ColorUtils.blendARGB(wallLight, deepVelvet, if (isDay) 0.32f else 0.44f)
        val crimson = if (isDay) ColorUtils.blendARGB(0xFF8B2228.toInt(), lightColor, 0.16f)
                      else ColorUtils.blendARGB(0xFF4A1824.toInt(), deepVelvet, 0.35f)
        val gold = if (isDay) ColorUtils.blendARGB(0xFFE0A938.toInt(), lightColor, 0.26f)
                   else ColorUtils.blendARGB(0xFF8A6B36.toInt(), lightColor, 0.20f)
        val winDark = ColorUtils.blendARGB(deepVelvet, 0xFF0A0D14.toInt(), 0.55f)

        // Stone foundation terrace anchoring gompa into cliff
        fillPaint.color = ColorUtils.blendARGB(wallShade, deepVelvet, 0.48f); fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.92f, yb); dynPath.lineTo(cx - h * 0.82f, by - h * 0.04f)
        dynPath.lineTo(cx + h * 0.80f, by - h * 0.04f); dynPath.lineTo(cx + h * 0.90f, yb); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Left monastery hall (tapered Tibetan inward-sloping walls)
        fillPaint.color = wallLight; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.82f, yb); dynPath.lineTo(cx - h * 0.77f, by - h * 0.34f)
        dynPath.lineTo(cx - h * 0.26f, by - h * 0.34f); dynPath.lineTo(cx - h * 0.22f, yb); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Shaded right courtyard wing for 3D depth
        fillPaint.color = wallShade; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx + h * 0.34f, yb); dynPath.lineTo(cx + h * 0.36f, by - h * 0.48f)
        dynPath.lineTo(cx + h * 0.75f, by - h * 0.48f); dynPath.lineTo(cx + h * 0.80f, yb); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Central assembly hall (Dukhang) — sunlit front + shaded right return
        fillPaint.color = wallLight; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.36f, yb); dynPath.lineTo(cx - h * 0.31f, by - h * 0.72f)
        dynPath.lineTo(cx + h * 0.26f, by - h * 0.72f); dynPath.lineTo(cx + h * 0.28f, yb); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        fillPaint.color = wallShade; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx + h * 0.25f, yb); dynPath.lineTo(cx + h * 0.25f, by - h * 0.72f)
        dynPath.lineTo(cx + h * 0.42f, by - h * 0.68f); dynPath.lineTo(cx + h * 0.45f, yb); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Upper shrine sanctum
        fillPaint.color = wallLight; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.18f, by - h * 0.72f); dynPath.lineTo(cx - h * 0.15f, by - h * 0.94f)
        dynPath.lineTo(cx + h * 0.19f, by - h * 0.94f); dynPath.lineTo(cx + h * 0.22f, by - h * 0.72f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Iconic crimson Tibetan roof friezes (gyaltsen bands)
        fillPaint.color = crimson; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx - h * 0.79f, by - h * 0.38f, cx - h * 0.26f, by - h * 0.31f, fillPaint)
        canvas.drawRect(cx + h * 0.35f, by - h * 0.52f, cx + h * 0.77f, by - h * 0.45f, fillPaint)
        canvas.drawRect(cx - h * 0.33f, by - h * 0.77f, cx + h * 0.43f, by - h * 0.69f, fillPaint)
        canvas.drawRect(cx - h * 0.17f, by - h * 0.97f, cx + h * 0.21f, by - h * 0.92f, fillPaint)

        // Upturned golden pagoda roof crest + spire finial
        fillPaint.color = gold; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.26f, by - h * 0.96f)
        dynPath.quadTo(cx - h * 0.10f, by - h * 1.00f, cx + h * 0.02f, by - h * 1.12f)
        dynPath.quadTo(cx + h * 0.14f, by - h * 1.00f, cx + h * 0.30f, by - h * 0.96f)
        dynPath.lineTo(cx + h * 0.22f, by - h * 0.93f)
        dynPath.lineTo(cx - h * 0.18f, by - h * 0.93f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        // Golden pinnacle spire
        strokePaint.color = gold; strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = density * 1.2f
        canvas.drawLine(cx + h * 0.02f, by - h * 1.11f, cx + h * 0.02f, by - h * 1.23f, strokePaint)
        canvas.drawCircle(cx + h * 0.02f, by - h * 1.23f, density * 1.3f, fillPaint)

        // Recessed Tibetan windows (dark in daylight, glowing warm butter-lamp gold at dusk/night)
        val lampStr = ((3f - sunAlt) / 7f).coerceIn(0f, 1f)
        if (lampStr > 0.05f) {
            drawSoftMist(canvas, cx, by - h * 0.42f, h * 2.1f, h * 1.15f, 0x00FFD268 or ((masterAlpha * 0.38f * lampStr).toInt() shl 24))
        }
        val winCol = if (lampStr > 0.05f) ColorUtils.blendARGB(winDark, 0xFFFFD05A.toInt(), (lampStr * 0.95f).coerceIn(0f, 1f)) else winDark
        fillPaint.color = winCol; fillPaint.alpha = (masterAlpha * 0.94f).toInt()
        val ww = h * 0.055f; val wh = h * 0.085f
        // Central hall upper & lower window rows
        for (wi in -1..1) {
            val wx = cx - h * 0.04f + wi * h * 0.16f
            canvas.drawRect(wx - ww, by - h * 0.59f, wx + ww, by - h * 0.59f + wh, fillPaint)
            canvas.drawRect(wx - ww, by - h * 0.38f, wx + ww, by - h * 0.38f + wh, fillPaint)
        }
        // Left & right wing windows
        canvas.drawRect(cx - h * 0.62f, by - h * 0.24f, cx - h * 0.62f + ww * 1.8f, by - h * 0.24f + wh, fillPaint)
        canvas.drawRect(cx - h * 0.44f, by - h * 0.24f, cx - h * 0.44f + ww * 1.8f, by - h * 0.24f + wh, fillPaint)
        canvas.drawRect(cx + h * 0.50f, by - h * 0.36f, cx + h * 0.50f + ww * 1.7f, by - h * 0.36f + wh, fillPaint)
    }

    private fun drawSwayingDeodarPine(canvas: Canvas, bx: Float, by: Float, h: Float, sway: Float, col: Int, masterAlpha: Int) {
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        dynPath.reset()
        // Trunk tapering cleanly to swaying tip
        val tipX = bx + sway
        val tipY = by - h
        dynPath.moveTo(bx - h * 0.036f, by)
        dynPath.lineTo(tipX, tipY)
        dynPath.lineTo(bx + h * 0.036f, by)
        dynPath.close()
        // 6 tiered drooping Himalayan deodar boughs
        val tiers = 6
        for (t in 0 until tiers) {
            val frac = t / tiers.toFloat() // 0 = top, 1 = bottom
            val tierY = tipY + h * (0.12f + frac * 0.72f)
            val tierSway = sway * (1f - frac * 0.82f)
            val cx = bx + tierSway
            val halfW = h * (0.09f + frac * 0.24f)
            val drop = h * (0.08f + frac * 0.04f)
            dynPath.moveTo(cx, tierY - h * 0.06f)
            dynPath.lineTo(cx - halfW, tierY + drop)
            dynPath.lineTo(cx - halfW * 0.42f, tierY + drop * 0.68f)
            dynPath.lineTo(cx, tierY + drop * 0.92f)
            dynPath.lineTo(cx + halfW * 0.42f, tierY + drop * 0.68f)
            dynPath.lineTo(cx + halfW, tierY + drop)
            dynPath.close()
        }
        canvas.drawPath(dynPath, fillPaint)
    }

    private fun drawAnimatedPrayerFlags(
        canvas: Canvas,
        x0: Float, baseY0: Float, poleH0: Float,
        x1: Float, baseY1: Float, poleH1: Float,
        density: Float, darkCol: Int, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val windScale = (steadyWind / 25f).coerceIn(0f, 1.6f)
        val flex0 = sin(timeSec * 1.45).toFloat() * density * (0.7f + windScale * 1.6f)
        val flex1 = sin(timeSec * 1.38 + 0.9).toFloat() * density * (0.6f + windScale * 1.4f)
        val tx0 = x0 + flex0; val ty0 = baseY0 - poleH0
        val tx1 = x1 + flex1; val ty1 = baseY1 - poleH1

        // Wooden prayer poles planted firmly into the foreground ridge
        val poleCol = ColorUtils.blendARGB(darkCol, 0xFF1F1612.toInt(), 0.35f)
        fillPaint.color = poleCol; fillPaint.alpha = masterAlpha
        // Left pole
        dynPath.reset()
        dynPath.moveTo(x0 - density * 1.55f, baseY0 + density * 6f)
        dynPath.lineTo(tx0 - density * 0.80f, ty0 - density * 4.5f)
        dynPath.lineTo(tx0 + density * 0.80f, ty0 - density * 4.5f)
        dynPath.lineTo(x0 + density * 1.55f, baseY0 + density * 6f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        // Right pole
        dynPath.reset()
        dynPath.moveTo(x1 - density * 1.45f, baseY1 + density * 6f)
        dynPath.lineTo(tx1 - density * 0.75f, ty1 - density * 4.0f)
        dynPath.lineTo(tx1 + density * 0.75f, ty1 - density * 4.0f)
        dynPath.lineTo(x1 + density * 1.45f, baseY1 + density * 6f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Small finial knots at top of both poles
        canvas.drawCircle(tx0, ty0 - density * 4.5f, density * 1.45f, fillPaint)
        canvas.drawCircle(tx1, ty1 - density * 4.0f, density * 1.35f, fillPaint)

        // Catenary rope anchored cleanly between the two wooden poles
        val sagMax = density * 13.5f
        strokePaint.color = 0xFF0A1018.toInt(); strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = density * 0.95f
        dynPath.reset(); dynPath.moveTo(tx0, ty0)
        dynPath.quadTo((tx0 + tx1) * 0.5f, (ty0 + ty1) * 0.5f + sagMax * 2f, tx1, ty1)
        canvas.drawPath(dynPath, strokePaint)

        // 10 Lungta prayer flags with travelling wind-wave flutter
        val flutterAmp = density * (1.8f + windScale * 3.8f)
        val waveFreq = 3.4 + steadyWind * 0.11
        for (i in 1..10) {
            val t = i / 11f
            val u = 1f - t
            val px = u * u * tx0 + 2f * u * t * ((tx0 + tx1) * 0.5f) + t * t * tx1
            val py = u * u * ty0 + 2f * u * t * ((ty0 + ty1) * 0.5f + sagMax * 2f) + t * t * ty1
            val fl = sin(timeSec * waveFreq - i * 0.68).toFloat() * flutterAmp
            val lift = (0.5f + 0.5f * cos(timeSec * waveFreq * 1.35 - i * 0.85).toFloat()) * density * (0.6f + windScale * 1.4f)
            fillPaint.color = prayerFlagColors[(i - 1) % prayerFlagColors.size]; fillPaint.alpha = (masterAlpha * 0.92f).toInt()
            dynPath.reset()
            dynPath.moveTo(px - density * 2.5f, py)
            dynPath.lineTo(px + density * 2.5f, py)
            dynPath.lineTo(px + density * 2.2f + fl, py + density * 6.8f - lift)
            dynPath.lineTo(px - density * 2.8f + fl, py + density * 6.3f - lift * 0.8f)
            dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }
    }

    private fun drawGlidingAlpineTrain(canvas: Canvas, w: Int, h: Int, density: Float, deckY: Float, sunAlt: Float, timeSec: Double, masterAlpha: Int) {
        val progress = (((timeSec * 0.032) % 1.4) - 0.2).toFloat()
        val xStart = progress * w
        val carW = w * 0.095f; val carH = h * 0.011f; val gap = w * 0.008f
        val y1 = deckY - density * 1.5f; val y0 = y1 - carH
        val winStr = ((4f - sunAlt) / 7f).coerceIn(0f, 1f)
        for (c in 0 until 3) {
            val cx0 = xStart + c * (carW + gap); val cx1 = cx0 + carW
            if (cx1 < 0f || cx0 > w) continue
            fillPaint.color = 0xFFBC2C28.toInt(); fillPaint.alpha = masterAlpha
            canvas.drawRect(cx0, y0, cx1, y1, fillPaint)
            fillPaint.color = 0xFFECEEF2.toInt()
            canvas.drawRect(cx0, y0, cx1, y0 + density * 1.2f, fillPaint)
            if (winStr > 0.05f) {
                fillPaint.color = 0xFFFFD470.toInt(); fillPaint.alpha = (masterAlpha * 0.92f * winStr).toInt()
                for (wi in 0 until 4) {
                    val wx = cx0 + carW * (0.14f + wi * 0.22f)
                    canvas.drawRect(wx, y0 + carH * 0.28f, wx + carW * 0.12f, y0 + carH * 0.68f, fillPaint)
                }
                // Warm forward headlight cone on the lead car after sunset
                if (c == 2) {
                    val beamLen = w * 0.068f
                    val hy = (y0 + y1) * 0.52f
                    fillPaint.color = 0xFFFFE699.toInt(); fillPaint.alpha = (masterAlpha * 0.22f * winStr).toInt()
                    dynPath.reset()
                    dynPath.moveTo(cx1, hy - density * 0.9f)
                    dynPath.lineTo(cx1 + beamLen, hy - density * 4.8f)
                    dynPath.lineTo(cx1 + beamLen, hy + density * 3.6f)
                    dynPath.lineTo(cx1, hy + density * 0.9f); dynPath.close()
                    canvas.drawPath(dynPath, fillPaint)
                    fillPaint.color = 0xFFFFF4CC.toInt(); fillPaint.alpha = (masterAlpha * 0.90f * winStr).toInt()
                    canvas.drawCircle(cx1, hy, density * 1.15f, fillPaint)
                }
            }
        }
    }

    private fun drawSpinningWindmillRotor(canvas: Canvas, cx: Float, hy: Float, r: Float, darkCol: Int, steadyWind: Float, timeSec: Double, masterAlpha: Int) {
        val spinRate = 0.35 + (steadyWind / 20.0).coerceIn(0.0, 2.5) * 1.4
        val baseAngle = timeSec * spinRate
        fillPaint.color = darkCol; fillPaint.alpha = masterAlpha
        strokePaint.color = darkCol; strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = r * 0.05f
        for (i in 0 until 12) {
            val ang = baseAngle + i * (PI / 6.0)
            val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            val px = -sa; val py = ca
            dynPath.reset()
            dynPath.moveTo(cx + ca * r * 0.25f - px * r * 0.06f, hy + sa * r * 0.25f - py * r * 0.06f)
            dynPath.lineTo(cx + ca * r - px * r * 0.14f, hy + sa * r - py * r * 0.14f)
            dynPath.lineTo(cx + ca * r + px * r * 0.06f, hy + sa * r + py * r * 0.06f)
            dynPath.lineTo(cx + ca * r * 0.25f + px * r * 0.04f, hy + sa * r * 0.25f + py * r * 0.04f)
            dynPath.close(); canvas.drawPath(dynPath, fillPaint)
        }
        canvas.drawCircle(cx, hy, r * 0.88f, strokePaint)
        canvas.drawCircle(cx, hy, r * 0.14f, fillPaint)
    }

    private fun drawVolcanicSmokePlume(
        canvas: Canvas, w: Int, h: Int, density: Float, px: Float, py: Float,
        isDay: Boolean, lavaStr: Float, lightColor: Int, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val windDrift = windDir * w * (0.09f + (steadyWind / 28f).coerceIn(0f, 1.5f) * 0.10f)
        val daySmokeCol = ColorUtils.blendARGB(0xFFCFD6DE.toInt(), lightColor, 0.22f)
        val nightSmokeCol = ColorUtils.blendARGB(0xFF3E3538.toInt(), 0xFF7A3824.toInt(), lavaStr * 0.45f)
        val smokeRgb = (ColorUtils.blendARGB(daySmokeCol, nightSmokeCol, lavaStr) and 0x00FFFFFF)
        val peakAlpha = if (isDay) 0.44f else (0.28f - lavaStr * 0.08f)
        for (i in 0 until 8) {
            val phase = (((timeSec * 0.068 + i * 0.125) % 1.0 + 1.0) % 1.0).toFloat()
            val env = Math.pow(sin(phase * PI).coerceAtLeast(0.0), 0.48).toFloat()
            val rise = phase * h * 0.22f
            val sx = px + windDrift * phase * phase + sin(timeSec * 0.7 + i * 1.9).toFloat() * density * (4f + phase * 10f)
            val sy = py - h * 0.008f - rise
            val rx = w * (0.042f + phase * 0.125f)
            val ry = h * (0.024f + phase * 0.062f)
            val a = (masterAlpha * peakAlpha * env).toInt().coerceIn(0, 255)
            if (a > 2) drawSoftMist(canvas, sx, sy, rx, ry, smokeRgb or (a shl 24))
        }
    }

    private fun drawGlowingLavaRiverAndEmbers(
        canvas: Canvas, w: Int, h: Int, density: Float, px: Float, py: Float, baseY: Float, foreShiftY: Float,
        lavaStr: Float, pulse: Float, windDir: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        dynPath.reset(); dynPath.moveTo(w * 0.56f, baseY + foreShiftY + h * 0.008f)
        dynPath.quadTo(w * 0.44f, h * 0.69f + foreShiftY, w * 0.58f, h * 0.73f + foreShiftY)
        dynPath.quadTo(w * 0.42f, h * 0.79f + foreShiftY, w * 0.74f, h * 0.84f + foreShiftY)
        dynPath.quadTo(w * 0.88f, h * 0.90f + foreShiftY, w * 0.56f, h * 0.94f + foreShiftY)
        dynPath.quadTo(w * 0.22f, h * 0.92f + foreShiftY, w * 0.16f, h * 0.86f + foreShiftY)

        // Cooled dark obsidian basalt channel visible subtly in daylight
        if (lavaStr < 0.95f) {
            strokePaint.color = 0xFF0A0C12.toInt()
            strokePaint.alpha = (masterAlpha * 0.52f * (1f - lavaStr)).toInt()
            strokePaint.strokeWidth = density * 3.6f
            canvas.drawPath(dynPath, strokePaint)
        }

        // Dusk/night glowing molten lava river + rising wind-drifting crater embers
        if (lavaStr > 0.02f) {
            strokePaint.color = 0xFFFF4D0D.toInt()
            strokePaint.alpha = (masterAlpha * 0.36f * pulse * lavaStr).toInt()
            strokePaint.strokeWidth = density * 8.0f
            canvas.drawPath(dynPath, strokePaint)
            strokePaint.color = 0xFFFF8C24.toInt()
            strokePaint.alpha = (masterAlpha * 0.78f * pulse * lavaStr).toInt()
            strokePaint.strokeWidth = density * 3.2f
            canvas.drawPath(dynPath, strokePaint)
            strokePaint.color = 0xFFFFDF75.toInt()
            strokePaint.alpha = (masterAlpha * 0.94f * pulse * lavaStr).toInt()
            strokePaint.strokeWidth = density * 1.4f
            canvas.drawPath(dynPath, strokePaint)

            val emberWind = windDir * density * (10f + (steadyWind / 25f).coerceIn(0f, 1.5f) * 16f)
            for (e in 0 until 8) {
                val lift = (((timeSec * (0.14 + (e % 3) * 0.03) + e * 0.137) % 1.0 + 1.0) % 1.0).toFloat()
                val fade = sin(lift * PI).toFloat().coerceIn(0f, 1f)
                val ex = px + emberWind * lift + sin(timeSec * 1.6 + e * 2.1).toFloat() * density * 11f
                val ey = py - lift * h * 0.18f
                fillPaint.color = if (e % 2 == 0) 0xFFFFD666.toInt() else 0xFFFF7A26.toInt()
                fillPaint.alpha = (masterAlpha * 0.90f * fade * lavaStr).toInt()
                canvas.drawCircle(ex, ey, density * (1.7f - lift * 0.9f), fillPaint)
            }
        }
    }

    private fun drawHighlandCastleRuin(
        canvas: Canvas, cx: Float, by: Float, h: Float, density: Float,
        isDay: Boolean, lightColor: Int, deepVelvet: Int, sunAlt: Float, timeSec: Double, masterAlpha: Int
    ) {
        val stoneLight = if (isDay) ColorUtils.blendARGB(0xFF9EA2A8.toInt(), lightColor, 0.32f)
                         else ColorUtils.blendARGB(0xFF4C5A70.toInt(), lightColor, 0.24f)
        val stoneMid = ColorUtils.blendARGB(stoneLight, deepVelvet, if (isDay) 0.34f else 0.45f)
        val stoneShade = ColorUtils.blendARGB(stoneLight, deepVelvet, if (isDay) 0.56f else 0.68f)
        val yb = by + density * 4f

        // Main keep tower (sunlit left face + shaded right face + crenellations)
        fillPaint.color = stoneLight; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx - h * 0.48f, by - h * 0.86f, cx - h * 0.18f, yb, fillPaint)
        fillPaint.color = stoneShade; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx - h * 0.18f, by - h * 0.86f, cx - h * 0.06f, yb, fillPaint)
        // Battlement merlons along keep top
        fillPaint.color = stoneLight
        for (m in 0 until 3) {
            val mx = cx - h * 0.48f + m * h * 0.145f
            canvas.drawRect(mx, by - h * 0.94f, mx + h * 0.085f, by - h * 0.85f, fillPaint)
        }

        // Ruined central hall & stepped Scottish crow-step gable
        fillPaint.color = stoneMid; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx - h * 0.07f, by - h * 0.52f, cx + h * 0.42f, yb, fillPaint)
        fillPaint.color = stoneLight
        dynPath.reset()
        dynPath.moveTo(cx + h * 0.03f, by - h * 0.52f)
        dynPath.lineTo(cx + h * 0.22f, by - h * 0.78f)
        dynPath.lineTo(cx + h * 0.42f, by - h * 0.52f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Eastern watch turret (sunlit edge + shaded side)
        fillPaint.color = stoneLight
        canvas.drawRect(cx + h * 0.38f, by - h * 0.68f, cx + h * 0.49f, yb, fillPaint)
        fillPaint.color = stoneShade
        canvas.drawRect(cx + h * 0.49f, by - h * 0.68f, cx + h * 0.58f, yb, fillPaint)

        // Subtle sunlit rim highlight along left keep edge for unmistakable silhouette separation
        strokePaint.color = ColorUtils.blendARGB(stoneLight, 0xFFF4F6FA.toInt(), 0.35f)
        strokePaint.alpha = (masterAlpha * if (isDay) 0.55f else 0.28f).toInt()
        strokePaint.strokeWidth = density * 1.0f
        canvas.drawLine(cx - h * 0.48f, by - h * 0.86f, cx - h * 0.48f, yb - density * 3f, strokePaint)

        // Dark lancet windows + flickering warm torchlight at dusk/night
        val winDark = ColorUtils.blendARGB(deepVelvet, 0xFF060810.toInt(), 0.65f)
        val torchStr = ((4f - sunAlt) / 7f).coerceIn(0f, 1f)
        val fl = (0.86f + 0.14f * sin(timeSec * 3.9)).toFloat()
        if (torchStr > 0.05f) {
            drawSoftMist(canvas, cx - h * 0.28f, by - h * 0.48f, density * 17f, density * 17f, 0x00FFCE60 or ((masterAlpha * 0.52f * torchStr * fl).toInt() shl 24))
        }
        fillPaint.color = if (torchStr > 0.05f) ColorUtils.blendARGB(winDark, 0xFFFFD468.toInt(), (torchStr * 0.94f).coerceIn(0f, 1f)) else winDark
        fillPaint.alpha = (masterAlpha * 0.94f).toInt()
        canvas.drawRect(cx - h * 0.36f, by - h * 0.64f, cx - h * 0.28f, by - h * 0.50f, fillPaint)
        canvas.drawRect(cx + h * 0.16f, by - h * 0.42f, cx + h * 0.24f, by - h * 0.28f, fillPaint)
    }

    private fun drawSwayingScotsPine(canvas: Canvas, bx: Float, by: Float, h: Float, sway: Float, col: Int, masterAlpha: Int) {
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        strokePaint.color = col; strokePaint.alpha = masterAlpha; strokePaint.strokeCap = Paint.Cap.ROUND
        // Gnarled, gently leaning Scots pine trunk
        dynPath.reset()
        dynPath.moveTo(bx - h * 0.045f, by)
        dynPath.quadTo(bx - h * 0.06f, by - h * 0.38f, bx + h * 0.01f + sway * 0.72f, by - h * 0.76f)
        dynPath.lineTo(bx + h * 0.055f + sway * 0.72f, by - h * 0.76f)
        dynPath.quadTo(bx + h * 0.00f, by - h * 0.38f, bx + h * 0.045f, by); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Spreading woody boughs reaching out to flat-topped Scottish needle clusters
        val topX = bx + h * 0.03f + sway * 0.75f
        val topY = by - h * 0.72f
        strokePaint.strokeWidth = h * 0.032f
        canvas.drawLine(bx - h * 0.01f + sway * 0.45f, by - h * 0.52f, bx - h * 0.28f + sway * 0.85f, by - h * 0.66f, strokePaint)
        canvas.drawLine(topX, topY, bx + h * 0.28f + sway * 0.95f, by - h * 0.78f, strokePaint)
        canvas.drawLine(topX, topY, bx - h * 0.14f + sway * 1.05f, by - h * 0.88f, strokePaint)

        // 5 irregular layered feathery needle clusters (flat-topped Scots pine crown)
        fun drawNeedles(cx: Float, cy: Float, rw: Float, rh: Float) {
            dynPath.reset()
            dynPath.moveTo(cx - rw, cy + rh * 0.25f)
            dynPath.quadTo(cx - rw * 0.75f, cy - rh * 0.95f, cx - rw * 0.15f, cy - rh * 0.72f)
            dynPath.quadTo(cx + rw * 0.25f, cy - rh * 1.10f, cx + rw * 0.72f, cy - rh * 0.65f)
            dynPath.quadTo(cx + rw * 1.05f, cy - rh * 0.15f, cx + rw * 0.85f, cy + rh * 0.38f)
            dynPath.quadTo(cx + rw * 0.20f, cy + rh * 0.62f, cx - rw * 0.35f, cy + rh * 0.46f)
            dynPath.quadTo(cx - rw * 0.85f, cy + rh * 0.55f, cx - rw, cy + rh * 0.25f)
            dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }
        drawNeedles(bx - h * 0.30f + sway * 0.88f, by - h * 0.68f, h * 0.22f, h * 0.10f)
        drawNeedles(bx - h * 0.14f + sway * 1.02f, by - h * 0.88f, h * 0.25f, h * 0.12f)
        drawNeedles(bx + h * 0.08f + sway * 1.12f, by - h * 0.94f, h * 0.27f, h * 0.12f)
        drawNeedles(bx + h * 0.28f + sway * 0.98f, by - h * 0.80f, h * 0.23f, h * 0.10f)
        drawNeedles(bx + h * 0.04f + sway * 0.86f, by - h * 0.74f, h * 0.20f, h * 0.09f)
    }

    private fun drawSwayingCypress(canvas: Canvas, cx: Float, by: Float, h: Float, sway: Float, col: Int, masterAlpha: Int) {
        fillPaint.color = col; fillPaint.alpha = masterAlpha
        val w = h * 0.13f
        dynPath.reset(); dynPath.moveTo(cx, by)
        dynPath.quadTo(cx - w, by - h * 0.35f, cx + sway, by - h)
        dynPath.quadTo(cx + w, by - h * 0.35f, cx, by); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
    }

    private fun drawProvenceMas(
        canvas: Canvas, cx: Float, by: Float, h: Float, density: Float,
        isDay: Boolean, lightColor: Int, deepVelvet: Int, sunAlt: Float, masterAlpha: Int
    ) {
        val wallLit = if (isDay) ColorUtils.blendARGB(0xFFF0E4CC.toInt(), lightColor, 0.25f)
                      else ColorUtils.blendARGB(0xFF56627E.toInt(), lightColor, 0.24f)
        val wallShade = ColorUtils.blendARGB(wallLit, deepVelvet, if (isDay) 0.30f else 0.44f)
        val roofTerra = if (isDay) ColorUtils.blendARGB(0xFFB85438.toInt(), lightColor, 0.22f)
                        else ColorUtils.blendARGB(0xFF4C282C.toInt(), deepVelvet, 0.35f)
        val roofDark = ColorUtils.blendARGB(roofTerra, deepVelvet, 0.32f)
        val yb = by + density * 3.5f

        // Sunlit main farmhouse front + shaded right return wall
        fillPaint.color = wallLit; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx - h * 0.72f, by - h * 0.58f, cx + h * 0.34f, yb, fillPaint)
        fillPaint.color = wallShade; fillPaint.alpha = masterAlpha
        canvas.drawRect(cx + h * 0.34f, by - h * 0.58f, cx + h * 0.84f, yb, fillPaint)

        // Small stone chimney
        fillPaint.color = wallShade
        canvas.drawRect(cx + h * 0.38f, by - h * 1.02f, cx + h * 0.52f, by - h * 0.62f, fillPaint)

        // Distinct terracotta clay pitched roof (overhanging front slope + shaded gable hip)
        fillPaint.color = roofTerra; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.82f, by - h * 0.56f)
        dynPath.lineTo(cx - h * 0.14f, by - h * 1.00f)
        dynPath.lineTo(cx + h * 0.42f, by - h * 0.56f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        fillPaint.color = roofDark; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(cx - h * 0.14f, by - h * 1.00f)
        dynPath.lineTo(cx + h * 0.54f, by - h * 0.94f)
        dynPath.lineTo(cx + h * 0.92f, by - h * 0.56f)
        dynPath.lineTo(cx + h * 0.42f, by - h * 0.56f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)

        // Warm glowing shuttered windows at dusk/night (or soft lavender-grey shutters in daylight)
        val winStr = ((4f - sunAlt) / 7f).coerceIn(0f, 1f)
        if (winStr > 0.05f) {
            drawSoftMist(canvas, cx - h * 0.18f, by - h * 0.32f, h * 1.6f, h * 0.9f, 0x00FFD268 or ((masterAlpha * 0.36f * winStr).toInt() shl 24))
        }
        val winCol = if (winStr > 0.05f) ColorUtils.blendARGB(0xFF4A5068.toInt(), 0xFFFFD46A.toInt(), (winStr * 0.95f).coerceIn(0f, 1f))
                     else ColorUtils.blendARGB(0xFF56647E.toInt(), deepVelvet, 0.25f)
        fillPaint.color = winCol; fillPaint.alpha = (masterAlpha * 0.92f).toInt()
        val ww = h * 0.095f; val wh = h * 0.17f
        canvas.drawRect(cx - h * 0.48f, by - h * 0.42f, cx - h * 0.48f + ww, by - h * 0.42f + wh, fillPaint)
        canvas.drawRect(cx - h * 0.18f, by - h * 0.42f, cx - h * 0.18f + ww, by - h * 0.42f + wh, fillPaint)
        canvas.drawRect(cx + h * 0.10f, by - h * 0.42f, cx + h * 0.10f + ww, by - h * 0.42f + wh, fillPaint)
    }

    private fun drawLavenderRows(
        canvas: Canvas, w: Int, h: Int, density: Float, shiftY: Float,
        isDay: Boolean, lightColor: Int, deepVelvet: Int, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val topY = h * 0.656f + shiftY
        val botY = h + 14f
        val midY1 = topY + (botY - topY) * 0.36f
        val midY2 = topY + (botY - topY) * 0.72f
        val vx = w * 0.60f
        val lavShade = ColorUtils.blendARGB(if (isDay) 0xFF583A84.toInt() else 0xFF1D1A3E.toInt(), deepVelvet, 0.25f)
        val lavLit = ColorUtils.blendARGB(if (isDay) 0xFF9872C6.toInt() else 0xFF342E64.toInt(), lightColor, if (isDay) 0.28f else 0.18f)
        val windFreq = 1.6 + (steadyWind / 25.0).coerceIn(0.0, 1.5) * 1.4

        for (i in 0 until 13) {
            val u = (i / 12f) * 2f - 1f
            val tx = vx + u * w * 0.048f
            val bx = vx + u * w * 1.18f
            // Gentle rolling hillside S-curve
            val sBend = sin(u * 2.1).toFloat() * w * 0.045f + w * 0.032f
            val c1x = tx + (bx - tx) * 0.32f - sBend * 0.65f
            val c2x = tx + (bx - tx) * 0.70f + sBend * 0.85f
            val topHalfW = density * 1.3f
            val botHalfW = w * 0.036f
            val shimmer = (0.5f + 0.5f * sin(timeSec * windFreq + i * 0.75).toFloat())

            // Full shaded mound volume
            fillPaint.color = lavShade; fillPaint.alpha = (masterAlpha * 0.90f).toInt()
            dynPath.reset()
            dynPath.moveTo(tx - topHalfW, topY)
            dynPath.cubicTo(c1x - botHalfW * 0.32f, midY1, c2x - botHalfW * 0.70f, midY2, bx - botHalfW, botY)
            dynPath.lineTo(bx + botHalfW, botY)
            dynPath.cubicTo(c2x + botHalfW * 0.70f, midY2, c1x + botHalfW * 0.32f, midY1, tx + topHalfW, topY)
            dynPath.close()
            canvas.drawPath(dynPath, fillPaint)

            // Sunlit lavender crest highlight with gentle breeze shimmer
            fillPaint.color = lavLit; fillPaint.alpha = (masterAlpha * (0.70f + shimmer * 0.20f)).toInt()
            dynPath.reset()
            dynPath.moveTo(tx - topHalfW * 0.8f, topY)
            dynPath.cubicTo(c1x - botHalfW * 0.28f, midY1, c2x - botHalfW * 0.62f, midY2, bx - botHalfW * 0.85f, botY)
            dynPath.lineTo(bx + botHalfW * 0.12f, botY)
            dynPath.cubicTo(c2x + botHalfW * 0.08f, midY2, c1x + botHalfW * 0.04f, midY1, tx + topHalfW * 0.15f, topY)
            dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }
    }

    private fun drawSantoriniDetails(
        canvas: Canvas, w: Int, h: Int, density: Float, cliffShift: Float,
        isDay: Boolean, lightColor: Int, deepVelvet: Int, sunAlt: Float, steadyWind: Float, timeSec: Double, masterAlpha: Int
    ) {
        val whiteCol = if (isDay) 0xFFF9F8F4.toInt() else ColorUtils.blendARGB(0xFF94A4C0.toInt(), lightColor, 0.25f)

        // 1. Crisp white Cycladic crosses atop the 3 cobalt domes
        strokePaint.color = whiteCol; strokePaint.alpha = (masterAlpha * 0.94f).toInt(); strokePaint.strokeWidth = density * 1.15f
        for (idx in 0 until 3) {
            val cx = w * when (idx) { 0 -> 0.67f; 1 -> 0.80f; else -> 0.90f }
            val by = h * when (idx) { 0 -> 0.530f; 1 -> 0.468f; else -> 0.518f } + cliffShift
            val sc = when (idx) { 0 -> 0.030f; 1 -> 0.036f; else -> 0.028f }
            val domeTopY = by - h * sc * 0.72f - w * sc * 1.8f * 0.42f
            val crossH = density * 5.2f
            canvas.drawLine(cx, domeTopY + density * 0.5f, cx, domeTopY - crossH, strokePaint)
            canvas.drawLine(cx - crossH * 0.42f, domeTopY - crossH * 0.62f, cx + crossH * 0.42f, domeTopY - crossH * 0.62f, strokePaint)
        }

        // 2. Magenta bougainvillea vine clusters tumbling over whitewashed terrace walls
        val bougCol = if (isDay) ColorUtils.blendARGB(0xFFD62B72.toInt(), lightColor, 0.16f)
                      else ColorUtils.blendARGB(0xFF5A1A3C.toInt(), deepVelvet, 0.30f)
        fillPaint.color = bougCol; fillPaint.alpha = (masterAlpha * 0.90f).toInt()
        for (b in 0 until 4) {
            val bx = w * when (b) { 0 -> 0.635f; 1 -> 0.745f; 2 -> 0.845f; else -> 0.675f }
            val by = h * when (b) { 0 -> 0.576f; 1 -> 0.548f; 2 -> 0.518f; else -> 0.636f } + cliffShift
            val r = density * (3.6f + (b % 2) * 0.9f)
            canvas.drawCircle(bx, by, r, fillPaint)
            canvas.drawCircle(bx + r * 0.85f, by + r * 0.35f, r * 0.78f, fillPaint)
            canvas.drawCircle(bx - r * 0.72f, by + r * 0.45f, r * 0.70f, fillPaint)
        }

        // 3. Classic whitewashed Oia Cycladic windmill on upper-left cliff shoulder with rotating canvas sails
        val wmX = w * 0.585f; val wmBaseY = h * 0.586f + cliffShift
        val wmH = h * 0.042f; val wmW = wmH * 0.44f
        val wallShade = ColorUtils.blendARGB(whiteCol, deepVelvet, if (isDay) 0.26f else 0.42f)
        fillPaint.color = whiteCol; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(wmX - wmW * 0.58f, wmBaseY); dynPath.lineTo(wmX - wmW * 0.44f, wmBaseY - wmH * 0.74f)
        dynPath.lineTo(wmX + wmW * 0.08f, wmBaseY - wmH * 0.74f); dynPath.lineTo(wmX + wmW * 0.12f, wmBaseY); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        fillPaint.color = wallShade; fillPaint.alpha = masterAlpha
        dynPath.reset()
        dynPath.moveTo(wmX + wmW * 0.08f, wmBaseY - wmH * 0.74f); dynPath.lineTo(wmX + wmW * 0.44f, wmBaseY - wmH * 0.74f)
        dynPath.lineTo(wmX + wmW * 0.58f, wmBaseY); dynPath.lineTo(wmX + wmW * 0.12f, wmBaseY); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        // Conical Cycladic thatch/terracotta roof cap
        fillPaint.color = if (isDay) ColorUtils.blendARGB(0xFFA66B44.toInt(), lightColor, 0.20f) else ColorUtils.blendARGB(0xFF42302A.toInt(), deepVelvet, 0.35f)
        dynPath.reset()
        dynPath.moveTo(wmX - wmW * 0.54f, wmBaseY - wmH * 0.72f)
        dynPath.lineTo(wmX, wmBaseY - wmH * 1.04f)
        dynPath.lineTo(wmX + wmW * 0.54f, wmBaseY - wmH * 0.72f); dynPath.close()
        canvas.drawPath(dynPath, fillPaint)
        // 8 rotating wooden spars + triangular white canvas sails
        val hubY = wmBaseY - wmH * 0.76f
        val sailR = wmH * 0.62f
        val rotAng = timeSec * (0.35 + (steadyWind / 24.0).coerceIn(0.0, 1.8) * 0.75)
        strokePaint.color = ColorUtils.blendARGB(deepVelvet, 0xFF5A4030.toInt(), 0.45f)
        strokePaint.alpha = masterAlpha; strokePaint.strokeWidth = density * 0.9f
        fillPaint.color = whiteCol; fillPaint.alpha = (masterAlpha * 0.86f).toInt()
        for (s in 0 until 8) {
            val a = rotAng + s * (PI / 4.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val px = -sa; val py = ca
            val tipX = wmX + ca * sailR; val tipY = hubY + sa * sailR
            canvas.drawLine(wmX, hubY, tipX, tipY, strokePaint)
            dynPath.reset()
            dynPath.moveTo(wmX + ca * sailR * 0.24f, hubY + sa * sailR * 0.24f)
            dynPath.lineTo(tipX, tipY)
            dynPath.lineTo(wmX + ca * sailR * 0.82f + px * sailR * 0.24f, hubY + sa * sailR * 0.82f + py * sailR * 0.24f)
            dynPath.close()
            canvas.drawPath(dynPath, fillPaint)
        }
        canvas.drawCircle(wmX, hubY, density * 1.6f, fillPaint)

        // 4. Warm golden village windows + soft amber halos after sunset
        val winStr = ((4f - sunAlt) / 7f).coerceIn(0f, 1f)
        if (winStr > 0.05f) {
            drawSoftMist(canvas, w * 0.74f, h * 0.57f + cliffShift, w * 0.24f, h * 0.11f, 0x00FFD066 or ((masterAlpha * 0.24f * winStr).toInt() shl 24))
            fillPaint.color = 0xFFFFD468.toInt(); fillPaint.alpha = (masterAlpha * 0.94f * winStr).toInt()
            for (wIdx in 0 until 10) {
                val wx = w * (0.59f + (wIdx % 5) * 0.072f)
                val wy = h * (0.605f - (wIdx % 5) * 0.028f + (wIdx / 5) * 0.036f) + cliffShift
                canvas.drawRect(wx - density * 1.3f, wy - density * 1.8f, wx + density * 1.3f, wy + density * 1.8f, fillPaint)
            }
        }
    }
}
