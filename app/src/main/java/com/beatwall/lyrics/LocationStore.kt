package com.beatwall.lyrics

import android.content.Context

data class SavedLocation(
    val lat: Double,
    val lon: Double,
    val cityName: String
)

object LocationStore {
    private const val PREFS = "beatwall_prefs"
    private const val KEY_LAT = "loc_lat"
    private const val KEY_LON = "loc_lon"
    private const val KEY_CITY = "loc_city"

    // Default: Sector 5, Rourkela, Odisha (769002)
    private const val DEFAULT_LAT = 22.2604
    private const val DEFAULT_LON = 84.8536
    private const val DEFAULT_CITY = "Rourkela, Odisha"

    fun load(context: Context): SavedLocation {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val latStr = prefs.getString(KEY_LAT, null)
        val lonStr = prefs.getString(KEY_LON, null)
        val city = prefs.getString(KEY_CITY, null)
        val lat = latStr?.toDoubleOrNull() ?: DEFAULT_LAT
        val lon = lonStr?.toDoubleOrNull() ?: DEFAULT_LON
        return SavedLocation(lat, lon, city?.ifBlank { DEFAULT_CITY } ?: DEFAULT_CITY)
    }

    fun save(context: Context, lat: Double, lon: Double, cityName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAT, lat.toString())
            .putString(KEY_LON, lon.toString())
            .putString(KEY_CITY, cityName)
            .apply()
    }
}
