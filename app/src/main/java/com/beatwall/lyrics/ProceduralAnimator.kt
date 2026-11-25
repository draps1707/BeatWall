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
 * for 120 Hz music & focus backdrops. Replaces dice-roll randomization with
 * song-seeded orbital phase, cover-art mood regimes, and momentum/friction damping.
 */
class ProceduralAnimator {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val shaderMatrix = Matrix()
    private val ovalRect = RectF()

    // Cached 2-slot RadialGradient pool for 0-byte/frame 120 Hz rendering
    private var cachedColA = 0
    private var cachedShaderA: RadialGradient? = null
    private var cachedColB = 0
    private var cachedShaderB: RadialGradient? = null

    // Continuous momentum/friction phase integrator (never jumps on song skip or pause)
    private var phaseRad = 0.0
    private var omega = 0.0
    private var theta0 = 0.42
    private var phi0 = 1.17

    fun setTrackSeed(trackKey: String?) {
        val h = (trackKey ?: "beatwall").hashCode()
        val u1 = ((h and 0xFFFF) / 65535.0) * 6.283185307179586
        val u2 = (((h ushr 16) and 0xFFFF) / 65535.0) * 6.283185307179586
        theta0 = u1
        phi0 = u2
    }

    fun stepPhysics(dtSec: Float, targetOmega: Double) {
        val dt = dtSec.coerceIn(0.001f, 0.05f).toDouble()
        // Smooth exponential momentum/friction ramp (coasts gently to rest on pause)
        val tau = if (targetOmega > omega) 0.45 else 0.65
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
            0 -> drawDeepBreathingHalo(canvas, width, height, t, primary, secondary, masterAlpha)
            1 -> drawAuroraSilkDrift(canvas, width, height, t, accent, primary, masterAlpha)
            else -> drawBinaryOrbitalNebula(canvas, width, height, t, accent, primary, masterAlpha)
        }
        paint.shader = null
    }

    // Regime 0: Moody / Monochrome — minimal OLED-clean breathing halo
    private fun drawDeepBreathingHalo(
        canvas: Canvas,
        w: Int,
        h: Int,
        t: Double,
        primary: Int,
        secondary: Int,
        alpha: Int
    ) {
        val breath = 1.0f + 0.09f * sin(t * 0.55 + theta0).toFloat()
        val cx = w * 0.50f + sin(t * 0.31 + phi0).toFloat() * (w * 0.06f)
        val cy = h * 0.48f + cos(t * 0.31 * 1.4142 + theta0).toFloat() * (h * 0.05f)
        val a1 = (alpha * 0.28f).toInt().coerceIn(0, 255)
        val a2 = (alpha * 0.16f).toInt().coerceIn(0, 255)
        drawCachedGlow(canvas, 0, cx, cy, w * 0.68f * breath, h * 0.46f * breath, (primary and 0x00FFFFFF) or (a1 shl 24))
        drawCachedGlow(canvas, 1, w * 0.50f, h * 0.60f, w * 0.72f, h * 0.42f, (secondary and 0x00FFFFFF) or (a2 shl 24))
    }

    // Regime 1: Warm / Acoustic — slow horizontal aurora wave-bands
    private fun drawAuroraSilkDrift(
        canvas: Canvas,
        w: Int,
        h: Int,
        t: Double,
        accent: Int,
        primary: Int,
        alpha: Int
    ) {
        val cx1 = w * 0.48f + sin(t * 0.42 + theta0).toFloat() * (w * 0.22f)
        val cy1 = h * 0.43f + cos(t * 0.42 * 1.4142 + phi0).toFloat() * (h * 0.09f)
        val cx2 = w * 0.52f - sin(t * 0.42 * 1.6180 + phi0).toFloat() * (w * 0.20f)
        val cy2 = h * 0.62f + cos(t * 0.34 + theta0).toFloat() * (h * 0.08f)

        val a1 = (alpha * 0.30f).toInt().coerceIn(0, 255)
        val a2 = (alpha * 0.24f).toInt().coerceIn(0, 255)
        drawCachedGlow(canvas, 0, cx1, cy1, w * 0.85f, h * 0.34f, (primary and 0x00FFFFFF) or (a1 shl 24))
        drawCachedGlow(canvas, 1, cx2, cy2, w * 0.78f, h * 0.32f, (accent and 0x00FFFFFF) or (a2 shl 24))
    }

    // Regime 2: Vibrant / Cool — quasi-periodic 1 : sqrt(2) : phi coupled binary orbital nebula
    private fun drawBinaryOrbitalNebula(
        canvas: Canvas,
        w: Int,
        h: Int,
        t: Double,
        accent: Int,
        primary: Int,
        alpha: Int
    ) {
        // Primary center follows irrational 1 : 1.618 : 1.414 Lissajous drift
        val px = w * 0.50f +
            sin(t * 0.38 + theta0).toFloat() * (w * 0.16f) +
            sin(t * 0.38 * 1.6180 + phi0).toFloat() * (w * 0.07f)
        val py = h * 0.48f +
            cos(t * 0.38 * 1.4142 + phi0).toFloat() * (h * 0.12f)

        // Secondary accent orbits around primary on an elliptical Keplerian path
        val orbitAng = t * 0.54 + theta0 * 0.5
        val ax = px + cos(orbitAng).toFloat() * (w * 0.22f)
        val ay = py + sin(orbitAng).toFloat() * (h * 0.14f)

        val breath = 1.0f + 0.08f * sin(t * 0.62 * 1.6180).toFloat()
        val a1 = (alpha * 0.32f).toInt().coerceIn(0, 255)
        val a2 = (alpha * 0.25f).toInt().coerceIn(0, 255)
        drawCachedGlow(canvas, 0, px, py, w * 0.76f * breath, h * 0.46f * breath, (primary and 0x00FFFFFF) or (a1 shl 24))
        drawCachedGlow(canvas, 1, ax, ay, w * 0.64f, h * 0.40f, (accent and 0x00FFFFFF) or (a2 shl 24))
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
        val sh = if (slot == 0) {
            if (cachedColA == colWithAlpha && cachedShaderA != null) {
                cachedShaderA!!
            } else {
                RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(colWithAlpha, (colWithAlpha and 0x00FFFFFF) or (((Color.alpha(colWithAlpha) * 0.34f).toInt()) shl 24), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                ).also {
                    cachedColA = colWithAlpha
                    cachedShaderA = it
                }
            }
        } else {
            if (cachedColB == colWithAlpha && cachedShaderB != null) {
                cachedShaderB!!
            } else {
                RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(colWithAlpha, (colWithAlpha and 0x00FFFFFF) or (((Color.alpha(colWithAlpha) * 0.34f).toInt()) shl 24), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                ).also {
                    cachedColB = colWithAlpha
                    cachedShaderB = it
                }
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
