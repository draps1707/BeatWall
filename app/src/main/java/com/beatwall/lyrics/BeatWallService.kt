package com.beatwall.lyrics

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
        private var frameScheduled = false
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
            setShadowLayer(36f, 0f, 14f, 0x99000000.toInt())
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
        }

        override fun onDestroy() {
            stopLoop()
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

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible && powerManager.isInteractive) {
                lastSkyComputeMs = 0L
                mainHandler.post { tracker.refreshActiveSessions() }
                snapToCurrentPosition()
                updateLoopState()
            } else {
                lastHiddenAtMs = SystemClock.elapsedRealtime()
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
                    try {
                        notifyColorsChanged()
                    } catch (_: Exception) {
                    }
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

        private fun targetFrameRate(): Float {
            if (!needsAnimation()) return 0f
            val targetLock = if (keyguardManager.isKeyguardLocked) 1f else 0f
            return if (
                abs(targetLock - lockBlend) > 0.005f || abs(lockVel) > 0.01f ||
                isCrossfading ||
                abs(targetLyricsAlpha - lyricsAlpha) > 0.005f || abs(lyricsAlphaVel) > 0.01f ||
                abs(targetEqAmp - eqAmp) > 0.005f || abs(eqVel) > 0.01f ||
                proceduralAnimator.isCoasting() || studioOmega > 0.002 ||
                activeState?.isPlaying == true
            ) {
                120f
            } else {
                30f
            }
        }

        private fun scheduleNextFrame() {
            if (!visible || !surfaceReady || !powerManager.isInteractive) return
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
            if (frameScheduled) {
                frameScheduled = false
                choreographer.removeFrameCallback(this)
            }
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
                setSurfaceFrameRate(0f)
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
                    if (crossfadeStartNanos == 0L) crossfadeStartNanos = frameTimeNanos
                    val elapsed = frameTimeNanos - crossfadeStartNanos
                    crossfadeProgress = (elapsed.toFloat() / crossfadeDurationNanos).coerceIn(0f, 1f)
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
                    val wK = 14.0f
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

                val targetStudioOmega = if (stNow != null && stNow.isPlaying && !isLowBattery && (stNow.isNotebookLm || stNow.lyrics.isEmpty())) {
                    if (isFocusNow) 0.45 else 1.0
                } else 0.0
                val stTau = if (targetStudioOmega > studioOmega) 0.45 else 0.65
                studioOmega += (targetStudioOmega - studioOmega) * (1.0 - kotlin.math.exp(-dtSec.toDouble() / stTau))
                if (abs(studioOmega) < 0.0005 && targetStudioOmega == 0.0) studioOmega = 0.0
                studioPhase += studioOmega * dtSec.toDouble()

                val st = activeState
                if (st != null && st.lyrics.isNotEmpty() && st.showLyrics && lyricYCenters.isNotEmpty()) {
                    val newIdx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
                    if (newIdx != currentLineIndex) {
                        currentLineIndex = newIdx
                        targetY = if (newIdx in lyricYCenters.indices) lyricYCenters[newIdx] else 0f
                    }

                    // Critically-damped 120Hz glide (~420ms)
                    val diff = targetY - springY
                    if (abs(diff) > 0.25f || abs(springVelocity) > 0.6f) {
                        val omega = 9.5f
                        val accel = omega * omega * diff - 2f * omega * springVelocity
                        springVelocity += accel * dtSec
                        springY += springVelocity * dtSec
                    } else {
                        springY = targetY
                        springVelocity = 0f
                    }
                }

                val rate = targetFrameRate()
                setSurfaceFrameRate(rate)
                if (rate > 30f || (frameTimeNanos - lastDrawNanos) >= 31_000_000L) {
                    lastDrawNanos = frameTimeNanos
                    drawFrame(frameTimeNanos)
                }

                if (needsAnimation()) {
                    frameScheduled = true
                    choreographer.postFrameCallback(this)
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
                val (_, effMin) = effectiveTimeMillisAndMinute()
                val loc = LocationStore.load(applicationContext)
                val weatherState = WeatherClient.getCachedOrRefresh(applicationContext, loc.lat, loc.lon)
                val circ = artProcessor.getCircadianTheme(currentDayOfMonth(), effMin, sky.sunAltDeg, weatherState.humidityPct)
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
            }

            val accent = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt()
            val primary = art?.primaryGlowColor ?: 0xFF38BDF8.toInt()
            val secondary = art?.secondaryGlowColor ?: 0xFF1E293B.toInt()
            val moodScale = art?.moodGlowScale ?: 1.0f
            val physicsRegime = if (isLongFormFocus) 0 else (art?.moodRegime ?: 2)

            if (!isLock && (state.isPlaying || proceduralAnimator.isCoasting()) && !isLowBattery) {
                val animScale = moodScale * (1f - 0.25f * gapRelaxFactor) * (if (isLongFormFocus) 0.50f else 1.0f)
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
            val clipTop = height * (0.21f + (0.415f - 0.21f) * lb)
            val clipBottom = height * (0.77f + (0.73f - 0.77f) * lb)
            val centerY = height * (0.49f + (0.572f - 0.49f) * lb)
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
            // Panoramic East (60°) -> South (180°) -> West (300°) sky window across screen width
            val rel = (((azDeg - 60f) % 360f + 360f) % 360f)
            val frac = (rel / 240f).coerceIn(0.06f, 0.94f)
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
            val horizonY = height * 0.66f
            // Gravitational Tidal Shoreline (83%..87% height)
            val shoreY = height * (0.85f - sky.tideFrac * 0.018f)
            val weather = weatherState.sky

            val sunX = projectAzimuthToX(sky.sunAzDeg)
            val sunY = projectAltitudeToY(sky.sunAltDeg, horizonY, isLock)
            val moonX = projectAzimuthToX(sky.moonAzDeg)
            val moonY = projectAltitudeToY(sky.moonAltDeg, horizonY, isLock)
            val orbX = if (sky.sunAltDeg > -4f) sunX else moonX

            fun drawSoftOval(cx: Float, cy: Float, rx: Float, ry: Float, colWithAlpha: Int) {
                val sh = if (cachedSoftMistColor == colWithAlpha && cachedSoftMistShader != null) {
                    cachedSoftMistShader!!
                } else {
                    RadialGradient(
                        0f, 0f, 1f,
                        intArrayOf(colWithAlpha, (colWithAlpha and 0x00FFFFFF) or (((Color.alpha(colWithAlpha) * 0.35f).toInt()) shl 24), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    ).also {
                        cachedSoftMistColor = colWithAlpha
                        cachedSoftMistShader = it
                    }
                }
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
                val mwCol = (0x00A5B4FC) or (((masterAlpha * 0.09f * mwFade).toInt().coerceIn(0, 255)) shl 24)
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
                canvas.rotate(sky.moonLimbTiltDeg)

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

            // 3. Sunlit Wind-Driven Clouds + Soft Sun-Wash + 4-Gull Thermal Flock (smooth twilight fade)
            val dayFactor = ((sky.sunAltDeg + 2f) / 6f).coerceIn(0f, 1f)
            if (dayFactor > 0.01f) {
                val windFactor = (0.75f + weatherState.windKmh / 24f).coerceIn(0.65f, 1.85f)
                val cloudScale = (0.35f + (weatherState.cloudCoverPct / 100f) * 0.95f).coerceIn(0.30f, 1.30f)
                val cloudTint = ColorUtils.blendARGB(Color.WHITE, circ.horizonColor, 0.22f) and 0x00FFFFFF
                val sunRimRgb = sky.sunColor and 0x00FFFFFF
                var nearSunBeam = 0f

                for (c in 0 until 3) {
                    val speed = (0.0062f + c * 0.0016f) * windFactor
                    val cx = (((0.18f + c * 0.36f + (timeSec * speed).toFloat()) % 1.48f) - 0.24f) * width
                    val cy = height * (0.26f + c * 0.11f)
                    val baseAlpha = (masterAlpha * (0.24f - c * 0.04f) * cloudScale * dayFactor).toInt().coerceIn(0, 255)
                    val col = cloudTint or (baseAlpha shl 24)
                    drawSoftOval(cx, cy, width * 0.30f, height * 0.032f, col)
                    drawSoftOval(cx - width * 0.10f, cy - height * 0.010f, width * 0.18f, height * 0.024f, col)
                    drawSoftOval(cx + width * 0.11f, cy + height * 0.006f, width * 0.20f, height * 0.022f, col)

                    // Sunlit rim highlight on the Sun-facing side of each cloud
                    val sunDir = if (sunX >= cx) 1f else -1f
                    val rimAlpha = (masterAlpha * 0.12f * cloudScale * dayFactor).toInt().coerceIn(0, 255)
                    drawSoftOval(cx + sunDir * width * 0.08f, cy - height * 0.004f, width * 0.16f, height * 0.018f, sunRimRgb or (rimAlpha shl 24))

                    val dSun = kotlin.math.abs(cx - sunX) / (width * 0.30f)
                    if (dSun < 1f) {
                        nearSunBeam = maxOf(nearSunBeam, 1f - dSun)
                    }
                }

                // Soft atmospheric sun-wash glow when a cloud drifts near the Sun
                if (nearSunBeam > 0.05f && sky.sunAltDeg > 4f) {
                    val spanY = (horizonY - sunY).coerceAtLeast(height * 0.08f)
                    val driftX = (sunX - width * 0.5f) * 0.08f
                    val upperAlpha = (masterAlpha * 0.055f * nearSunBeam * dayFactor).toInt().coerceIn(0, 255)
                    val lowerAlpha = (masterAlpha * 0.035f * nearSunBeam * dayFactor).toInt().coerceIn(0, 255)
                    drawSoftOval(
                        sunX + driftX * 0.5f,
                        sunY + spanY * 0.34f,
                        width * 0.20f,
                        spanY * 0.40f,
                        sunRimRgb or (upperAlpha shl 24)
                    )
                    drawSoftOval(
                        sunX + driftX,
                        sunY + spanY * 0.64f,
                        width * 0.28f,
                        spanY * 0.38f,
                        sunRimRgb or (lowerAlpha shl 24)
                    )
                }

                // 4-Gull distant thermal-soaring V-flock
                baselinePaint.strokeWidth = density * 1.15f
                baselinePaint.color = 0xFF1E293B.toInt()
                for (g in 0 until 4) {
                    val offsetX = g * 0.055f
                    val offsetY = (g % 2) * 0.018f + (g / 2) * 0.024f
                    val gx = (((0.22f + offsetX + (timeSec * 0.014 * windFactor).toFloat()) % 1.28f) - 0.14f) * width
                    val gy = height * (0.29f + offsetY) - sin(timeSec * 0.8 + g * 0.9).toFloat() * (height * 0.007f)
                    val span = width * (0.014f - g * 0.0018f)
                    val flap = sin(timeSec * 2.8 + g * 1.3).toFloat() * (span * 0.38f)
                    baselinePaint.alpha = (masterAlpha * (0.42f - g * 0.05f) * dayFactor).toInt().coerceIn(0, 255)
                    canvas.drawLine(gx - span, gy - flap, gx, gy + span * 0.22f, baselinePaint)
                    canvas.drawLine(gx, gy + span * 0.22f, gx + span, gy - flap, baselinePaint)
                }
            }

            // 3B. Golden-Hour Horizon Scatter (-6°..+8°) & Weather Rain / Lightning
            if (sky.sunAltDeg in -6f..8f) {
                val ghStrength = (1f - kotlin.math.abs(sky.sunAltDeg - 1f) / 7f).coerceIn(0f, 1f)
                val ghAlpha = (masterAlpha * 0.28f * ghStrength).toInt().coerceIn(0, 255)
                val ghRgb = sky.sunCoronaColor and 0x00FFFFFF
                drawSoftOval(sunX, horizonY, width * 0.72f, height * 0.085f, ghRgb or (ghAlpha shl 24))
            }

            if (weather == SkyWeather.CLOUDY || weather == SkyWeather.RAIN || weather == SkyWeather.THUNDERSTORM) {
                val hazeAlpha = (masterAlpha * (if (weather == SkyWeather.CLOUDY) 0.16f else 0.12f)).toInt().coerceIn(0, 255)
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
                val windSlant = width * (0.008f + (weatherState.windKmh / 65f).coerceIn(0f, 0.026f))
                baselinePaint.strokeWidth = density * 0.9f
                baselinePaint.color = 0xFFE2E8F0.toInt()
                baselinePaint.alpha = (masterAlpha * 0.22f).toInt()
                for (rIdx in 0 until 20) {
                    val hDrop = abs(sin(rIdx * 12.9898 + 0.5)).toFloat()
                    val rProg = (((timeSec * (1.55 + hDrop * 0.35)) + rIdx * 0.19 + hDrop * 0.37) % 1.0 + 1.0) % 1.0
                    val rx = (hDrop * width) - (rProg.toFloat() * windSlant * 2.2f)
                    val ry = rProg.toFloat() * horizonY
                    canvas.drawLine(rx, ry, rx - windSlant, ry + height * 0.024f, baselinePaint)
                }
            }

            canvas.restore()

            // Sea Raindrop Ripples
            if (weather == SkyWeather.RAIN || weather == SkyWeather.THUNDERSTORM) {
                baselinePaint.strokeWidth = density * 0.8f
                baselinePaint.color = 0xFFBAE6FD.toInt()
                for (rp in 0 until 4) {
                    val hRp = abs(sin(rp * 12.9898 + 1.3)).toFloat()
                    val rpProg = (((timeSec * (0.50 + hRp * 0.20)) + rp * 0.25 + hRp * 0.31) % 1.0).toFloat()
                    val rx = width * (0.12f + 0.76f * hRp)
                    val ry = horizonY + (shoreY - horizonY) * (0.22f + 0.54f * abs(sin(rp * 78.233)).toFloat())
                    val rad = width * (0.01f + rpProg * 0.035f)
                    baselinePaint.alpha = (masterAlpha * 0.24f * (1f - rpProg)).toInt().coerceIn(0, 255)
                    backdropRect.set(rx - rad, ry - rad * 0.28f, rx + rad, ry + rad * 0.28f)
                    canvas.drawOval(backdropRect, baselinePaint)
                }
            }

            // 4. Seamless Horizon Mirage & Haze Band + Wind-Modulated Sun/Moon Water Reflection Column
            val mirageRgb = (if (isDay) circ.horizonColor else 0xFF334155.toInt()) and 0x00FFFFFF
            val mirageAlpha = (masterAlpha * (if (isDay) 0.24f else 0.11f)).toInt().coerceIn(0, 255)
            drawSoftOval(width * 0.5f, horizonY, width * 0.90f, height * 0.020f, mirageRgb or (mirageAlpha shl 24))

            val seaSpan = (shoreY - horizonY).coerceAtLeast(1f)
            val shimRgb = if (sky.sunAltDeg > -4f) (sky.sunColor and 0x00FFFFFF) else (sky.moonColor and 0x00FFFFFF)
            val lumBoost = if (sky.sunAltDeg > -4f) 1.0f else (0.55f + 0.45f * sky.moonIllumination.coerceIn(0f, 1f))
            val windScale = (0.75f + (weatherState.windKmh / 28f)).coerceIn(0.75f, 1.8f)
            val shimBreath = 0.88f + 0.12f * sin(timeSec * 0.9).toFloat()

            // Tapered perspective reflection cone (brighter/narrower near horizon, wider/softer toward shore)
            val upperAlpha = (masterAlpha * (if (isDay) 0.22f else 0.16f) * lumBoost * shimBreath).toInt().coerceIn(0, 255)
            val lowerAlpha = (masterAlpha * (if (isDay) 0.12f else 0.09f) * lumBoost * shimBreath).toInt().coerceIn(0, 255)
            drawSoftOval(orbX, horizonY + seaSpan * 0.24f, width * 0.085f * windScale, seaSpan * 0.30f, shimRgb or (upperAlpha shl 24))
            drawSoftOval(orbX, horizonY + seaSpan * 0.56f, width * 0.155f * windScale, seaSpan * 0.44f, shimRgb or (lowerAlpha shl 24))

            // 8 organic Cox-Munk wave micro-glints (1 : sqrt(2) : phi shimmer, zero horizontal laser bars)
            baselinePaint.color = shimRgb or (0xFF000000.toInt())
            baselinePaint.strokeWidth = density * 1.15f
            for (gIdx in 0 until 8) {
                val fracY = 0.08f + (gIdx / 7f) * 0.72f
                val gy = horizonY + seaSpan * fracY
                val wavePhase = timeSec * (1.1 + (gIdx % 3) * 0.37) * 1.618 + gIdx * 2.13
                val glintVis = ((sin(wavePhase) + 1.0) * 0.5).toFloat()
                val spreadX = sin(timeSec * 0.65 * 1.4142 + gIdx * 1.7).toFloat() * (width * (0.018f + 0.032f * fracY) * windScale)
                val glintHalfW = width * (0.008f + 0.014f * fracY) * (0.65f + 0.35f * windScale) * (0.5f + 0.5f * glintVis)
                val gAlpha = (masterAlpha * (0.28f - 0.16f * fracY) * lumBoost * glintVis).toInt().coerceIn(0, 255)
                if (gAlpha > 2) {
                    baselinePaint.alpha = gAlpha
                    canvas.drawLine(orbX + spreadX - glintHalfW, gy, orbX + spreadX + glintHalfW, gy, baselinePaint)
                }
            }

            // 5. Tiny Distant Sailboat
            val boatSpeed = 0.0045 + (weatherState.windKmh / 24.0).coerceIn(0.2, 1.5) * 0.0035
            val boatX = (((0.20f + (timeSec * boatSpeed).toFloat()) % 1.16f) - 0.08f) * width
            val boatBob = sin(timeSec * (1.3 + weatherState.windKmh * 0.02)).toFloat() * (density * (1.4f + (weatherState.windKmh / 20f).coerceIn(0f, 1.5f)))
            val boatY = horizonY + height * 0.013f + boatBob
            val boatCol = if (isDay) 0xFF0F172A.toInt() else 0xFF020617.toInt()
            eqBarPaint.color = boatCol
            eqBarPaint.alpha = (masterAlpha * 0.60f).toInt()
            val bw = width * 0.025f
            val bh = width * 0.021f
            eqBarRect.set(boatX - bw * 0.5f, boatY, boatX + bw * 0.5f, boatY + density * 2.2f)
            canvas.drawRoundRect(eqBarRect, density, density, eqBarPaint)
            moonPath.reset()
            moonPath.moveTo(boatX - bw * 0.05f, boatY)
            moonPath.lineTo(boatX - bw * 0.05f, boatY - bh)
            moonPath.lineTo(boatX + bw * 0.42f, boatY - density * 1.5f)
            moonPath.close()
            canvas.drawPath(moonPath, eqBarPaint)

            // 6. Lineless Wind & Tide-Driven 3-Wave Surf + Receding Shore Foam (0 wire lines, 0 allocs)
            val swellRgb = if (isDay) 0x00E0F2FE else 0x0094A3B8
            val foamRgb = if (isDay) 0x00F8FAFC else 0x00CBD5E1
            val surfEnergy = (0.76f + (weatherState.windKmh / 36f) + (sky.tideFrac * 0.15f)).coerceIn(0.72f, 1.45f)
            val waveSpeed = 0.044 + (weatherState.windKmh / 500.0).coerceIn(0.0, 0.030)

            // Subtle breathing tidal shoreline mist right at shoreY (83%..87%)
            val tideBreath = 0.85f + 0.15f * sin(timeSec * 0.45).toFloat()
            val tideAlpha = (masterAlpha * (if (isDay) 0.14f else 0.09f) * tideBreath).toInt().coerceIn(0, 255)
            drawSoftOval(width * 0.5f, shoreY + height * 0.004f, width * 0.78f, height * 0.011f, foamRgb or (tideAlpha shl 24))

            for (wIdx in 0 until 3) {
                val waveProg = (((timeSec * waveSpeed) + wIdx / 3.0) % 1.0).toFloat()
                val persp = waveProg * kotlin.math.sqrt(waveProg)
                val wy = horizonY + seaSpan * (0.34f + persp * 0.66f)
                val env = sin(waveProg * PI).toFloat()

                // Organic lateral drift & split swell bands (zero thin wire strokes)
                val driftX = sin(timeSec * 0.32 * 1.618 + wIdx * 2.1).toFloat() * (width * 0.06f)
                val cx = width * (0.44f + (wIdx % 2) * 0.12f) + driftX
                val halfW = width * (0.30f + 0.12f * waveProg)
                val bandH = height * (0.006f + 0.008f * waveProg)
                val glowAlpha = (masterAlpha * env * (if (isDay) 0.17f else 0.10f) * surfEnergy).toInt().coerceIn(0, 255)
                if (glowAlpha > 2) {
                    drawSoftOval(cx, wy, halfW, bandH, swellRgb or (glowAlpha shl 24))
                    // Secondary offset swell lobe for natural broken crest volume
                    drawSoftOval(cx - halfW * 0.35f, wy - bandH * 0.25f, halfW * 0.55f, bandH * 0.75f, foamRgb or ((glowAlpha * 0.55f).toInt() shl 24))
                }

                // Receding shore foam wash as the swell reaches shoreY
                if (waveProg > 0.66f) {
                    val washFrac = ((waveProg - 0.66f) / 0.34f).coerceIn(0f, 1f)
                    val washEnv = sin(washFrac * PI).toFloat()
                    val washY = shoreY + height * (0.009f * washEnv)
                    val washAlpha = (masterAlpha * washEnv * (if (isDay) 0.22f else 0.12f) * surfEnergy).toInt().coerceIn(0, 255)
                    if (washAlpha > 2) {
                        drawSoftOval(cx, washY, halfW * 1.12f, height * (0.008f + 0.008f * washEnv), foamRgb or (washAlpha shl 24))
                    }
                }
            }
        }

        private fun drawNotebookTwoHostStudio(
            canvas: Canvas,
            state: ActiveTrackState,
            isLock: Boolean,
            timeSec: Double,
            masterAlpha: Int
        ) {
            val cyBase = if (isLock) height * 0.55f else height * 0.48f
            val cxBase = width * 0.50f
            val p = studioPhase
            val baryX = sin(p * 0.42).toFloat() * (width * 0.025f)
            val baryY = kotlin.math.cos(p * 0.42 * 1.4142).toFloat() * (height * 0.015f)
            val cx = cxBase + baryX
            val cy = cyBase + baryY

            val turnA = (0.50f + 0.50f * (0.65f * sin(p * 2.1).toFloat() + 0.35f * sin(p * 2.1 * 1.618).toFloat())).coerceIn(0f, 1f)
            val turnB = (0.50f + 0.50f * (0.65f * sin(p * 2.1 + PI).toFloat() + 0.35f * sin(p * 2.1 * 1.618 + 2.1).toFloat())).coerceIn(0f, 1f)
            val sep = width * (0.115f - 0.018f * (turnA * turnB))

            val rA = width * (0.14f + 0.05f * turnA * eqAmp)
            val rB = width * (0.14f + 0.05f * turnB * eqAmp)

            orbPaint.shader = RadialGradient(
                cx - sep, cy, rA,
                intArrayOf(
                    (0x0038BDF8) or (((masterAlpha * (0.38f + 0.25f * turnA)).toInt()) shl 24),
                    (0x0038BDF8) or (((masterAlpha * 0.10f).toInt()) shl 24),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx - sep, cy, rA, orbPaint)

            orbPaint.shader = RadialGradient(
                cx + sep, cy, rB,
                intArrayOf(
                    (0x00818CF8) or (((masterAlpha * (0.38f + 0.25f * turnB)).toInt()) shl 24),
                    (0x00818CF8) or (((masterAlpha * 0.10f).toInt()) shl 24),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx + sep, cy, rB, orbPaint)
            orbPaint.shader = null

            val posMs = state.currentPositionMs()
            val durMs = state.durationMs
            val timerText = if (durMs > 0L) {
                "${formatTime(posMs)}  /  ${formatTime(durMs)}"
            } else {
                formatTime(posMs)
            }
            studioTimerPaint.textSize = width * 0.036f
            studioTimerPaint.alpha = (masterAlpha * 0.78f).toInt()
            canvas.drawText(timerText, cx, cy + width * 0.22f, studioTimerPaint)
        }

        private fun drawInstrumentalStudio(
            canvas: Canvas,
            state: ActiveTrackState,
            lb: Float,
            timeSec: Double,
            accent: Int,
            primary: Int,
            masterAlpha: Int,
            isFocusMode: Boolean = false
        ) {
            val isPlaying = state.isPlaying && !isLowBattery
            val cy = height * (0.49f + (0.56f - 0.49f) * lb)
            val cx = width * 0.50f
            val ribbonLeft = width * 0.14f
            val ribbonW = width * 0.72f
            val focusAmpScale = if (isFocusMode) 0.55f else 1.0f
            val focusSpeedScale = if (isFocusMode) 0.45 else 1.0
            val amp = width * 0.030f * eqAmp * focusAmpScale

            // Option B: 3 Harmonic Silk Ribbons undulating smoothly across center (flatten on pause)
            val steps = 40
            for (rIdx in 0 until 3) {
                val col = if (rIdx == 1) primary else accent
                val baseA = when (rIdx) {
                    0 -> 0.28f
                    1 -> 0.42f
                    else -> 0.22f
                }
                ribbonPaint.strokeWidth = density * (if (rIdx == 1) 1.9f else 1.25f)
                ribbonPaint.color = col
                ribbonPaint.alpha = (masterAlpha * baseA * (0.45f + 0.55f * eqAmp)).toInt().coerceIn(0, 255)
                ribbonPath.reset()
                val freq = 2.6 + rIdx * 0.85
                val speed = (2.8 + rIdx * 0.65) * focusSpeedScale
                for (s in 0..steps) {
                    val frac = s.toFloat() / steps
                    val x = ribbonLeft + frac * ribbonW
                    val env = sin(frac * PI).toFloat()
                    val y = cy + sin(frac * freq * PI - studioPhase * speed + rIdx * 1.4).toFloat() * amp * env
                    if (s == 0) ribbonPath.moveTo(x, y) else ribbonPath.lineTo(x, y)
                }
                canvas.drawPath(ribbonPath, ribbonPaint)
            }

            // Option A: Soft floating ♪ ♫ ♬ notes above + minimal MM:SS / MM:SS timer below
            val noteGap = width * 0.060f
            instNotePaint.textSize = width * 0.044f
            instNotePaint.color = accent
            val noteBaseY = cy - width * 0.11f
            for (d in -1..1) {
                if (isPlaying) {
                    val phase = timeSec * 2.8 - (d + 1) * 0.9
                    val wave = 0.45f + 0.55f * ((sin(phase) + 1.0) * 0.5).toFloat()
                    val bob = sin(phase).toFloat() * (width * 0.008f) * eqAmp
                    instNotePaint.alpha = (masterAlpha * 0.82f * wave).toInt().coerceIn(20, 255)
                    canvas.drawText(instNotes[d + 1], cx + d * noteGap, noteBaseY + bob, instNotePaint)
                } else {
                    instNotePaint.alpha = (masterAlpha * 0.38f).toInt().coerceIn(15, 255)
                    canvas.drawText(instNotes[d + 1], cx + d * noteGap, noteBaseY, instNotePaint)
                }
            }

            val posMs = state.currentPositionMs()
            val durMs = state.durationMs
            val timerText = if (durMs > 0L) {
                "${formatTime(posMs)}  /  ${formatTime(durMs)}"
            } else {
                formatTime(posMs)
            }
            studioTimerPaint.textSize = width * 0.034f
            studioTimerPaint.alpha = (masterAlpha * (if (isPlaying) 0.78f else 0.45f)).toInt()
            canvas.drawText(timerText, cx, cy + width * 0.135f, studioTimerPaint)
        }

        private fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val m = totalSec / 60L
            val s = totalSec % 60L
            return "%02d:%02d".format(m, s)
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

            // Option 2: Traveling 5-Bar Micro Silk Equalizer along song timeline
            val progFrac = if (state.durationMs > 0L) state.progressFraction() else 0f
            drawMicroSilkEqualizer(
                canvas = canvas,
                timeSec = timeSec,
                startX = textLeft,
                centerY = artistBaselineY + eqRowOffset,
                totalW = maxTextW,
                progressFrac = progFrac,
                ampScale = eqAmp,
                accent = accent,
                alpha = headerAlpha
            )
        }

        private fun drawMicroSilkEqualizer(
            canvas: Canvas,
            timeSec: Double,
            startX: Float,
            centerY: Float,
            totalW: Float,
            progressFrac: Float,
            ampScale: Float,
            accent: Int,
            alpha: Int
        ) {
            val barCount = 5
            val barW = width * 0.0072f
            val barGap = width * 0.0155f
            val maxBarH = width * 0.052f
            val eqSpan = (barCount - 1) * barGap + barW
            val padGap = width * 0.011f
            val posX = startX + (totalW - eqSpan).coerceAtLeast(0f) * progressFrac.coerceIn(0f, 1f)

            baselinePaint.strokeWidth = width * 0.0026f
            baselinePaint.color = accent

            // Played line behind the 5 bars
            val playedEnd = posX - padGap
            if (playedEnd > startX + 2f) {
                baselinePaint.alpha = (alpha * 0.62f).toInt().coerceIn(0, 255)
                canvas.drawLine(startX, centerY, playedEnd, centerY, baselinePaint)
            }

            // Soft traveling glow halo behind the 5-bar equalizer
            if (ampScale > 0.05f) {
                val eqCenterX = posX + eqSpan * 0.5f
                val haloR = maxBarH * 1.15f
                val haloAlpha = (alpha * 0.24f * ampScale).toInt().coerceIn(0, 255)
                val haloCol = (accent and 0x00FFFFFF) or (haloAlpha shl 24)
                val haloSh = if (cachedSoftMistColor == haloCol && cachedSoftMistShader != null) cachedSoftMistShader!! else {
                    RadialGradient(0f, 0f, 1f, intArrayOf(haloCol, (accent and 0x00FFFFFF) or (((haloAlpha * 0.35f).toInt()) shl 24), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                        .also { cachedSoftMistColor = haloCol; cachedSoftMistShader = it }
                }
                shaderMatrix.reset(); shaderMatrix.setScale(haloR * 1.4f, haloR); shaderMatrix.postTranslate(eqCenterX, centerY)
                haloSh.setLocalMatrix(shaderMatrix)
                orbPaint.shader = haloSh
                backdropRect.set(eqCenterX - haloR * 1.4f, centerY - haloR, eqCenterX + haloR * 1.4f, centerY + haloR)
                canvas.drawOval(backdropRect, orbPaint)
                orbPaint.shader = null
            }

            // 5-Bar Breathing Equalizer sitting at current song timestamp
            eqBarPaint.color = accent
            eqBarPaint.alpha = (alpha * (0.45f + 0.52f * ampScale)).toInt().coerceIn(0, 255)
            val breathEnv = 0.75f + 0.25f * sin(timeSec * 2.1).toFloat()
            for (b in 0 until barCount) {
                val wave = if (ampScale > 0.01f) {
                    0.15f + 0.85f * ((sin(timeSec * (5.2 * lyricPaceScale * (1f - 0.22f * gapRelaxFactor)) + b * 1.15) + 1.0) * 0.5).toFloat() * breathEnv
                } else {
                    0f
                }
                val bh = barW + (maxBarH - barW) * wave * ampScale
                val bx = posX + b * barGap
                eqBarRect.set(bx, centerY - bh * 0.5f, bx + barW, centerY + bh * 0.5f)
                canvas.drawRoundRect(eqBarRect, barW * 0.5f, barW * 0.5f, eqBarPaint)
            }

            // Unplayed hairline ahead of the 5 bars
            val unplayedStart = posX + eqSpan + padGap
            if (unplayedStart < startX + totalW - 2f) {
                baselinePaint.alpha = (alpha * 0.22f).toInt().coerceIn(0, 255)
                canvas.drawLine(unplayedStart, centerY, startX + totalW, centerY, baselinePaint)
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

            val approxIdx = LrcParser.findLineIndex(state.lyrics, state.currentPositionMs()).coerceIn(0, maxIdx)
            val start = (approxIdx - 4).coerceAtLeast(0)
            val end = (approxIdx + 4).coerceAtMost(maxIdx)
            val timeSec = frameTimeNanos / 1_000_000_000.0
            val clipH = (clipBottom - clipTop).coerceAtLeast(1f)
            val mistZone = clipH * 0.14f
            val posMs = state.currentPositionMs()
            val isPlaying = state.isPlaying && !isLowBattery

            // Album-art pastel-tinted lyric colors (75% white + 25% accent for sung; 55% white + 45% accent for unsung)
            val sungRgb = ColorUtils.blendARGB(Color.WHITE, accentColor, 0.25f) and 0x00FFFFFF
            val dimRgb = ColorUtils.blendARGB(Color.WHITE, accentColor, 0.45f) and 0x00FFFFFF

            for (i in start..end) {
                val layout = layouts[i]
                val lineOffset = yCenters[i] - scrollY
                val yPos = centerY + lineOffset - (layout.height / 2f)

                val topFade = smoothstep((yPos - clipTop) / mistZone)
                val botFade = smoothstep((clipBottom - (yPos + layout.height)) / mistZone)
                val mistFactor = minOf(topFade, botFade)
                if (mistFactor <= 0.005f) continue

                // Continuous distance in line-spacing units (0.0 at center, 1.0 at ±1 line, 2.0 at ±2, etc.)
                val stepDist = abs(lineOffset) / lineStepPx.coerceAtLeast(1f)
                val focusStrength = 1f - smoothstep((stepDist / 0.65f).coerceIn(0f, 1f))
                val distFade = (1f / (1f + 1.15f * stepDist * stepDist)).coerceIn(0.05f, 1.0f)
                val pausedScale = if (isPlaying) 1.0f else 0.72f
                val lineAlpha = (masterAlpha * distFade * mistFactor * pausedScale).toInt().coerceIn(0, 255)
                if (lineAlpha <= 2) continue

                // Left-aligned (bottom-left) -> Center-aligned (focus) -> Right-aligned (top-right)
                val relY = (lineOffset / (clipH * 0.48f)).coerceIn(-1.2f, 1.2f)
                val maxLw = if (i in maxLineWidths.indices) maxLineWidths[i] else 0f
                val alignBias = -relY.coerceIn(-1f, 1f)
                val alignShift = alignBias * ((layout.width - maxLw) * 0.44f).coerceAtLeast(0f)
                val diagShift = -relY * (width * 0.12f) * (1f - (maxLw / layout.width.coerceAtLeast(1)).coerceIn(0f, 0.85f) * 0.5f)
                val dx = alignShift + diagShift

                val scale = (1.06f - 0.50f * smoothstep((stepDist / 3.2f).coerceIn(0f, 1f))).coerceIn(0.55f, 1.06f)

                canvas.save()
                canvas.translate(centerX + dx, yPos)
                canvas.scale(scale, scale, layout.width / 2f, layout.height / 2f)

                if (state.lyrics[i].isInterlude) {
                    dotPaint.color = accentColor
                    val dotRadius = width * 0.012f
                    val dotGap = width * 0.034f
                    val baseX = layout.width / 2f
                    val dotY = layout.height / 2f
                    for (d in -1..1) {
                        if (isPlaying) {
                            val phase = timeSec * 3.2 - (d + 1) * 0.85
                            val wave = 0.35f + 0.65f * ((sin(phase) + 1.0) * 0.5).toFloat()
                            dotPaint.alpha = (lineAlpha * wave).toInt().coerceIn(20, 255)
                            canvas.drawCircle(baseX + d * dotGap, dotY, dotRadius * (0.85f + 0.25f * wave), dotPaint)
                        } else {
                            dotPaint.alpha = (lineAlpha * 0.45f).toInt().coerceIn(15, 255)
                            canvas.drawCircle(baseX + d * dotGap, dotY, dotRadius * 0.90f, dotPaint)
                        }
                    }
                } else if (focusStrength > 0.20f) {
                    // Cached zero-alloc GPU RadialGradient spotlight behind focused line
                    if (isPlaying && focusStrength > 0.30f) {
                        val spotR = maxOf(layout.width * 0.46f, layout.height * 1.4f)
                        val spotColor = (accentColor and 0x00FFFFFF) or (((lineAlpha * 0.22f * focusStrength).toInt().coerceIn(0, 255)) shl 24)
                        val spotShader = if (cachedSpotlightColor == spotColor && cachedSpotlightShader != null) {
                            cachedSpotlightShader!!
                        } else {
                            RadialGradient(
                                0f, 0f, 1f,
                                intArrayOf(spotColor, Color.TRANSPARENT),
                                null, Shader.TileMode.CLAMP
                            ).also {
                                cachedSpotlightColor = spotColor
                                cachedSpotlightShader = it
                            }
                        }
                        shaderMatrix.reset()
                        shaderMatrix.setScale(spotR, spotR)
                        shaderMatrix.postTranslate(layout.width / 2f, layout.height / 2f)
                        spotShader.setLocalMatrix(shaderMatrix)
                        spotlightPaint.shader = spotShader
                        canvas.drawCircle(layout.width / 2f, layout.height / 2f, spotR, spotlightPaint)
                        spotlightPaint.shader = null
                    }

                    val lineStart = state.lyrics[i].timeMs
                    val nextStart = if (i + 1 < state.lyrics.size) state.lyrics[i + 1].timeMs else lineStart + 3500L
                    val lineDur = (nextStart - lineStart).coerceIn(750L, 5000L)
                    val totalSweepFrac = if (i == approxIdx) {
                        ((posMs - lineStart).toFloat() / (lineDur * 0.90f)).coerceIn(0.03f, 1.0f)
                    } else {
                        1.0f
                    }

                    val lineCount = layout.lineCount.coerceAtLeast(1)
                    var totalW = 0f
                    for (l in 0 until lineCount) {
                        totalW += layout.getLineWidth(l).coerceAtLeast(1f)
                    }
                    var consumedW = 0f
                    val sungTargetW = totalW * totalSweepFrac

                    val brightTint = sungRgb or (lineAlpha shl 24)
                    val dimAlphaTint = dimRgb or (((lineAlpha * 0.50f).toInt()) shl 24)
                    val sweepKey = (brightTint.toLong() shl 32) or (dimAlphaTint.toLong() and 0xFFFFFFFFL)
                    val baseSweepShader = if (cachedSweepKey == sweepKey && cachedSweepShader != null) {
                        cachedSweepShader!!
                    } else {
                        LinearGradient(
                            -1f, 0f, 1f, 0f,
                            intArrayOf(brightTint, dimAlphaTint),
                            null, Shader.TileMode.CLAMP
                        ).also {
                            cachedSweepKey = sweepKey
                            cachedSweepShader = it
                        }
                    }

                    layout.paint.style = Paint.Style.FILL
                    for (l in 0 until lineCount) {
                        val lw = layout.getLineWidth(l).coerceAtLeast(1f)
                        val localFrac = ((sungTargetW - consumedW) / lw).coerceIn(0f, 1f)
                        consumedW += lw

                        val lTop = layout.getLineTop(l).toFloat() - 16f
                        val lBot = layout.getLineBottom(l).toFloat() + 16f
                        val leftEdge = (layout.width - lw) / 2f

                        canvas.save()
                        canvas.clipRect(-24f, lTop, layout.width + 24f, lBot)

                        if (localFrac <= 0.005f) {
                            layout.paint.shader = null
                            layout.paint.color = dimRgb or (((lineAlpha * 0.50f).toInt()) shl 24)
                        } else if (localFrac >= 0.995f) {
                            layout.paint.shader = null
                            layout.paint.color = brightTint
                        } else {
                            val sweepX = leftEdge + lw * localFrac
                            val softEdge = (lw * 0.16f).coerceAtLeast(22f)
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
                } else {
                    layout.paint.shader = null
                    layout.paint.style = Paint.Style.FILL
                    layout.paint.color = dimRgb or (lineAlpha shl 24)
                    layout.draw(canvas)
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
