package com.beatwall.lyrics

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), MediaSessionTracker.Listener {

    private lateinit var cardNotif: View
    private lateinit var cardBattery: View
    private lateinit var cardNowPlaying: View
    private lateinit var imgCurrentCover: ImageView
    private lateinit var txtCurrentTitle: TextView
    private lateinit var txtCurrentArtist: TextView
    private lateinit var txtActiveDot: TextView
    private lateinit var btnSetWallpaper: Button

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
        refreshUi()
    }

    override fun onPause() {
        MediaSessionTracker.getInstance(applicationContext).removeListener(this)
        super.onPause()
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

    private fun updateNowPlayingCard(state: ActiveTrackState?) {
        if (state == null) {
            txtCurrentTitle.text = "Ready"
            txtCurrentArtist.text = "Play Spotify, YT Music, or YouTube"
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
