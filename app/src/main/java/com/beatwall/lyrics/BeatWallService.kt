package com.beatwall.lyrics

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BeatWallService : WallpaperService() {

    override fun onCreate() {
        super.onCreate()
        MediaListenerService.requestRebindSafe(this)
    }

    override fun onCreateEngine(): Engine = BeatWallEngine()

    inner class BeatWallEngine : Engine(),
        Choreographer.FrameCallback,
        MediaSessionTracker.Listener {

        private val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        private val powerManager =
            getSystemService(Context.POWER_SERVICE) as PowerManager
        private val batteryManager =
            getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        private val tracker = MediaSessionTracker.getInstance(applicationContext)
        private val artProcessor = ArtEffectProcessor()
        private val landscapeEngine = LandscapeEngine()
        private val choreographer = Choreographer.getInstance()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val proceduralAnimator = ProceduralAnimator()

        private val density = resources.displayMetrics.density

        private var visible = false
        private var surfaceReady = false
        private var width = 1080
        private var height = 2400
        private var lastHiddenAtMs = 0L

        private var isCharging = false
        private var isLowBattery = false
        private var lastEnvCacheMs = 0L

        // Tracks One UI launcher zoom (0f = Home screen, >0.12f = app open/close or drawer blur)
        // so BeatWall yields GPU budget to One UI's compositor during app transitions.
        private var wallpaperZoom = 0f

        // 24-slot quantized RadialGradient ring cache (eliminates ~1,200 allocations/sec at 120 Hz)
        private val softRadialColors = IntArray(24) { Int.MIN_VALUE }
        private val softRadialShaders = arrayOfNulls<RadialGradient>(24)
        private var softRadialCursor = 0

        private fun getCachedSoftRadialShader(colWithAlpha: Int): RadialGradient {
            val rgb = colWithAlpha and 0x00FFFFFF
            val qA = (((Color.alpha(colWithAlpha) + 4) / 8) * 8).coerceIn(0, 255)
            val key = rgb or (qA shl 24)
            for (i in softRadialColors.indices) {
                val sh = softRadialShaders[i]
                if (sh != null && softRadialColors[i] == key) return sh
            }
            val midCol = rgb or (((qA * 0.35f).toInt()) shl 24)
            val built = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(key, midCol, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val idx = softRadialCursor
            softRadialCursor = (softRadialCursor + 1) % softRadialShaders.size
            softRadialColors[idx] = key
            softRadialShaders[idx] = built
            return built
        }

        // 16-slot quantized LinearGradient cache for zero-allocation 120 Hz karaoke sweeps
        private val sweepShaderKeys = LongArray(16) { Long.MIN_VALUE }
        private val sweepShaders = arrayOfNulls<LinearGradient>(16)
        private var sweepShaderCursor = 0
        private val interludeNotes = arrayOf("♪", "♫", "♩", "♬", "♪", "♫", "♩", "♬")
        private val lyricHslBuf = FloatArray(3)
        private val nbHslBuf = FloatArray(3)
        private var isThermallyWarm = false
        private var lastThermalCheckMs = 0L

        private fun refreshThermalGuard(nowMs: Long) {
            if (nowMs - lastThermalCheckMs < 15_000L && lastThermalCheckMs != 0L) return
            lastThermalCheckMs = nowMs
            try {
                val headroom = powerManager.getThermalHeadroom(10)
                if (!headroom.isNaN() && headroom > 0f) {
                    isThermallyWarm = if (isThermallyWarm) headroom >= 0.72f else headroom >= 0.85f
                }
            } catch (_: Exception) {
            }
        }

        private fun getCachedSweepShader(sungCol: Int, unsungCol: Int): LinearGradient {
            val sA = (((Color.alpha(sungCol) + 4) / 8) * 8).coerceIn(0, 255)
            val uA = (((Color.alpha(unsungCol) + 4) / 8) * 8).coerceIn(0, 255)
            val sQ = (sungCol and 0x00FFFFFF) or (sA shl 24)
            val uQ = (unsungCol and 0x00FFFFFF) or (uA shl 24)
            val key = (sQ.toLong() shl 32) or (uQ.toLong() and 0xFFFFFFFFL)
            for (i in sweepShaderKeys.indices) {
                val sh = sweepShaders[i]
                if (sh != null && sweepShaderKeys[i] == key) return sh
            }
            val built = LinearGradient(
                -1f, 0f, 1f, 0f,
                intArrayOf(sQ, uQ),
                null, Shader.TileMode.CLAMP
            )
            val idx = sweepShaderCursor
            sweepShaderCursor = (sweepShaderCursor + 1) % sweepShaders.size
            sweepShaderKeys[idx] = key
            sweepShaders[idx] = built
            return built
        }

        private fun boostLyricAccent(accentColor: Int): Int {
            ColorUtils.colorToHSL(accentColor, lyricHslBuf)
            // High-vibrancy saturation & luminance so album colors glow vividly instead of turning muddy/brown
            lyricHslBuf[1] = lyricHslBuf[1].coerceIn(0.82f, 1.0f)
            lyricHslBuf[2] = lyricHslBuf[2].coerceIn(0.62f, 0.76f)
            return ColorUtils.HSLToColor(lyricHslBuf)
        }

        // Instant live refresh when Quick Settings tile or MainActivity changes world/time
        private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            lastEnvCacheMs = 0L
            lastSkyComputeMs = 0L
        }

        // Debounced SystemUI color notification (fires after crossfade finishes so One UI never hitches mid-transition)
        private val deferredColorsRunnable = Runnable {
            try {
                notifyColorsChanged()
            } catch (_: Exception) {
            }
        }

        // Long-idle-only session resync (runs after unlock animation finishes, never blocking Frame 1)
        private val deferredSessionRefreshRunnable = Runnable {
            if (visible && powerManager.isInteractive) tracker.refreshActiveSessions()
        }
        private var cachedDay = 1
        private var cachedDayOfYear = 1
        private var cachedMin = 720
        private var cachedLoc = SavedLocation(22.2604, 84.8536, "Rourkela")
        private var cachedWeather = WeatherState(SkyWeather.CLEAR, 8f, 10, 50)

        private var activeState: ActiveTrackState? = null
        private var prevState: ActiveTrackState? = null
        private var isCrossfading = false
        private var crossfadeProgress = 1f
        private var crossfadeStartNanos = 0L
        private val crossfadeDurationNanos = 850_000_000L

        private var lyricsAlpha = 0f
        private var lyricsAlphaVel = 0f
        private var targetLyricsAlpha = 0f
        private var eqAmp = 0f
        private var eqVel = 0f
        private var lyricPaceScale = 1.0f
        private var gapRelaxFactor = 0.0f
        private var targetEqAmp = 0f
        private var lockBlend = if (keyguardManager.isKeyguardLocked) 1f else 0f
        private var lockVel = 0f
        private var studioPhase = 0.0
        private var studioOmega = 0.0

        private var centeredLayouts: List<StaticLayout> = emptyList()
        private var lyricYCenters: FloatArray = FloatArray(0)
        private var lyricMaxLineW: FloatArray = FloatArray(0)
        private var avgLineStep = 140f

        private var prevCenteredLayouts: List<StaticLayout> = emptyList()
        private var prevLyricYCenters: FloatArray = FloatArray(0)
        private var prevLyricMaxLineW: FloatArray = FloatArray(0)
        private var prevAvgLineStep = 140f
        private var prevSpringY = 0f
        private var prevLyricsAlpha = 0f
        private var measuredTrackKey: String? = null

        private var headerTitleLayout: StaticLayout? = null
        private var headerTitleCacheKey: String? = null

        private var currentLineIndex = -1
        private var springY = 0f
        private var springVelocity = 0f
        private var targetY = 0f
        private var lastFrameNanos = 0L
        private var lastDrawNanos = 0L
        private var currentRequestedHz = -1f
        private var cachedPreferredHighFrameRate: Float? = null
        private var frameScheduled = false
        private var lowRateCallbackPending = false
        // When the scene is in its low-motion mode, avoid waking the render loop
        // on every display vsync. A delayed main-thread callback re-enters the
        // Choreographer at roughly 30 fps; high-motion/music scenes remain vsync-driven.
        private val lowRateFrameRunnable = Runnable {
            lowRateCallbackPending = false
            if (frameScheduled && visible && surfaceReady && powerManager.isInteractive) {
                choreographer.postFrameCallback(this)
            } else {
                frameScheduled = false
            }
        }
        private var cachedSkySnapshot: SkySnapshot? = null
        private var lastSkyComputeMs = 0L

        private fun effectiveTimeMillisAndMinute(): Pair<Long, Int> {
            val prevMin = LocationStore.getPreviewMinute(applicationContext)
            if (prevMin >= 0) {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, prevMin / 60)
                    set(Calendar.MINUTE, prevMin % 60)
                }
                return cal.timeInMillis to prevMin
            }
            return System.currentTimeMillis() to currentMinuteOfDay()
        }

        private fun getSkySnapshot(isLock: Boolean): SkySnapshot {
            val prevMin = LocationStore.getPreviewMinute(applicationContext)
            val nowMs = System.currentTimeMillis()
            val existing = cachedSkySnapshot
            if (prevMin < 0 && existing != null && kotlin.math.abs(nowMs - lastSkyComputeMs) < 30_000L) {
                return existing
            }
            val loc = LocationStore.load(applicationContext)
            val (targetMs, _) = effectiveTimeMillisAndMinute()
            val snap = SkyAstronomy.compute(targetMs, loc.lat, loc.lon)
            val horizonY = height * 0.66f
            val topY = if (isLock) height * 0.09f else height * 0.07f
            visibleStarCount = SkyAstronomy.projectStars(
                snap.lstDeg, snap.latDeg, width, horizonY, topY,
                starX, starY, starR, starCol, starMag
            )
            visibleMwCount = SkyAstronomy.projectMilkyWay(
                snap.lstDeg, snap.latDeg, width, horizonY, topY, mwX, mwY
            )
            cachedSkySnapshot = snap
            lastSkyComputeMs = nowMs
            return snap
        }

        // Pre-allocated sidereal star & Milky Way projection buffers (updated once every 30s, 0 allocations/frame)
        private val maxStars = 120
        private var visibleStarCount = 0
        private val starX = FloatArray(maxStars)
        private val starY = FloatArray(maxStars)
        private val starR = FloatArray(maxStars)
        private val starCol = IntArray(maxStars)
        private val starMag = FloatArray(maxStars)
        private val mwX = FloatArray(10)
        private val mwY = FloatArray(10)
        private var visibleMwCount = 0

        // Reusable shader & matrix cache (zero per-frame allocations at 120 Hz)
        private val shaderMatrix = Matrix()
        private var cachedBacklightColor = 0
        private var cachedBacklightShader: RadialGradient? = null
        private var cachedSpotlightColor = 0
        private var cachedSpotlightShader: RadialGradient? = null
        private var cachedSweepKey = 0L
        private var cachedSweepShader: LinearGradient? = null
        private var cachedSoftMistColor = 0
        private var cachedSoftMistShader: RadialGradient? = null

        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            isDither = true
        }
        private val backlightPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            isDither = true
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x38FFFFFF
            strokeWidth = 2.0f
        }
        private val coverProgressGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4.0f * density
        }
        private val coverProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.0f * density
        }
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            isDither = true
        }
        private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val eqBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val spotlightPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            isDither = true
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val instNotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val ribbonPath = Path()
        private val instNotes = arrayOf("♪", "♫", "♬")

        private val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            letterSpacing = -0.02f
        }
        private val headerArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = 0xC4FFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.015f
        }
        private val activeLyricPaint = TextPaint(
            Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        ).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isDither = true
        }
        private val studioTimerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }

        private val clipPath = Path()
        private val coverRimPath = Path()
        private val coverProgressPath = Path()
        private val pathMeasure = PathMeasure()
        private val moonPath = Path()
        private val moonCutPath = Path()
        private val miniArtRect = RectF()
        private val eqBarRect = RectF()
        private val arcRect = RectF()
        private val backdropRect = RectF()
        private val lockLyricsClip = RectF()
        private val homeLyricsClip = RectF()

        private val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        stopLoop()
                        return
                    }
                    Intent.ACTION_USER_PRESENT -> { /* keep backdrop steady on unlock */ }
                    Intent.ACTION_BATTERY_CHANGED -> updateBatteryFlags(intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updateBatteryFlags(null)
                }
                if (visible && powerManager.isInteractive) {
                    snapToCurrentPosition()
                    updateLoopState()
                } else {
                    stopLoop()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            tracker.addListener(this)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            val stickyBatt = ContextCompat.registerReceiver(
                this@BeatWallService,
                systemReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            updateBatteryFlags(stickyBatt)
            applicationContext.getSharedPreferences("beatwall_prefs", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsChangeListener)
        }

        override fun onDestroy() {
            stopLoop()
            mainHandler.removeCallbacks(deferredColorsRunnable)
            mainHandler.removeCallbacks(deferredSessionRefreshRunnable)
            try {
                applicationContext.getSharedPreferences("beatwall_prefs", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefsChangeListener)
            } catch (_: Exception) {
            }
            tracker.removeListener(this)
            try {
                unregisterReceiver(systemReceiver)
            } catch (_: Exception) {
            }
            super.onDestroy()
        }

        private fun updateBatteryFlags(battIntent: Intent?) {
            if (battIntent != null) {
                val status = battIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val level = battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val pct = (level * 100) / scale
                isLowBattery = (!isCharging && pct in 0..14) || powerManager.isPowerSaveMode
            } else {
                val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                isCharging = batteryManager.isCharging
                isLowBattery = (!isCharging && pct in 0..14) || powerManager.isPowerSaveMode
            }
            targetEqAmp = if (activeState?.isPlaying == true && !isLowBattery) 1f else 0f
        }

        private fun currentDayOfMonth(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

        private fun currentMinuteOfDay(): Int {
            val cal = Calendar.getInstance()
            return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }

        override fun onComputeColors(): WallpaperColors? {
            val art = activeState?.processedArt
            if (art != null) {
                return WallpaperColors(
                    Color.valueOf(art.lyricAccentColor),
                    Color.valueOf(art.primaryGlowColor),
                    Color.valueOf(art.secondaryGlowColor),
                    WallpaperColors.HINT_SUPPORTS_DARK_THEME
                )
            }
            val sky = getSkySnapshot(keyguardManager.isKeyguardLocked)
            val circ = artProcessor.getCircadianTheme(currentDayOfMonth(), currentMinuteOfDay(), sky.sunAltDeg)
            return WallpaperColors(
                Color.valueOf(circ.accent),
                Color.valueOf(circ.primary),
                Color.valueOf(circ.secondary),
                WallpaperColors.HINT_SUPPORTS_DARK_THEME
            )
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            updateLoopState()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
            cachedPreferredHighFrameRate = null
            lockLyricsClip.set(0f, height * 0.415f, width.toFloat(), height * 0.73f)
            homeLyricsClip.set(0f, height * 0.21f, width.toFloat(), height * 0.77f)
            headerTitleCacheKey = null
            rebuildLayoutsIfNeeded(force = true)
            if (visible) scheduleNextFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            stopLoop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onZoomChanged(zoom: Float) {
            val prevZoomed = wallpaperZoom > 0.08f
            wallpaperZoom = zoom.coerceIn(0f, 1f)
            val nowZoomed = wallpaperZoom > 0.08f
            if (prevZoomed != nowZoomed && visible && powerManager.isInteractive) {
                updateLoopState()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible && powerManager.isInteractive) {
                val hiddenMs = if (lastHiddenAtMs > 0L) SystemClock.elapsedRealtime() - lastHiddenAtMs else 0L
                // Keep warm in-memory sky/weather caches on quick unlocks, app switches, shade & drawer toggles
                if (hiddenMs > 15_000L || lastEnvCacheMs == 0L) {
                    lastSkyComputeMs = 0L
                    lastEnvCacheMs = 0L
                }
                // Active session listener already tracks media live; only force-resync after >60s hidden, off Frame 1
                if (hiddenMs > 60_000L) {
                    mainHandler.removeCallbacks(deferredSessionRefreshRunnable)
                    mainHandler.postDelayed(deferredSessionRefreshRunnable, 350L)
                }
                snapToCurrentPosition()
                updateLoopState()
            } else {
                lastHiddenAtMs = SystemClock.elapsedRealtime()
                mainHandler.removeCallbacks(deferredSessionRefreshRunnable)
                stopLoop()
            }
        }

        override fun onTrackStateChanged(state: ActiveTrackState?) {
            try {
                val prevKey = activeState?.trackKey
                val newKey = state?.trackKey
                val artChanged = activeState?.processedArt !== state?.processedArt

                if (prevKey != newKey) {
                    if (activeState != null || state != null) {
                        // Preserve original outgoing scene if a rapid second metadata update arrives before dissolve begins
                        if (!isCrossfading || crossfadeProgress > 0.05f) {
                            prevState = activeState
                            prevCenteredLayouts = centeredLayouts
                            prevLyricYCenters = lyricYCenters
                            prevLyricMaxLineW = lyricMaxLineW
                            prevAvgLineStep = avgLineStep
                            prevSpringY = springY
                            prevLyricsAlpha = lyricsAlpha
                        }
                        isCrossfading = true
                        crossfadeProgress = 0f
                        crossfadeStartNanos = 0L
                    }
                    currentLineIndex = -1
                    springY = 0f
                    springVelocity = 0f
                    targetY = 0f
                    if (state?.showLyrics != true || state.lyrics.isEmpty()) {
                        lyricsAlpha = 0f
                        lyricsAlphaVel = 0f
                    }
                    proceduralAnimator.setTrackSeed(state?.trackKey)
                } else if (artChanged && !isCrossfading && activeState != null) {
                    prevState = activeState
                    prevCenteredLayouts = centeredLayouts
                    prevLyricYCenters = lyricYCenters
                    prevLyricMaxLineW = lyricMaxLineW
                    prevAvgLineStep = avgLineStep
                    prevSpringY = springY
                    prevLyricsAlpha = lyricsAlpha
                    isCrossfading = true
                    crossfadeProgress = 0f
                    crossfadeStartNanos = 0L
                }

                activeState = state
                targetLyricsAlpha = if (state?.showLyrics == true && state.lyrics.isNotEmpty()) 1f else 0f
                targetEqAmp = if (state?.isPlaying == true && !isLowBattery) 1f else 0f
                if (prevKey == null && state == null) {
                    lyricsAlpha = 0f
                    eqAmp = 0f
                }
                if (artChanged) {
                    mainHandler.removeCallbacks(deferredColorsRunnable)
                    mainHandler.postDelayed(deferredColorsRunnable, 1000L)
                }
                rebuildLayoutsIfNeeded(force = false)
                if (visible && powerManager.isInteractive) {
                    snapToCurrentPosition()
                    updateLoopState()
                }
            } catch (_: Throwable) {
            }
        }

        private fun snapToCurrentPosition() {
            val st = activeState ?: return
            if (st.lyrics.isEmpty()) return
            val idx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
            currentLineIndex = idx
            if (idx in lyricYCenters.indices) {
                targetY = lyricYCenters[idx]
                springY = targetY
                springVelocity = 0f
            }
        }

        private fun computeDynamicLyricTextSize(lines: List<LyricLine>, contentW: Int): Float {
            val textLines = lines.filter { !it.isInterlude && it.text.isNotBlank() }
            if (textLines.isEmpty()) return width * 0.072f

            val refSize = width * 0.075f
            activeLyricPaint.textSize = refSize
            val widths = textLines.map { activeLyricPaint.measureText(it.text) }.sorted()
            val p75Width = widths[((widths.size - 1) * 0.75f).toInt().coerceIn(0, widths.lastIndex)]
            if (p75Width <= 1f) return width * 0.084f

            val idealSize = refSize * ((contentW * 0.84f) / p75Width)
            return idealSize.coerceIn(width * 0.062f, width * 0.086f)
        }

        private fun rebuildLayoutsIfNeeded(force: Boolean) {
            val st = activeState
            if (st == null || st.lyrics.isEmpty()) {
                centeredLayouts = emptyList()
                lyricYCenters = FloatArray(0)
                lyricMaxLineW = FloatArray(0)
                measuredTrackKey = null
                return
            }
            if (!force && measuredTrackKey == st.trackKey) return
            measuredTrackKey = st.trackKey

            val contentW = (width * 0.88f).toInt().coerceAtLeast(220)
            val dynSize = computeDynamicLyricTextSize(st.lyrics, contentW)
            activeLyricPaint.textSize = dynSize
            val lineGap = dynSize * 0.58f

            centeredLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(4f, 1.04f)
                    .setIncludePad(true)
                    .setMaxLines(3)
                    .build()
            }

            lyricMaxLineW = FloatArray(centeredLayouts.size) { i ->
                val l = centeredLayouts[i]
                (0 until l.lineCount).maxOfOrNull { l.getLineWidth(it) } ?: 0f
            }
            lyricYCenters = computeYCenters(centeredLayouts, lineGap)
            avgLineStep = if (lyricYCenters.size >= 2) {
                ((lyricYCenters.last() - lyricYCenters.first()) / (lyricYCenters.size - 1)).coerceAtLeast(width * 0.13f)
            } else {
                width * 0.15f
            }
        }

        private fun computeYCenters(layouts: List<StaticLayout>, gap: Float): FloatArray {
            val centers = FloatArray(layouts.size)
            var y = 0f
            for (i in layouts.indices) {
                val h = layouts[i].height.toFloat()
                if (i == 0) {
                    centers[i] = 0f
                    y = h / 2f + gap
                } else {
                    centers[i] = y + h / 2f
                    y += h + gap
                }
            }
            return centers
        }

        private fun needsAnimation(): Boolean {
            if (!visible || !surfaceReady || !powerManager.isInteractive) return false
            // Pause to 0 fps while One UI blurs/zooms the wallpaper (App Drawer, folders, app open/close)
            if (wallpaperZoom > 0.08f && !isCrossfading) return false
            val targetLock = if (keyguardManager.isKeyguardLocked) 1f else 0f
            if (abs(targetLock - lockBlend) > 0.005f || abs(lockVel) > 0.01f) return true
            if (isCrossfading) return true
            if (abs(targetLyricsAlpha - lyricsAlpha) > 0.005f || abs(lyricsAlphaVel) > 0.01f) return true
            if (abs(targetEqAmp - eqAmp) > 0.005f || abs(eqVel) > 0.01f) return true
            if (proceduralAnimator.isCoasting() || studioOmega > 0.002) return true
            if (isLowBattery) return false
            val st = activeState
            if (st != null && !st.isPlaying) return false
            return true
        }

        private fun isHighRateNeeded(): Boolean {
            return !isLowBattery && !isThermallyWarm
        }

        private fun preferredHighFrameRate(): Float {
            cachedPreferredHighFrameRate?.let { return it }
            // Avoid hard-coding 120Hz on devices whose display tops out at 60Hz
            // or 90Hz. Cache the display-mode query; do not enumerate modes per frame.
            val rate = try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.defaultDisplay.supportedModes.maxOfOrNull { it.refreshRate }?.coerceAtLeast(60f) ?: 60f
            } catch (_: Exception) {
                60f
            }
            cachedPreferredHighFrameRate = rate
            return rate
        }

        private fun targetFrameRate(): Float {
            if (!needsAnimation()) return 0f
            // Never down-vote SurfaceFlinger to 30f (which pulls One UI off 120Hz);
            // return preferredHighFrameRate() when active or 0f ("no preference") otherwise.
            return if (isHighRateNeeded()) preferredHighFrameRate() else 0f
        }

        private fun scheduleNextFrame() {
            if (!visible || !surfaceReady || !powerManager.isInteractive) return
            // If a low-rate timer is waiting and the workload becomes active,
            // cancel the timer and resume on the next display vsync immediately.
            if (isHighRateNeeded() && lowRateCallbackPending) {
                mainHandler.removeCallbacks(lowRateFrameRunnable)
                lowRateCallbackPending = false
                frameScheduled = false
            }
            if (!frameScheduled) {
                frameScheduled = true
                lastFrameNanos = 0L
                choreographer.postFrameCallback(this)
            }
        }

        private fun updateLoopState() {
            val targetHz = targetFrameRate()
            if (targetHz > 0f) {
                setSurfaceFrameRate(targetHz)
                scheduleNextFrame()
            } else {
                stopLoop()
            }
        }

        private fun stopLoop() {
            frameScheduled = false
            lowRateCallbackPending = false
            choreographer.removeFrameCallback(this)
            mainHandler.removeCallbacks(lowRateFrameRunnable)
            setSurfaceFrameRate(0f)
        }

        private fun setSurfaceFrameRate(hz: Float) {
            if (currentRequestedHz == hz || !surfaceReady) return
            try {
                val surface = surfaceHolder.surface ?: return
                if (!surface.isValid) return
                surface.setFrameRate(
                    hz,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                )
                currentRequestedHz = hz
            } catch (_: Exception) {
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!visible || !surfaceReady || !powerManager.isInteractive) {
                return
            }

            try {
                val dtSec = if (lastFrameNanos == 0L) {
                    1f / 120f
                } else {
                    ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                }
                lastFrameNanos = frameTimeNanos

                if (isCrossfading) {
                    val durSec = (crossfadeDurationNanos / 1_000_000_000f).coerceAtLeast(0.35f)
                    crossfadeProgress = (crossfadeProgress + dtSec / durSec).coerceIn(0f, 1f)
                    if (crossfadeProgress >= 1f) {
                        isCrossfading = false
                        prevState = null
                        prevCenteredLayouts = emptyList()
                        prevLyricYCenters = FloatArray(0)
                        prevLyricMaxLineW = FloatArray(0)
                    }
                }

                // Critically-damped springs (zero hard stops on Lock<->Home, Play<->Pause, Lyrics)
                val alphaDiff = targetLyricsAlpha - lyricsAlpha
                if (abs(alphaDiff) > 0.003f || abs(lyricsAlphaVel) > 0.01f) {
                    val wL = 11.0f
                    val accL = wL * wL * alphaDiff - 2f * wL * lyricsAlphaVel
                    lyricsAlphaVel += accL * dtSec
                    lyricsAlpha = (lyricsAlpha + lyricsAlphaVel * dtSec).coerceIn(0f, 1f)
                } else {
                    lyricsAlpha = targetLyricsAlpha
                    lyricsAlphaVel = 0f
                }

                val targetLock = if (keyguardManager.isKeyguardLocked) 1f else 0f
                val lockDiff = targetLock - lockBlend
                if (abs(lockDiff) > 0.003f || abs(lockVel) > 0.01f) {
                    val wK = 5.5f
                    val accK = wK * wK * lockDiff - 2f * wK * lockVel
                    lockVel += accK * dtSec
                    lockBlend = (lockBlend + lockVel * dtSec).coerceIn(0f, 1f)
                } else {
                    lockBlend = targetLock
                    lockVel = 0f
                }

                targetEqAmp = if (activeState?.isPlaying == true && !isLowBattery) 1f else 0f
                val eqDiff = targetEqAmp - eqAmp
                if (abs(eqDiff) > 0.003f || abs(eqVel) > 0.01f) {
                    val wE = 12.0f
                    val accE = wE * wE * eqDiff - 2f * wE * eqVel
                    eqVel += accE * dtSec
                    eqAmp = (eqAmp + eqVel * dtSec).coerceIn(0f, 1f)
                } else {
                    eqAmp = targetEqAmp
                    eqVel = 0f
                }

                // Momentum & friction phase integrators for music physics + NotebookLM studio
                val stNow = activeState
                val isFocusNow = stNow != null && stNow.lyrics.isEmpty() &&
                    stNow.durationMs >= 20L * 60L * 1000L &&
                    (stNow.packageName == "com.google.android.youtube" || stNow.packageName == "com.android.chrome")
                val targetMusicOmega = if (stNow != null && stNow.isPlaying && !isLowBattery) {
                    if (isFocusNow) 0.35 else (0.85 * lyricPaceScale * (1.0 - 0.25 * gapRelaxFactor))
                } else 0.0
                proceduralAnimator.stepPhysics(dtSec, targetMusicOmega)

                val targetStudioOmega = if (stNow != null && stNow.isPlaying && !isLowBattery) {
                    if (isFocusNow) 0.45 else 1.0
                } else 0.0
                val stTau = if (targetStudioOmega > studioOmega) 0.45 else 0.65
                studioOmega += (targetStudioOmega - studioOmega) * (1.0 - kotlin.math.exp(-dtSec.toDouble() / stTau))
                if (abs(studioOmega) < 0.0005 && targetStudioOmega == 0.0) studioOmega = 0.0
                studioPhase += studioOmega * dtSec.toDouble()

                val st = activeState
                if (st != null && st.lyrics.isNotEmpty() && st.showLyrics && lyricYCenters.isNotEmpty()) {
                    val newIdx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs() + 220L)
                    if (newIdx != currentLineIndex) {
                        currentLineIndex = newIdx
                        targetY = if (newIdx in lyricYCenters.indices) lyricYCenters[newIdx] else 0f
                    }

                    // Critically-damped 120Hz butter-smooth glide (~950ms)
                    val diff = targetY - springY
                    if (abs(diff) > 0.20f || abs(springVelocity) > 0.4f) {
                        val omega = 3.6f
                        val accel = omega * omega * diff - 2f * omega * springVelocity
                        springVelocity += accel * dtSec
                        springY += springVelocity * dtSec
                    } else {
                        springY = targetY
                        springVelocity = 0f
                    }
                }

                refreshThermalGuard(SystemClock.elapsedRealtime())
                val highRate = isHighRateNeeded()
                // Keep the surface hint aligned with the render cadence. This
                // avoids requesting a high refresh rate while the low-motion loop
                // is drawing only about 30 frames per second.
                setSurfaceFrameRate(if (highRate) preferredHighFrameRate() else 0f)
                if (highRate || (frameTimeNanos - lastDrawNanos) >= 31_000_000L) {
                    lastDrawNanos = frameTimeNanos
                    drawFrame(frameTimeNanos)
                }

                if (needsAnimation()) {
                    frameScheduled = true
                    if (highRate) {
                        choreographer.postFrameCallback(this)
                    } else {
                        // The low-motion scene previously ran doFrame at display
                        // refresh (often 90/120 Hz) while drawing only ~32 fps.
                        // Throttle the whole update loop, not just Canvas draws.
                        lowRateCallbackPending = true
                        mainHandler.postDelayed(lowRateFrameRunnable, 31L)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun drawFrame(frameTimeNanos: Long) {
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (_: Exception) {
                try { holder.lockCanvas() } catch (_: Exception) { null }
            } ?: return

            try {
                val isLock = keyguardManager.isKeyguardLocked
                canvas.drawColor(0xFF05060A.toInt())

                if (isCrossfading && crossfadeProgress < 1f) {
                    val t = crossfadeProgress
                    val eased = t * t * t * (10f - 15f * t + 6f * t * t)
                    drawState(canvas, prevState, isLock, 1f - eased, frameTimeNanos)
                    drawState(canvas, activeState, isLock, eased, frameTimeNanos)
                } else {
                    drawState(canvas, activeState, isLock, 1f, frameTimeNanos)
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }

        private fun drawState(
            canvas: Canvas,
            state: ActiveTrackState?,
            isLock: Boolean,
            alphaFactor: Float,
            frameTimeNanos: Long
        ) {
            if (alphaFactor <= 0.01f) return
            val masterAlpha = (alphaFactor * 255).toInt().coerceIn(0, 255)
            val timeSec = frameTimeNanos / 1_000_000_000.0

            if (state == null) {
                val sky = getSkySnapshot(isLock)
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastEnvCacheMs >= 15_000L || lastEnvCacheMs == 0L) {
                    lastEnvCacheMs = nowMs
                    cachedDay = currentDayOfMonth()
                    val prevDoy = LocationStore.getPreviewDayOfYear(applicationContext)
                    cachedDayOfYear = if (prevDoy in 1..366) {
                        prevDoy
                    } else {
                        val cal = Calendar.getInstance()
                        landscapeEngine.shuffledLiveBiomeDay(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR))
                    }
                    val (_, effMin) = effectiveTimeMillisAndMinute()
                    cachedMin = effMin
                    cachedLoc = LocationStore.load(applicationContext)
                    cachedWeather = WeatherClient.getCachedOrRefresh(applicationContext, cachedLoc.lat, cachedLoc.lon)
                }
                val weatherState = cachedWeather
                val circ = artProcessor.getCircadianTheme(cachedDay, cachedMin, sky.sunAltDeg, weatherState.humidityPct)
                bmpPaint.alpha = masterAlpha
                backdropRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(circ.backdrop, null, backdropRect, bmpPaint)
                drawMotherEarthCircadianScene(canvas, circ, sky, weatherState, isLock, timeSec, masterAlpha)
                return
            }

            val isLongFormFocus = state.lyrics.isEmpty() &&
                state.durationMs >= 20L * 60L * 1000L &&
                (state.packageName == "com.google.android.youtube" || state.packageName == "com.android.chrome")

            if (state === activeState) {
                var targetPace = 1.0f
                var targetRelax = 0.0f
                if (state.lyrics.isNotEmpty() && state.isPlaying) {
                    val approxIdx = LrcParser.findLineIndex(state.lyrics, state.currentPositionMs())
                    if (approxIdx in 0 until state.lyrics.lastIndex) {
                        val lineGapMs = (state.lyrics[approxIdx + 1].timeMs - state.lyrics[approxIdx].timeMs).coerceAtLeast(500L)
                        targetPace = (3500f / lineGapMs.toFloat()).coerceIn(0.75f, 1.32f)
                        if (lineGapMs > 10_000L) targetRelax = 1.0f
                    }
                }
                lyricPaceScale += (targetPace - lyricPaceScale) * 0.04f
                gapRelaxFactor += (targetRelax - gapRelaxFactor) * 0.03f
            }

            val art = state.processedArt
            if (art != null) {
                val breathSec = if (isLongFormFocus) 22.0 else art.moodBreathPeriodSec
                val breathAmp = if (isLongFormFocus) 0.009f else 0.0175f
                drawBreathingBackdrop(canvas, art.blurredBackdrop, state.isPlaying && !isLowBattery, timeSec, masterAlpha, breathSec, breathAmp)
                // Gentle readability scrim so bright silver/peach covers never wash out 2-shade lyrics
                orbPaint.shader = null
                orbPaint.color = 0xFF05070D.toInt()
                orbPaint.alpha = (masterAlpha * 0.30f).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), orbPaint)
            }

            val accent = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt()
            val primary = art?.primaryGlowColor ?: 0xFF38BDF8.toInt()
            val secondary = art?.secondaryGlowColor ?: 0xFF1E293B.toInt()
            val moodScale = art?.moodGlowScale ?: 1.0f
            val physicsRegime = if (isLongFormFocus) 0 else (art?.moodRegime ?: 2)

            val homeAnimFade = (1f - lockBlend).coerceIn(0f, 1f)
            if (homeAnimFade > 0.02f && (state.isPlaying || proceduralAnimator.isCoasting()) && !isLowBattery) {
                val lyricCalm = if (state.showLyrics && state.lyrics.isNotEmpty()) 0.45f else 0.85f
                val animScale = moodScale * lyricCalm * homeAnimFade * (1f - 0.25f * gapRelaxFactor) * (if (isLongFormFocus) 0.50f else 1.0f)
                val animAlpha = (masterAlpha * animScale).toInt().coerceIn(0, 255)
                proceduralAnimator.draw(
                    canvas = canvas,
                    width = width,
                    height = height,
                    accent = accent,
                    primary = primary,
                    secondary = secondary,
                    regime = physicsRegime,
                    masterAlpha = animAlpha
                )
            }

            val lb = lockBlend
            val headerTop = height * (0.085f + (0.305f - 0.085f) * lb)
            val headerFactor = if (state.isPlaying) 1f else 0.50f
            val headerAlpha = (masterAlpha * headerFactor).toInt().coerceIn(0, 255)
            drawUnifiedCompactHeader(canvas, state, art, headerTop, timeSec, accent, primary, headerAlpha)

            if (state.isNotebookLm) {
                drawNotebookTwoHostStudio(canvas, state, isLock, timeSec, masterAlpha)
                return
            }

            val isCurrent = state === activeState
            val lyrAlpha = (masterAlpha * (if (isCurrent) lyricsAlpha else prevLyricsAlpha)).toInt().coerceIn(0, 255)
            val layouts = if (isCurrent) centeredLayouts else prevCenteredLayouts
            val centers = if (isCurrent) lyricYCenters else prevLyricYCenters
            val maxWidths = if (isCurrent) lyricMaxLineW else prevLyricMaxLineW
            val stepPx = if (isCurrent) avgLineStep else prevAvgLineStep
            val yScroll = if (isCurrent) springY else prevSpringY
            val clipTop = height * (0.205f + (0.42f - 0.205f) * lb)
            val clipBottom = height * (0.825f + (0.74f - 0.825f) * lb)
            val centerY = height * (0.512f + (0.572f - 0.512f) * lb)
            val centerX = width * 0.06f

            if (lyrAlpha > 2 && layouts.isNotEmpty() && centers.isNotEmpty()) {
                drawWindowedKaraokeLyrics(
                    canvas = canvas,
                    state = state,
                    layouts = layouts,
                    yCenters = centers,
                    maxLineWidths = maxWidths,
                    lineStepPx = stepPx,
                    scrollY = yScroll,
                    centerX = centerX,
                    centerY = centerY,
                    clipTop = clipTop,
                    clipBottom = clipBottom,
                    accentColor = accent,
                    masterAlpha = lyrAlpha,
                    frameTimeNanos = frameTimeNanos
                )
            } else if (!state.isFetchingLyrics && (!state.showLyrics || state.lyrics.isEmpty())) {
                drawInstrumentalStudio(canvas, state, lb, timeSec, accent, primary, masterAlpha, isLongFormFocus)
            }
        }

        private fun projectAzimuthToX(azDeg: Float): Float {
            // Panoramic East (60°) -> South (180°) -> West (300°) sky window centered on South (180°)
            // Signed offset prevents NE rises (<60°) from wrapping around to the West/right edge.
            var signedFromSouth = ((azDeg - 180f) % 360f + 540f) % 360f - 180f
            val frac = ((signedFromSouth + 120f) / 240f).coerceIn(0.06f, 0.94f)
            return width * (0.08f + 0.84f * frac)
        }

        private fun projectAltitudeToY(altDeg: Float, horizonY: Float, isLock: Boolean): Float {
            val topY = if (isLock) height * 0.09f else height * 0.07f
            val altFrac = (altDeg / 85f).coerceIn(-0.08f, 1.0f)
            return horizonY - (horizonY - topY) * altFrac
        }

        private fun drawMotherEarthCircadianScene(
            canvas: Canvas,
            circ: CircadianTheme,
            sky: SkySnapshot,
            weatherState: WeatherState,
            isLock: Boolean,
            timeSec: Double,
            masterAlpha: Int
        ) {
            val isDay = sky.sunAltDeg > -2f
            val horizonY = landscapeEngine.horizonY(height, lockBlend)
            val weather = weatherState.sky
            val clarityBoost = landscapeEngine.skyClarityBoost(cachedDayOfYear)
            val windDir = if (weatherState.windDirDeg in 0f..180f) -1f else 1f

            val sunX = projectAzimuthToX(sky.sunAzDeg)
            val sunY = projectAltitudeToY(sky.sunAltDeg, horizonY, isLock)
            val moonX = projectAzimuthToX(sky.moonAzDeg)
            val moonY = projectAltitudeToY(sky.moonAltDeg, horizonY, isLock)
            val orbX = if (sky.sunAltDeg > -4f) sunX else moonX

            fun drawSoftOval(cx: Float, cy: Float, rx: Float, ry: Float, colWithAlpha: Int) {
                val sh = getCachedSoftRadialShader(colWithAlpha)
                shaderMatrix.reset()
                shaderMatrix.setScale(rx, ry)
                shaderMatrix.postTranslate(cx, cy)
                sh.setLocalMatrix(shaderMatrix)
                orbPaint.shader = sh
                backdropRect.set(cx - rx, cy - ry, cx + rx, cy + ry)
                canvas.drawOval(backdropRect, orbPaint)
                orbPaint.shader = null
            }

            // Clip all sky bodies strictly above the sea horizon
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), horizonY)

            // 1A. Milky Way Band (rotates with LST during dark astronomical night)
            val mwFade = ((-10f - sky.sunAltDeg) / 8f).coerceIn(0f, 1f)
            if (mwFade > 0.05f && visibleMwCount > 0 && weather == SkyWeather.CLEAR) {
                val mwCol = (0x00A5B4FC) or (((masterAlpha * 0.09f * mwFade * clarityBoost).toInt().coerceIn(0, 255)) shl 24)
                for (mIdx in 0 until visibleMwCount) {
                    drawSoftOval(mwX[mIdx], mwY[mIdx], width * 0.24f, height * 0.09f, mwCol)
                }
            }

            // 1B. Real Sidereal Stars & 5 Naked-Eye Planets (magnitude-thresholded at twilight)
            val starFade = ((-3f - sky.sunAltDeg) / 9f).coerceIn(0f, 1f)
            if (starFade > 0.02f) {
                val cloudDim = (1f - (weatherState.cloudCoverPct / 140f)).coerceIn(0.25f, 1f)
                orbPaint.shader = null
                for (i in 0 until visibleStarCount) {
                    // Brightest stars emerge earlier in twilight
                    val magThresh = ((-3f - sky.sunAltDeg) / 9f) * 4.5f - 1.5f
                    if (starMag[i] > magThresh) continue
                    val twinkle = 0.45f + 0.55f * ((sin(timeSec * 1.3 + i * 1.7) + 1.0) * 0.5).toFloat()
                    orbPaint.color = starCol[i]
                    orbPaint.alpha = (masterAlpha * 0.62f * starFade * cloudDim * twinkle).toInt().coerceIn(0, 255)
                    canvas.drawCircle(starX[i], starY[i], starR[i] * (density * 0.42f), orbPaint)
                }

                // 5 Naked-Eye Planets (Mercury, Venus, Mars, Jupiter, Saturn)
                for (p in sky.planets) {
                    val px = projectAzimuthToX(p.azDeg)
                    val py = projectAltitudeToY(p.altDeg, horizonY, isLock)
                    orbPaint.color = p.color
                    orbPaint.alpha = (masterAlpha * 0.90f * starFade * cloudDim).toInt().coerceIn(0, 255)
                    canvas.drawCircle(px, py, density * (if (p.name == "Venus" || p.name == "Jupiter") 2.1f else 1.5f), orbPaint)
                }

                // 1C. Cycle-Hashed Varied Shooting Stars (+ Meteor Shower Peak Boost)
                if (starFade > 0.45f && weather == SkyWeather.CLEAR) {
                    val period = if (sky.isMeteorShowerPeak || circ.particleKind == 4) 10.0 else 26.0
                    val cycle = kotlin.math.floor(timeSec / period).toLong()
                    val phase = timeSec % period
                    val dur = 0.65 + ((cycle * 37L) % 3) * 0.12
                    if (phase < dur) {
                        val h1 = abs(sin(cycle * 12.9898)).toFloat()
                        val h2 = abs(sin(cycle * 78.233)).toFloat()
                        val dir = if (cycle % 2L == 0L) 1f else -1f
                        val startX = width * (0.18f + 0.64f * h1)
                        val startY = height * (0.07f + 0.24f * h2)
                        val prog = (phase / dur).toFloat()
                        val dxSpan = width * (0.24f + 0.14f * h2) * dir
                        val dySpan = height * (0.10f + 0.08f * h1)
                        val sx = startX + dxSpan * prog
                        val sy = startY + dySpan * prog
                        val tail = 0.18f * (1f - prog * 0.4f)
                        val ex = sx - dxSpan * tail
                        val ey = sy - dySpan * tail
                        val fade = sin(prog * PI).toFloat() * starFade
                        baselinePaint.strokeWidth = density * 1.35f
                        baselinePaint.color = Color.WHITE
                        baselinePaint.alpha = (masterAlpha * 0.80f * fade).toInt().coerceIn(0, 255)
                        canvas.drawLine(sx, sy, ex, ey, baselinePaint)
                    }
                }
            }

            // 2A. Real Sun (or Solar Eclipse Corona!) with Kasten-Young Air-Mass Color
            if (sky.sunAltDeg > -3f) {
                val horizonFade = ((sky.sunAltDeg + 3f) / 3f).coerceIn(0f, 1f)
                val coronaCol = if (isCharging) 0xFF34D399.toInt() else sky.sunCoronaColor
                val pulse = if (isCharging) 0.75f + 0.25f * sin(timeSec * 2.2).toFloat() else 1f
                val glowR = width * 0.16f
                val sunGlowAlpha = (masterAlpha * 0.48f * horizonFade * pulse).toInt().coerceIn(0, 255)
                drawSoftOval(sunX, sunY, glowR, glowR, (coronaCol and 0x00FFFFFF) or (sunGlowAlpha shl 24))

                orbPaint.color = if (sky.isSolarEclipse) 0xFF05060A.toInt() else sky.sunColor
                orbPaint.alpha = (masterAlpha * 0.94f * horizonFade).toInt()
                canvas.drawCircle(sunX, sunY, width * 0.021f, orbPaint)
            }

            // 2B. Real-Phase Moon with Air-Mass Orange->Silver Color, Bright-Limb Tilt & Daytime Blending
            if (sky.moonAltDeg > -2f) {
                val moonHorizonFade = ((sky.moonAltDeg + 2f) / 2.5f).coerceIn(0f, 1f)
                val dayVisibility = if (isDay) 0.38f else 0.94f
                val r = width * 0.024f * sky.moonSizeScale
                val moonGlowR = r * 5.2f
                val glowCol = if (isCharging && sky.sunAltDeg <= -3f) 0xFF34D399.toInt() else sky.moonCoronaColor
                val moonGlowAlpha = (masterAlpha * 0.36f * moonHorizonFade * dayVisibility).toInt().coerceIn(0, 255)
                drawSoftOval(moonX, moonY, moonGlowR, moonGlowR, (glowCol and 0x00FFFFFF) or (moonGlowAlpha shl 24))

                canvas.save()
                canvas.translate(moonX, moonY)
                canvas.rotate(-sky.moonLimbTiltDeg - 90f)

                if (!isDay) {
                    orbPaint.color = sky.moonColor
                    orbPaint.alpha = (masterAlpha * 0.12f * moonHorizonFade).toInt()
                    canvas.drawCircle(0f, 0f, r, orbPaint)
                }

                val k = sky.moonIllumination.coerceIn(0.02f, 1f)
                val termRx = r * kotlin.math.abs(2f * k - 1f)
                moonPath.reset()
                arcRect.set(-r, -r, r, r)
                moonPath.arcTo(arcRect, -90f, 180f, true)
                arcRect.set(-termRx, -r, termRx, r)
                if (k >= 0.5f) {
                    moonPath.arcTo(arcRect, 90f, 180f, false)
                } else {
                    moonPath.arcTo(arcRect, 90f, -180f, false)
                }
                moonPath.close()

                orbPaint.color = sky.moonColor
                orbPaint.alpha = (masterAlpha * dayVisibility * moonHorizonFade).toInt()
                canvas.drawPath(moonPath, orbPaint)
                canvas.restore()
            }

            // 3. Sunlit Wind-Driven Clouds (driven by wind_direction_10m & cloud_cover_low/high)
            val dayFactor = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            if (dayFactor > 0.01f) {
                val windFactor = (0.75f + weatherState.windKmh / 24f).coerceIn(0.65f, 1.85f)
                val lowMix = (weatherState.cloudLowPct / 100f).coerceIn(0f, 1f)
                val highMix = (weatherState.cloudHighPct / 100f).coerceIn(0f, 1f)
                val cloudScale = (0.32f + (weatherState.cloudCoverPct / 100f) * 0.95f).coerceIn(0.28f, 1.35f)
                val cloudTint = ColorUtils.blendARGB(Color.WHITE, circ.horizonColor, 0.22f) and 0x00FFFFFF
                val sunRimRgb = sky.sunColor and 0x00FFFFFF
                var nearSunBeam = 0f

                for (c in 0 until 3) {
                    val speed = (0.0028f + c * 0.0007f) * windFactor * windDir
                    val cx = ((((0.18f + c * 0.36f + (timeSec * speed).toFloat()) % 1.48f) + 1.48f) % 1.48f - 0.24f) * width
                    val cy = height * (0.24f + c * 0.10f)
                    val layerBoost = if (c == 0) (0.85f + 0.35f * highMix) else (0.85f + 0.35f * lowMix)
                    val baseAlpha = (masterAlpha * (0.23f - c * 0.04f) * cloudScale * layerBoost * dayFactor).toInt().coerceIn(0, 255)
                    val col = cloudTint or (baseAlpha shl 24)
                    drawSoftOval(cx, cy, width * 0.30f, height * (0.028f + 0.010f * lowMix), col)
                    drawSoftOval(cx - width * 0.10f, cy - height * 0.010f, width * 0.18f, height * 0.022f, col)
                    drawSoftOval(cx + width * 0.11f, cy + height * 0.006f, width * 0.20f, height * 0.020f, col)

                    val sunDir = if (sunX >= cx) 1f else -1f
                    val rimAlpha = (masterAlpha * 0.12f * cloudScale * dayFactor).toInt().coerceIn(0, 255)
                    drawSoftOval(cx + sunDir * width * 0.08f, cy - height * 0.004f, width * 0.16f, height * 0.018f, sunRimRgb or (rimAlpha shl 24))

                    val dSun = kotlin.math.abs(cx - sunX) / (width * 0.30f)
                    if (dSun < 1f) {
                        nearSunBeam = maxOf(nearSunBeam, 1f - dSun)
                    }
                }

                if (nearSunBeam > 0.05f && sky.sunAltDeg > 4f) {
                    val spanY = (horizonY - sunY).coerceAtLeast(height * 0.08f)
                    val driftX = (sunX - width * 0.5f) * 0.08f
                    val upperAlpha = (masterAlpha * 0.055f * nearSunBeam * dayFactor).toInt().coerceIn(0, 255)
                    val lowerAlpha = (masterAlpha * 0.035f * nearSunBeam * dayFactor).toInt().coerceIn(0, 255)
                    drawSoftOval(sunX + driftX * 0.5f, sunY + spanY * 0.34f, width * 0.20f, spanY * 0.40f, sunRimRgb or (upperAlpha shl 24))
                    drawSoftOval(sunX + driftX, sunY + spanY * 0.64f, width * 0.28f, spanY * 0.38f, sunRimRgb or (lowerAlpha shl 24))
                }
            }

            // 3B. Golden-Hour Horizon Scatter & Weather Rain / Lightning (scaled by precipitation & visibility)
            if (sky.sunAltDeg in -6f..8f) {
                val ghStrength = (1f - kotlin.math.abs(sky.sunAltDeg - 1f) / 7f).coerceIn(0f, 1f)
                val ghAlpha = (masterAlpha * 0.28f * ghStrength).toInt().coerceIn(0, 255)
                val ghRgb = sky.sunCoronaColor and 0x00FFFFFF
                drawSoftOval(sunX, horizonY, width * 0.72f, height * 0.085f, ghRgb or (ghAlpha shl 24))
            }

            val visFog = ((4500f - weatherState.visibilityM) / 4000f).coerceIn(0f, 1f)
            if (weather == SkyWeather.CLOUDY || weather == SkyWeather.RAIN || weather == SkyWeather.THUNDERSTORM || visFog > 0.1f) {
                val baseHaze = if (weather == SkyWeather.CLOUDY) 0.16f else if (weather == SkyWeather.CLEAR) 0.08f else 0.12f
                val hazeAlpha = (masterAlpha * (baseHaze + visFog * 0.16f)).toInt().coerceIn(0, 255)
                drawSoftOval(width * 0.5f, horizonY - height * 0.04f, width * 0.85f, height * 0.09f, (0x00CBD5E1) or (hazeAlpha shl 24))
            }

            if (weather == SkyWeather.THUNDERSTORM) {
                val flashPhase = timeSec % 18.0
                if (flashPhase < 0.25) {
                    val fEnv = sin((flashPhase / 0.25) * PI).toFloat()
                    val fAlpha = (masterAlpha * 0.36f * fEnv).toInt().coerceIn(0, 255)
                    drawSoftOval(width * 0.68f, height * 0.38f, width * 0.45f, height * 0.14f, (0x00E0F2FE) or (fAlpha shl 24))
                }
            }

            if (weather == SkyWeather.RAIN || weather == SkyWeather.THUNDERSTORM) {
                val dropCount = (12 + (weatherState.precipMm * 3f).toInt()).coerceIn(12, 28)
                val windSlant = width * (0.008f + (weatherState.windKmh / 65f).coerceIn(0f, 0.026f)) * windDir
                baselinePaint.strokeWidth = density * 0.9f
                baselinePaint.color = 0xFFE2E8F0.toInt()
                baselinePaint.alpha = (masterAlpha * 0.22f).toInt()
                for (rIdx in 0 until dropCount) {
                    val hDrop = abs(sin(rIdx * 12.9898 + 0.5)).toFloat()
                    val rProg = (((timeSec * (1.55 + hDrop * 0.35)) + rIdx * 0.19 + hDrop * 0.37) % 1.0 + 1.0) % 1.0
                    val rx = (hDrop * width) + (rProg.toFloat() * windSlant * 2.2f)
                    val ry = rProg.toFloat() * horizonY
                    canvas.drawLine(rx, ry, rx + windSlant, ry + height * 0.024f, baselinePaint)
                }
            }

            canvas.restore()

            // 4. 366-Day Living Landscape Engine (20 Worlds, Alpenglow, Biology, Lock<->Home Parallax)
            landscapeEngine.drawForeground(
                canvas = canvas,
                width = width,
                height = height,
                density = density,
                dayOfYear = cachedDayOfYear,
                sky = sky,
                circ = circ,
                weather = weatherState,
                sunX = sunX,
                sunY = sunY,
                moonX = moonX,
                moonY = moonY,
                lockBlend = lockBlend,
                timeSec = timeSec,
                masterAlpha = masterAlpha
            )
        }

        private fun drawNotebookTwoHostStudio(
            canvas: Canvas,
            state: ActiveTrackState,
            isLock: Boolean,
            timeSec: Double,
            masterAlpha: Int
        ) {
            // Dual-Host AI Podcast Studio: serene continuous 360-deg color spectrum (~4 min/cycle)
            // Quantized to 4-deg hue steps for zero-allocation 24-slot RadialGradient cache hits at 120 Hz
            val baseHue = (((state.title.hashCode() and 0x7FFFFFFF) % 360).toFloat())
            val rawHueA = (baseHue + (timeSec * 1.5).toFloat()) % 360f
            val hueA = ((rawHueA / 4f).toInt() * 4f) % 360f
            val hueB = (hueA + 70f) % 360f
            nbHslBuf[0] = hueA; nbHslBuf[1] = 0.82f; nbHslBuf[2] = 0.62f
            val hostACol = ColorUtils.HSLToColor(nbHslBuf)
            nbHslBuf[0] = hueB; nbHslBuf[1] = 0.80f; nbHslBuf[2] = 0.66f
            val hostBCol = ColorUtils.HSLToColor(nbHslBuf)
            val hostARgb = hostACol and 0x00FFFFFF
            val hostBRgb = hostBCol and 0x00FFFFFF

            val cy = if (isLock) height * 0.56f else height * 0.50f
            val cx = width * 0.50f
            val p = studioPhase

            // Smooth conversational turn-taking between Host A (left-biased) and Host B (right-biased)
            val turnA = (0.45f + 0.55f * (0.65f * sin(p * 1.45).toFloat() + 0.35f * sin(p * 2.35).toFloat())).coerceIn(0.15f, 1f)
            val turnB = (0.45f + 0.55f * (0.65f * sin(p * 1.45 + PI).toFloat() + 0.35f * sin(p * 2.35 + 2.0).toFloat())).coerceIn(0.15f, 1f)

            // Soft alternating studio halos behind the active speaking host
            val sep = width * 0.16f
            val rA = width * (0.24f + 0.08f * turnA * eqAmp)
            val rB = width * (0.24f + 0.08f * turnB * eqAmp)
            val shA = getCachedSoftRadialShader(hostARgb or (((masterAlpha * (0.24f + 0.24f * turnA) * eqAmp).toInt().coerceIn(0, 255)) shl 24))
            shaderMatrix.reset(); shaderMatrix.setScale(rA, rA * 0.72f); shaderMatrix.postTranslate(cx - sep, cy)
            shA.setLocalMatrix(shaderMatrix); orbPaint.shader = shA
            backdropRect.set(cx - sep - rA, cy - rA * 0.72f, cx - sep + rA, cy + rA * 0.72f)
            canvas.drawOval(backdropRect, orbPaint)

            val shB = getCachedSoftRadialShader(hostBRgb or (((masterAlpha * (0.24f + 0.24f * turnB) * eqAmp).toInt().coerceIn(0, 255)) shl 24))
            shaderMatrix.reset(); shaderMatrix.setScale(rB, rB * 0.72f); shaderMatrix.postTranslate(cx + sep, cy)
            shB.setLocalMatrix(shaderMatrix); orbPaint.shader = shB
            backdropRect.set(cx + sep - rB, cy - rB * 0.72f, cx + sep + rB, cy + rB * 0.72f)
            canvas.drawOval(backdropRect, orbPaint)
            orbPaint.shader = null

            // Two intertwined living voice waveforms (Host A & Host B) crossing cleanly across center
            val waveW = width * 0.84f
            val startX = cx - waveW * 0.5f
            val steps = 48
            for (host in 0..1) {
                val hCol = if (host == 0) hostACol else hostBCol
                val hTurn = if (host == 0) turnA else turnB
                val freq = if (host == 0) 2.6 else 3.2
                val speed = if (host == 0) 2.8 else -2.3
                val amp = width * (0.042f + 0.056f * hTurn) * (0.25f + 0.75f * eqAmp)

                ribbonPath.reset()
                for (s in 0..steps) {
                    val u = s / steps.toFloat()
                    val x = startX + u * waveW
                    val env = sin(u * PI).toFloat().let { it * it }
                    val yOff = (sin(u * PI * 2.0 * freq + p * speed) * 0.65 +
                        sin(u * PI * 2.0 * (freq * 1.75) - p * speed * 1.3) * 0.35).toFloat() * amp * env
                    if (s == 0) ribbonPath.moveTo(x, cy + yOff) else ribbonPath.lineTo(x, cy + yOff)
                }
                // Soft glow under-stroke + crisp core voice wave
                ribbonPaint.color = hCol
                ribbonPaint.strokeWidth = density * (4.2f + 2.5f * hTurn)
                ribbonPaint.alpha = (masterAlpha * (0.18f + 0.16f * hTurn)).toInt().coerceIn(0, 255)
                canvas.drawPath(ribbonPath, ribbonPaint)

                ribbonPaint.color = ColorUtils.blendARGB(hCol, Color.WHITE, 0.32f * hTurn)
                ribbonPaint.strokeWidth = density * (1.8f + 0.9f * hTurn)
                ribbonPaint.alpha = (masterAlpha * (0.58f + 0.38f * hTurn)).toInt().coerceIn(0, 255)
                canvas.drawPath(ribbonPath, ribbonPaint)
            }

            // Subtle speaker turn-indicator dots below the intertwined waves (no duplicate timer!)
            val dotY = cy + width * 0.135f
            dotPaint.color = hostACol; dotPaint.alpha = (masterAlpha * (0.35f + 0.65f * turnA)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx - density * 9f, dotY, density * (2.6f + 1.4f * turnA), dotPaint)
            dotPaint.color = hostBCol; dotPaint.alpha = (masterAlpha * (0.35f + 0.65f * turnB)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx + density * 9f, dotY, density * (2.6f + 1.4f * turnB), dotPaint)
        }

        private fun drawInstrumentalStudio(
            canvas: Canvas,
            state: ActiveTrackState,
            lockBlend: Float,
            timeSec: Double,
            accent: Int,
            primary: Int,
            masterAlpha: Int,
            isFocusMode: Boolean = false
        ) {
            // Rich 24-bar symmetric rounded-pill harmonic wave visualizer + 2 silky ribbons + rising sparks (no duplicate timer)
            val cx = width * 0.50f
            val cy = height * (0.505f + (0.565f - 0.505f) * lockBlend)
            val vibrant = boostLyricAccent(accent)
            val brightTip = ColorUtils.blendARGB(vibrant, Color.WHITE, 0.42f)
            val focusScale = if (isFocusMode) 0.58f else 1.0f

            // 1. Soft breathing radial aura
            if (eqAmp > 0.02f) {
                val auraBreath = 1.0f + 0.12f * sin(studioPhase * 1.15).toFloat()
                val auraRx = width * 0.44f * auraBreath
                val auraRy = width * 0.18f * auraBreath
                val auraAlpha = (masterAlpha * (if (isFocusMode) 0.16f else 0.28f) * eqAmp).toInt().coerceIn(0, 255)
                val auraSh = getCachedSoftRadialShader((vibrant and 0x00FFFFFF) or (auraAlpha shl 24))
                shaderMatrix.reset(); shaderMatrix.setScale(auraRx, auraRy); shaderMatrix.postTranslate(cx, cy)
                auraSh.setLocalMatrix(shaderMatrix)
                orbPaint.shader = auraSh
                backdropRect.set(cx - auraRx, cy - auraRy, cx + auraRx, cy + auraRy)
                canvas.drawOval(backdropRect, orbPaint)
                orbPaint.shader = null
            }

            // 2. Two silky background harmonic ribbons weaving behind the spectrum
            val spanW = width * 0.84f
            val startX = cx - spanW * 0.5f
            val steps = 40
            for (rIdx in 0 until 2) {
                val rCol = if (rIdx == 0) vibrant else primary
                val amp = width * (0.048f + rIdx * 0.014f) * (0.25f + 0.75f * eqAmp) * focusScale
                val freq = 2.0 + rIdx * 0.7
                val speed = (if (rIdx == 0) 2.1 else -1.7)
                ribbonPath.reset()
                for (s in 0..steps) {
                    val u = s / steps.toFloat()
                    val x = startX + u * spanW
                    val env = sin(u * PI).toFloat().let { it * it }
                    val yOff = sin(u * PI * 2.0 * freq + studioPhase * speed).toFloat() * amp * env
                    if (s == 0) ribbonPath.moveTo(x, cy + yOff) else ribbonPath.lineTo(x, cy + yOff)
                }
                ribbonPaint.color = rCol
                ribbonPaint.strokeWidth = density * 1.7f
                ribbonPaint.alpha = (masterAlpha * 0.34f * focusScale).toInt().coerceIn(0, 255)
                canvas.drawPath(ribbonPath, ribbonPaint)
            }

            // 3. 24-bar symmetric rounded-pill harmonic spectrum (120 Hz zero-allocation)
            val barCount = 24
            val specW = width * 0.76f
            val barStep = specW / (barCount - 1)
            val barStartX = cx - specW * 0.5f
            val maxHalfH = width * 0.115f * focusScale
            eqBarPaint.strokeCap = Paint.Cap.ROUND
            eqBarPaint.strokeWidth = density * 3.8f

            for (b in 0 until barCount) {
                val u = b / (barCount - 1f) // 0..1
                val bell = sin(u * PI).toFloat()
                val sym = abs(u - 0.5f) * 2f // 0 at center, 1 at edges
                val wave1 = 0.5f + 0.5f * sin(studioPhase * 3.1 - sym * 5.2).toFloat()
                val wave2 = 0.5f + 0.5f * sin(studioPhase * 4.7 + b * 0.65).toFloat()
                val energy = (0.16f + 0.84f * (0.62f * wave1 + 0.38f * wave2) * eqAmp) * bell
                val halfH = (density * 3.5f + maxHalfH * energy)
                val bx = barStartX + b * barStep

                // Soft translucent bloom under-stroke (zero Gaussian blur cost) + crisp bright core bar
                val barCol = ColorUtils.blendARGB(vibrant, brightTip, (energy * 0.85f).coerceIn(0f, 1f))
                if (energy > 0.28f) {
                    eqBarPaint.color = vibrant
                    eqBarPaint.strokeWidth = density * 8.6f
                    eqBarPaint.alpha = (masterAlpha * 0.20f * energy).toInt().coerceIn(0, 255)
                    canvas.drawLine(bx, cy - halfH, bx, cy + halfH, eqBarPaint)
                }
                eqBarPaint.strokeWidth = density * 3.8f
                eqBarPaint.color = barCol
                eqBarPaint.alpha = (masterAlpha * (0.42f + 0.56f * energy)).toInt().coerceIn(0, 255)
                canvas.drawLine(bx, cy - halfH, bx, cy + halfH, eqBarPaint)
            }

            // 4. Subtle rising ambient sparks in album's vibrant glow
            if (eqAmp > 0.1f && !isFocusMode) {
                for (sp in 0 until 8) {
                    val phase = (((studioPhase * 0.14 + sp * 0.137) % 1.0 + 1.0) % 1.0).toFloat()
                    val fade = sin(phase * PI).toFloat().coerceIn(0f, 1f)
                    val sx = cx + sin(sp * 2.4 + studioPhase * 0.6).toFloat() * width * 0.34f
                    val sy = cy + width * 0.08f - phase * width * 0.24f
                    dotPaint.color = brightTip
                    dotPaint.alpha = (masterAlpha * 0.55f * fade * eqAmp).toInt().coerceIn(0, 255)
                    canvas.drawCircle(sx, sy, density * (1.3f + (sp % 3) * 0.5f), dotPaint)
                }
            }
        }

        private fun drawBreathingBackdrop(
            canvas: Canvas,
            bmp: android.graphics.Bitmap,
            animate: Boolean,
            timeSec: Double,
            alpha: Int,
            breathPeriodSec: Double = 14.0,
            breathAmp: Float = 0.0175f
        ) {
            val breath = if (animate) {
                1.0f + breathAmp * (1f + sin(timeSec * (2.0 * PI / breathPeriodSec)).toFloat())
            } else 1.0f
            val extraW = width * (breath - 1f) * 0.5f
            val extraH = height * (breath - 1f) * 0.5f
            backdropRect.set(-extraW, -extraH, width + extraW, height + extraH)
            bmpPaint.alpha = alpha
            canvas.drawBitmap(bmp, null, backdropRect, bmpPaint)
        }

        private fun ensureHeaderTitleLayout(title: String, maxTextW: Int): StaticLayout {
            val key = "$title|$maxTextW"
            val existing = headerTitleLayout
            if (existing != null && headerTitleCacheKey == key) return existing
            headerTitlePaint.textSize = width * 0.052f
            val built = StaticLayout.Builder.obtain(title, 0, title.length, headerTitlePaint, maxTextW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setLineSpacing(2f, 1.02f)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            headerTitleLayout = built
            headerTitleCacheKey = key
            return built
        }

        private fun drawUnifiedCompactHeader(
            canvas: Canvas,
            state: ActiveTrackState,
            art: ProcessedArt?,
            headerTop: Float,
            timeSec: Double,
            accent: Int,
            primary: Int,
            headerAlpha: Int
        ) {
            val leftMargin = width * 0.06f
            val rightEdge = width * 0.94f
            val thumbSize = width * 0.19f

            if (art != null) {
                miniArtRect.set(leftMargin, headerTop, leftMargin + thumbSize, headerTop + thumbSize)
                val backColor = (primary and 0x00FFFFFF) or (((headerAlpha * 0.36f).toInt()) shl 24)
                val backShader = if (cachedBacklightColor == backColor && cachedBacklightShader != null) {
                    cachedBacklightShader!!
                } else {
                    RadialGradient(
                        0f, 0f, 1f,
                        intArrayOf(backColor, Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP
                    ).also {
                        cachedBacklightColor = backColor
                        cachedBacklightShader = it
                    }
                }
                val backR = thumbSize * 1.15f
                shaderMatrix.reset()
                shaderMatrix.setScale(backR, backR)
                shaderMatrix.postTranslate(miniArtRect.centerX(), miniArtRect.centerY())
                backShader.setLocalMatrix(shaderMatrix)
                backlightPaint.shader = backShader
                canvas.drawCircle(miniArtRect.centerX(), miniArtRect.centerY(), backR, backlightPaint)
                backlightPaint.shader = null

                val radius = thumbSize * 0.18f
                shadowPaint.alpha = headerAlpha
                canvas.drawRoundRect(miniArtRect, radius, radius, shadowPaint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(miniArtRect, radius, radius, Path.Direction.CW)
                canvas.clipPath(clipPath)
                bmpPaint.alpha = headerAlpha
                canvas.drawBitmap(art.coverArt, null, miniArtRect, bmpPaint)
                canvas.restore()

                rimPaint.alpha = (headerAlpha * 0.25f).toInt()
                canvas.drawRoundRect(miniArtRect, radius, radius, rimPaint)

                if (state.durationMs > 0L) {
                    drawClockwiseCoverProgressRim(
                        canvas = canvas,
                        rect = miniArtRect,
                        radius = radius,
                        fraction = state.progressFraction(),
                        accent = accent,
                        alpha = headerAlpha,
                        isPlaying = state.isPlaying && !isLowBattery
                    )
                }
            }

            val textLeft = leftMargin + thumbSize + width * 0.045f
            val maxTextW = (rightEdge - textLeft).coerceAtLeast(200f)

            val titleLayout = ensureHeaderTitleLayout(state.title, maxTextW.toInt())
            headerTitlePaint.alpha = headerAlpha
            headerArtistPaint.textSize = width * 0.036f
            headerArtistPaint.alpha = (headerAlpha * 0.80f).toInt()

            val titleH = titleLayout.height.toFloat()
            val artistH = headerArtistPaint.fontSpacing
            val eqRowOffset = thumbSize * 0.23f
            val totalBlockH = titleH + artistH * 0.92f + eqRowOffset
            val blockTop = (headerTop + (thumbSize - totalBlockH) * 0.5f).coerceAtLeast(headerTop - thumbSize * 0.04f)

            canvas.save()
            canvas.translate(textLeft, blockTop)
            titleLayout.draw(canvas)
            canvas.restore()

            val artistBaselineY = blockTop + titleH + artistH * 0.74f
            val displayArtist = state.artist.ifBlank {
                if (state.packageName == "com.google.android.youtube") "YouTube" else "Music"
            }
            canvas.drawText(
                ellipsize(displayArtist, headerArtistPaint, maxTextW),
                textLeft,
                artistBaselineY,
                headerArtistPaint
            )

            // Squiggly reactive progress line right below Artist Name (0% -> current position)
            val progFrac = if (state.durationMs > 0L) state.progressFraction() else 0f
            drawSquigglyProgressLine(
                canvas = canvas,
                startX = textLeft,
                centerY = artistBaselineY + thumbSize * 0.20f,
                totalW = maxTextW,
                progressFrac = progFrac,
                ampScale = eqAmp,
                accent = accent,
                alpha = headerAlpha
            )

            // Elapsed / Total timer + sequential bouncing ♪ ♫ ♪ ♬ notes cleanly below Album Art
            val artCenterX = leftMargin + thumbSize * 0.5f
            val subArtY = headerTop + thumbSize + width * 0.036f
            val posMs = state.currentPositionMs()
            val durMs = state.durationMs
            val timeStr = if (durMs > 0L) "${formatTime(posMs)} / ${formatTime(durMs)}" else formatTime(posMs)
            studioTimerPaint.textSize = width * 0.027f
            studioTimerPaint.color = ColorUtils.blendARGB(accent, Color.WHITE, 0.35f)
            studioTimerPaint.alpha = (headerAlpha * 0.85f).toInt().coerceIn(0, 255)
            canvas.drawText(timeStr, artCenterX, subArtY, studioTimerPaint)

            val noteY = subArtY + width * 0.042f
            val noteGap = width * 0.036f
            val noteSymbols = arrayOf("♪", "♫", "♪", "♬")
            instNotePaint.textSize = width * 0.034f
            instNotePaint.color = accent
            for (n in 0 until 4) {
                val nPhase = studioPhase * 3.6 - n * 0.85
                val breath = 0.55f + 0.45f * ((sin(nPhase) + 1.0) * 0.5).toFloat()
                val bounce = -kotlin.math.abs(sin(nPhase)).toFloat() * (width * 0.010f) * eqAmp
                val nx = artCenterX + (n - 1.5f) * noteGap
                instNotePaint.alpha = (headerAlpha * (0.35f + 0.65f * breath * eqAmp)).toInt().coerceIn(20, 255)
                canvas.drawText(noteSymbols[n], nx, noteY + bounce, instNotePaint)
            }
        }

        private fun drawSquigglyProgressLine(
            canvas: Canvas,
            startX: Float,
            centerY: Float,
            totalW: Float,
            progressFrac: Float,
            ampScale: Float,
            accent: Int,
            alpha: Int
        ) {
            val frac = progressFrac.coerceIn(0f, 1f)
            val playedW = (totalW * frac).coerceAtLeast(0f)
            val playedEnd = startX + playedW

            // Unplayed calm hairline ahead of current position
            baselinePaint.strokeWidth = density * 1.1f
            baselinePaint.color = accent
            if (playedEnd < startX + totalW - 2f) {
                baselinePaint.alpha = (alpha * 0.22f).toInt().coerceIn(0, 255)
                canvas.drawLine(playedEnd, centerY, startX + totalW, centerY, baselinePaint)
            }

            // Played portion: reactive squiggly wave from startX -> playedEnd (flattens on pause)
            if (playedW > 2f) {
                ribbonPaint.color = accent
                ribbonPaint.strokeWidth = density * 1.85f
                ribbonPaint.alpha = (alpha * (0.55f + 0.42f * ampScale)).toInt().coerceIn(0, 255)
                val amp = width * 0.0068f * ampScale
                val steps = (playedW / (density * 2.0f)).toInt().coerceIn(16, 160)
                ribbonPath.reset()
                for (s in 0..steps) {
                    val fx = s.toFloat() / steps
                    val x = startX + playedW * fx
                    val env = if (fx > 0.94f) ((1f - fx) / 0.06f) else 1f
                    val y = centerY + sin((x / (width * 0.0065f)) - studioPhase * 5.2).toFloat() * amp * env
                    if (s == 0) ribbonPath.moveTo(x, y) else ribbonPath.lineTo(x, y)
                }
                canvas.drawPath(ribbonPath, ribbonPaint)
            }
        }

        private fun drawClockwiseCoverProgressRim(
            canvas: Canvas,
            rect: RectF,
            radius: Float,
            fraction: Float,
            accent: Int,
            alpha: Int,
            isPlaying: Boolean
        ) {
            val frac = fraction.coerceIn(0f, 1f)
            if (frac <= 0.002f) return

            val cx = rect.centerX()
            val l = rect.left
            val t = rect.top
            val r = rect.right
            val b = rect.bottom
            val cr = radius.coerceAtMost(minOf(rect.width(), rect.height()) * 0.5f)
            val d = cr * 2f

            coverRimPath.reset()
            coverRimPath.moveTo(cx, t)
            coverRimPath.lineTo(r - cr, t)
            arcRect.set(r - d, t, r, t + d)
            coverRimPath.arcTo(arcRect, -90f, 90f, false)
            coverRimPath.lineTo(r, b - cr)
            arcRect.set(r - d, b - d, r, b)
            coverRimPath.arcTo(arcRect, 0f, 90f, false)
            coverRimPath.lineTo(l + cr, b)
            arcRect.set(l, b - d, l + d, b)
            coverRimPath.arcTo(arcRect, 90f, 90f, false)
            coverRimPath.lineTo(l, t + cr)
            arcRect.set(l, t, l + d, t + d)
            coverRimPath.arcTo(arcRect, 180f, 90f, false)
            coverRimPath.close()

            pathMeasure.setPath(coverRimPath, false)
            val totalLen = pathMeasure.length
            if (totalLen <= 0f) return

            coverProgressPath.reset()
            pathMeasure.getSegment(0f, totalLen * frac, coverProgressPath, true)

            if (isPlaying) {
                coverProgressGlowPaint.color = accent
                coverProgressGlowPaint.alpha = (alpha * 0.34f).toInt()
                canvas.drawPath(coverProgressPath, coverProgressGlowPaint)
            }

            coverProgressPaint.color = accent
            coverProgressPaint.alpha = (alpha * if (isPlaying) 0.94f else 0.45f).toInt()
            canvas.drawPath(coverProgressPath, coverProgressPaint)
        }

        private fun smoothstep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun drawWindowedKaraokeLyrics(
            canvas: Canvas,
            state: ActiveTrackState,
            layouts: List<StaticLayout>,
            yCenters: FloatArray,
            maxLineWidths: FloatArray,
            lineStepPx: Float,
            scrollY: Float,
            centerX: Float,
            centerY: Float,
            clipTop: Float,
            clipBottom: Float,
            accentColor: Int,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            val maxIdx = minOf(layouts.lastIndex, state.lyrics.lastIndex, yCenters.lastIndex)
            if (maxIdx < 0) return

            val posMs = state.currentPositionMs()
            val approxIdx = LrcParser.findLineIndex(state.lyrics, posMs).coerceIn(0, maxIdx)
            // Anchor the 9-line (-4..+4) window to visual scrollY so outer lines never pop in/out on timestamp ticks
            var visualCenterIdx = approxIdx
            var bestDist = Float.MAX_VALUE
            val scanLo = (approxIdx - 6).coerceAtLeast(0)
            val scanHi = (approxIdx + 6).coerceAtMost(maxIdx)
            for (k in scanLo..scanHi) {
                val d = abs(yCenters[k] - scrollY)
                if (d < bestDist) {
                    bestDist = d
                    visualCenterIdx = k
                }
            }
            val start = (visualCenterIdx - 5).coerceAtLeast(0)
            val end = (visualCenterIdx + 5).coerceAtMost(maxIdx)
            val clipH = (clipBottom - clipTop).coerceAtLeast(1f)
            val mistZone = clipH * 0.035f
            val isPlaying = state.isPlaying && !isLowBattery

            // Smoothly fill full corner-to-corner diagonal on Homescreen and fold cleanly above Lockscreen fingerprint
            val safeStep = lineStepPx.coerceAtLeast(1f)
            val homeStep = (clipH / 9.2f).coerceAtLeast(safeStep * 0.88f)
            val effectiveStep = homeStep + (safeStep - homeStep) * lockBlend
            val spreadRatio = (effectiveStep / safeStep).coerceIn(0.88f, 1.85f)

            // Strictly 2 shades (Option 2):
            // Shade 1 (Unsung & all outer lines): Soft Translucent Album Color (no plain white stealing brightness)
            // Shade 2 (Active sung sweep): Bright White-Hot Pastel Glow of that same album color
            val vibrantColor = boostLyricAccent(accentColor)
            val sungRgb = ColorUtils.blendARGB(vibrantColor, Color.WHITE, 0.34f) and 0x00FFFFFF
            val unsungRgb = vibrantColor and 0x00FFFFFF

            for (i in start..end) {
                val layout = layouts[i]
                val rawOffset = yCenters[i] - scrollY
                val lineOffset = rawOffset * spreadRatio
                val yPos = centerY + lineOffset - (layout.height / 2f)

                val topFade = smoothstep((yPos - clipTop) / mistZone)
                val botFade = smoothstep((clipBottom - (yPos + layout.height)) / mistZone)
                val mistFactor = minOf(topFade, botFade)
                if (mistFactor <= 0.005f) continue

                // Corner-to-corner readable pyramid: softly dimmed at +/-4..5 (48% floor) -> brightest at dead center
                val stepDist = abs(rawOffset) / safeStep
                val distFade = (1f / (1f + 0.14f * stepDist * stepDist)).coerceIn(0.48f, 1.0f)
                val pausedScale = if (isPlaying) 1.0f else 0.78f
                val lineAlpha = (masterAlpha * distFade * mistFactor * pausedScale).toInt().coerceIn(0, 255)
                if (lineAlpha <= 2) continue

                // True corner-to-corner diagonal: Total Bottom-Left (4%) -> Dead Center -> Total Top-Right (96%)
                val scale = (1.06f - 0.56f * smoothstep((stepDist / 3.8f).coerceIn(0f, 1f))).coerceIn(0.46f, 1.06f)
                val relY = (lineOffset / (clipH * 0.44f)).coerceIn(-1f, 1f)
                val maxLw = if (i in maxLineWidths.indices) maxLineWidths[i] else 0f
                val halfScaledW = (maxLw * scale) * 0.5f
                val layoutCenterX = centerX + layout.width * 0.5f
                val leftEdgeDx = (width * 0.04f + halfScaledW) - layoutCenterX
                val rightEdgeDx = (width * 0.96f - halfScaledW) - layoutCenterX
                val dx = when {
                    relY > 0f -> leftEdgeDx * smoothstep(relY)
                    relY < 0f -> rightEdgeDx * smoothstep(-relY)
                    else -> 0f
                }

                canvas.save()
                canvas.translate(centerX + dx, yPos)
                canvas.scale(scale, scale, layout.width / 2f, layout.height / 2f)

                if (state.lyrics[i].isInterlude) {
                    // Multi-row diagonal instrumental: smooth symbol-by-symbol wave sweep (⌒ ⌒ ⌒ ⌒)
                    val lineStart = state.lyrics[i].timeMs
                    val nextStart = if (i + 1 < state.lyrics.size) state.lyrics[i + 1].timeMs else lineStart + 3000L
                    val lineDur = (nextStart - lineStart).coerceAtLeast(800L)
                    val rowProg = when {
                        i < approxIdx -> 1.0f
                        i == approxIdx -> ((posMs - lineStart).toFloat() / (lineDur * 0.96f)).coerceIn(0f, 1f)
                        else -> 0.0f
                    }
                    val releaseFade = if (i < approxIdx) {
                        val nextLineMs = state.lyrics[approxIdx].timeMs
                        (1f - ((posMs - nextLineMs).coerceAtLeast(0L) / 450f)).coerceIn(0f, 1f)
                    } else 0f

                    val sungTint = sungRgb or (lineAlpha shl 24)
                    val unsungTint = unsungRgb or (((lineAlpha * 0.74f).toInt().coerceIn(0, 255)) shl 24)
                    val baseX = layout.width / 2f
                    val noteY = layout.height / 2f + width * 0.014f
                    val noteCount = 6
                    val noteGap = width * 0.082f
                    val sweepPos = rowProg * (noteCount - 1)

                    for (n in 0 until noteCount) {
                        val nx = baseX + (n - (noteCount - 1) * 0.5f) * noteGap
                        val litBlend = when {
                            i == approxIdx -> smoothstep((sweepPos - n + 0.85f) / 0.85f)
                            releaseFade > 0.02f -> releaseFade
                            else -> 0f
                        }
                        // Soft Gaussian arc hop (⌒) as the bright sweep glides smoothly over each symbol
                        val distToHead = abs(sweepPos - n)
                        val hopArc = if (i == approxIdx && distToHead < 1.2f) {
                            val u = 1f - (distToHead / 1.2f)
                            (u * u * (3f - 2f * u)) * (width * 0.022f) * eqAmp
                        } else 0f
                        val gentleFloat = sin(studioPhase * 2.4 - n * 0.75).toFloat() * (width * 0.004f) * eqAmp
                        instNotePaint.textSize = width * (0.048f + 0.008f * litBlend)
                        instNotePaint.color = ColorUtils.blendARGB(unsungTint, sungTint, litBlend)
                        canvas.drawText(interludeNotes[n], nx, noteY - hopArc + gentleFloat, instNotePaint)
                    }
                } else {
                    val lineStart = state.lyrics[i].timeMs
                    val nextStart = if (i + 1 < state.lyrics.size) state.lyrics[i + 1].timeMs else lineStart + 3500L
                    val lineDur = (nextStart - lineStart).coerceIn(700L, 12000L)
                    val totalSweepFrac = when {
                        i < approxIdx -> 1.0f
                        i == approxIdx -> ((posMs - lineStart).toFloat() / (lineDur * 0.96f)).coerceIn(0.0f, 1.0f)
                        else -> 0.0f
                    }

                    // Strictly 2 clean states: Soft Translucent White -> Luminous Album Color
                    // Already-sung lines smoothly relax back into Soft White as they rise above center (zero 3rd shade)
                    val sungTint = sungRgb or (lineAlpha shl 24)
                    val unsungTint = unsungRgb or (((lineAlpha * 0.74f).toInt().coerceIn(0, 255)) shl 24)
                    val releaseFade = if (i < approxIdx) {
                        val nextLineMs = state.lyrics[approxIdx].timeMs
                        (1f - ((posMs - nextLineMs).coerceAtLeast(0L) / 450f)).coerceIn(0f, 1f)
                    } else 0f

                    if (releaseFade > 0.02f) {
                        layout.paint.shader = null
                        layout.paint.style = Paint.Style.FILL
                        layout.paint.color = ColorUtils.blendARGB(unsungTint, sungTint, releaseFade)
                        layout.draw(canvas)
                    } else if (i != approxIdx || totalSweepFrac <= 0.005f || lineAlpha < 20) {
                        layout.paint.shader = null
                        layout.paint.style = Paint.Style.FILL
                        layout.paint.color = unsungTint
                        layout.draw(canvas)
                    } else if (totalSweepFrac >= 0.995f) {
                        layout.paint.shader = null
                        layout.paint.style = Paint.Style.FILL
                        layout.paint.color = sungTint
                        layout.draw(canvas)
                    } else {
                        val lineCount = layout.lineCount.coerceAtLeast(1)
                        var totalW = 0f
                        for (l in 0 until lineCount) {
                            totalW += layout.getLineWidth(l).coerceAtLeast(1f)
                        }
                        var consumedW = 0f
                        val sungTargetW = totalW * totalSweepFrac
                        val baseSweepShader = getCachedSweepShader(sungTint, unsungTint)

                        layout.paint.style = Paint.Style.FILL
                        for (l in 0 until lineCount) {
                            val lw = layout.getLineWidth(l).coerceAtLeast(1f)
                            val localFrac = ((sungTargetW - consumedW) / lw).coerceIn(0f, 1f)
                            consumedW += lw

                            val lTop = if (l == 0) layout.getLineTop(0).toFloat() - 24f else {
                                (layout.getLineBottom(l - 1) + layout.getLineTop(l)) * 0.5f
                            }
                            val lBot = if (l == lineCount - 1) layout.getLineBottom(l).toFloat() + 24f else {
                                (layout.getLineBottom(l) + layout.getLineTop(l + 1)) * 0.5f
                            }
                            val leftEdge = layout.getLineLeft(l)

                            canvas.save()
                            canvas.clipRect(-24f, lTop, layout.width + 24f, lBot)

                            if (localFrac <= 0.005f) {
                                layout.paint.shader = null
                                layout.paint.color = unsungTint
                            } else if (localFrac >= 0.995f) {
                                layout.paint.shader = null
                                layout.paint.color = sungTint
                            } else {
                                val sweepX = leftEdge + lw * localFrac
                                val softEdge = 12f // ~24px crisp letter-by-letter Shade 1 -> Shade 2 transition
                                shaderMatrix.reset()
                                shaderMatrix.setScale(softEdge, 1f)
                                shaderMatrix.postTranslate(sweepX, 0f)
                                baseSweepShader.setLocalMatrix(shaderMatrix)
                                layout.paint.shader = baseSweepShader
                            }
                            layout.draw(canvas)
                            canvas.restore()
                        }
                        layout.paint.shader = null
                    }
                }

                canvas.restore()
            }
        }

        private fun ellipsize(text: String, paint: TextPaint, maxW: Float): String {
            if (paint.measureText(text) <= maxW) return text
            var cut = text
            while (cut.length > 3 && paint.measureText("$cut…") > maxW) {
                cut = cut.dropLast(1)
            }
            return "$cut…"
        }
    }
}
