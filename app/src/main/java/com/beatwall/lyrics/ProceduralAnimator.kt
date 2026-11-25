package com.beatwall.lyrics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic, zero-allocation quasi-periodic physics engine (1 : sqrt(2) : phi)
 * for 120 Hz music & focus backdrops. Uses 3 distinct luminous pools with visible
 * orbital drift, cover-art mood regimes, and momentum/friction damping.
 */
class ProceduralAnimator {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val ovalRect = RectF()

    // Cached 3-slot RadialGradient pool for 0-byte/frame 120 Hz rendering
    private var cachedColA = 0
    private var cachedShaderA: RadialGradient? = null
    private var cachedColB = 0
    private var cachedShaderB: RadialGradient? = null
    private var cachedColC = 0
    private var cachedShaderC: RadialGradient? = null

    private var phaseRad = 0.0
    private var omega = 0.0
    private var theta0 = 0.42
    private var phi0 = 1.17

    fun setTrackSeed(trackKey: String?) {
        val h = (trackKey ?: "beatwall").hashCode()
        theta0 = ((h and 0xFFFF) / 65535.0) * 6.283185307179586
        phi0 = (((h ushr 16) and 0xFFFF) / 65535.0) * 6.283185307179586
    }

    fun stepPhysics(dtSec: Float, targetOmega: Double) {
        val dt = dtSec.coerceIn(0.001f, 0.05f).toDouble()
        val tau = if (targetOmega > omega) 0.40 else 0.60
        val blend = 1.0 - kotlin.math.exp(-dt / tau)
        omega += (targetOmega - omega) * blend
        if (abs(omega) < 0.0005 && targetOmega == 0.0) {
            omega = 0.0
        }
        phaseRad += omega * dt
    }

    fun isCoasting(): Boolean = omega > 0.002

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        accent: Int,
        primary: Int,
        secondary: Int,
        regime: Int,
        masterAlpha: Int
    ) {
        if (masterAlpha <= 2 || width <= 0 || height <= 0) return
        val t = phaseRad

        when (regime.coerceIn(0, 2)) {
            0 -> drawDeepBreathingHalo(canvas, width, height, t, primary, accent, masterAlpha)
            1 -> drawAuroraSilkDrift(canvas, width, height, t, accent, primary, secondary, masterAlpha)
            else -> drawBinaryOrbitalNebula(canvas, width, height, t, accent, primary, secondary, masterAlpha)
        }
        paint.shader = null
    }

    // Regime 0: Moody / Monochrome / Focus — calm breathing halo with subtle drift
    private fun drawDeepBreathingHalo(
        canvas: Canvas,
        w: Int,
        h: Int,
        t: Double,
        primary: Int,
        accent: Int,
        alpha: Int
    ) {
        val breath = 1.0f + 0.12f * sin(t * 1.15 + theta0).toFloat()
        val cx = w * 0.50f + sin(t * 0.85 + phi0).toFloat() * (w * 0.14f)
        val cy = h * 0.48f + cos(t * 0.85 * 1.4142 + theta0).toFloat() * (h * 0.10f)
        val ax = w * 0.50f - sin(t * 0.72 * 1.6180 + theta0).toFloat() * (w * 0.16f)
        val ay = h * 0.56f - cos(t * 0.72 + phi0).toFloat() * (h * 0.09f)

        val a1 = (alpha * 0.40f).toInt().coerceIn(0, 255)
        val a2 = (alpha * 0.28f).toInt().coerceIn(0, 255)
        drawCachedGlow(canvas, 0, cx, cy, w * 0.52f * breath, h * 0.34f * breath, (primary and 0x00FFFFFF) or (a1 shl 24))
        drawCachedGlow(canvas, 1, ax, ay, w * 0.42f, h * 0.28f, (accent and 0x00FFFFFF) or (a2 shl 24))
    }

    // Regime 1: Warm / Acoustic — 3 drifting silk aurora bands
    private fun drawAuroraSilkDrift(
        canvas: Canvas,
        w: Int,
        h: Int,
        t: Double,
        accent: Int,
        primary: Int,
        secondary: Int,
        alpha: Int
    ) {
        val cx1 = w * 0.48f + sin(t * 1.05 + theta0).toFloat() * (w * 0.26f)
        val cy1 = h * 0.40f + cos(t * 1.05 * 1.4142 + phi0).toFloat() * (h * 0.12f)
        val cx2 = w * 0.52f - sin(t * 0.95 * 1.6180 + phi0).toFloat() * (w * 0.24f)
        val cy2 = h * 0.62f + cos(t * 0.88 + theta0).toFloat() * (h * 0.11f)
        val cx3 = w * 0.50f + sin(t * 0.76 * 1.4142 + theta0 * 0.5).toFloat() * (w * 0.20f)
        val cy3 = h * 0.50f + sin(t * 0.92 * 1.6180).toFloat() * (h * 0.14f)

        val a1 = (alpha * 0.44f).toInt().coerceIn(0, 255)
        val a2 = (alpha * 0.38f).toInt().coerceIn(0, 255)
        val a3 = (alpha * 0.28f).toInt().coerceIn(0, 255)
        drawCachedGlow(canvas, 0, cx1, cy1, w * 0.58f, h * 0.24f, (primary and 0x00FFFFFF) or (a1 shl 24))
        drawCachedGlow(canvas, 1, cx2, cy2, w * 0.50f, h * 0.22f, (accent and 0x00FFFFFF) or (a2 shl 24))
        drawCachedGlow(canvas, 2, cx3, cy3, w * 0.44f, h * 0.20f, (secondary and 0x00FFFFFF) or (a3 shl 24))
    }

    // Regime 2: Vibrant / Cool — 3 luminous quasi-periodic 1 : sqrt(2) : phi orbiting pools
    private fun drawBinaryOrbitalNebula(
        canvas: Canvas,
        w: Int,
        h: Int,
        t: Double,
        accent: Int,
        primary: Int,
        secondary: Int,
        alpha: Int
    ) {
        // Primary center follows irrational 1 : 1.618 : 1.414 Lissajous drift
        val px = w * 0.50f +
            sin(t * 1.05 + theta0).toFloat() * (w * 0.22f) +
            sin(t * 1.05 * 1.6180 + phi0).toFloat() * (w * 0.10f)
        val py = h * 0.48f +
            cos(t * 1.05 * 1.4142 + phi0).toFloat() * (h * 0.15f)

        // Accent pool orbits around primary on an elliptical Keplerian path
        val orbitAng = t * 1.32 + theta0 * 0.5
        val ax = px + cos(orbitAng).toFloat() * (w * 0.26f)
        val ay = py + sin(orbitAng).toFloat() * (h * 0.16f)

        // Counter-orbiting secondary highlight pool for visible depth
        val orbitAng2 = -t * 0.92 * 1.6180 + phi0
        val sx = w * 0.50f + cos(orbitAng2).toFloat() * (w * 0.24f)
        val sy = h * 0.52f + sin(orbitAng2).toFloat() * (h * 0.18f)

        val breath = 1.0f + 0.11f * sin(t * 1.45 * 1.6180).toFloat()
        val a1 = (alpha * 0.48f).toInt().coerceIn(0, 255)
        val a2 = (alpha * 0.42f).toInt().coerceIn(0, 255)
        val a3 = (alpha * 0.30f).toInt().coerceIn(0, 255)
        drawCachedGlow(canvas, 0, px, py, w * 0.48f * breath, h * 0.30f * breath, (primary and 0x00FFFFFF) or (a1 shl 24))
        drawCachedGlow(canvas, 1, ax, ay, w * 0.40f, h * 0.26f, (accent and 0x00FFFFFF) or (a2 shl 24))
        drawCachedGlow(canvas, 2, sx, sy, w * 0.44f, h * 0.28f, (secondary and 0x00FFFFFF) or (a3 shl 24))
    }

    private fun drawCachedGlow(
        canvas: Canvas,
        slot: Int,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        colWithAlpha: Int
    ) {
        val midCol = (colWithAlpha and 0x00FFFFFF) or (((Color.alpha(colWithAlpha) * 0.38f).toInt()) shl 24)
        val sh = when (slot) {
            0 -> if (cachedColA == colWithAlpha && cachedShaderA != null) cachedShaderA!! else {
                RadialGradient(0f, 0f, 1f, intArrayOf(colWithAlpha, midCol, Color.TRANSPARENT), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
                    .also { cachedColA = colWithAlpha; cachedShaderA = it }
            }
            1 -> if (cachedColB == colWithAlpha && cachedShaderB != null) cachedShaderB!! else {
                RadialGradient(0f, 0f, 1f, intArrayOf(colWithAlpha, midCol, Color.TRANSPARENT), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
                    .also { cachedColB = colWithAlpha; cachedShaderB = it }
            }
            else -> if (cachedColC == colWithAlpha && cachedShaderC != null) cachedShaderC!! else {
                RadialGradient(0f, 0f, 1f, intArrayOf(colWithAlpha, midCol, Color.TRANSPARENT), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
                    .also { cachedColC = colWithAlpha; cachedShaderC = it }
            }
        }
        shaderMatrix.reset()
        shaderMatrix.setScale(rx, ry)
        shaderMatrix.postTranslate(cx, cy)
        sh.setLocalMatrix(shaderMatrix)
        paint.shader = sh
        ovalRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        canvas.drawOval(ovalRect, paint)
    }
}
