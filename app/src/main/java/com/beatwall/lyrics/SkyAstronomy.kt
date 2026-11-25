package com.beatwall.lyrics

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class SkySnapshot(
    val sunAltDeg: Float,
    val sunAzDeg: Float,
    val sunColor: Int,
    val sunCoronaColor: Int,
    val moonAltDeg: Float,
    val moonAzDeg: Float,
    val moonIllumination: Float,
    val isWaxing: Boolean,
    val moonLimbTiltDeg: Float,
    val moonSizeScale: Float,
    val sunriseLocalHours: Double,
    val sunsetLocalHours: Double
)

object SkyAstronomy {

    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI

    fun compute(utcMillis: Long, latDeg: Double, lonDeg: Double): SkySnapshot {
        val jd = utcMillis / 86_400_000.0 + 2440587.5
        val d = jd - 2451545.0

        val latRad = latDeg * DEG2RAD
        val epsDeg = 23.439 - 0.0000004 * d
        val epsRad = epsDeg * DEG2RAD

        // 1. Sun Ecliptic & Equatorial Coordinates
        val sunL = normDeg(280.460 + 0.9856474 * d)
        val sunG = normDeg(357.528 + 0.9856003 * d)
        val sunGRad = sunG * DEG2RAD
        val lambdaSunDeg = normDeg(sunL + 1.915 * sin(sunGRad) + 0.020 * sin(2.0 * sunGRad))
        val lambdaSunRad = lambdaSunDeg * DEG2RAD

        val alphaSunRad = atan2(cos(epsRad) * sin(lambdaSunRad), cos(lambdaSunRad))
        val alphaSunDeg = normDeg(alphaSunRad * RAD2DEG)
        val deltaSunRad = asin(sin(epsRad) * sin(lambdaSunRad))

        val lstDeg = normDeg(280.4606 + 360.985647 * d + lonDeg)
        val hSunRad = (lstDeg - alphaSunDeg) * DEG2RAD

        val sunAltRad = asin(
            (sin(latRad) * sin(deltaSunRad) + cos(latRad) * cos(deltaSunRad) * cos(hSunRad))
                .coerceIn(-1.0, 1.0)
        )
        val sunAltDeg = (sunAltRad * RAD2DEG).toFloat()
        val sunAzDeg = normDeg(
            atan2(
                sin(hSunRad),
                cos(hSunRad) * sin(latRad) - tan(deltaSunRad) * cos(latRad)
            ) * RAD2DEG + 180.0
        ).toFloat()

        // 2. Moon Geocentric Ecliptic & Topocentric Horizontal Coordinates
        val moonL = normDeg(218.316 + 13.176396 * d)
        val moonM = normDeg(134.963 + 13.064993 * d)
        val moonF = normDeg(93.272 + 13.229350 * d)

        val moonMRad = moonM * DEG2RAD
        val moonFRad = moonF * DEG2RAD
        val lambdaMoonDeg = normDeg(moonL + 6.289 * sin(moonMRad))
        val betaMoonDeg = 5.128 * sin(moonFRad)

        val lambdaMoonRad = lambdaMoonDeg * DEG2RAD
        val betaMoonRad = betaMoonDeg * DEG2RAD

        val alphaMoonRad = atan2(
            sin(lambdaMoonRad) * cos(epsRad) - tan(betaMoonRad) * sin(epsRad),
            cos(lambdaMoonRad)
        )
        val alphaMoonDeg = normDeg(alphaMoonRad * RAD2DEG)
        val deltaMoonRad = asin(
            (sin(betaMoonRad) * cos(epsRad) + cos(betaMoonRad) * sin(epsRad) * sin(lambdaMoonRad))
                .coerceIn(-1.0, 1.0)
        )

        val hMoonRad = (lstDeg - alphaMoonDeg) * DEG2RAD
        val moonGeoAltRad = asin(
            (sin(latRad) * sin(deltaMoonRad) + cos(latRad) * cos(deltaMoonRad) * cos(hMoonRad))
                .coerceIn(-1.0, 1.0)
        )
        val moonGeoAltDeg = moonGeoAltRad * RAD2DEG
        val parallaxDeg = 0.95 * cos(moonGeoAltRad)
        val moonAltDeg = (moonGeoAltDeg - parallaxDeg).toFloat()

        val moonAzDeg = normDeg(
            atan2(
                sin(hMoonRad),
                cos(hMoonRad) * sin(latRad) - tan(deltaMoonRad) * cos(latRad)
            ) * RAD2DEG + 180.0
        ).toFloat()

        // 3. Exact Moon Illumination, Waxing/Waning, Apparent Size & Bright-Limb Tilt
        val cosPsi = (cos(betaMoonRad) * cos((lambdaMoonDeg - sunL) * DEG2RAD)).coerceIn(-1.0, 1.0)
        val illum = ((1.0 - cosPsi) * 0.5).toFloat().coerceIn(0f, 1f)
        val diffLon = normDeg(lambdaMoonDeg - lambdaSunDeg)
        val isWaxing = diffLon < 180.0

        // Elliptical distance variation (363,300 km perigee .. 405,500 km apogee)
        val distKm = 385000.0 - 20905.0 * cos(moonMRad)
        val moonSizeScale = (384400.0 / distKm).toFloat().coerceIn(0.93f, 1.07f)

        val qRad = atan2(
            sin(hMoonRad),
            tan(latRad) * cos(deltaMoonRad) - sin(deltaMoonRad) * cos(hMoonRad)
        )
        val dAlpha = alphaSunRad - alphaMoonRad
        val chi0Rad = atan2(
            cos(deltaSunRad) * sin(dAlpha),
            sin(deltaSunRad) * cos(deltaMoonRad) - cos(deltaSunRad) * sin(deltaMoonRad) * cos(dAlpha)
        )
        val limbTiltDeg = ((chi0Rad - qRad) * RAD2DEG).toFloat()

        // 4. Kasten-Young Air Mass Sun Color
        val (sunColor, coronaColor) = computeSunColors(sunAltDeg)

        // 5. Sunrise / Sunset Local Hours
        val h0Rad = -0.833 * DEG2RAD
        val cosH0 = ((sin(h0Rad) - sin(latRad) * sin(deltaSunRad)) / (cos(latRad) * cos(deltaSunRad)))
            .coerceIn(-1.0, 1.0)
        val h0Deg = acos(cosH0) * RAD2DEG
        val eotMin = 4.0 * normSignedDeg(sunL - alphaSunDeg)
        val solarNoonUtc = 12.0 - lonDeg / 15.0 - eotMin / 60.0
        val tzHours = TimeZone.getDefault().getOffset(utcMillis) / 3_600_000.0
        val sunriseLocal = ((solarNoonUtc - h0Deg / 15.0 + tzHours) % 24.0 + 24.0) % 24.0
        val sunsetLocal = ((solarNoonUtc + h0Deg / 15.0 + tzHours) % 24.0 + 24.0) % 24.0

        return SkySnapshot(
            sunAltDeg = sunAltDeg,
            sunAzDeg = sunAzDeg,
            sunColor = sunColor,
            sunCoronaColor = coronaColor,
            moonAltDeg = moonAltDeg,
            moonAzDeg = moonAzDeg,
            moonIllumination = illum,
            isWaxing = isWaxing,
            moonLimbTiltDeg = limbTiltDeg,
            moonSizeScale = moonSizeScale,
            sunriseLocalHours = sunriseLocal,
            sunsetLocalHours = sunsetLocal
        )
    }

    private fun computeSunColors(altDeg: Float): Pair<Int, Int> {
        val h = altDeg.toDouble().coerceAtLeast(-2.0)
        val m = if (h >= 0.0) {
            1.0 / (sin(h * DEG2RAD) + 0.50572 * (h + 6.07995).pow(-1.6364))
        } else {
            38.0 + abs(h) * 4.0
        }.coerceIn(1.0, 42.0)

        // Normalized warmth factor from air mass (m=1 -> 0f white-warm, m>=25 -> 1f deep sunset orange)
        val warmth = ((m - 1.5) / 22.0).toFloat().coerceIn(0f, 1f)
        val disc = when {
            warmth < 0.35f -> ColorUtils.blendARGB(0xFFFFFBEB.toInt(), 0xFFFEF08A.toInt(), warmth / 0.35f)
            warmth < 0.70f -> ColorUtils.blendARGB(0xFFFEF08A.toInt(), 0xFFFDBA74.toInt(), (warmth - 0.35f) / 0.35f)
            else -> ColorUtils.blendARGB(0xFFFDBA74.toInt(), 0xFFFB7185.toInt(), (warmth - 0.70f) / 0.30f)
        }
        val corona = when {
            warmth < 0.40f -> ColorUtils.blendARGB(0xFFFDE68A.toInt(), 0xFFFBBF24.toInt(), warmth / 0.40f)
            else -> ColorUtils.blendARGB(0xFFFBBF24.toInt(), 0xFFF97316.toInt(), (warmth - 0.40f) / 0.60f)
        }
        return disc to corona
    }

    private fun normDeg(deg: Double): Double {
        var x = deg % 360.0
        if (x < 0.0) x += 360.0
        return x
    }

    private fun normSignedDeg(deg: Double): Double {
        var x = normDeg(deg)
        if (x > 180.0) x -= 360.0
        return x
    }

    fun formatDecimalHour(h: Double): String {
        val totalMin = (h * 60.0).toInt().coerceIn(0, 1439)
        val hr24 = totalMin / 60
        val min = totalMin % 60
        val amPm = if (hr24 >= 12) "PM" else "AM"
        val hr12 = when {
            hr24 == 0 -> 12
            hr24 > 12 -> hr24 - 12
            else -> hr24
        }
        return "%d:%02d %s".format(hr12, min, amPm)
    }
}
