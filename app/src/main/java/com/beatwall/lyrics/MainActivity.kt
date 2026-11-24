package com.beatwall.lyrics

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var idleStore: IdleWallpaperStore
    private lateinit var statusHome: TextView
    private lateinit var statusLock: TextView
    private lateinit var statusNotif: TextView
    private lateinit var statusBattery: TextView

    private val pickHomeLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                idleStore.saveHomeIdle(uri)
                refreshChecklist()
            }
        }

    private val pickLockLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                idleStore.saveLockIdle(uri)
                refreshChecklist()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        idleStore = IdleWallpaperStore(this)
        statusHome = findViewById(R.id.statusHome)
        statusLock = findViewById(R.id.statusLock)
        statusNotif = findViewById(R.id.statusNotif)
        statusBattery = findViewById(R.id.statusBattery)

        findViewById<Button>(R.id.btnPickHome).setOnClickListener {
            pickHomeLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.btnPickLock).setOnClickListener {
            pickLockLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener {
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
        refreshChecklist()
    }

    private fun refreshChecklist() {
        statusHome.text = if (idleStore.hasHomeIdle()) "Ready" else "Pick image"
        statusHome.setTextColor(if (idleStore.hasHomeIdle()) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())

        statusLock.text = if (idleStore.hasLockIdle()) "Ready" else "Pick image"
        statusLock.setTextColor(if (idleStore.hasLockIdle()) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())

        val notifOk = MediaListenerService.isEnabled(this)
        statusNotif.text = if (notifOk) "Enabled" else "Required"
        statusNotif.setTextColor(if (notifOk) 0xFF34D399.toInt() else 0xFFF87171.toInt())

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        statusBattery.text = if (battOk) "Unrestricted" else "Recommended"
        statusBattery.setTextColor(if (battOk) 0xFF34D399.toInt() else 0xFFFBBF24.toInt())
    }
}
