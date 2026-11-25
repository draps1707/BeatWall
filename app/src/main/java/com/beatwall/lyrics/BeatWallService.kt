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
        private var crossfadeProgress = 1f
        private var crossfadeStartNanos = 0L
        private val crossfadeDurationNanos = 550_000_000L

        private var lyricsAlpha = 0f
        private var targetLyricsAlpha = 0f
        private var eqAmp = 0f
        private var targetEqAmp = 0f
        private var lockBlend = if (keyguardManager.isKeyguardLocked) 1f else 0f

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

        private fun getSkySnapshot(): SkySnapshot {
            val nowMs = System.currentTimeMillis()
            val existing = cachedSkySnapshot
            if (existing != null && kotlin.math.abs(nowMs - lastSkyComputeMs) < 30_000L) {
                return existing
            }
            val loc = LocationStore.load(applicationContext)
            return SkyAstronomy.compute(nowMs, loc.lat, loc.lon).also {
                cachedSkySnapshot = it
                lastSkyComputeMs = nowMs
            }
        }

        // Pre-computed night starfield (zero per-frame allocation)
        private val starCount = 38
        private val starX = FloatArray(starCount)
        private val starY = FloatArray(starCount)
        private val starR = FloatArray(starCount)
        private val starPhase = FloatArray(starCount)

        init {
            val rng = Random(2026)
            for (i in 0 until starCount) {
                starX[i] = rng.nextFloat()
                starY[i] = 0.05f + rng.nextFloat() * 0.58f
                starR[i] = 1.0f + rng.nextFloat() * 1.4f
                starPhase[i] = rng.nextFloat() * (2f * PI.toFloat())
            }
        }

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
            val sky = getSkySnapshot()
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
                    if (activeState != null) {
                        prevState = activeState
                        prevCenteredLayouts = centeredLayouts
                        prevLyricYCenters = lyricYCenters
                        prevLyricMaxLineW = lyricMaxLineW
                        prevAvgLineStep = avgLineStep
                        prevSpringY = springY
                        prevLyricsAlpha = lyricsAlpha
                        crossfadeProgress = 0f
                        crossfadeStartNanos = 0L
                    }
                    currentLineIndex = -1
                    springY = 0f
                    springVelocity = 0f
                    targetY = 0f
                    if (state?.showLyrics != true || state.lyrics.isEmpty()) {
                        lyricsAlpha = 0f
                    }
                    proceduralAnimator.reroll(calmOnly = state == null)
                } else if (artChanged && crossfadeProgress >= 1f && activeState != null) {
                    prevState = activeState
                    prevCenteredLayouts = centeredLayouts
                    prevLyricYCenters = lyricYCenters
                    prevLyricMaxLineW = lyricMaxLineW
                    prevAvgLineStep = avgLineStep
                    prevSpringY = springY
                    prevLyricsAlpha = lyricsAlpha
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
            if (abs(targetLock - lockBlend) > 0.01f) return true
            if (crossfadeProgress < 1f) return true
            if (abs(targetLyricsAlpha - lyricsAlpha) > 0.01f) return true
            if (abs(targetEqAmp - eqAmp) > 0.01f) return true
            if (isLowBattery) return false
            val st = activeState
            if (st != null && !st.isPlaying) return false
            return true
        }

        private fun targetFrameRate(): Float {
            if (!needsAnimation()) return 0f
            val targetLock = if (keyguardManager.isKeyguardLocked) 1f else 0f
            return if (
                abs(targetLock - lockBlend) > 0.01f ||
                crossfadeProgress < 1f ||
                abs(targetLyricsAlpha - lyricsAlpha) > 0.01f ||
                abs(targetEqAmp - eqAmp) > 0.01f ||
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

                if (crossfadeProgress < 1f) {
                    if (crossfadeStartNanos == 0L) crossfadeStartNanos = frameTimeNanos
                    val elapsed = frameTimeNanos - crossfadeStartNanos
                    crossfadeProgress = (elapsed.toFloat() / crossfadeDurationNanos).coerceIn(0f, 1f)
                    if (crossfadeProgress >= 1f) {
                        prevState = null
                        prevCenteredLayouts = emptyList()
                        prevLyricYCenters = FloatArray(0)
                        prevLyricMaxLineW = FloatArray(0)
                    }
                }

                val alphaDiff = targetLyricsAlpha - lyricsAlpha
                if (abs(alphaDiff) > 0.01f) {
                    val step = (dtSec / 0.35f) * if (alphaDiff > 0) 1f else -1f
                    lyricsAlpha = (lyricsAlpha + step).coerceIn(0f, 1f)
                    if (abs(targetLyricsAlpha - lyricsAlpha) <= 0.02f) lyricsAlpha = targetLyricsAlpha
                } else {
                    lyricsAlpha = targetLyricsAlpha
                }

                val targetLock = if (keyguardManager.isKeyguardLocked) 1f else 0f
                val lockDiff = targetLock - lockBlend
                if (abs(lockDiff) > 0.005f) {
                    val step = (dtSec / 0.24f) * if (lockDiff > 0f) 1f else -1f
                    lockBlend = (lockBlend + step).coerceIn(0f, 1f)
                    if (abs(targetLock - lockBlend) <= 0.01f) lockBlend = targetLock
                } else {
                    lockBlend = targetLock
                }

                targetEqAmp = if (activeState?.isPlaying == true && !isLowBattery) 1f else 0f
                val eqDiff = targetEqAmp - eqAmp
                if (abs(eqDiff) > 0.01f) {
                    val step = (dtSec / 0.30f) * if (eqDiff > 0) 1f else -1f
                    eqAmp = (eqAmp + step).coerceIn(0f, 1f)
                    if (abs(targetEqAmp - eqAmp) <= 0.02f) eqAmp = targetEqAmp
                } else {
                    eqAmp = targetEqAmp
                }

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

                if (crossfadeProgress < 1f && prevState != null) {
                    val eased = crossfadeProgress * crossfadeProgress * (3f - 2f * crossfadeProgress)
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
                val sky = getSkySnapshot()
                val circ = artProcessor.getCircadianTheme(currentDayOfMonth(), currentMinuteOfDay(), sky.sunAltDeg)
                bmpPaint.alpha = masterAlpha
                backdropRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(circ.backdrop, null, backdropRect, bmpPaint)
                drawMotherEarthCircadianScene(canvas, circ, sky, isLock, timeSec, masterAlpha)
                return
            }

            val art = state.processedArt
            if (art != null) {
                drawBreathingBackdrop(canvas, art.blurredBackdrop, state.isPlaying && !isLowBattery, timeSec, masterAlpha)
            }

            val accent = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt()
            val primary = art?.primaryGlowColor ?: 0xFF38BDF8.toInt()
            val secondary = art?.secondaryGlowColor ?: 0xFF1E293B.toInt()

            if (!isLock && state.isPlaying && !isLowBattery) {
                proceduralAnimator.draw(
                    canvas = canvas,
                    width = width,
                    height = height,
                    timeSec = timeSec,
                    accent = accent,
                    primary = primary,
                    secondary = secondary,
                    masterAlpha = masterAlpha
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
            } else if (!state.showLyrics || state.lyrics.isEmpty()) {
                drawInstrumentalStudio(canvas, state, lb, timeSec, accent, primary, masterAlpha)
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
            isLock: Boolean,
            timeSec: Double,
            masterAlpha: Int
        ) {
            val isDay = sky.sunAltDeg > -2f
            val horizonY = height * 0.66f
            val shoreY = height * 0.85f

            val sunX = projectAzimuthToX(sky.sunAzDeg)
            val sunY = projectAltitudeToY(sky.sunAltDeg, horizonY, isLock)
            val moonX = projectAzimuthToX(sky.moonAzDeg)
            val moonY = projectAltitudeToY(sky.moonAltDeg, horizonY, isLock)
            val orbX = if (sky.sunAltDeg > -4f) sunX else moonX

            // Clip all sky bodies strictly above the sea horizon so sunrise/sunset/moonrise sink into water
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), horizonY)

            // 1. Twilight-aware Stars & Shooting Stars (fade in smoothly as Sun drops below -3°)
            val starFade = ((-3f - sky.sunAltDeg) / 9f).coerceIn(0f, 1f)
            if (starFade > 0.02f) {
                orbPaint.shader = null
                orbPaint.color = Color.WHITE
                for (i in 0 until starCount) {
                    val twinkle = 0.35f + 0.65f * ((sin(timeSec * 1.1 + starPhase[i]) + 1.0) * 0.5).toFloat()
                    orbPaint.alpha = (masterAlpha * 0.46f * starFade * twinkle).toInt().coerceIn(0, 255)
                    canvas.drawCircle(starX[i] * width, starY[i] * height, starR[i] * (density * 0.42f), orbPaint)
                }
                if (starFade > 0.45f) {
                    val period = if (circ.particleKind == 4) 11.0 else 28.0
                    val phase = timeSec % period
                    if (phase < 0.70) {
                        val prog = (phase / 0.70).toFloat()
                        val sx = width * (0.80f - prog * 0.44f)
                        val sy = height * (0.10f + prog * 0.18f)
                        val ex = sx + width * 0.08f * (1f - prog * 0.3f)
                        val ey = sy - height * 0.035f * (1f - prog * 0.3f)
                        val fade = sin(prog * PI).toFloat() * starFade
                        baselinePaint.strokeWidth = density * 1.3f
                        baselinePaint.color = Color.WHITE
                        baselinePaint.alpha = (masterAlpha * 0.75f * fade).toInt().coerceIn(0, 255)
                        canvas.drawLine(sx, sy, ex, ey, baselinePaint)
                    }
                }
            }

            // 2A. Real Sun (if above -3° horizon) with Kasten-Young Air-Mass Color
            if (sky.sunAltDeg > -3f) {
                val horizonFade = ((sky.sunAltDeg + 3f) / 3f).coerceIn(0f, 1f)
                val coronaCol = if (isCharging) 0xFF34D399.toInt() else sky.sunCoronaColor
                val pulse = if (isCharging) 0.75f + 0.25f * sin(timeSec * 2.2).toFloat() else 1f
                val glowR = width * 0.16f
                orbPaint.shader = RadialGradient(
                    sunX, sunY, glowR,
                    intArrayOf(
                        (coronaCol and 0x00FFFFFF) or (((masterAlpha * 0.48f * horizonFade * pulse).toInt()) shl 24),
                        (coronaCol and 0x00FFFFFF) or (((masterAlpha * 0.14f * horizonFade * pulse).toInt()) shl 24),
                        Color.TRANSPARENT
                    ),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(sunX, sunY, glowR, orbPaint)
                orbPaint.shader = null

                orbPaint.color = sky.sunColor
                orbPaint.alpha = (masterAlpha * 0.94f * horizonFade).toInt()
                canvas.drawCircle(sunX, sunY, width * 0.021f, orbPaint)
            }

            // 2B. Real-Phase Moon (even in daytime if above horizon!) rotated by Bright-Limb Tilt (χ_local)
            if (sky.moonAltDeg > -2f) {
                val moonHorizonFade = ((sky.moonAltDeg + 2f) / 2.5f).coerceIn(0f, 1f)
                val dayVisibility = if (isDay) 0.42f else 0.94f
                val r = width * 0.024f * sky.moonSizeScale
                val moonGlowR = r * 5.2f
                val glowCol = if (isCharging && sky.sunAltDeg <= -3f) 0xFF34D399.toInt() else circ.accent
                orbPaint.shader = RadialGradient(
                    moonX, moonY, moonGlowR,
                    intArrayOf(
                        (glowCol and 0x00FFFFFF) or (((masterAlpha * 0.34f * moonHorizonFade * dayVisibility).toInt()) shl 24),
                        Color.TRANSPARENT
                    ),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(moonX, moonY, moonGlowR, orbPaint)
                orbPaint.shader = null

                canvas.save()
                canvas.translate(moonX, moonY)
                canvas.rotate(sky.moonLimbTiltDeg)

                // Subtle Earthshine on unlit portion at night
                if (!isDay) {
                    orbPaint.color = Color.WHITE
                    orbPaint.alpha = (masterAlpha * 0.12f * moonHorizonFade).toInt()
                    canvas.drawCircle(0f, 0f, r, orbPaint)
                }

                // Render exact illuminated phase fraction (0% New -> 50% Quarter -> 98% Gibbous -> 100% Full)
                val k = sky.moonIllumination.coerceIn(0.02f, 1f)
                val termRx = r * kotlin.math.abs(2f * k - 1f)
                moonPath.reset()
                // Lit half-circle on top/right side (rotated to face Sun via moonLimbTiltDeg)
                arcRect.set(-r, -r, r, r)
                moonPath.arcTo(arcRect, -90f, 180f, true)
                arcRect.set(-termRx, -r, termRx, r)
                if (k >= 0.5f) {
                    // Gibbous: bulge outward
                    moonPath.arcTo(arcRect, 90f, 180f, false)
                } else {
                    // Crescent: curve inward
                    moonPath.arcTo(arcRect, 90f, -180f, false)
                }
                moonPath.close()

                orbPaint.color = if (isDay) 0xFFF1F5F9.toInt() else Color.WHITE
                orbPaint.alpha = (masterAlpha * dayVisibility * moonHorizonFade).toInt()
                canvas.drawPath(moonPath, orbPaint)
                canvas.restore()
            }

            // Helper: cached unit RadialGradient for zero-alloc soft mist/clouds/reflections
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

            // 3. Soft Organic Feathered Clouds (Day & Evening, zero pill shapes!) + 2 Distant Gulls
            if (isDay) {
                val cloudTint = ColorUtils.blendARGB(Color.WHITE, circ.horizonColor, 0.22f) and 0x00FFFFFF
                for (c in 0 until 3) {
                    val speed = 0.0062f + c * 0.0016f
                    val cx = (((0.18f + c * 0.36f + (timeSec * speed).toFloat()) % 1.48f) - 0.24f) * width
                    val cy = height * (0.26f + c * 0.11f)
                    val baseAlpha = (masterAlpha * (0.24f - c * 0.04f)).toInt().coerceIn(0, 255)
                    val col = cloudTint or (baseAlpha shl 24)
                    drawSoftOval(cx, cy, width * 0.30f, height * 0.032f, col)
                    drawSoftOval(cx - width * 0.10f, cy - height * 0.010f, width * 0.18f, height * 0.024f, col)
                    drawSoftOval(cx + width * 0.11f, cy + height * 0.006f, width * 0.20f, height * 0.022f, col)
                }

                // 2 tiny distant V-gulls gliding calmly across the upper sky
                baselinePaint.strokeWidth = density * 1.15f
                baselinePaint.color = 0xFF1E293B.toInt()
                for (g in 0 until 2) {
                    val gx = (((0.25f + g * 0.11f + (timeSec * 0.016).toFloat()) % 1.24f) - 0.12f) * width
                    val gy = height * (0.31f + g * 0.026f) - sin(timeSec * 0.8 + g).toFloat() * (height * 0.008f)
                    val span = width * (0.015f - g * 0.003f)
                    val flap = sin(timeSec * 2.8 + g * 1.4).toFloat() * (span * 0.38f)
                    baselinePaint.alpha = (masterAlpha * 0.40f).toInt()
                    canvas.drawLine(gx - span, gy - flap, gx, gy + span * 0.22f, baselinePaint)
                    canvas.drawLine(gx, gy + span * 0.22f, gx + span, gy - flap, baselinePaint)
                }
            }

            canvas.restore()

            // 4. Clean Horizon Line + Continuous Shimmering Sun/Moon Water Reflection Column
            baselinePaint.strokeWidth = density * 0.85f
            baselinePaint.color = Color.WHITE
            baselinePaint.alpha = (masterAlpha * (if (isDay) 0.22f else 0.12f)).toInt()
            canvas.drawLine(0f, horizonY, width.toFloat(), horizonY, baselinePaint)

            val seaMidY = horizonY + (shoreY - horizonY) * 0.42f
            val seaSpanY = (shoreY - horizonY) * 0.52f
            val shimRgb = if (sky.sunAltDeg > -4f) (sky.sunColor and 0x00FFFFFF) else (circ.accent and 0x00FFFFFF)
            val shimBreath = 0.88f + 0.12f * sin(timeSec * 0.9).toFloat()
            val shimAlpha = (masterAlpha * (if (isDay) 0.22f else 0.14f) * shimBreath).toInt().coerceIn(0, 255)
            drawSoftOval(orbX, seaMidY, width * 0.15f, seaSpanY, shimRgb or (shimAlpha shl 24))
            // Soft horizontal water glints inside reflection column (feathered, never sticks)
            for (r in 0 until 3) {
                val ry = horizonY + (shoreY - horizonY) * (0.14f + r * 0.22f)
                val rxShift = sin(timeSec * 0.7 + r * 1.5).toFloat() * (width * 0.025f)
                val rAlpha = (masterAlpha * (0.12f - r * 0.02f)).toInt().coerceIn(0, 255)
                drawSoftOval(orbX + rxShift, ry, width * (0.11f + r * 0.03f), height * 0.005f, shimRgb or (rAlpha shl 24))
            }

            // 5. Tiny Distant Sailboat Silhouette Drifting Slowly on the Sea
            val boatX = (((0.20f + (timeSec * 0.0060).toFloat()) % 1.16f) - 0.08f) * width
            val boatBob = sin(timeSec * 1.5).toFloat() * (density * 1.8f)
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

            // 6. Calm, Noticeable Ocean Swells (~16.5s per swell: soft glow band + delicate translucent foam crest)
            val swellRgb = if (isDay) 0x00E0F2FE else 0x0022D3EE
            val steps = 28
            for (wIdx in 0 until 2) {
                val waveProg = (((timeSec * 0.060) + wIdx * 0.5) % 1.0).toFloat()
                val wy = horizonY + (shoreY - horizonY) * (0.15f + waveProg * 0.82f)
                val env = sin(waveProg * PI).toFloat()
                val glowAlpha = (masterAlpha * env * (if (isDay) 0.18f else 0.14f)).toInt().coerceIn(0, 255)
                if (glowAlpha > 2) {
                    drawSoftOval(width * 0.5f, wy, width * 0.68f, height * 0.012f, swellRgb or (glowAlpha shl 24))
                }
                val crestAlpha = (masterAlpha * env * (if (isDay) 0.30f else 0.22f)).toInt().coerceIn(0, 255)
                if (crestAlpha > 2) {
                    baselinePaint.strokeWidth = density * 1.15f
                    baselinePaint.color = if (isDay) 0xFFF8FAFC.toInt() else 0xFF22D3EE.toInt()
                    baselinePaint.alpha = crestAlpha
                    moonPath.reset()
                    for (k in 0..steps) {
                        val fx = k.toFloat() / steps
                        val wx = fx * width
                        val dy = sin(fx * 2.2 * PI + timeSec * 0.7 + wIdx * 1.8).toFloat() * (height * 0.005f)
                        if (k == 0) moonPath.moveTo(wx, wy + dy) else moonPath.lineTo(wx, wy + dy)
                    }
                    canvas.drawPath(moonPath, baselinePaint)
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
            val isPlaying = state.isPlaying && !isLowBattery
            val cy = if (isLock) height * 0.55f else height * 0.48f
            val cx = width * 0.50f
            val sep = width * 0.11f

            val turnA = if (isPlaying) 0.55f + 0.45f * sin(timeSec * 2.4).toFloat() else 0.45f
            val turnB = if (isPlaying) 0.55f + 0.45f * sin(timeSec * 2.4 + PI).toFloat() else 0.45f

            val rA = width * (0.14f + 0.05f * turnA)
            val rB = width * (0.14f + 0.05f * turnB)

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
            masterAlpha: Int
        ) {
            val isPlaying = state.isPlaying && !isLowBattery
            val cy = height * (0.49f + (0.56f - 0.49f) * lb)
            val cx = width * 0.50f
            val ribbonLeft = width * 0.14f
            val ribbonW = width * 0.72f
            val amp = width * 0.030f * eqAmp

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
                val speed = 2.8 + rIdx * 0.65
                for (s in 0..steps) {
                    val frac = s.toFloat() / steps
                    val x = ribbonLeft + frac * ribbonW
                    val env = sin(frac * PI).toFloat()
                    val y = cy + sin(frac * freq * PI - timeSec * speed + rIdx * 1.4).toFloat() * amp * env
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
            alpha: Int
        ) {
            val breath = if (animate) {
                1.0f + 0.0175f * (1f + sin(timeSec * (2.0 * PI / 14.0)).toFloat())
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
            val eqRowOffset = thumbSize * 0.15f
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
            val barW = width * 0.0055f
            val barGap = width * 0.013f
            val maxBarH = width * 0.036f
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

            // 5-Bar Breathing Equalizer sitting at current song timestamp
            eqBarPaint.color = accent
            eqBarPaint.alpha = (alpha * (0.45f + 0.52f * ampScale)).toInt().coerceIn(0, 255)
            val breathEnv = 0.75f + 0.25f * sin(timeSec * 2.1).toFloat()
            for (b in 0 until barCount) {
                val wave = if (ampScale > 0.01f) {
                    0.15f + 0.85f * ((sin(timeSec * 5.2 + b * 1.15) + 1.0) * 0.5).toFloat() * breathEnv
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
