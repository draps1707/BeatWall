package com.beatwall.lyrics

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import java.util.Calendar
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
import kotlin.random.Random

data class SkyBody(
    val name: String,
    val altDeg: Float,
    val azDeg: Float,
    val mag: Float,
    val color: Int
)

data class SkySnapshot(
    val sunAltDeg: Float,
    val sunAzDeg: Float,
    val sunColor: Int,
    val sunCoronaColor: Int,
    val moonAltDeg: Float,
    val moonAzDeg: Float,
    val moonColor: Int,
    val moonCoronaColor: Int,
    val moonIllumination: Float,
    val isWaxing: Boolean,
    val moonLimbTiltDeg: Float,
    val moonSizeScale: Float,
    val isLunarEclipse: Boolean,
    val isSolarEclipse: Boolean,
    val tideFrac: Float,
    val isMeteorShowerPeak: Boolean,
    val lstDeg: Double,
    val latDeg: Double,
    val planets: List<SkyBody>,
    val sunriseLocalHours: Double,
    val sunsetLocalHours: Double
)

object SkyAstronomy {

    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI

    private data class StarEntry(val raDeg: Double, val decDeg: Double, val mag: Float, val color: Int)

    // 35 brightest named stars + 80 deterministic sidereal filler stars
    private val starCatalog: List<StarEntry> by lazy {
        val named = listOf(
            StarEntry(101.287, -16.716, -1.46f, 0xFFCAE8FF.toInt()), // Sirius
            StarEntry(95.988, -52.696, -0.74f, 0xFFFFF8E7.toInt()),  // Canopus
            StarEntry(219.902, -60.834, -0.27f, 0xFFFFF1D0.toInt()), // Rigil Kentaurus
            StarEntry(213.915, 19.182, -0.05f, 0xFFFFC98B.toInt()),  // Arcturus
            StarEntry(279.234, 38.784, 0.03f, 0xFFD6E9FF.toInt()),   // Vega
            StarEntry(79.172, 45.998, 0.08f, 0xFFFFEBB5.toInt()),    // Capella
            StarEntry(78.634, -8.202, 0.13f, 0xFFBFDFFF.toInt()),    // Rigel
            StarEntry(114.825, 5.225, 0.34f, 0xFFFFF6E5.toInt()),    // Procyon
            StarEntry(24.429, -57.237, 0.46f, 0xFFBFDFFF.toInt()),   // Achernar
            StarEntry(88.793, 7.407, 0.50f, 0xFFFFAD75.toInt()),     // Betelgeuse
            StarEntry(210.956, -60.373, 0.61f, 0xFFBFDFFF.toInt()),  // Hadar
            StarEntry(297.696, 8.868, 0.76f, 0xFFEAF4FF.toInt()),    // Altair
            StarEntry(186.650, -63.099, 0.77f, 0xFFBFDFFF.toInt()),  // Acrux
            StarEntry(68.980, 16.509, 0.86f, 0xFFFFB87A.toInt()),    // Aldebaran
            StarEntry(247.352, -26.432, 0.96f, 0xFFFF9E6D.toInt()),  // Antares
            StarEntry(201.298, -11.161, 0.97f, 0xFFC5E3FF.toInt()),  // Spica
            StarEntry(116.329, 28.026, 1.14f, 0xFFFFDFB0.toInt()),   // Pollux
            StarEntry(344.413, -29.622, 1.16f, 0xFFE6F2FF.toInt()),  // Fomalhaut
            StarEntry(310.358, 45.280, 1.25f, 0xFFE6F2FF.toInt()),   // Deneb
            StarEntry(152.093, 11.967, 1.35f, 0xFFD1E8FF.toInt()),   // Regulus
            StarEntry(104.656, -28.972, 1.50f, 0xFFBFDFFF.toInt()),  // Adhara
            StarEntry(113.649, 31.888, 1.58f, 0xFFEAF4FF.toInt()),   // Castor
            StarEntry(263.402, -37.104, 1.62f, 0xFFBFDFFF.toInt()),  // Shaula
            StarEntry(81.283, 6.350, 1.64f, 0xFFC5E3FF.toInt()),     // Bellatrix
            StarEntry(81.573, 28.608, 1.65f, 0xFFCFE8FF.toInt()),    // Elnath
            StarEntry(84.053, -1.202, 1.69f, 0xFFBFDFFF.toInt()),    // Alnilam (Orion Belt)
            StarEntry(85.190, -1.943, 1.77f, 0xFFBFDFFF.toInt()),    // Alnitak (Orion Belt)
            StarEntry(83.002, -0.299, 2.23f, 0xFFBFDFFF.toInt()),    // Mintaka (Orion Belt)
            StarEntry(165.932, 61.751, 1.79f, 0xFFFFDFB0.toInt()),   // Dubhe (Big Dipper)
            StarEntry(165.460, 56.382, 2.37f, 0xFFEAF4FF.toInt()),   // Merak (Big Dipper)
            StarEntry(193.507, 55.959, 1.77f, 0xFFEAF4FF.toInt()),   // Alioth (Big Dipper)
            StarEntry(206.885, 49.313, 1.86f, 0xFFC5E3FF.toInt()),   // Alkaid (Big Dipper)
            StarEntry(37.954, 89.264, 1.98f, 0xFFFFF1D0.toInt()),    // Polaris
            StarEntry(51.081, 49.861, 1.80f, 0xFFFFF4DC.toInt()),    // Mirfak
            StarEntry(31.793, 23.462, 2.00f, 0xFFFFCFA0.toInt())     // Hamal
        )
        val rng = Random(1337)
        val filler = ArrayList<StarEntry>(80)
        for (i in 0 until 80) {
            val ra = rng.nextDouble() * 360.0
            val dec = asin(rng.nextDouble() * 2.0 - 1.0) * RAD2DEG
            val mag = 2.4f + rng.nextFloat() * 1.9f
            val col = when (i % 4) {
                0 -> 0xFFD6E9FF.toInt()
                1 -> 0xFFFFF6E5.toInt()
                2 -> 0xFFFFDFBA.toInt()
                else -> Color.WHITE
            }
            filler.add(StarEntry(ra, dec, mag, col))
        }
        named + filler
    }

    // Galactic plane points along Milky Way band (RA deg, Dec deg)
    private val milkyWayNodes = arrayOf(
        266.4 to -29.0, // Sagittarius (Galactic Center)
        285.0 to -5.0,  // Scutum / Aquila
        305.0 to 36.0,  // Cygnus
        335.0 to 55.0,  // Cepheus
        15.0 to 60.0,   // Cassiopeia
        65.0 to 42.0,   // Perseus
        95.0 to 12.0,   // Monoceros / Orion
        125.0 to -38.0, // Vela
        165.0 to -60.0  // Carina / Crux
    )

    fun compute(utcMillis: Long, latDeg: Double, lonDeg: Double): SkySnapshot {
        val jd = utcMillis / 86_400_000.0 + 2440587.5
        val d = jd - 2451545.0

        val latRad = latDeg * DEG2RAD
        val epsDeg = 23.439 - 0.0000004 * d
        val epsRad = epsDeg * DEG2RAD

        // 1. Sun
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
            atan2(sin(hSunRad), cos(hSunRad) * sin(latRad) - tan(deltaSunRad) * cos(latRad)) * RAD2DEG + 180.0
        ).toFloat()

        // 2. Moon
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
        val moonAltDeg = (moonGeoAltDeg - 0.95 * cos(moonGeoAltRad)).toFloat()
        val moonAzDeg = normDeg(
            atan2(sin(hMoonRad), cos(hMoonRad) * sin(latRad) - tan(deltaMoonRad) * cos(latRad)) * RAD2DEG + 180.0
        ).toFloat()

        // 3. Exact Moon Phase, Size, Bright-Limb Tilt & Eclipse Check
        val cosPsi = (cos(betaMoonRad) * cos((lambdaMoonDeg - sunL) * DEG2RAD)).coerceIn(-1.0, 1.0)
        val psiDeg = acos(cosPsi) * RAD2DEG
        val illum = ((1.0 - cosPsi) * 0.5).toFloat().coerceIn(0f, 1f)
        val isWaxing = normDeg(lambdaMoonDeg - lambdaSunDeg) < 180.0
        val distKm = 385000.0 - 20905.0 * cos(moonMRad)
        val moonSizeScale = (384400.0 / distKm).toFloat().coerceIn(0.93f, 1.07f)

        val isLunarEclipse = illum > 0.995f && abs(betaMoonDeg) < 0.90
        val isSolarEclipse = illum < 0.005f && psiDeg < 0.55

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

        // 4. Air-Mass Sun & Moon Colors
        val (sunColor, coronaColor) = computeSunColors(sunAltDeg)
        val (moonColor, moonCoronaColor) = computeMoonColors(moonAltDeg, isLunarEclipse)

        // 5. Gravitational Tidal Bulge (-1..+1)
        val tideFrac = (0.68 * cos(2.0 * hMoonRad) + 0.32 * cos(2.0 * hSunRad)).toFloat().coerceIn(-1f, 1f)

        // 6. Meteor Shower Peaks
        val cal = Calendar.getInstance().apply { timeInMillis = utcMillis }
        val mo = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val isShower = (mo == 1 && day in 3..4) ||
            (mo == 8 && day in 11..13) ||
            (mo == 10 && day in 20..22) ||
            (mo == 12 && day in 13..14)

        // 7. 5 Naked-Eye Planets
        val planets = computePlanets(d, epsRad, lambdaSunRad, sunL, sunG, lstDeg, latRad)

        // 8. Sunrise / Sunset
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
            moonColor = moonColor,
            moonCoronaColor = moonCoronaColor,
            moonIllumination = illum,
            isWaxing = isWaxing,
            moonLimbTiltDeg = limbTiltDeg,
            moonSizeScale = moonSizeScale,
            isLunarEclipse = isLunarEclipse,
            isSolarEclipse = isSolarEclipse,
            tideFrac = tideFrac,
            isMeteorShowerPeak = isShower,
            lstDeg = lstDeg,
            latDeg = latDeg,
            planets = planets,
            sunriseLocalHours = sunriseLocal,
            sunsetLocalHours = sunsetLocal
        )
    }

    fun projectStars(
        lstDeg: Double,
        latDeg: Double,
        width: Int,
        horizonY: Float,
        topY: Float,
        outX: FloatArray,
        outY: FloatArray,
        outR: FloatArray,
        outCol: IntArray,
        outMag: FloatArray
    ): Int {
        val latRad = latDeg * DEG2RAD
        var count = 0
        for (s in starCatalog) {
            if (count >= outX.size) break
            val hRad = (lstDeg - s.raDeg) * DEG2RAD
            val dRad = s.decDeg * DEG2RAD
            val altRad = asin((sin(latRad) * sin(dRad) + cos(latRad) * cos(dRad) * cos(hRad)).coerceIn(-1.0, 1.0))
            val altDeg = (altRad * RAD2DEG).toFloat()
            if (altDeg < 1.5f) continue

            val azDeg = normDeg(atan2(sin(hRad), cos(hRad) * sin(latRad) - tan(dRad) * cos(latRad)) * RAD2DEG + 180.0).toFloat()
            val rel = (((azDeg - 60f) % 360f + 360f) % 360f)
            if (rel > 240f) continue

            val x = width * (0.04f + 0.92f * (rel / 240f))
            val y = horizonY - (horizonY - topY) * (altDeg / 85f).coerceIn(0f, 1f)
            outX[count] = x
            outY[count] = y
            outR[count] = (2.6f - s.mag * 0.42f).coerceIn(0.9f, 2.9f)
            outCol[count] = s.color
            outMag[count] = s.mag
            count++
        }
        return count
    }

    fun projectMilkyWay(
        lstDeg: Double,
        latDeg: Double,
        width: Int,
        horizonY: Float,
        topY: Float,
        outX: FloatArray,
        outY: FloatArray
    ): Int {
        val latRad = latDeg * DEG2RAD
        var count = 0
        for ((ra, dec) in milkyWayNodes) {
            if (count >= outX.size) break
            val hRad = (lstDeg - ra) * DEG2RAD
            val dRad = dec * DEG2RAD
            val altRad = asin((sin(latRad) * sin(dRad) + cos(latRad) * cos(dRad) * cos(hRad)).coerceIn(-1.0, 1.0))
            val altDeg = (altRad * RAD2DEG).toFloat()
            if (altDeg < 2f) continue

            val azDeg = normDeg(atan2(sin(hRad), cos(hRad) * sin(latRad) - tan(dRad) * cos(latRad)) * RAD2DEG + 180.0).toFloat()
            val rel = (((azDeg - 60f) % 360f + 360f) % 360f)
            if (rel > 240f) continue

            outX[count] = width * (0.04f + 0.92f * (rel / 240f))
            outY[count] = horizonY - (horizonY - topY) * (altDeg / 85f).coerceIn(0f, 1f)
            count++
        }
        return count
    }

    private fun computePlanets(
        d: Double,
        epsRad: Double,
        lambdaSunRad: Double,
        sunL: Double,
        sunG: Double,
        lstDeg: Double,
        latRad: Double
    ): List<SkyBody> {
        // Earth heliocentric (x, y)
        val eR = 1.00014 - 0.01671 * cos(sunG * DEG2RAD)
        val eLon = lambdaSunRad + PI
        val ex = eR * cos(eLon)
        val ey = eR * sin(eLon)

        data class Elem(val name: String, val a: Double, val e: Double, val iDeg: Double, val l0: Double, val lRate: Double, val wBar: Double, val node: Double, val mag: Float, val col: Int)
        val elems = listOf(
            Elem("Mercury", 0.3871, 0.2056, 7.00, 252.25, 4.092334, 77.46, 48.33, -0.4f, 0xFFE2E8F0.toInt()),
            Elem("Venus", 0.7233, 0.0068, 3.39, 181.98, 1.602130, 131.53, 76.68, -4.1f, 0xFFFFFBEB.toInt()),
            Elem("Mars", 1.5237, 0.0934, 1.85, 355.45, 0.524021, 336.04, 49.56, -0.5f, 0xFFFB923C.toInt()),
            Elem("Jupiter", 5.2026, 0.0485, 1.30, 34.40, 0.083091, 14.75, 100.56, -2.3f, 0xFFFEF3C7.toInt()),
            Elem("Saturn", 9.5549, 0.0555, 2.49, 49.94, 0.033444, 92.43, 113.72, 0.5f, 0xFFFDE68A.toInt())
        )

        val out = ArrayList<SkyBody>(5)
        for (p in elems) {
            val l = normDeg(p.l0 + p.lRate * d)
            val m = (l - p.wBar) * DEG2RAD
            val nu = m + 2.0 * p.e * sin(m)
            val r = p.a * (1.0 - p.e * p.e) / (1.0 + p.e * cos(nu))
            val lonH = nu + p.wBar * DEG2RAD
            val nodeR = p.node * DEG2RAD
            val incR = p.iDeg * DEG2RAD

            val px = r * (cos(nodeR) * cos(lonH - nodeR) - sin(nodeR) * sin(lonH - nodeR) * cos(incR))
            val py = r * (sin(nodeR) * cos(lonH - nodeR) + cos(nodeR) * sin(lonH - nodeR) * cos(incR))
            val pz = r * (sin(lonH - nodeR) * sin(incR))

            val dx = px - ex
            val dy = py - ey
            val dz = pz

            val xEq = dx
            val yEq = dy * cos(epsRad) - dz * sin(epsRad)
            val zEq = dy * sin(epsRad) + dz * cos(epsRad)

            val alphaDeg = normDeg(atan2(yEq, xEq) * RAD2DEG)
            val deltaRad = atan2(zEq, sqrt(xEq * xEq + yEq * yEq))

            val hRad = (lstDeg - alphaDeg) * DEG2RAD
            val altRad = asin((sin(latRad) * sin(deltaRad) + cos(latRad) * cos(deltaRad) * cos(hRad)).coerceIn(-1.0, 1.0))
            val altDeg = (altRad * RAD2DEG).toFloat()
            if (altDeg < 1f) continue
            val azDeg = normDeg(atan2(sin(hRad), cos(hRad) * sin(latRad) - tan(deltaRad) * cos(latRad)) * RAD2DEG + 180.0).toFloat()
            out.add(SkyBody(p.name, altDeg, azDeg, p.mag, p.col))
        }
        return out
    }

    private fun computeSunColors(altDeg: Float): Pair<Int, Int> {
        val h = altDeg.toDouble().coerceAtLeast(-2.0)
        val m = if (h >= 0.0) {
            1.0 / (sin(h * DEG2RAD) + 0.50572 * (h + 6.07995).pow(-1.6364))
        } else {
            38.0 + abs(h) * 4.0
        }.coerceIn(1.0, 42.0)

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

    private fun computeMoonColors(altDeg: Float, isLunarEclipse: Boolean): Pair<Int, Int> {
        if (isLunarEclipse) {
            return 0xFFDC4A2E.toInt() to 0xFF991B1B.toInt()
        }
        val h = altDeg.toDouble().coerceAtLeast(0.0)
        val m = (1.0 / (sin(h * DEG2RAD) + 0.50572 * (h + 6.07995).pow(-1.6364))).coerceIn(1.0, 38.0)
        // High (>30 deg, m~1.5) -> silvery-ivory (0xFFF8F2E4), Horizon (0-5 deg, m~15-38) -> deep amber-orange (0xFFFFA54B)
        val warmth = ((m - 1.5) / 18.0).toFloat().coerceIn(0f, 1f)
        val disc = when {
            warmth < 0.45f -> ColorUtils.blendARGB(0xFFFFF8EC.toInt(), 0xFFFDE68A.toInt(), warmth / 0.45f)
            else -> ColorUtils.blendARGB(0xFFFDE68A.toInt(), 0xFFFF9F43.toInt(), (warmth - 0.45f) / 0.55f)
        }
        val corona = ColorUtils.blendARGB(0xFFCBD5E1.toInt(), 0xFFFB923C.toInt(), warmth)
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
