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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Curated Gallery Edition: 12 Archetypes x 5 Secondary x 4 ColorModes x 3 Tempos x 4 Geometries x 3 Densities x 2 Directions
 * = 17,280 calm, museum-grade Home Screen ambient combinations in 28 bytes of RAM. Zero touch listeners.
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

    private var dna = rollRandom(-1)

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
            0 -> 0.75f
            1 -> 1.0f
            else -> 1.25f
        }

        val c1 = pickColor1(accent, primary, secondary, t)
        val c2 = pickColor2(accent, primary, secondary)

        when (dna.archetype) {
            0 -> drawPixelPills(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            1 -> drawBokehOrbs(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            2 -> drawChiaroscuroAurora(canvas, width, height, t, c1, c2, masterAlpha)
            3 -> drawConcentricHalos(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            4 -> drawThermalDustMotes(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            5 -> drawSilkHarmonicRibbons(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            6 -> drawAstrolabeArcs(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
            7 -> drawEdgeAtmosphere(canvas, width, height, t, c1, c2, masterAlpha)
            8 -> drawRainAndConstellation(canvas, width, height, t, c1, c2, densityScale, masterAlpha)
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
            else -> if (sin(t * 0.35) > 0) accent else primary
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

    private fun drawPixelPills(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        paint.shader = null
        val count = (5 * density).toInt().coerceIn(4, 7)
        val barW = w * 0.018f
        val gap = barW * 0.65f
        val maxH = h * 0.11f

        for (i in 0 until count) {
            val freq = 2.0 + i * 0.35
            val nL = 0.28f + 0.72f * ((sin(t * freq + i * 0.9) + 1.0) * 0.5).toFloat()
            val nR = 0.28f + 0.72f * ((cos(t * freq + i * 1.1) + 1.0) * 0.5).toFloat()
            paint.color = if (i % 2 == 0) c1 else c2
            paint.alpha = (alpha * 0.18f).toInt()

            if (dna.geometry == 1 || dna.geometry == 3) {
                val totalW = count * 2 * (barW + gap)
                val x = (w - totalW) / 2f + i * 2 * (barW + gap)
                val bh = maxH * 0.50f * nL
                rect.set(x, h * 0.72f - bh, x + barW, h * 0.72f)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            } else {
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

    private fun drawBokehOrbs(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (5 * density).toInt().coerceIn(4, 8)
        for (i in 0 until count) {
            val prog = (((t * (0.045 + i * 0.011)) + i * 0.17) % 1.0 + 1.0) % 1.0
            val cy = h * (0.76f - prog.toFloat() * 0.56f)
            val baseX = if (dna.geometry == 0) (if (i % 2 == 0) 0.22f else 0.78f) else (0.16f + (i * 0.68f / count))
            val cx = w * baseX + sin(t * 0.7 + i).toFloat() * (w * 0.05f)
            val r = w * (0.10f + (i % 3) * 0.035f)
            val col = if (i % 2 == 0) c1 else c2
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf((col and 0x00FFFFFF) or (((alpha * 0.20f).toInt()) shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun drawChiaroscuroAurora(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val sx = sin(t * 0.38).toFloat() * (w * 0.22f)
        val sy = cos(t * 0.28).toFloat() * (h * 0.10f)
        val cx = w * 0.5f + sx
        val cy = h * 0.48f + sy
        val r = w * 0.80f
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                (c1 and 0x00FFFFFF) or (((alpha * 0.24f).toInt()) shl 24),
                (c2 and 0x00FFFFFF) or (((alpha * 0.10f).toInt()) shl 24),
                Color.TRANSPARENT
            ),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
    }

    private fun drawConcentricHalos(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val cx = if (dna.geometry == 1) w * 0.5f else (w * 0.085f + w * 0.0675f)
        val cy = if (dna.geometry == 1) h * 0.52f else (h * 0.22f + w * 0.0675f)
        val rings = (3 * density).toInt().coerceIn(2, 4)
        for (i in 0 until rings) {
            val p = (((t * 0.32 + i.toDouble() / rings) % 1.0) + 1.0) % 1.0
            val pf = p.toFloat()
            strokePaint.color = if (i % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 2.2f
            strokePaint.alpha = (alpha * 0.24f * (1f - pf)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, w * (0.08f + pf * 0.25f), strokePaint)
        }
    }

    private fun drawThermalDustMotes(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        paint.shader = null
        val count = (16 * density).toInt().coerceIn(10, 22)
        for (i in 0 until count) {
            val prog = (((t * (0.035 + (i % 5) * 0.012) + i * 0.13) % 1.0) + 1.0) % 1.0
            val x: Float
            val y: Float
            if (dna.geometry == 1) {
                val angle = i * 2.39996 + t * 0.28
                val rad = w * 0.04f * sqrt(i.toFloat() + 1f) * (0.88f + 0.12f * sin(t + i).toFloat())
                x = w * 0.5f + (cos(angle) * rad).toFloat()
                y = h * 0.50f + (sin(angle) * rad).toFloat()
            } else {
                x = w * ((i * 0.618f) % 0.88f + 0.06f) + sin(t * 0.9 + i).toFloat() * (w * 0.025f)
                y = h * (0.76f - prog.toFloat() * 0.56f)
            }
            val twinkle = 0.35f + 0.65f * ((sin(t * 2.4 + i * 1.7) + 1.0) * 0.5).toFloat()
            paint.color = if (i % 3 == 0) c2 else c1
            paint.alpha = (alpha * 0.28f * twinkle).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, w * (0.003f + (i % 3) * 0.0015f), paint)
        }
    }

    private fun drawSilkHarmonicRibbons(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val waves = if (density > 1.1f) 3 else 2
        val baseY = if (dna.geometry == 3) h * 0.70f else h * 0.54f
        for (wv in 0 until waves) {
            path.reset()
            val amp = h * (0.022f + wv * 0.011f)
            val freq = 2.2 + wv * 0.9
            val steps = 28
            for (s in 0..steps) {
                val frac = s.toFloat() / steps
                val x = frac * w
                val env = sin(frac * PI).toFloat()
                val y = baseY + sin(frac * freq * PI + t * (1.8 + wv * 0.5)).toFloat() * amp * env
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            strokePaint.color = if (wv % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 2.5f
            strokePaint.alpha = (alpha * 0.22f).toInt()
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawAstrolabeArcs(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val cx = w * 0.5f
        val cy = h * 0.52f
        val arcs = (4 * density).toInt().coerceIn(3, 5)
        for (i in 0 until arcs) {
            val r = w * (0.19f + i * 0.075f)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            val startAngle = if (dna.geometry == 0) {
                (sin(t * (0.7 + i * 0.18)) * 50.0).toFloat() - 90f
            } else {
                ((t * (22.0 + i * 7.0) + i * 65.0) % 360.0).toFloat()
            }
            strokePaint.color = if (i % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 2.0f
            strokePaint.alpha = (alpha * 0.18f).toInt()
            canvas.drawArc(rect, startAngle, 105f, false, strokePaint)
        }
    }

    private fun drawEdgeAtmosphere(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val pulse = 0.45f + 0.55f * ((sin(t * 1.4) + 1.0) * 0.5).toFloat()
        val edgeW = w * 0.055f
        val edgeAlpha = (alpha * 0.24f * pulse).toInt().coerceIn(0, 255)

        paint.shader = LinearGradient(
            0f, 0f, edgeW, 0f,
            intArrayOf((c1 and 0x00FFFFFF) or (edgeAlpha shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.14f, edgeW, h * 0.84f, paint)

        paint.shader = LinearGradient(
            w.toFloat(), 0f, w - edgeW, 0f,
            intArrayOf((c2 and 0x00FFFFFF) or (edgeAlpha shl 24), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - edgeW, h * 0.14f, w.toFloat(), h * 0.84f, paint)
    }

    private fun drawRainAndConstellation(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        paint.shader = null
        if (dna.geometry == 2) {
            val drops = (10 * density).toInt().coerceIn(7, 14)
            strokePaint.shader = null
            strokePaint.strokeWidth = 2.0f
            for (i in 0 until drops) {
                val prog = (((t * 0.28 + i * 0.19) % 1.0) + 1.0) % 1.0
                val x = w * ((i * 0.23f) % 0.9f + 0.05f)
                val y = h * (0.16f + prog.toFloat() * 0.62f)
                strokePaint.color = if (i % 2 == 0) c1 else c2
                strokePaint.alpha = (alpha * 0.18f).toInt()
                canvas.drawLine(x, y, x - w * 0.012f, y + h * 0.026f, strokePaint)
            }
        } else {
            for (row in 0 until 4) {
                for (col in 0 until 6) {
                    val x = w * (0.18f + col * 0.128f)
                    val y = h * (0.36f + row * 0.08f)
                    val wave = ((sin(t * 2.0 - (row + col) * 0.6) + 1.0) * 0.5).toFloat()
                    paint.color = if ((row + col) % 2 == 0) c1 else c2
                    paint.alpha = (alpha * (0.06f + 0.16f * wave)).toInt()
                    canvas.drawCircle(x, y, w * (0.003f + wave * 0.004f), paint)
                }
            }
        }
    }

    private fun drawLightPillars(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        val count = (4 * density).toInt().coerceIn(3, 5)
        val pillarW = w * 0.16f
        for (i in 0 until count) {
            val cx = w * (0.15f + i * (0.70f / count)) + sin(t * 0.55 + i).toFloat() * (w * 0.06f)
            val col = if (i % 2 == 0) c1 else c2
            val a = (alpha * 0.15f).toInt()
            paint.shader = LinearGradient(
                cx - pillarW, 0f, cx + pillarW, 0f,
                intArrayOf(Color.TRANSPARENT, (col and 0x00FFFFFF) or (a shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(cx - pillarW, h * 0.18f, cx + pillarW, h * 0.78f, paint)
        }
    }

    private fun drawTwinMoons(canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int) {
        val cx = w * 0.5f
        val cy = h * 0.50f
        val rx = w * 0.34f
        val ry = h * 0.18f
        for (m in 0..1) {
            val ang = t * 0.65 + m * PI
            val mx = cx + (cos(ang) * rx).toFloat()
            val my = cy + (sin(ang) * ry).toFloat()
            val r = w * 0.14f
            val col = if (m == 0) c1 else c2
            paint.shader = RadialGradient(
                mx, my, r,
                intArrayOf((col and 0x00FFFFFF) or (((alpha * 0.24f).toInt()) shl 24), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(mx, my, r, paint)
        }
    }

    private fun drawSonarRipples(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, density: Float, alpha: Int
    ) {
        strokePaint.shader = null
        val count = (3 * density).toInt().coerceIn(2, 4)
        for (i in 0 until count) {
            val p = (((t * 0.28 + i.toDouble() / count) % 1.0) + 1.0) % 1.0
            val pf = p.toFloat()
            val cx = w * (0.30f + ((i * 0.37f) % 0.40f))
            val cy = h * (0.38f + ((i * 0.29f) % 0.24f))
            strokePaint.color = if (i % 2 == 0) c1 else c2
            strokePaint.strokeWidth = 2.2f
            strokePaint.alpha = (alpha * 0.22f * (1f - pf)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, w * (0.04f + pf * 0.22f), strokePaint)
        }
    }

    private fun drawSecondaryLayer(
        canvas: Canvas, w: Int, h: Int, t: Double, c1: Int, c2: Int, alpha: Int
    ) {
        when (dna.secondary) {
            0 -> return
            1 -> {
                paint.shader = null
                paint.color = Color.WHITE
                for (i in 0 until 7) {
                    val tw = ((sin(t * 2.2 + i * 1.4) + 1.0) * 0.5).toFloat()
                    paint.alpha = (alpha * 0.18f * tw).toInt()
                    canvas.drawCircle(w * ((i * 0.31f) % 0.85f + 0.08f), h * (0.28f + (i * 0.06f)), 2.5f, paint)
                }
            }
            2 -> {
                val p = ((sin(t * 1.1) + 1.0) * 0.5).toFloat()
                strokePaint.shader = null
                strokePaint.color = c1
                strokePaint.strokeWidth = 3f
                strokePaint.alpha = (alpha * 0.14f * p).toInt()
                canvas.drawLine(2f, h * 0.22f, 2f, h * 0.72f, strokePaint)
                canvas.drawLine(w - 2f, h * 0.22f, w - 2f, h * 0.72f, strokePaint)
            }
            3 -> {
                val p = ((sin(t * 1.0) + 1.0) * 0.5).toFloat()
                strokePaint.shader = null
                strokePaint.color = c2
                strokePaint.strokeWidth = 2f
                strokePaint.alpha = (alpha * 0.12f * p).toInt()
                canvas.drawCircle(w * 0.5f, h * 0.52f, w * (0.26f + p * 0.04f), strokePaint)
            }
            4 -> {
                val r = w * 0.55f
                paint.shader = RadialGradient(
                    w * 0.5f, h * 0.74f, r,
                    intArrayOf((c1 and 0x00FFFFFF) or (((alpha * 0.15f).toInt()) shl 24), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(w * 0.5f, h * 0.74f, r, paint)
            }
        }
    }
}
