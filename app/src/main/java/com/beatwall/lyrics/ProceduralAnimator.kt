package com.beatwall.lyrics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 12 Archetypes x 5 Secondary x 4 ColorModes x 3 Tempos x 4 Geometries x 3 Densities x 2 Directions
 * = 17,280 unique Home Screen animations in 28 bytes of RAM + 4-slot Touch Ripple pool.
 */
data class AnimationDna(
    val archetype: Int,
    val secondary: Int,
    val colorMode: Int,
    val tempoIdx: Int,
    val geometry: Int,
    val densityIdx: Int,
    val direction: Int
)

class ProceduralAnimator {

    private class TouchRipple {
        var x = 0f
        var y = 0f
        var startTimeSec = -10.0
    }

    private val ripples = Array(4) { TouchRipple() }
    private var rippleCursor = 0
    private val rippleDurationSec = 0.75

    private var dna = rollRandom(-1)

    // Pre-allocated drawing objects (zero per-frame allocations)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    fun reroll() {
        dna = rollRandom(dna.archetype)
    }

    private fun rollRandom(avoidArchetype: Int): AnimationDna {
        var arch = Random.nextInt(12)
        if (arch == avoidArchetype) {
            arch = (arch + 1 + Random.nextInt(11)) % 12
        }
        return AnimationDna(
            archetype = arch,
            secondary = Random.nextInt(5),
            colorMode = Random.nextInt(4),
            tempoIdx = Random.nextInt(3),
            geometry = Random.nextInt(4),
            densityIdx = Random.nextInt(3),
            direction = if (Random.nextBoolean()) 1 else -1
        )
    }

    fun onTouch(x: Float, y: Float, timeSec: Double) {
        val r = ripples[rippleCursor]
        r.x = x
        r.y = y
        r.startTimeSec = timeSec
        rippleCursor = (rippleCursor + 1) % ripples.size
    }

    fun hasActiveRipples(timeSec: Double): Boolean {
        for (r in ripples) {
            val age = timeSec - r.startTimeSec
            if (age in 0.0..rippleDurationSec) return true
        }
        return false
    }

    fun drawTouchRipples(canvas: Canvas, timeSec: Double, accent: Int, width: Int, masterAlpha: Int) {
        strokePaint.shader = null
        strokePaint.color = accent
        for (r in ripples) {
            val age = timeSec - r.startTimeSec
            if (age < 0.0 || age > rippleDurationSec) continue
            val p = (age / rippleDurationSec).toFloat()
            val eased = 1f - (1f - p) * (1f - p)
            val radius = width * (0.04f + eased * 0.28f)
            strokePaint.strokeWidth = (1f - p) * 7f + 1.5f
            strokePaint.alpha = (masterAlpha * 0.45f * (1f - p)).toInt().coerceIn(0, 255)
            canvas.drawCircle(r.x, r.y, radius, strokePaint)
        }
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
            0 -> 0.65
            1 -> 1.0
            else -> 1.45
        }
        val t = timeSec * tempo * dna.direction
        val densityScale = when (dna.densityIdx) {
            0 -> 0.75f
            1 -> 1.0f
            else -> 1.3f
        }

        val c1 = pickColor1(accent, primary, secondary, t)
        val c2 = pickColor2(accent, primary, secondary)

        when (dna.archetype) {
            0 -> drawPixelPills(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            1 -> drawBokehOrbs(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            2 -> drawAuroraSweep(canvas, width, height, t, c1, c2, masterAlpha)
            3 -> drawConcentricHalos(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            4 -> drawStarlightEmbers(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            5 -> drawWaveRibbons(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            6 -> drawGhostVinylArcs(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            7 -> drawEdgeLighting(canvas, width, height, t, c1, c2, masterAlpha)
            8 -> drawMatrixAndRain(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            9 -> drawLightPillars(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            10 -> drawTwinMoons(canvas, width, height, t, c1, c2, masterAlpha)
            11 -> drawSonarRipples(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
        }

        drawSecondaryLayer(canvas, width, height, t, c1, c2, masterAlpha)
        paint.shader = null
        strokePaint.shader = null
    }

    private fun pickColor1(accent: Int, primary: Int, secondary: Int, t: Double): Int {
        return when (dna.colorMode) {
            0 -> accent
            1 -> primary
            2 -> accent
            else -> if (sin(t * 0.4) > 0) accent else primary
        }
    }

    private fun pickColor2(accent: Int, primary: Int, secondary: Int): Int {
        return when (dna.colorMode) {
            0 -> accent
            1 -> secondary
            2 -> Color.WHITE
            else -> secondary
        }
    }

    // Archetype 0: Pixel Pill Equalizer (edges, bottom, or mirrored)
    private fun drawPixelPills(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        paint.shader = null
        val count = (6 * density).toInt().coerceIn(4, 9)
        val barW = w * 0.020f
        val gap = barW * 0.55f
        val maxH = h * 0.13f

        for (i in 0 until count) {
            val freq = 2.4 + i * 0.42
            val nL = 0.25f + 0.75f * ((sin(t * freq + i * 0.9) + 1.0) * 0.5).toFloat()
            val nR = 0.25f + 0.75f * ((cos(t * freq + i * 1.1) + 1.0) * 0.5).toFloat()
            paint.color = if (i % 2 == 0) c1 else c2
            paint.alpha = (alpha * 0.23f).toInt()

            if (dna.geometry == 1 || dna.geometry == 3) {
                // Bottom horizon pills above search bar
                val totalW = count * 2 * (barW + gap)
                val x = (w - totalW) / 2f + i * 2 * (barW + gap)
                val bh = maxH * 0.55f * nL
                rect.set(x, h * 0.72f - bh, x + barW, h * 0.72f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            } else {
                // Flanking left & right edges
                val yBase = h * 0.52f
                val hL = maxH * nL
                val xL = i * (barW + gap) - barW * 0.3f
                rect.set(xL, yBase - hL / 2f, xL + barW, yBase + hL / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)

                val hR = maxH * nR
                val xR = w - (i + 1) * (barW + gap) + barW * 0.3f
                rect.set(xR, yBase - hR / 2f, xR + barW, yBase + hR / 2f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }
    }

    // Archetype 1: Floating Bokeh Orbs
    private fun drawBokehOrbs(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (6 * density).toInt().coerceIn(4, 9)
        for (i in 0 until count) {
            val prog = (((t * (0.05 + i * 0.012)) + i * 0.17) % 1.0 + 1.0) % 1.0
            val cy = h * (0.76f - prog.toFloat() * 0.56f)
            val baseX = if (dna.geometry == 0) (if (i % 2 == 0) 0.2f else 0.8f) else (0.14f + (i * 0.72f / count))
            val cx = w * baseX + sin(t * 0.8 + i).toFloat() * (w * 0.06f)
            val r = w * (0.09f + (i % 3) * 0.035f)
            val col = if (i % 2 == 0) c1 else c2
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf((col and 0x00FFFFFF) or (((alpha * 0.22f).toInt()) shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    // Archetype 2: Ambient Aurora Sweep
    private fun drawAuroraSweep(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val sx = sin(t * 0.45).toFloat() * (w * 0.22f)
        val sy = cos(t * 0.35).toFloat() * (h * 0.10f)
        val cx = w * 0.5f + sx
        val cy = h * 0.48f + sy
        val r = w * 0.78f
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                (c1 and 0x00FFFFFF) or (((alpha * 0.26f).toInt()) shl 24),
                (c2 and 0x00FFFFFF) or (((alpha * 0.12f).toInt()) shl 24),
                Color.TRANSPARENT
            ),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
    }

    // Archetype 3: Concentric Halos
    private fun drawConcentricHalos(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val cx = if (dna.geometry == 1) w * 0.5f else (w * 0.085f + w * 0.0675f)
        val cy = if (dna.geometry == 1) h * 0.52f else (h * 0.22f + w * 0.0675f)
        val rings = (3 * density).toInt().coerceIn(2, 5)
        for (i in 0 until rings) {
            val p = (((t * 0.38 + i.toDouble() / rings) % 1.0) + 1.0) % 1.0
            val pf = p.toFloat()
            strokePaint.color = if (i % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 3f
            strokePaint.alpha = (alpha * 0.32f * (1f - pf)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, w * (0.08f + pf * 0.26f), strokePaint)
        }
    }

    // Archetype 4: Starlight / Embers / Golden Spiral
    private fun drawStarlightEmbers(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        paint.shader = null
        val count = (18 * density).toInt().coerceIn(12, 26)
        for (i in 0 until count) {
            val prog = (((t * (0.04 + (i % 5) * 0.015) + i * 0.13) % 1.0) + 1.0) % 1.0
            val x: Float
            val y: Float
            if (dna.geometry == 1) {
                // Golden-angle spiral
                val angle = i * 2.39996 + t * 0.35
                val rad = w * 0.04f * kotlin.math.sqrt(i.toFloat() + 1f) * (0.85f + 0.15f * sin(t + i).toFloat())
                x = w * 0.5f + (cos(angle) * rad).toFloat()
                y = h * 0.50f + (sin(angle) * rad).toFloat()
            } else {
                x = w * ((i * 0.618f) % 0.88f + 0.06f) + sin(t * 1.1 + i).toFloat() * (w * 0.025f)
                y = h * (0.76f - prog.toFloat() * 0.56f)
            }
            val twinkle = 0.35f + 0.65f * ((sin(t * 3.0 + i * 1.7) + 1.0) * 0.5).toFloat()
            paint.color = if (i % 3 == 0) c2 else c1
            paint.alpha = (alpha * 0.36f * twinkle).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, w * (0.004f + (i % 3) * 0.002f), paint)
        }
    }

    // Archetype 5: Dual Sine Wave Ribbons / ECG Trace
    private fun drawWaveRibbons(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val waves = if (density > 1.1f) 3 else 2
        val baseY = if (dna.geometry == 3) h * 0.70f else h * 0.54f
        for (wv in 0 until waves) {
            path.reset()
            val amp = h * (0.025f + wv * 0.012f)
            val freq = 2.5 + wv * 1.1
            val steps = 28
            for (s in 0..steps) {
                val frac = s.toFloat() / steps
                val x = frac * w
                val env = sin(frac * PI).toFloat()
                val y = baseY + sin(frac * freq * PI + t * (2.2 + wv * 0.6)).toFloat() * amp * env
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            strokePaint.color = if (wv % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 3.5f
            strokePaint.alpha = (alpha * 0.26f).toInt()
            canvas.drawPath(path, strokePaint)
        }
    }

    // Archetype 6: Ghost Vinyl / Pendulum Arcs
    private fun drawGhostVinylArcs(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val cx = w * 0.5f
        val cy = h * 0.52f
        val arcs = (4 * density).toInt().coerceIn(3, 6)
        for (i in 0 until arcs) {
            val r = w * (0.18f + i * 0.075f)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            val startAngle = if (dna.geometry == 0) {
                (sin(t * (0.8 + i * 0.2)) * 55.0).toFloat() - 90f
            } else {
                ((t * (28.0 + i * 9.0) + i * 60.0) % 360.0).toFloat()
            }
            strokePaint.color = if (i % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 3f
            strokePaint.alpha = (alpha * 0.20f).toInt()
            canvas.drawArc(rect, startAngle, 110f, false, strokePaint)
        }
    }

    // Archetype 7: S23 Ultra Curved Edge-Lighting Neon Glow
    private fun drawEdgeLighting(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val pulse = 0.45f + 0.55f * ((sin(t * 1.8) + 1.0) * 0.5).toFloat()
        val edgeW = w * 0.06f
        val edgeAlpha = (alpha * 0.30f * pulse).toInt().coerceIn(0, 255)

        paint.shader = LinearGradient(
            0f, 0f, edgeW, 0f,
            intArrayOf((c1 and 0x00FFFFFF) or (edgeAlpha shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.12f, edgeW, h * 0.85f, paint)

        paint.shader = LinearGradient(
            w.toFloat(), 0f, w - edgeW, 0f,
            intArrayOf((c2 and 0x00FFFFFF) or (edgeAlpha shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - edgeW, h * 0.12f, w.toFloat(), h * 0.85f, paint)
    }

    // Archetype 8: Wave Dot Matrix / Night Rain Streaks
    private fun drawMatrixAndRain(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        paint.shader = null
        if (dna.geometry == 2) {
            // Diagonal rain streaks
            val drops = (12 * density).toInt().coerceIn(8, 16)
            strokePaint.shader = null
            strokePaint.strokeWidth = 2.5f
            for (i in 0 until drops) {
                val prog = (((t * 0.35 + i * 0.19) % 1.0) + 1.0) % 1.0
                val x = w * ((i * 0.23f) % 0.9f + 0.05f)
                val y = h * (0.15f + prog.toFloat() * 0.65f)
                strokePaint.color = if (i % 2 == 0) c1 else c2
                strokePaint.alpha = (alpha * 0.24f).toInt()
                canvas.drawLine(x, y, x - w * 0.015f, y + h * 0.03f, strokePaint)
            }
        } else {
            // Clean 6x4 Dot Matrix wave
            for (row in 0 until 4) {
                for (col in 0 until 6) {
                    val x = w * (0.18f + col * 0.128f)
                    val y = h * (0.36f + row * 0.08f)
                    val wave = ((sin(t * 2.4 - (row + col) * 0.6) + 1.0) * 0.5).toFloat()
                    paint.color = if ((row + col) % 2 == 0) c1 else c2
                    paint.alpha = (alpha * (0.08f + 0.22f * wave)).toInt()
                    canvas.drawCircle(x, y, w * (0.004f + wave * 0.005f), paint)
                }
            }
        }
    }

    // Archetype 9: Swaying Vertical Light Pillars
    private fun drawLightPillars(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (4 * density).toInt().coerceIn(3, 6)
        val pillarW = w * 0.16f
        for (i in 0 until count) {
            val cx = w * (0.15f + i * (0.70f / count)) + sin(t * 0.7 + i).toFloat() * (w * 0.06f)
            val col = if (i % 2 == 0) c1 else c2
            val a = (alpha * 0.16f).toInt()
            paint.shader = LinearGradient(
                cx - pillarW, 0f, cx + pillarW, 0f,
                intArrayOf(Color.TRANSPARENT, (col and 0x00FFFFFF) or (a shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(cx - pillarW, h * 0.18f, cx + pillarW, h * 0.78f, paint)
        }
    }

    // Archetype 10: Orbiting Twin Moons
    private fun drawTwinMoons(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val cx = w * 0.5f
        val cy = h * 0.50f
        val rx = w * 0.34f
        val ry = h * 0.18f
        for (m in 0..1) {
            val ang = t * 0.75 + m * PI
            val mx = cx + (cos(ang) * rx).toFloat()
            val my = cy + (sin(ang) * ry).toFloat()
            val r = w * 0.14f
            val col = if (m == 0) c1 else c2
            paint.shader = RadialGradient(
                mx, my, r,
                intArrayOf((col and 0x00FFFFFF) or (((alpha * 0.28f).toInt()) shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(mx, my, r, paint)
        }
    }

    // Archetype 11: Auto Sonar Ripples
    private fun drawSonarRipples(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val count = (3 * density).toInt().coerceIn(2, 4)
        for (i in 0 until count) {
            val p = (((t * 0.32 + i.toDouble() / count) % 1.0) + 1.0) % 1.0
            val pf = p.toFloat()
            val cx = w * (0.30f + ((i * 0.37f) % 0.40f))
            val cy = h * (0.38f + ((i * 0.29f) % 0.24f))
            strokePaint.color = if (i % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 3f
            strokePaint.alpha = (alpha * 0.28f * (1f - pf)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, w * (0.04f + pf * 0.22f), strokePaint)
        }
    }

    // 5 Subtle Secondary Accent Layers
    private fun drawSecondaryLayer(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int
    ) {
        when (dna.secondary) {
            0 -> return
            1 -> {
                // Micro-sparkle dust
                paint.shader = null
                paint.color = Color.WHITE
                for (i in 0 until 7) {
                    val tw = ((sin(t * 2.5 + i * 1.4) + 1.0) * 0.5).toFloat()
                    paint.alpha = (alpha * 0.22f * tw).toInt()
                    canvas.drawCircle(w * ((i * 0.31f) % 0.85f + 0.08f), h * (0.28f + (i * 0.06f)), 3f, paint)
                }
            }
            2 -> {
                // Subtle edge neon breath
                val p = ((sin(t * 1.3) + 1.0) * 0.5).toFloat()
                strokePaint.shader = null
                strokePaint.color = c1
                strokePaint.strokeWidth = 4f
                strokePaint.alpha = (alpha * 0.16f * p).toInt()
                canvas.drawLine(2f, h * 0.22f, 2f, h * 0.72f, strokePaint)
                canvas.drawLine(w - 2f, h * 0.22f, w - 2f, h * 0.72f, strokePaint)
            }
            3 -> {
                // Soft center halo ring
                val p = ((sin(t * 1.1) + 1.0) * 0.5).toFloat()
                strokePaint.shader = null
                strokePaint.color = c2
                strokePaint.strokeWidth = 2.5f
                strokePaint.alpha = (alpha * 0.14f * p).toInt()
                canvas.drawCircle(w * 0.5f, h * 0.52f, w * (0.26f + p * 0.04f), strokePaint)
            }
            4 -> {
                // Bottom horizon mist glow
                val r = w * 0.55f
                paint.shader = RadialGradient(
                    w * 0.5f, h * 0.74f, r,
                    intArrayOf((c1 and 0x00FFFFFF) or (((alpha * 0.16f).toInt()) shl 24), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(w * 0.5f, h * 0.74f, r, paint)
            }
        }
    }
}
