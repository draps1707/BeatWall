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
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
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
        private var crossfadeStartMs = 0L
        private val crossfadeDurationMs = 550L

        private var lyricsAlpha = 0f
        private var targetLyricsAlpha = 0f
        private var waveAmp = 0f
        private var targetWaveAmp = 0f

        private var centeredLayouts: List<StaticLayout> = emptyList()
        private var lyricYCenters: FloatArray = FloatArray(0)
        private var focusWeights: FloatArray = FloatArray(0)
        private var measuredTrackKey: String? = null

        private var headerTitleLayout: StaticLayout? = null
        private var headerTitleCacheKey: String? = null

        private var currentLineIndex = -1
        private var springY = 0f
        private var springVelocity = 0f
        private var targetY = 0f
        private var lastFrameNanos = 0L
        private var currentRequestedHz = -1f
        private var frameScheduled = false

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
        private val squigglyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            letterSpacing = -0.02f
        }
        private val headerArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xC4FFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.015f
        }
        private val activeLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isDither = true
        }

        private val clipPath = Path()
        private val coverRimPath = Path()
        private val coverProgressPath = Path()
        private val pathMeasure = PathMeasure()
        private val squigglyPath = Path()
        private val moonPath = Path()
        private val moonCutPath = Path()
        private val miniArtRect = RectF()
        private val arcRect = RectF()
        private val backdropRect = RectF()
        private val lockLyricsClip = RectF()
        private val homeLyricsClip = RectF()

        private val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> proceduralAnimator.reroll(calmOnly = activeState == null)
                    Intent.ACTION_BATTERY_CHANGED -> updateBatteryFlags(intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> updateBatteryFlags(null)
                }
                if (visible && powerManager.isInteractive) {
                    snapToCurrentPosition()
                    updateLoopState()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            tracker.addListener(this)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            val stickyBatt = registerReceiver(systemReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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
            targetWaveAmp = if (activeState?.isPlaying == true && !isLowBattery) 1f else 0f
        }

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
            val circ = artProcessor.getCircadianTheme(currentMinuteOfDay())
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
            val prevKey = activeState?.trackKey
            val newKey = state?.trackKey
            val artChanged = activeState?.processedArt !== state?.processedArt

            if (prevKey != newKey || artChanged) {
                if (activeState != null) {
                    prevState = activeState
                    crossfadeProgress = 0f
                    crossfadeStartMs = SystemClock.elapsedRealtime()
                }
                if (prevKey != newKey) {
                    currentLineIndex = -1
                    springY = 0f
                    springVelocity = 0f
                    targetY = 0f
                    proceduralAnimator.reroll(calmOnly = state == null)
                }
            }

            activeState = state
            targetLyricsAlpha = if (state?.showLyrics == true && state.lyrics.isNotEmpty()) 1f else 0f
            targetWaveAmp = if (state?.isPlaying == true && !isLowBattery) 1f else 0f
            if (prevKey == null && state == null) {
                lyricsAlpha = 0f
                waveAmp = 0f
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
            for (i in focusWeights.indices) {
                focusWeights[i] = if (i == idx) 1f else 0f
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
            return idealSize.coerceIn(width * 0.062f, width * 0.088f)
        }

        private fun rebuildLayoutsIfNeeded(force: Boolean) {
            val st = activeState
            if (st == null || st.lyrics.isEmpty()) {
                centeredLayouts = emptyList()
                lyricYCenters = FloatArray(0)
                focusWeights = FloatArray(0)
                measuredTrackKey = null
                return
            }
            if (!force && measuredTrackKey == st.trackKey) return
            measuredTrackKey = st.trackKey

            val contentW = (width * 0.88f).toInt().coerceAtLeast(220)
            val dynSize = computeDynamicLyricTextSize(st.lyrics, contentW)
            activeLyricPaint.textSize = dynSize
            val lineGap = dynSize * 0.42f

            centeredLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(4f, 1.04f)
                    .setIncludePad(true)
                    .setMaxLines(3)
                    .build()
            }

            lyricYCenters = computeYCenters(centeredLayouts, lineGap)
            focusWeights = FloatArray(centeredLayouts.size)
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
            if (abs(targetWaveAmp - waveAmp) > 0.01f) return true
            if (isLowBattery) return false
            return true
        }

        private fun targetFrameRate(): Float {
            if (!needsAnimation()) return 0f
            return if (
                activeState != null ||
                crossfadeProgress < 1f ||
                abs(targetLyricsAlpha - lyricsAlpha) > 0.01f ||
                abs(targetWaveAmp - waveAmp) > 0.01f
            ) {
                120f
            } else {
                60f
            }
        }

        private fun scheduleNextFrame() {
            if (!visible || !surfaceReady || !powerManager.isInteractive) return
            if (!frameScheduled) {
                frameScheduled = true
                lastFrameNanos = System.nanoTime()
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
                    Surface.CHANGE_FRAME_RATE_ALWAYS
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

            val dtSec = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos

            if (crossfadeProgress < 1f) {
                val elapsed = SystemClock.elapsedRealtime() - crossfadeStartMs
                crossfadeProgress = (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
                if (crossfadeProgress >= 1f) prevState = null
            }

            val alphaDiff = targetLyricsAlpha - lyricsAlpha
            if (abs(alphaDiff) > 0.01f) {
                val step = (dtSec / 0.35f) * if (alphaDiff > 0) 1f else -1f
                lyricsAlpha = (lyricsAlpha + step).coerceIn(0f, 1f)
                if (abs(targetLyricsAlpha - lyricsAlpha) <= 0.02f) lyricsAlpha = targetLyricsAlpha
            } else {
                lyricsAlpha = targetLyricsAlpha
            }

            targetWaveAmp = if (activeState?.isPlaying == true && !isLowBattery) 1f else 0f
            val waveDiff = targetWaveAmp - waveAmp
            if (abs(waveDiff) > 0.01f) {
                val step = (dtSec / 0.30f) * if (waveDiff > 0) 1f else -1f
                waveAmp = (waveAmp + step).coerceIn(0f, 1f)
                if (abs(targetWaveAmp - waveAmp) <= 0.02f) waveAmp = targetWaveAmp
            } else {
                waveAmp = targetWaveAmp
            }

            val st = activeState
            if (st != null && st.lyrics.isNotEmpty() && st.showLyrics && lyricYCenters.isNotEmpty()) {
                val newIdx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
                if (newIdx != currentLineIndex) {
                    currentLineIndex = newIdx
                    targetY = if (newIdx in lyricYCenters.indices) lyricYCenters[newIdx] else 0f
                }

                // Silky critically-damped spring (~380ms glide)
                val diff = targetY - springY
                if (abs(diff) > 0.30f || abs(springVelocity) > 0.8f) {
                    val omega = 10.5f
                    val accel = omega * omega * diff - 2f * omega * springVelocity
                    springVelocity += accel * dtSec
                    springY += springVelocity * dtSec
                } else {
                    springY = targetY
                    springVelocity = 0f
                }

                // Smooth per-line focus morph (~280ms)
                val activeIdx = currentLineIndex.coerceAtLeast(0)
                val focusStep = dtSec / 0.28f
                val startFocus = (activeIdx - 4).coerceAtLeast(0)
                val endFocus = (activeIdx + 4).coerceAtMost(focusWeights.lastIndex)
                for (i in startFocus..endFocus) {
                    val targetW = if (i == activeIdx) 1f else 0f
                    val wDiff = targetW - focusWeights[i]
                    if (abs(wDiff) > 0.005f) {
                        val delta = focusStep * if (wDiff > 0f) 1f else -1f
                        focusWeights[i] = (focusWeights[i] + delta).coerceIn(0f, 1f)
                        if (abs(targetW - focusWeights[i]) <= 0.01f) focusWeights[i] = targetW
                    } else {
                        focusWeights[i] = targetW
                    }
                }
            }

            setSurfaceFrameRate(targetFrameRate())
            drawFrame(frameTimeNanos)

            if (needsAnimation()) {
                frameScheduled = true
                choreographer.postFrameCallback(this)
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
                val minOfDay = currentMinuteOfDay()
                val circ = artProcessor.getCircadianTheme(minOfDay)
                drawBreathingBackdrop(canvas, circ.backdrop, !isLowBattery, timeSec, masterAlpha)
                if (!isLock && !isLowBattery) {
                    proceduralAnimator.draw(
                        canvas = canvas,
                        width = width,
                        height = height,
                        timeSec = timeSec,
                        accent = circ.accent,
                        primary = circ.primary,
                        secondary = circ.secondary,
                        masterAlpha = masterAlpha
                    )
                }
                drawSkyCelestialOrb(canvas, minOfDay, isLock, timeSec, circ.accent, masterAlpha)
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

            val lyrAlpha = (masterAlpha * lyricsAlpha).toInt().coerceIn(0, 255)
            val clipRect = if (isLock) lockLyricsClip else homeLyricsClip
            val centerY = if (isLock) height * 0.572f else height * 0.49f
            val centerX = width * 0.06f

            if (lyrAlpha > 3 && centeredLayouts.isNotEmpty() && lyricYCenters.isNotEmpty()) {
                canvas.save()
                canvas.clipRect(clipRect)
                drawWindowedKaraokeLyrics(
                    canvas = canvas,
                    state = state,
                    layouts = centeredLayouts,
                    yCenters = lyricYCenters,
                    centerX = centerX,
                    centerY = centerY,
                    clipTop = clipRect.top,
                    clipBottom = clipRect.bottom,
                    accentColor = accent,
                    masterAlpha = lyrAlpha,
                    frameTimeNanos = frameTimeNanos
                )
                canvas.restore()
            }
        }

        private fun drawSkyCelestialOrb(
            canvas: Canvas,
            minuteOfDay: Int,
            isLock: Boolean,
            timeSec: Double,
            accent: Int,
            masterAlpha: Int
        ) {
            val isDay = minuteOfDay in 360..1080
            val frac = if (isDay) {
                (minuteOfDay - 360).toFloat() / 720f
            } else {
                val m = if (minuteOfDay > 1080) minuteOfDay - 1080 else minuteOfDay + (1440 - 1080)
                m.toFloat() / 720f
            }.coerceIn(0f, 1f)

            val orbX = width * (0.15f + 0.70f * frac)
            val arcLift = sin(frac * PI).toFloat()
            val orbY = if (isLock) {
                height * (0.135f - 0.055f * arcLift)
            } else {
                height * (0.23f - 0.11f * arcLift)
            }

            val baseOrbColor = if (isCharging) {
                0xFF34D399.toInt()
            } else if (isDay) {
                0xFFFBBF24.toInt()
            } else {
                accent
            }

            val pulse = if (isCharging) 0.75f + 0.25f * sin(timeSec * 2.2).toFloat() else 1f
            val glowR = width * 0.085f

            orbPaint.shader = RadialGradient(
                orbX, orbY, glowR,
                intArrayOf(
                    (baseOrbColor and 0x00FFFFFF) or (((masterAlpha * 0.48f * pulse).toInt()) shl 24),
                    (baseOrbColor and 0x00FFFFFF) or (((masterAlpha * 0.14f * pulse).toInt()) shl 24),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orbX, orbY, glowR, orbPaint)
            orbPaint.shader = null

            if (isDay) {
                orbPaint.color = 0xFFFEF3C7.toInt()
                orbPaint.alpha = (masterAlpha * 0.90f).toInt()
                canvas.drawCircle(orbX, orbY, width * 0.017f, orbPaint)
            } else {
                val r = width * 0.022f
                moonPath.reset()
                moonPath.addCircle(orbX, orbY, r, Path.Direction.CW)
                moonCutPath.reset()
                moonCutPath.addCircle(orbX + r * 0.42f, orbY - r * 0.28f, r * 0.86f, Path.Direction.CW)
                moonPath.op(moonCutPath, Path.Op.DIFFERENCE)

                orbPaint.color = Color.WHITE
                orbPaint.alpha = (masterAlpha * 0.90f).toInt()
                canvas.drawPath(moonPath, orbPaint)
            }
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
                backlightPaint.shader = RadialGradient(
                    miniArtRect.centerX(), miniArtRect.centerY(), thumbSize * 1.15f,
                    intArrayOf((primary and 0x00FFFFFF) or (((headerAlpha * 0.36f).toInt()) shl 24), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
                canvas.drawCircle(miniArtRect.centerX(), miniArtRect.centerY(), thumbSize * 1.15f, backlightPaint)
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
            val waveOffset = thumbSize * 0.14f
            val totalBlockH = titleH + artistH * 0.92f + waveOffset
            val blockTop = (headerTop + (thumbSize - totalBlockH) * 0.5f).coerceAtLeast(headerTop - thumbSize * 0.04f)

            canvas.save()
            canvas.translate(textLeft, blockTop)
            titleLayout.draw(canvas)
            canvas.restore()

            val artistBaselineY = blockTop + titleH + artistH * 0.74f
            canvas.drawText(
                ellipsize(state.artist, headerArtistPaint, maxTextW),
                textLeft,
                artistBaselineY,
                headerArtistPaint
            )

            // Full-width Pixel-inspired squiggly wave under Artist name (smoothly flattens when paused)
            drawPixelSquigglyWave(
                canvas = canvas,
                timeSec = timeSec,
                startX = textLeft,
                centerY = artistBaselineY + waveOffset,
                waveW = maxTextW,
                ampScale = waveAmp,
                accent = accent,
                alpha = headerAlpha
            )
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

        private fun drawPixelSquigglyWave(
            canvas: Canvas,
            timeSec: Double,
            startX: Float,
            centerY: Float,
            waveW: Float,
            ampScale: Float,
            accent: Int,
            alpha: Int
        ) {
            val amp = width * 0.0080f * ampScale
            val steps = 48
            squigglyPath.reset()
            for (s in 0..steps) {
                val frac = s.toFloat() / steps
                val x = startX + frac * waveW
                val edgeTaper = sin(frac * PI).toFloat().coerceAtLeast(0.18f)
                val y = if (amp > 0.1f) {
                    centerY + sin(frac * 8.0 * PI - timeSec * 5.2).toFloat() * amp * edgeTaper
                } else {
                    centerY
                }
                if (s == 0) squigglyPath.moveTo(x, y) else squigglyPath.lineTo(x, y)
            }
            squigglyPaint.strokeWidth = width * 0.0042f
            squigglyPaint.color = accent
            squigglyPaint.alpha = (alpha * (0.38f + 0.52f * ampScale)).toInt().coerceIn(0, 255)
            canvas.drawPath(squigglyPath, squigglyPaint)
        }

        private fun drawWindowedKaraokeLyrics(
            canvas: Canvas,
            state: ActiveTrackState,
            layouts: List<StaticLayout>,
            yCenters: FloatArray,
            centerX: Float,
            centerY: Float,
            clipTop: Float,
            clipBottom: Float,
            accentColor: Int,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            val activeIdx = currentLineIndex.coerceAtLeast(0)
            val start = (activeIdx - 3).coerceAtLeast(0)
            val end = (activeIdx + 3).coerceAtMost(layouts.lastIndex)
            val timeSec = frameTimeNanos / 1_000_000_000.0
            val mistZone = (clipBottom - clipTop) * 0.10f
            val posMs = state.currentPositionMs()
            val isPlaying = state.isPlaying && !isLowBattery

            for (i in start..end) {
                val layout = layouts[i]
                val dist = abs(i - activeIdx)
                val focusW = if (i in focusWeights.indices) focusWeights[i] else if (i == activeIdx) 1f else 0f

                // Subtle Apple Music-style staggered cascade inertia
                val stagger = (i - activeIdx) * (-springVelocity * 0.014f).coerceIn(-height * 0.012f, height * 0.012f)
                val lineOffset = yCenters[i] - springY + stagger
                val yPos = centerY + lineOffset - (layout.height / 2f)
                val lineCenterY = yPos + layout.height / 2f

                val topFade = ((lineCenterY - clipTop) / mistZone).coerceIn(0f, 1f)
                val botFade = ((clipBottom - lineCenterY) / mistZone).coerceIn(0f, 1f)
                val mistFactor = minOf(topFade, botFade)
                if (mistFactor <= 0.02f) continue

                val baseDistAlpha = when (dist) {
                    0 -> 1.0f
                    1 -> 0.52f
                    2 -> 0.24f
                    3 -> 0.09f
                    else -> 0f
                }
                val pausedScale = if (isPlaying) 1.0f else 0.72f
                val smoothAlpha = (baseDistAlpha + (1.0f - baseDistAlpha) * focusW) * mistFactor * pausedScale
                val lineAlpha = (masterAlpha * smoothAlpha).toInt().coerceIn(0, 255)
                if (lineAlpha <= 2) continue

                canvas.save()
                canvas.translate(centerX, yPos)

                val scale = 0.94f + 0.10f * focusW
                val pivotX = layout.width / 2f
                canvas.scale(scale, scale, pivotX, layout.height / 2f)

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
                            // Calm static dots when paused — zero pulsing glow
                            dotPaint.alpha = (lineAlpha * 0.45f).toInt().coerceIn(15, 255)
                            canvas.drawCircle(baseX + d * dotGap, dotY, dotRadius * 0.90f, dotPaint)
                        }
                    }
                } else if (i == activeIdx || focusW > 0.25f) {
                    val lineStart = state.lyrics[i].timeMs
                    val nextStart = if (i + 1 < state.lyrics.size) state.lyrics[i + 1].timeMs else lineStart + 3500L
                    val lineDur = (nextStart - lineStart).coerceIn(750L, 5000L)
                    val totalSweepFrac = if (i == activeIdx) {
                        ((posMs - lineStart).toFloat() / (lineDur * 0.90f)).coerceIn(0.03f, 1.0f)
                    } else {
                        1.0f
                    }

                    // Soft album-tinted text bloom on active line while playing
                    if (isPlaying && focusW > 0.1f) {
                        val glowAlpha = (lineAlpha * 0.38f * focusW).toInt().coerceIn(0, 255)
                        val glowCol = (accentColor and 0x00FFFFFF) or (glowAlpha shl 24)
                        layout.paint.setShadowLayer(18f * focusW, 0f, 0f, glowCol)
                    } else {
                        layout.paint.clearShadowLayer()
                    }

                    // True line-by-line sequential sweep across wrapped lines (48% frosted -> 100% crisp white)
                    val lineCount = layout.lineCount.coerceAtLeast(1)
                    var totalW = 0f
                    for (l in 0 until lineCount) {
                        totalW += layout.getLineWidth(l).coerceAtLeast(1f)
                    }
                    var consumedW = 0f
                    val sungTargetW = totalW * totalSweepFrac

                    val brightWhite = (0xFFFFFF) or (lineAlpha shl 24)
                    val dimWhite = (0xFFFFFF) or (((lineAlpha * 0.48f).toInt()) shl 24)

                    layout.paint.style = Paint.Style.FILL
                    for (l in 0 until lineCount) {
                        val lw = layout.getLineWidth(l).coerceAtLeast(1f)
                        val localFrac = ((sungTargetW - consumedW) / lw).coerceIn(0f, 1f)
                        consumedW += lw

                        val lTop = layout.getLineTop(l).toFloat() - 8f
                        val lBot = layout.getLineBottom(l).toFloat() + 8f
                        val leftEdge = (layout.width - lw) / 2f

                        canvas.save()
                        canvas.clipRect(-24f, lTop, layout.width + 24f, lBot)

                        if (localFrac <= 0.005f) {
                            layout.paint.shader = null
                            layout.paint.color = Color.WHITE
                            layout.paint.alpha = (lineAlpha * 0.48f).toInt()
                        } else if (localFrac >= 0.995f) {
                            layout.paint.shader = null
                            layout.paint.color = Color.WHITE
                            layout.paint.alpha = lineAlpha
                        } else {
                            val sweepX = leftEdge + lw * localFrac
                            val softEdge = (lw * 0.16f).coerceAtLeast(22f)
                            layout.paint.shader = LinearGradient(
                                sweepX - softEdge, 0f, sweepX + softEdge, 0f,
                                intArrayOf(brightWhite, dimWhite),
                                null,
                                Shader.TileMode.CLAMP
                            )
                        }
                        layout.draw(canvas)
                        canvas.restore()
                    }
                    layout.paint.shader = null
                    layout.paint.clearShadowLayer()
                } else {
                    layout.paint.shader = null
                    layout.paint.clearShadowLayer()
                    layout.paint.style = Paint.Style.FILL
                    layout.paint.color = Color.WHITE
                    layout.paint.alpha = lineAlpha
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
