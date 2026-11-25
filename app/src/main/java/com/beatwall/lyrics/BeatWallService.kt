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
                    Intent.ACTION_USER_PRESENT -> proceduralAnimator.reroll(calmOnly = activeState == null)
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
            val circ = artProcessor.getCircadianTheme(currentDayOfMonth(), currentMinuteOfDay())
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
                val now = SystemClock.elapsedRealtime()
                if (lastHiddenAtMs > 0L && (now - lastHiddenAtMs) > 3000L) {
                    proceduralAnimator.reroll(calmOnly = activeState == null)
                }
                mainHandler.post { tracker.refreshActiveSessions() }
                snapToCurrentPosition()
                drawFrame(System.nanoTime())
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
                    drawFrame(System.nanoTime())
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
            return if (
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
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
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
                val dayOfMonth = currentDayOfMonth()
                val minOfDay = currentMinuteOfDay()
                val circ = artProcessor.getCircadianTheme(dayOfMonth, minOfDay)
                bmpPaint.alpha = masterAlpha
                backdropRect.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(circ.backdrop, null, backdropRect, bmpPaint)
                drawMotherEarthCircadianScene(canvas, circ, minOfDay, isLock, timeSec, masterAlpha)
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

            val headerTop = if (isLock) height * 0.305f else height * 0.085f
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
            val clipRect = if (isLock) lockLyricsClip else homeLyricsClip
            val centerY = if (isLock) height * 0.572f else height * 0.49f
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
                    clipTop = clipRect.top,
                    clipBottom = clipRect.bottom,
                    accentColor = accent,
                    masterAlpha = lyrAlpha,
                    frameTimeNanos = frameTimeNanos
                )
            }
        }

        private fun drawMotherEarthCircadianScene(
            canvas: Canvas,
            circ: CircadianTheme,
            minuteOfDay: Int,
            isLock: Boolean,
            timeSec: Double,
            masterAlpha: Int
        ) {
            val isDay = minuteOfDay in 360..1080
            val horizonY = height * 0.66f
            val shoreY = height * 0.85f

            // 1. Night-only Stars & Occasional Shooting Star (NEVER in daytime!)
            if (!isDay) {
                orbPaint.shader = null
                orbPaint.color = Color.WHITE
                for (i in 0 until starCount) {
                    val twinkle = 0.35f + 0.65f * ((sin(timeSec * 1.1 + starPhase[i]) + 1.0) * 0.5).toFloat()
                    orbPaint.alpha = (masterAlpha * 0.44f * twinkle).toInt().coerceIn(0, 255)
                    canvas.drawCircle(starX[i] * width, starY[i] * height, starR[i] * (density * 0.42f), orbPaint)
                }
                val period = if (circ.particleKind == 4) 11.0 else 28.0
                val phase = timeSec % period
                if (phase < 0.70) {
                    val prog = (phase / 0.70).toFloat()
                    val sx = width * (0.80f - prog * 0.44f)
                    val sy = height * (0.10f + prog * 0.18f)
                    val ex = sx + width * 0.08f * (1f - prog * 0.3f)
                    val ey = sy - height * 0.035f * (1f - prog * 0.3f)
                    val fade = sin(prog * PI).toFloat()
                    baselinePaint.strokeWidth = density * 1.3f
                    baselinePaint.color = Color.WHITE
                    baselinePaint.alpha = (masterAlpha * 0.75f * fade).toInt().coerceIn(0, 255)
                    canvas.drawLine(sx, sy, ex, ey, baselinePaint)
                }
            }

            // 2. Soft Solar Corona Sun (Day) OR Earthshine Crescent Moon (Night)
            val frac = if (isDay) {
                (minuteOfDay - 360).toFloat() / 720f
            } else {
                val m = if (minuteOfDay > 1080) minuteOfDay - 1080 else minuteOfDay + (1440 - 1080)
                m.toFloat() / 720f
            }.coerceIn(0f, 1f)

            val orbX = width * (0.16f + 0.68f * frac)
            val arcLift = sin(frac * PI).toFloat()
            val orbY = if (isLock) {
                height * (0.145f - 0.055f * arcLift)
            } else {
                height * (0.22f - 0.10f * arcLift)
            }

            val baseOrbColor = if (isCharging) 0xFF34D399.toInt() else if (isDay) 0xFFFDE68A.toInt() else circ.accent
            val pulse = if (isCharging) 0.75f + 0.25f * sin(timeSec * 2.2).toFloat() else 1f
            val glowR = width * 0.15f
            orbPaint.shader = RadialGradient(
                orbX, orbY, glowR,
                intArrayOf(
                    (baseOrbColor and 0x00FFFFFF) or (((masterAlpha * 0.45f * pulse).toInt()) shl 24),
                    (baseOrbColor and 0x00FFFFFF) or (((masterAlpha * 0.14f * pulse).toInt()) shl 24),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orbX, orbY, glowR, orbPaint)
            orbPaint.shader = null

            if (isDay) {
                // Seamless glowing Sun disc (no hard outer ring)
                orbPaint.color = 0xFFFFFBEB.toInt()
                orbPaint.alpha = (masterAlpha * 0.92f).toInt()
                canvas.drawCircle(orbX, orbY, width * 0.020f, orbPaint)
            } else {
                val r = width * 0.024f
                orbPaint.color = Color.WHITE
                orbPaint.alpha = (masterAlpha * 0.12f).toInt()
                canvas.drawCircle(orbX, orbY, r, orbPaint)
                moonPath.reset()
                moonPath.addCircle(orbX, orbY, r, Path.Direction.CW)
                moonCutPath.reset()
                moonCutPath.addCircle(orbX + r * 0.42f, orbY - r * 0.28f, r * 0.86f, Path.Direction.CW)
                moonPath.op(moonCutPath, Path.Op.DIFFERENCE)
                orbPaint.color = Color.WHITE
                orbPaint.alpha = (masterAlpha * 0.94f).toInt()
                canvas.drawPath(moonPath, orbPaint)
            }

            // 3. Drifting Wispy Clouds & 2 Distant Gulls (Day & Evening only)
            if (isDay) {
                orbPaint.shader = null
                orbPaint.color = Color.WHITE
                for (c in 0 until 3) {
                    val speed = 0.008f + c * 0.003f
                    val cx = (((0.18f + c * 0.34f + (timeSec * speed).toFloat()) % 1.36f) - 0.18f) * width
                    val cy = height * (0.25f + c * 0.11f)
                    val cw = width * (0.28f + c * 0.05f)
                    val ch = height * 0.016f
                    orbPaint.alpha = (masterAlpha * (0.12f - c * 0.02f)).toInt().coerceIn(0, 255)
                    backdropRect.set(cx - cw * 0.5f, cy - ch, cx + cw * 0.5f, cy + ch)
                    canvas.drawRoundRect(backdropRect, ch, ch, orbPaint)
                    backdropRect.set(cx - cw * 0.30f, cy - ch * 1.45f, cx + cw * 0.22f, cy + ch * 0.5f)
                    canvas.drawRoundRect(backdropRect, ch, ch, orbPaint)
                }

                // 2 tiny distant V-gulls gliding slowly across the upper sky
                baselinePaint.strokeWidth = density * 1.2f
                baselinePaint.color = 0xFF1E293B.toInt()
                for (g in 0 until 2) {
                    val gx = (((0.25f + g * 0.11f + (timeSec * 0.014).toFloat()) % 1.24f) - 0.12f) * width
                    val gy = height * (0.31f + g * 0.026f) - sin(timeSec * 0.6 + g).toFloat() * (height * 0.008f)
                    val span = width * (0.015f - g * 0.003f)
                    val flap = sin(timeSec * 3.6 + g * 1.4).toFloat() * (span * 0.35f)
                    baselinePaint.alpha = (masterAlpha * 0.42f).toInt()
                    canvas.drawLine(gx - span, gy - flap, gx, gy + span * 0.22f, baselinePaint)
                    canvas.drawLine(gx, gy + span * 0.22f, gx + span, gy - flap, baselinePaint)
                }
            }

            // 4. Clean Horizon Line + Sun/Moon Shimmer Reflection on Sea
            baselinePaint.strokeWidth = density * 0.9f
            baselinePaint.color = Color.WHITE
            baselinePaint.alpha = (masterAlpha * (if (isDay) 0.26f else 0.14f)).toInt()
            canvas.drawLine(0f, horizonY, width.toFloat(), horizonY, baselinePaint)

            val shimColor = if (isDay) 0xFFFEF3C7.toInt() else 0xFFE2E8F0.toInt()
            baselinePaint.color = shimColor
            for (s in 0 until 7) {
                val fracS = s / 6f
                val sy = horizonY + (shoreY - horizonY) * (0.08f + fracS * 0.70f)
                val sw = width * (0.06f + fracS * 0.10f) * (0.85f + 0.15f * sin(timeSec * 1.8 + s).toFloat())
                val sx = orbX + sin(timeSec * 1.3 + s * 0.9).toFloat() * (width * 0.015f)
                baselinePaint.strokeWidth = density * 1.2f
                baselinePaint.alpha = (masterAlpha * (0.24f - fracS * 0.18f)).toInt().coerceIn(0, 255)
                canvas.drawLine(sx - sw * 0.5f, sy, sx + sw * 0.5f, sy, baselinePaint)
            }

            // 5. Tiny Distant Sailboat Silhouette Drifting Slowly on the Sea
            val boatX = (((0.20f + (timeSec * 0.0055).toFloat()) % 1.16f) - 0.08f) * width
            val boatBob = sin(timeSec * 1.4).toFloat() * (density * 1.1f)
            val boatY = horizonY + height * 0.014f + boatBob
            val boatCol = if (isDay) 0xFF0F172A.toInt() else 0xFF020617.toInt()
            eqBarPaint.color = boatCol
            eqBarPaint.alpha = (masterAlpha * 0.62f).toInt()
            val bw = width * 0.026f
            val bh = width * 0.022f
            // Hull
            eqBarRect.set(boatX - bw * 0.5f, boatY, boatX + bw * 0.5f, boatY + density * 2.4f)
            canvas.drawRoundRect(eqBarRect, density, density, eqBarPaint)
            // Sail triangle
            moonPath.reset()
            moonPath.moveTo(boatX - bw * 0.05f, boatY)
            moonPath.lineTo(boatX - bw * 0.05f, boatY - bh)
            moonPath.lineTo(boatX + bw * 0.42f, boatY - density * 1.5f)
            moonPath.close()
            canvas.drawPath(moonPath, eqBarPaint)

            // 6. Gentle Rolling Seafoam Wave (Bioluminescent cyan-teal glow at night!)
            val waveProg = ((timeSec * 0.11) % 1.0).toFloat()
            val wy = horizonY + (shoreY - horizonY) * (0.22f + waveProg * 0.72f)
            val waveAlpha = sin(waveProg * PI).toFloat() * (if (isDay) 0.42f else 0.28f)
            baselinePaint.strokeWidth = density * 1.6f
            baselinePaint.color = if (isDay) Color.WHITE else 0xFF22D3EE.toInt()
            baselinePaint.alpha = (masterAlpha * waveAlpha).toInt().coerceIn(0, 255)
            moonPath.reset()
            val steps = 28
            for (k in 0..steps) {
                val fx = k.toFloat() / steps
                val wx = fx * width
                val dy = sin(fx * 2.2 * PI + timeSec * 0.9).toFloat() * (height * 0.006f)
                if (k == 0) moonPath.moveTo(wx, wy + dy) else moonPath.lineTo(wx, wy + dy)
            }
            canvas.drawPath(moonPath, baselinePaint)
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

            // Option 2: 5-Bar Micro Silk Equalizer on left + calm hairline
            drawMicroSilkEqualizer(
                canvas = canvas,
                timeSec = timeSec,
                startX = textLeft,
                centerY = artistBaselineY + eqRowOffset,
                totalW = maxTextW,
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
            ampScale: Float,
            accent: Int,
            alpha: Int
        ) {
            val barCount = 5
            val barW = width * 0.0046f
            val barGap = width * 0.011f
            val maxBarH = width * 0.019f
            val eqSpan = barCount * barGap

            eqBarPaint.color = accent
            eqBarPaint.alpha = (alpha * (0.42f + 0.50f * ampScale)).toInt().coerceIn(0, 255)

            for (b in 0 until barCount) {
                val wave = if (ampScale > 0.01f) {
                    0.25f + 0.75f * ((sin(timeSec * 3.8 + b * 0.95) + 1.0) * 0.5).toFloat()
                } else {
                    0f
                }
                val bh = barW + (maxBarH - barW) * wave * ampScale
                val bx = startX + b * barGap
                eqBarRect.set(bx, centerY - bh * 0.5f, bx + barW, centerY + bh * 0.5f)
                canvas.drawRoundRect(eqBarRect, barW * 0.5f, barW * 0.5f, eqBarPaint)
            }

            val lineStart = startX + eqSpan + width * 0.012f
            if (lineStart < startX + totalW) {
                baselinePaint.strokeWidth = width * 0.0026f
                baselinePaint.color = accent
                baselinePaint.alpha = (alpha * 0.24f).toInt().coerceIn(0, 255)
                canvas.drawLine(lineStart, centerY, startX + totalW, centerY, baselinePaint)
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
