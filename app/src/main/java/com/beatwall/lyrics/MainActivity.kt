package com.beatwall.lyrics

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.app.TimePickerDialog
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MediaSessionTracker.Listener {

    private lateinit var cardNotif: View
    private lateinit var cardBattery: View
    private lateinit var cardNowPlaying: View
    private lateinit var imgCurrentCover: ImageView
    private lateinit var txtCurrentTitle: TextView
    private lateinit var txtCurrentArtist: TextView
    private lateinit var txtActiveDot: TextView
    private lateinit var txtLocationCity: TextView
    private lateinit var txtSkyMetrics: TextView
    private lateinit var edtCityInput: EditText
    private lateinit var btnPickTime: Button
    private lateinit var btnWorldDropdown: Button
    private lateinit var btnSetWallpaper: Button

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            detectCoarseLocation()
        } else {
            Toast.makeText(this, "Location permission denied; using saved city", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardNotif = findViewById(R.id.cardNotif)
        cardBattery = findViewById(R.id.cardBattery)
        cardNowPlaying = findViewById(R.id.cardNowPlaying)
        imgCurrentCover = findViewById(R.id.imgCurrentCover)
        txtCurrentTitle = findViewById(R.id.txtCurrentTitle)
        txtCurrentArtist = findViewById(R.id.txtCurrentArtist)
        txtActiveDot = findViewById(R.id.txtActiveDot)
        txtLocationCity = findViewById(R.id.txtLocationCity)
        txtSkyMetrics = findViewById(R.id.txtSkyMetrics)
        edtCityInput = findViewById(R.id.edtCityInput)
        btnPickTime = findViewById(R.id.btnPickTime)
        btnWorldDropdown = findViewById(R.id.btnWorldDropdown)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)

        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnDetectCity).setOnClickListener {
            val perm = Manifest.permission.ACCESS_COARSE_LOCATION
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                detectCoarseLocation()
            } else {
                locationPermLauncher.launch(perm)
            }
        }
        findViewById<Button>(R.id.btnSetCity).setOnClickListener {
            val query = edtCityInput.text.toString().trim()
            if (query.isNotEmpty()) {
                geocodeCityName(query)
            }
        }

        btnPickTime.setOnClickListener {
            val prevMin = LocationStore.getPreviewMinute(this)
            val cal = Calendar.getInstance()
            val initHour = if (prevMin >= 0) prevMin / 60 else cal.get(Calendar.HOUR_OF_DAY)
            val initMin = if (prevMin >= 0) prevMin % 60 else cal.get(Calendar.MINUTE)
            TimePickerDialog(this, { _, hourOfDay, minute ->
                LocationStore.setPreviewMinute(this, hourOfDay * 60 + minute)
                updatePreviewButtons()
                refreshSkyCard()
            }, initHour, initMin, false).show()
        }

        findViewById<Button>(R.id.btnPrevLive).setOnClickListener {
            LocationStore.setPreviewMinute(this, -1)
            LocationStore.setPreviewDayOfYear(this, -1)
            updatePreviewButtons()
            refreshSkyCard()
            Toast.makeText(this, "Live sky restored", Toast.LENGTH_SHORT).show()
        }

        btnWorldDropdown.setOnClickListener {
            val biomes = WorldBiome.values()
            val labels = Array(biomes.size + 1) { i ->
                if (i == 0) "Today (Live Daily Rotation)" else "${i}. ${biomes[i - 1].title}"
            }
            val savedDay = LocationStore.getPreviewDayOfYear(this)
            val checked = if (savedDay in 1..biomes.size) savedDay else 0
            AlertDialog.Builder(this)
                .setTitle("Select World Biome")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    val dayVal = if (which == 0) -1 else which
                    LocationStore.setPreviewDayOfYear(this, dayVal)
                    updatePreviewButtons()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnSetWallpaper.setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, BeatWallService::class.java)
                )
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        MediaSessionTracker.getInstance(applicationContext).addListener(this)
        updatePreviewButtons()
        refreshUi()
        refreshSkyCard()
    }

    override fun onPause() {
        MediaSessionTracker.getInstance(applicationContext).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        LocationStore.setPreviewMinute(this, -1)
        super.onDestroy()
    }

    override fun onTrackStateChanged(state: ActiveTrackState?) {
        runOnUiThread { updateNowPlayingCard(state) }
    }

    private fun refreshUi() {
        val notifOk = MediaListenerService.isEnabled(this)
        cardNotif.visibility = if (notifOk) View.GONE else View.VISIBLE

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        cardBattery.visibility = if (battOk) View.GONE else View.VISIBLE

        val wm = WallpaperManager.getInstance(this)
        val isActiveWall = wm.wallpaperInfo?.packageName == packageName
        btnSetWallpaper.text = if (isActiveWall) "Live Wallpaper Active ✓" else "Set Live Wallpaper"
        btnSetWallpaper.alpha = if (isActiveWall) 0.85f else 1.0f

        cardNowPlaying.visibility = if (notifOk && battOk) View.VISIBLE else View.GONE
    }

    private fun updatePreviewButtons() {
        val prevMin = LocationStore.getPreviewMinute(this)
        btnPickTime.text = if (prevMin >= 0) {
            val h24 = prevMin / 60
            val m = prevMin % 60
            val amPm = if (h24 >= 12) "PM" else "AM"
            val h12 = when {
                h24 == 0 -> 12
                h24 > 12 -> h24 - 12
                else -> h24
            }
            String.format(Locale.getDefault(), "Preview: %d:%02d %s ▾", h12, m, amPm)
        } else {
            "Time: Live ▾"
        }
        val biomes = WorldBiome.values()
        val savedDay = LocationStore.getPreviewDayOfYear(this)
        btnWorldDropdown.text = if (savedDay in 1..biomes.size) {
            "World: ${savedDay}. ${biomes[savedDay - 1].title} ▾"
        } else {
            "World: Today (Live) ▾"
        }
    }

    private fun refreshSkyCard() {
        val loc = LocationStore.load(this)
        val prevMin = LocationStore.getPreviewMinute(this)
        val targetMs = if (prevMin >= 0) {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, prevMin / 60)
                set(Calendar.MINUTE, prevMin % 60)
            }.timeInMillis
        } else {
            System.currentTimeMillis()
        }
        val snap = SkyAstronomy.compute(targetMs, loc.lat, loc.lon)
        val weather = WeatherClient.getCachedOrRefresh(this, loc.lat, loc.lon)
        txtLocationCity.text = if (prevMin >= 0) {
            val tStr = SkyAstronomy.formatDecimalHour(prevMin / 60.0)
            "${loc.cityName} (Preview $tStr)"
        } else {
            loc.cityName
        }
        val rise = SkyAstronomy.formatDecimalHour(snap.sunriseLocalHours)
        val set = SkyAstronomy.formatDecimalHour(snap.sunsetLocalHours)
        val moonPct = (snap.moonIllumination * 100f).roundToInt()
        val sunAlt = snap.sunAltDeg.roundToInt()
        val wTag = weather.sky.name.lowercase().replaceFirstChar { it.uppercase() }
        txtSkyMetrics.text = "$wTag (${weather.windKmh.roundToInt()} km/h) • Sun $sunAlt° • Rise $rise • Set $set • Moon $moonPct%"
    }

    @Suppress("MissingPermission")
    private fun detectCoarseLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        if (loc == null) {
            Toast.makeText(this, "Could not read cached city; enter city manually", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            var label = "%.2f°, %.2f°".format(loc.latitude, loc.longitude)
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                val addr = list?.firstOrNull()
                if (addr != null) {
                    val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
                    val state = addr.adminArea
                    label = listOfNotNull(city, state).distinct().joinToString(", ").ifBlank { label }
                }
            } catch (_: Exception) {
            }
            LocationStore.save(this, loc.latitude, loc.longitude, label)
            runOnUiThread {
                refreshSkyCard()
                Toast.makeText(this, "Sky updated for $label", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun geocodeCityName(query: String) {
        thread {
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.getDefault()).getFromLocationName(query, 1)
                val addr = list?.firstOrNull()
                if (addr != null) {
                    val city = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: query
                    val state = addr.adminArea
                    val label = listOfNotNull(city, state).distinct().joinToString(", ").ifBlank { query }
                    LocationStore.save(this, addr.latitude, addr.longitude, label)
                    runOnUiThread {
                        edtCityInput.setText("")
                        refreshSkyCard()
                        Toast.makeText(this, "Sky updated for $label", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                Toast.makeText(this, "City not found; check spelling or internet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateNowPlayingCard(state: ActiveTrackState?) {
        if (state == null) {
            txtCurrentTitle.text = "Ready"
            txtCurrentArtist.text = "Play Spotify, YT Music, or NotebookLM"
            txtActiveDot.text = "● Connected"
            imgCurrentCover.setImageResource(R.mipmap.ic_launcher_round)
            return
        }
        txtCurrentTitle.text = state.title
        txtCurrentArtist.text = state.artist.ifBlank { "BeatWall" }
        txtActiveDot.text = if (state.isPlaying) "● Playing" else "● Paused"
        val cover = state.processedArt?.coverArt
        if (cover != null) {
            imgCurrentCover.setImageBitmap(cover)
        } else {
            imgCurrentCover.setImageResource(R.mipmap.ic_launcher_round)
        }
    }
}
