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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
        private val tracker = MediaSessionTracker.getInstance(applicationContext)
        private val artProcessor = ArtEffectProcessor()
        private val choreographer = Choreographer.getInstance()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val proceduralAnimator = ProceduralAnimator()

        private var visible = false
        private var surfaceReady = false
        private var width = 1080
        private var height = 2400
        private var lastHiddenAtMs = 0L

        private var activeState: ActiveTrackState? = null
        private var prevState: ActiveTrackState? = null
        private var crossfadeProgress = 1f
        private var crossfadeStartMs = 0L
        private val crossfadeDurationMs = 350L

        private var lyricsAlpha = 0f
        private var targetLyricsAlpha = 0f

        private var lockLayouts: List<StaticLayout> = emptyList()
        private var homeLayouts: List<StaticLayout> = emptyList()
        private var lockYCenters: FloatArray = FloatArray(0)
        private var homeYCenters: FloatArray = FloatArray(0)
        private var measuredTrackKey: String? = null

        private var currentLineIndex = -1
        private var springY = 0f
        private var springVelocity = 0f
        private var targetY = 0f
        private var lastFrameNanos = 0L
        private var is120HzRequested = false
        private var frameScheduled = false

        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val backlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            setShadowLayer(36f, 0f, 14f, 0x99000000.toInt())
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x38FFFFFF
            strokeWidth = 2.5f
        }
        private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x26FFFFFF
        }
        private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val animPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            letterSpacing = -0.02f
        }
        private val headerArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB8FFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.02f
        }
        private val activeLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        private val clipPath = Path()
        private val miniArtRect = RectF()
        private val pillRect = RectF()
        private val backdropRect = RectF()
        private val lockLyricsClip = RectF()
        private val homeLyricsClip = RectF()

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    proceduralAnimator.reroll(calmOnly = activeState == null)
                }
                if (visible && powerManager.isInteractive) {
                    snapToCurrentPosition()
                    scheduleNextFrame()
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
            }
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }

        override fun onDestroy() {
            stopLoop()
            tracker.removeListener(this)
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
            super.onDestroy()
        }

        override fun onComputeColors(): WallpaperColors? {
            val art = activeState?.processedArt
            if (art != null) {
                return WallpaperColors(
                    Color.valueOf(art.primaryGlowColor),
                    Color.valueOf(art.secondaryGlowColor),
                    Color.valueOf(art.lyricAccentColor),
                    WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                )
            }
            val circ = artProcessor.getCircadianTheme(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            return WallpaperColors(
                Color.valueOf(circ.primary),
                Color.valueOf(circ.secondary),
                Color.valueOf(circ.accent),
                WallpaperColors.HINT_SUPPORTS_DARK_TEXT
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
            // Unified Lock Screen lyrics zone: 46%–83% (below compact header, above emergency text/Now Bar)
            lockLyricsClip.set(0f, height * 0.46f, width.toFloat(), height * 0.83f)
            // Unified Home Screen lyrics zone: 32%–72%
            homeLyricsClip.set(0f, height * 0.32f, width.toFloat(), height * 0.72f)
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
                // Async session refresh so app-switching stays 100% stutter-free while catching missed skips
                mainHandler.post { tracker.refreshActiveSessions() }
                snapToCurrentPosition()
                scheduleNextFrame()
            } else {
                lastHiddenAtMs = SystemClock.elapsedRealtime()
                stopLoop()
            }
        }

        override fun onTrackStateChanged(state: ActiveTrackState?) {
            val prevKey = activeState?.trackKey
            val newKey = state?.trackKey
            val artChanged = activeState?.processedArt !== state?.processedArt
            if (prevKey != newKey) {
                prevState = activeState
                crossfadeProgress = 0f
                crossfadeStartMs = SystemClock.elapsedRealtime()
                currentLineIndex = -1
                springY = 0f
                springVelocity = 0f
                targetY = 0f
                proceduralAnimator.reroll(calmOnly = state == null)
            }
            activeState = state
            targetLyricsAlpha = if (state?.showLyrics == true && state.lyrics.isNotEmpty()) 1f else 0f
            if (prevKey == null && state == null) {
                lyricsAlpha = 0f
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
                scheduleNextFrame()
            }
        }

        private fun snapToCurrentPosition() {
            val st = activeState ?: return
            if (st.lyrics.isEmpty()) return
            val idx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
            currentLineIndex = idx
            val centers = if (keyguardManager.isKeyguardLocked) lockYCenters else homeYCenters
            if (idx in centers.indices) {
                targetY = centers[idx]
                springY = targetY
                springVelocity = 0f
            }
        }

        private fun rebuildLayoutsIfNeeded(force: Boolean) {
            val st = activeState
            if (st == null || st.lyrics.isEmpty()) {
                lockLayouts = emptyList()
                homeLayouts = emptyList()
                lockYCenters = FloatArray(0)
                homeYCenters = FloatArray(0)
                measuredTrackKey = null
                return
            }
            if (!force && measuredTrackKey == st.trackKey) return
            measuredTrackKey = st.trackKey

            val contentW = (width * 0.82f).toInt().coerceAtLeast(200)
            activeLyricPaint.textSize = width * 0.052f
            val lineGap = height * 0.022f

            lockLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(4f, 1.04f)
                    .setIncludePad(true)
                    .setMaxLines(3)
                    .build()
            }
            homeLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(4f, 1.04f)
                    .setIncludePad(true)
                    .setMaxLines(3)
                    .build()
            }

            lockYCenters = computeYCenters(lockLayouts, lineGap)
            homeYCenters = computeYCenters(homeLayouts, lineGap)
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
            // Always animate smoothly when visible (Circadian Ambient when idle, Karaoke+Animations when playing)
            return true
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
            if (needsAnimation()) {
                setSurface120Hz(true)
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
            setSurface120Hz(false)
        }

        private fun setSurface120Hz(enable: Boolean) {
            if (is120HzRequested == enable || !surfaceReady) return
            try {
                val surface = surfaceHolder.surface ?: return
                if (!surface.isValid) return
                val rate = if (enable) 120f else 0f
                surface.setFrameRate(
                    rate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
                is120HzRequested = enable
            } catch (_: Exception) {
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!visible || !surfaceReady || !powerManager.isInteractive) {
                setSurface120Hz(false)
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

            val st = activeState
            val isLock = keyguardManager.isKeyguardLocked
            val centers = if (isLock) lockYCenters else homeYCenters
            if (st != null && st.lyrics.isNotEmpty() && st.showLyrics && centers.isNotEmpty()) {
                val newIdx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
                if (newIdx != currentLineIndex) {
                    currentLineIndex = newIdx
                    targetY = if (newIdx in centers.indices) centers[newIdx] else 0f
                }
                val diff = targetY - springY
                if (abs(diff) > 0.35f || abs(springVelocity) > 1f) {
                    val omega = 15f
                    val accel = omega * omega * diff - 2f * omega * springVelocity
                    springVelocity += accel * dtSec
                    springY += springVelocity * dtSec
                } else {
                    springY = targetY
                    springVelocity = 0f
                }
            }

            setSurface120Hz(true)
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
                    drawState(canvas, prevState, isLock, 1f - crossfadeProgress, frameTimeNanos)
                    drawState(canvas, activeState, isLock, crossfadeProgress, frameTimeNanos)
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

            // 1. Option A: Pure Circadian Ambient Mode when music is not playing
            if (state == null) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val circ = artProcessor.getCircadianTheme(hour)
                drawBreathingBackdrop(canvas, circ.backdrop, true, timeSec, masterAlpha)
                if (!isLock) {
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
                return
            }

            val art = state.processedArt
            if (art != null) {
                drawBreathingBackdrop(canvas, art.blurredBackdrop, state.isPlaying, timeSec, masterAlpha)
            }

            val accent = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt()
            val primary = art?.primaryGlowColor ?: 0xFF38BDF8.toInt()
            val secondary = art?.secondaryGlowColor ?: 0xFF1E293B.toInt()

            if (!isLock && state.isPlaying) {
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

            // 2. Unified Sleek Compact Horizontal Header on BOTH Lock Screen and Home Screen
            // Smoothly centers at ~43% when no lyrics, or sits cleanly at top of zone (34% Lock / 22% Home) when lyrics active
            val headerTop = if (isLock) {
                height * (0.34f + (1f - lyricsAlpha) * 0.08f)
            } else {
                height * (0.22f + (1f - lyricsAlpha) * 0.21f)
            }
            val headerFactor = if (state.isPlaying) 1f else 0.55f
            val headerAlpha = (masterAlpha * headerFactor).toInt().coerceIn(0, 255)
            drawUnifiedCompactHeader(canvas, state, art, headerTop, timeSec, accent, primary, headerAlpha)

            // 3. Lyrics Zone (Dynamic Line Spacing + Karaoke Sweep)
            val lyrAlpha = (masterAlpha * lyricsAlpha).toInt().coerceIn(0, 255)
            val layouts = if (isLock) lockLayouts else homeLayouts
            val centers = if (isLock) lockYCenters else homeYCenters
            val clipRect = if (isLock) lockLyricsClip else homeLyricsClip
            val centerY = if (isLock) height * 0.645f else height * 0.525f
            val centerX = width * 0.09f

            if (lyrAlpha > 3 && layouts.isNotEmpty() && centers.isNotEmpty()) {
                canvas.save()
                canvas.clipRect(clipRect)
                drawWindowedKaraokeLyrics(
                    canvas = canvas,
                    state = state,
                    layouts = layouts,
                    yCenters = centers,
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
            val leftMargin = width * 0.085f
            val thumbSize = width * 0.135f

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

                rimPaint.alpha = (headerAlpha * 0.30f).toInt()
                canvas.drawRoundRect(miniArtRect, radius, radius, rimPaint)
            }

            val textLeft = leftMargin + thumbSize + width * 0.038f
            val maxTextW = width * 0.52f
            headerTitlePaint.textSize = width * 0.041f
            headerTitlePaint.alpha = headerAlpha
            headerArtistPaint.textSize = width * 0.032f
            headerArtistPaint.alpha = (headerAlpha * 0.78f).toInt()

            canvas.drawText(
                ellipsize(state.title, headerTitlePaint, maxTextW),
                textLeft,
                headerTop + thumbSize * 0.44f,
                headerTitlePaint
            )
            canvas.drawText(
                ellipsize(state.artist, headerArtistPaint, maxTextW),
                textLeft,
                headerTop + thumbSize * 0.80f,
                headerArtistPaint
            )

            if (state.isPlaying) {
                drawMiniHeaderBars(canvas, timeSec, width * 0.88f, headerTop + thumbSize * 0.5f, accent, headerAlpha)
            }

            if (state.durationMs > 0L) {
                val barTop = headerTop + thumbSize + height * 0.014f
                val barW = width * 0.83f
                drawProgressHairline(canvas, leftMargin, barTop, barW, state.progressFraction(), accent, headerAlpha)
            }
        }

        private fun drawProgressHairline(
            canvas: Canvas,
            left: Float,
            top: Float,
            totalW: Float,
            fraction: Float,
            accent: Int,
            alpha: Int
        ) {
            val h = 5f
            progressTrackPaint.alpha = (alpha * 0.16f).toInt()
            pillRect.set(left, top, left + totalW, top + h)
            canvas.drawRoundRect(pillRect, h / 2f, h / 2f, progressTrackPaint)

            val fillW = (totalW * fraction).coerceAtLeast(h)
            progressFillPaint.color = accent
            progressFillPaint.alpha = (alpha * 0.90f).toInt()
            pillRect.set(left, top, left + fillW, top + h)
            canvas.drawRoundRect(pillRect, h / 2f, h / 2f, progressFillPaint)
        }

        private fun drawMiniHeaderBars(
            canvas: Canvas,
            timeSec: Double,
            centerX: Float,
            centerY: Float,
            accent: Int,
            alpha: Int
        ) {
            animPaint.color = accent
            animPaint.alpha = (alpha * 0.88f).toInt()
            val barW = width * 0.011f
            val gap = barW * 0.8f
            val totalW = 5 * barW + 4 * gap
            val startX = centerX - totalW / 2f
            val maxH = width * 0.055f

            for (i in 0 until 5) {
                val phase = timeSec * (4.2 + i * 0.7) + i * 1.1
                val norm = 0.30f + 0.70f * ((sin(phase) + 1.0) * 0.5).toFloat()
                val bh = maxH * norm
                val x = startX + i * (barW + gap)
                pillRect.set(x, centerY - bh / 2f, x + barW, centerY + bh / 2f)
                canvas.drawRoundRect(pillRect, barW / 2f, barW / 2f, animPaint)
            }
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
            val start = (activeIdx - 2).coerceAtLeast(0)
            val end = (activeIdx + 2).coerceAtMost(layouts.lastIndex)
            val timeSec = frameTimeNanos / 1_000_000_000.0
            val mistZone = (clipBottom - clipTop) * 0.22f
            val posMs = state.currentPositionMs()

            for (i in start..end) {
                val layout = layouts[i]
                val lineOffset = yCenters[i] - springY
                val yPos = centerY + lineOffset - (layout.height / 2f)
                val lineCenterY = yPos + layout.height / 2f

                val topFade = ((lineCenterY - clipTop) / mistZone).coerceIn(0f, 1f)
                val botFade = ((clipBottom - lineCenterY) / mistZone).coerceIn(0f, 1f)
                val mistFactor = minOf(topFade, botFade)
                if (mistFactor <= 0.02f) continue

                val isFocused = i == activeIdx
                val focusStrength = if (isFocused) 1f else 0f
                val alphaFactor = (if (isFocused) 1f else 0.42f) * mistFactor
                val lineAlpha = (masterAlpha * alphaFactor).toInt().coerceIn(0, 255)
                if (lineAlpha <= 2) continue

                canvas.save()
                canvas.translate(centerX, yPos)

                val scale = 1.0f + 0.07f * focusStrength
                val pivotX = if (layout.alignment == Layout.Alignment.ALIGN_CENTER) layout.width / 2f else 0f
                canvas.scale(scale, scale, pivotX, layout.height / 2f)

                if (state.lyrics[i].isInterlude) {
                    dotPaint.color = accentColor
                    val dotRadius = width * 0.011f
                    val dotGap = width * 0.032f
                    val baseX = if (layout.alignment == Layout.Alignment.ALIGN_CENTER) layout.width / 2f else dotGap * 1.5f
                    val dotY = layout.height / 2f
                    for (d in -1..1) {
                        val phase = timeSec * 3.2 - (d + 1) * 0.85
                        val wave = 0.35f + 0.65f * ((sin(phase) + 1.0) * 0.5).toFloat()
                        dotPaint.alpha = (lineAlpha * wave).toInt().coerceIn(20, 255)
                        canvas.drawCircle(baseX + d * dotGap, dotY, dotRadius * (0.85f + 0.25f * wave), dotPaint)
                    }
                } else if (isFocused) {
                    // Apple Music-style horizontal chunk sweep across the active line
                    val lineStart = state.lyrics[i].timeMs
                    val nextStart = if (i + 1 < state.lyrics.size) state.lyrics[i + 1].timeMs else lineStart + 3500L
                    val lineDur = (nextStart - lineStart).coerceIn(800L, 4500L)
                    val sweepFrac = ((posMs - lineStart).toFloat() / (lineDur * 0.88f)).coerceIn(0.05f, 1.0f)

                    val maxLineW = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: layout.width.toFloat()
                    val leftEdge = if (layout.alignment == Layout.Alignment.ALIGN_CENTER) {
                        (layout.width - maxLineW) / 2f
                    } else 0f
                    val sweepX = leftEdge + maxLineW * sweepFrac
                    val softEdge = (maxLineW * 0.14f).coerceAtLeast(24f)

                    val brightWhite = (0xFFFFFF) or (lineAlpha shl 24)
                    val dimWhite = (0xFFFFFF) or (((lineAlpha * 0.48f).toInt()) shl 24)

                    layout.paint.style = Paint.Style.FILL
                    layout.paint.shader = LinearGradient(
                        sweepX - softEdge, 0f, sweepX + softEdge, 0f,
                        intArrayOf(brightWhite, dimWhite),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    layout.draw(canvas)
                    layout.paint.shader = null
                } else {
                    layout.paint.shader = null
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
