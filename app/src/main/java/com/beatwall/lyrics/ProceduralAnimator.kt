package com.beatwall.lyrics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 100% Volumetric Light Engine (Zero rings, zero arcs, zero wireframe lines).
 * 6 Volumetric Archetypes x 4 ColorModes x 3 Tempos x 3 Densities x 2 Directions = 432 combinations
 * at an elegant, noticeable 25%-38% opacity in both Music and Circadian modes.
 */
data class AnimationDna(
    val archetype: Int,
    val colorMode: Int,
    val tempoIdx: Int,
    val densityIdx: Int,
    val direction: Int
)

class ProceduralAnimator {

    private var dna = rollRandom(-1, calmOnly = false)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun reroll(calmOnly: Boolean = false) {
        dna = rollRandom(dna.archetype, calmOnly)
    }

    private fun rollRandom(avoidArchetype: Int, calmOnly: Boolean): AnimationDna {
        var arch = Random.nextInt(6)
        if (arch == avoidArchetype) {
            arch = (arch + 1 + Random.nextInt(5)) % 6
        }
        return AnimationDna(
            archetype = arch,
            colorMode = Random.nextInt(4),
            tempoIdx = if (calmOnly) 0 else Random.nextInt(3),
            densityIdx = Random.nextInt(3),
            direction = if (Random.nextBoolean()) 1 else -1
        )
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        timeSec: Double,
        accent: Int,
        primary: Int,
        secondary: Int,
        masterAlpha: Int
    ) {
        val tempo = when (dna.tempoIdx) {
            0 -> 0.55
            1 -> 0.85
            else -> 1.15
        }
        val t = timeSec * tempo * dna.direction
        val densityScale = when (dna.densityIdx) {
            0 -> 0.8f
            1 -> 1.0f
            else -> 1.25f
        }

        val c1 = pickColor1(accent, primary, t)
        val c2 = pickColor2(accent, primary, secondary)

        when (dna.archetype) {
            0 -> drawAuroraClouds(canvas, width, height, t, c1, c2, masterAlpha)
            1 -> drawLavaLampBlobs(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            2 -> drawBokehLightPools(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            3 -> drawDiagonalGodRays(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            4 -> drawOrbitingNebulas(canvas, width, height, t, c1, c2, masterAlpha)
            5 -> drawSoftEdgeNebula(canvas, width, height, t, c1, c2, masterAlpha)
        }
        paint.shader = null
    }

    private fun pickColor1(accent: Int, primary: Int, t: Double): Int {
        return when (dna.colorMode) {
            0 -> accent
            1 -> primary
            2 -> accent
            else -> if (sin(t * 0.35) > 0) accent else primary
        }
    }

    private fun pickColor2(accent: Int, primary: Int, secondary: Int): Int {
        return when (dna.colorMode) {
            0 -> primary
            1 -> secondary
            2 -> Color.WHITE
            else -> secondary
        }
    }

    // 0: Dual drifting aurora clouds
    private fun drawAuroraClouds(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val cx1 = w * 0.45f + sin(t * 0.42).toFloat() * (w * 0.24f)
        val cy1 = h * 0.44f + cos(t * 0.32).toFloat() * (h * 0.12f)
        val r1 = w * 0.82f
        paint.shader = RadialGradient(
            cx1, cy1, r1,
            intArrayOf((c1 and 0x00FFFFFF) or (((alpha * 0.34f).toInt()) shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx1, cy1, r1, paint)

        val cx2 = w * 0.55f - sin(t * 0.36).toFloat() * (w * 0.22f)
        val cy2 = h * 0.62f - cos(t * 0.28).toFloat() * (h * 0.10f)
        val r2 = w * 0.72f
        paint.shader = RadialGradient(
            cx2, cy2, r2,
            intArrayOf((c2 and 0x00FFFFFF) or (((alpha * 0.26f).toInt()) shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx2, cy2, r2, paint)
    }

    // 1: Slow Lava-Lamp color blobs
    private fun drawLavaLampBlobs(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (4 * density).toInt().coerceIn(3, 5)
        for (i in 0 until count) {
            val cx = w * (0.22f + (i * 0.56f / (count - 1).coerceAtLeast(1))) +
                sin(t * (0.5 + i * 0.12) + i * 1.3).toFloat() * (w * 0.10f)
            val cy = h * 0.50f + cos(t * (0.4 + i * 0.11) + i * 0.9).toFloat() * (h * 0.22f)
            val r = w * (0.28f + (i % 2) * 0.10f)
            val col = if (i % 2 == 0) c1 else c2
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    (col and 0x00FFFFFF) or (((alpha * 0.30f).toInt()) shl 24),
                    (col and 0x00FFFFFF) or (((alpha * 0.10f).toInt()) shl 24),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    // 2: Soft Bokeh light pools
    private fun drawBokehLightPools(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (5 * density).toInt().coerceIn(4, 7)
        for (i in 0 until count) {
            val prog = (((t * (0.045 + i * 0.011)) + i * 0.19) % 1.0 + 1.0) % 1.0
            val cy = h * (0.80f - prog.toFloat() * 0.64f)
            val cx = w * (0.14f + (i * 0.72f / count)) + sin(t * 0.7 + i).toFloat() * (w * 0.06f)
            val r = w * (0.14f + (i % 3) * 0.045f)
            val col = if (i % 2 == 0) c1 else c2
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf((col and 0x00FFFFFF) or (((alpha * 0.28f).toInt()) shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    // 3: Soft Diagonal God-Rays / Light Pillars
    private fun drawDiagonalGodRays(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (3 * density).toInt().coerceIn(3, 5)
        val beamW = w * 0.22f
        for (i in 0 until count) {
            val cx = w * (0.18f + i * (0.64f / (count - 1).coerceAtLeast(1))) +
                sin(t * 0.45 + i * 1.1).toFloat() * (w * 0.08f)
            val col = if (i % 2 == 0) c1 else c2
            val pulse = 0.55f + 0.45f * ((sin(t * 0.8 + i) + 1.0) * 0.5).toFloat()
            val a = (alpha * 0.24f * pulse).toInt()
            paint.shader = LinearGradient(
                cx - beamW, h * 0.15f, cx + beamW, h * 0.80f,
                intArrayOf(Color.TRANSPARENT, (col and 0x00FFFFFF) or (a shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.10f, w.toFloat(), h * 0.86f, paint)
        }
    }

    // 4: Twin Orbiting Nebulas
    private fun drawOrbitingNebulas(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val cx = w * 0.5f
        val cy = h * 0.50f
        val rx = w * 0.34f
        val ry = h * 0.20f
        for (m in 0..1) {
            val ang = t * 0.55 + m * PI
            val mx = cx + (cos(ang) * rx).toFloat()
            val my = cy + (sin(ang) * ry).toFloat()
            val r = w * 0.36f
            val col = if (m == 0) c1 else c2
            paint.shader = RadialGradient(
                mx, my, r,
                intArrayOf((col and 0x00FFFFFF) or (((alpha * 0.32f).toInt()) shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(mx, my, r, paint)
        }
    }

    // 5: Feathered Edge Nebula Glow (zero hard lines)
    private fun drawSoftEdgeNebula(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val p1 = 0.55f + 0.45f * ((sin(t * 0.9) + 1.0) * 0.5).toFloat()
        val p2 = 0.55f + 0.45f * ((cos(t * 0.8) + 1.0) * 0.5).toFloat()
        val r = w * 0.48f

        paint.shader = RadialGradient(
            0f, h * 0.42f, r,
            intArrayOf((c1 and 0x00FFFFFF) or (((alpha * 0.34f * p1).toInt()) shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, h * 0.42f, r, paint)

        paint.shader = RadialGradient(
            w.toFloat(), h * 0.62f, r,
            intArrayOf((c2 and 0x00FFFFFF) or (((alpha * 0.34f * p2).toInt()) shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w.toFloat(), h * 0.62f, r, paint)
    }
}
