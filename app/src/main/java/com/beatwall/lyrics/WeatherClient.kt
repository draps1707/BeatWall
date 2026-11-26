package com.beatwall.lyrics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

enum class SkyWeather {
    CLEAR,
    CLOUDY,
    RAIN,
    THUNDERSTORM
}

data class WeatherState(
    val sky: SkyWeather,
    val windKmh: Float,
    val cloudCoverPct: Int,
    val humidityPct: Int,
    val windGustsKmh: Float = 12f,
    val windDirDeg: Float = 90f,
    val cloudLowPct: Int = 10,
    val cloudHighPct: Int = 10,
    val tempC: Float = 24f,
    val visibilityM: Float = 12000f,
    val precipMm: Float = 0f
)

object WeatherClient {
    private const val PREFS = "beatwall_prefs"
    private const val KEY_CODE = "weather_code"
    private const val KEY_WIND = "weather_wind"
    private const val KEY_GUSTS = "weather_gusts"
    private const val KEY_WDIR = "weather_wdir"
    private const val KEY_CLOUD = "weather_cloud"
    private const val KEY_CLOW = "weather_clow"
    private const val KEY_CHIGH = "weather_chigh"
    private const val KEY_HUM = "weather_hum"
    private const val KEY_TEMP = "weather_temp"
    private const val KEY_VIS = "weather_vis"
    private const val KEY_PRECIP = "weather_precip"
    private const val KEY_TS = "weather_ts"
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"
    private const val CACHE_TTL_MS = 45 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var inFlight = false
    @Volatile private var cachedState: WeatherState? = null
    @Volatile private var lastCheckMs = 0L

    fun getCachedOrRefresh(context: Context, lat: Double, lon: Double): WeatherState {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (cachedState == null) {
            val savedCode = prefs.getInt(KEY_CODE, 0)
            val savedWind = prefs.getFloat(KEY_WIND, 8f)
            val savedCloud = prefs.getInt(KEY_CLOUD, 25)
            val savedHum = prefs.getInt(KEY_HUM, 55)
            val savedGusts = prefs.getFloat(KEY_GUSTS, 12f)
            val savedDir = prefs.getFloat(KEY_WDIR, 90f)
            val savedCLow = prefs.getInt(KEY_CLOW, 10)
            val savedCHigh = prefs.getInt(KEY_CHIGH, 10)
            val savedTemp = prefs.getFloat(KEY_TEMP, 24f)
            val savedVis = prefs.getFloat(KEY_VIS, 12000f)
            val savedPrecip = prefs.getFloat(KEY_PRECIP, 0f)
            cachedState = WeatherState(
                mapWmoCode(savedCode), savedWind, savedCloud, savedHum,
                savedGusts, savedDir, savedCLow, savedCHigh, savedTemp, savedVis, savedPrecip
            )
            lastCheckMs = prefs.getLong(KEY_TS, 0L)
        }

        val lastLat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: lat
        val lastLon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: lon
        val moved = abs(lastLat - lat) > 0.15 || abs(lastLon - lon) > 0.15

        if (!inFlight && (moved || (now - lastCheckMs) > CACHE_TTL_MS)) {
            inFlight = true
            scope.launch {
                try {
                    val url = URL(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m," +
                            "cloud_cover,cloud_cover_low,cloud_cover_high,relative_humidity_2m," +
                            "temperature_2m,visibility,precipitation"
                    )
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val cur = JSONObject(body).optJSONObject("current")
                        val code = cur?.optInt("weather_code", 0) ?: 0
                        val wind = cur?.optDouble("wind_speed_10m", 8.0)?.toFloat() ?: 8f
                        val gusts = cur?.optDouble("wind_gusts_10m", wind * 1.4)?.toFloat() ?: (wind * 1.4f)
                        val wdir = cur?.optDouble("wind_direction_10m", 90.0)?.toFloat() ?: 90f
                        val cloud = cur?.optInt("cloud_cover", 25) ?: 25
                        val cLow = cur?.optInt("cloud_cover_low", 10) ?: 10
                        val cHigh = cur?.optInt("cloud_cover_high", 10) ?: 10
                        val hum = cur?.optInt("relative_humidity_2m", 55) ?: 55
                        val temp = cur?.optDouble("temperature_2m", 24.0)?.toFloat() ?: 24f
                        val vis = cur?.optDouble("visibility", 12000.0)?.toFloat() ?: 12000f
                        val precip = cur?.optDouble("precipitation", 0.0)?.toFloat() ?: 0f

                        val st = WeatherState(
                            mapWmoCode(code), wind, cloud, hum,
                            gusts, wdir, cLow, cHigh, temp, vis, precip
                        )
                        cachedState = st
                        lastCheckMs = System.currentTimeMillis()
                        prefs.edit()
                            .putInt(KEY_CODE, code)
                            .putFloat(KEY_WIND, wind)
                            .putFloat(KEY_GUSTS, gusts)
                            .putFloat(KEY_WDIR, wdir)
                            .putInt(KEY_CLOUD, cloud)
                            .putInt(KEY_CLOW, cLow)
                            .putInt(KEY_CHIGH, cHigh)
                            .putInt(KEY_HUM, hum)
                            .putFloat(KEY_TEMP, temp)
                            .putFloat(KEY_VIS, vis)
                            .putFloat(KEY_PRECIP, precip)
                            .putLong(KEY_TS, lastCheckMs)
                            .putString(KEY_LAT, lat.toString())
                            .putString(KEY_LON, lon.toString())
                            .apply()
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    lastCheckMs = System.currentTimeMillis() - (CACHE_TTL_MS - 5 * 60 * 1000L)
                } finally {
                    inFlight = false
                }
            }
        }
        return cachedState ?: WeatherState(SkyWeather.CLEAR, 8f, 25, 55)
    }

    private fun mapWmoCode(code: Int): SkyWeather {
        return when (code) {
            in 95..99 -> SkyWeather.THUNDERSTORM
            in 51..67, in 80..82 -> SkyWeather.RAIN
            in 2..3, in 45..48 -> SkyWeather.CLOUDY
            else -> SkyWeather.CLEAR
        }
    }
}
