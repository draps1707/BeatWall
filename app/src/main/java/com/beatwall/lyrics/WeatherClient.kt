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

object WeatherClient {
    private const val PREFS = "beatwall_prefs"
    private const val KEY_CODE = "weather_code"
    private const val KEY_TS = "weather_ts"
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"
    private const val CACHE_TTL_MS = 45 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var inFlight = false
    @Volatile private var cachedWeather: SkyWeather? = null
    @Volatile private var lastCheckMs = 0L

    fun getCachedOrRefresh(context: Context, lat: Double, lon: Double): SkyWeather {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (cachedWeather == null) {
            val savedCode = prefs.getInt(KEY_CODE, 0)
            cachedWeather = mapWmoCode(savedCode)
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
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=weather_code"
                    )
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val code = JSONObject(body)
                            .optJSONObject("current")
                            ?.optInt("weather_code", 0) ?: 0
                        val mapped = mapWmoCode(code)
                        cachedWeather = mapped
                        lastCheckMs = System.currentTimeMillis()
                        prefs.edit()
                            .putInt(KEY_CODE, code)
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
        return cachedWeather ?: SkyWeather.CLEAR
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
