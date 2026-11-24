package com.beatwall.lyrics

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
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
        private val choreographer = Choreographer.getInstance()
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
        private var measuredTrackKey: String? = null

        private var currentLineIndex = -1
        private var springY = 0f
        private var springVelocity = 0f
        private var targetY = 0f
        private var lastFrameNanos = 0L
        private var is120HzRequested = false
        private var frameScheduled = false

        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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

        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val homeTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        private val homeArtistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB8FFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        private val activeLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        private val clipPath = Path()
        private val artRect = RectF()
        private val miniArtRect = RectF()
        private val pillRect = RectF()
        private val screenRect = Rect()
        private val lockLyricsClip = RectF()
        private val homeLyricsClip = RectF()

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    proceduralAnimator.reroll()
                }
                if (visible && powerManager.isInteractive) {
                    snapToCurrentPosition()
                    scheduleNextFrame()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
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

        override fun onTouchEvent(event: MotionEvent?) {
            if (event == null) return
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
            ) {
                if (visible && powerManager.isInteractive && !keyguardManager.isKeyguardLocked) {
                    val idx = event.actionIndex
                    proceduralAnimator.onTouch(
                        event.getX(idx),
                        event.getY(idx),
                        System.nanoTime() / 1_000_000_000.0
                    )
                    updateLoopState()
                }
            }
            super.onTouchEvent(event)
        }

        override fun onComputeColors(): WallpaperColors? {
            val art = activeState?.processedArt ?: return super.onComputeColors()
            return WallpaperColors(
                Color.valueOf(art.primaryGlowColor),
                Color.valueOf(art.secondaryGlowColor),
                Color.valueOf(art.lyricAccentColor),
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
            screenRect.set(0, 0, width, height)
            lockLyricsClip.set(0f, height * 0.625f, width.toFloat(), height * 0.825f)
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
                    proceduralAnimator.reroll()
                }
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
            if (prevKey != newKey && prevKey != null) {
                prevState = activeState
                crossfadeProgress = 0f
                crossfadeStartMs = SystemClock.elapsedRealtime()
                currentLineIndex = -1
                springY = 0f
                springVelocity = 0f
                targetY = 0f
                proceduralAnimator.reroll()
            }
            activeState = state
            targetLyricsAlpha = if (state?.showLyrics == true && state.lyrics.isNotEmpty()) 1f else 0f
            if (prevKey == null) {
                lyricsAlpha = targetLyricsAlpha
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

        private fun lineStepPx(): Float = height * 0.056f

        private fun snapToCurrentPosition() {
            val st = activeState ?: return
            if (st.lyrics.isEmpty()) return
            val idx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
            currentLineIndex = idx
            targetY = idx.coerceAtLeast(0) * lineStepPx()
            springY = targetY
            springVelocity = 0f
        }

        private fun rebuildLayoutsIfNeeded(force: Boolean) {
            val st = activeState
            if (st == null || st.lyrics.isEmpty()) {
                lockLayouts = emptyList()
                homeLayouts = emptyList()
                measuredTrackKey = null
                return
            }
            if (!force && measuredTrackKey == st.trackKey) return
            measuredTrackKey = st.trackKey

            val lockWidth = (width * 0.84f).toInt().coerceAtLeast(200)
            val homeWidth = (width * 0.80f).toInt().coerceAtLeast(200)
            activeLyricPaint.textSize = width * 0.052f

            lockLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, lockWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(4f, 1.04f)
                    .setIncludePad(true)
                    .setMaxLines(2)
                    .build()
            }
            homeLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, homeWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(4f, 1.04f)
                    .setIncludePad(true)
                    .setMaxLines(2)
                    .build()
            }
        }

        private fun needsAnimation(): Boolean {
            if (!visible || !surfaceReady || !powerManager.isInteractive) return false
            if (crossfadeProgress < 1f) return true
            if (abs(targetLyricsAlpha - lyricsAlpha) > 0.01f) return true
            if (proceduralAnimator.hasActiveRipples(System.nanoTime() / 1_000_000_000.0)) return true
            val st = activeState ?: return false
            if (st.isPlaying) return true
            val springMoving = abs(targetY - springY) > 0.4f || abs(springVelocity) > 1f
            return springMoving
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
            val lineStep = lineStepPx()
            if (st != null && st.lyrics.isNotEmpty() && st.showLyrics) {
                val newIdx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
                if (newIdx != currentLineIndex) {
                    currentLineIndex = newIdx
                    targetY = newIdx.coerceAtLeast(0) * lineStep
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

            setSurface120Hz(needsAnimation())
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
            if (alphaFactor <= 0.01f || state == null) return
            val masterAlpha = (alphaFactor * 255).toInt().coerceIn(0, 255)

            val art = state.processedArt
            if (art != null) {
                bmpPaint.alpha = masterAlpha
                canvas.drawBitmap(art.blurredBackdrop, null, screenRect, bmpPaint)
            }

            if (isLock) {
                drawLockScreenAppleMusic(canvas, state, art, masterAlpha, frameTimeNanos)
            } else {
                drawHomeScreenDynamic(canvas, state, art, masterAlpha, frameTimeNanos)
            }
        }

        private fun drawLockScreenAppleMusic(
            canvas: Canvas,
            state: ActiveTrackState,
            art: ProcessedArt?,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            val accent = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt()
            val posterShift = (1f - lyricsAlpha) * 0.055f

            if (art != null) {
                val artSize = width * 0.42f
                val left = (width - artSize) / 2f
                val top = height * (0.335f + posterShift)
                artRect.set(left, top, left + artSize, top + artSize)
                val radius = artSize * 0.08f

                shadowPaint.alpha = masterAlpha
                canvas.drawRoundRect(artRect, radius, radius, shadowPaint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(artRect, radius, radius, Path.Direction.CW)
                canvas.clipPath(clipPath)
                bmpPaint.alpha = masterAlpha
                canvas.drawBitmap(art.coverArt, null, artRect, bmpPaint)
                canvas.restore()

                rimPaint.alpha = (masterAlpha * 0.28f).toInt()
                canvas.drawRoundRect(artRect, radius, radius, rimPaint)
            }

            titlePaint.textSize = width * 0.044f
            titlePaint.alpha = masterAlpha
            artistPaint.textSize = width * 0.034f
            artistPaint.alpha = (masterAlpha * 0.82f).toInt()

            val titleY = height * (0.562f + posterShift)
            canvas.drawText(ellipsize(state.title, titlePaint, width * 0.82f), width / 2f, titleY, titlePaint)
            canvas.drawText(ellipsize(state.artist, artistPaint, width * 0.78f), width / 2f, titleY + width * 0.045f, artistPaint)

            if (state.durationMs > 0L && state.isPlaying) {
                val barW = width * 0.42f
                val barLeft = (width - barW) / 2f
                val barTop = height * (0.602f + posterShift)
                drawProgressHairline(canvas, barLeft, barTop, barW, state.progressFraction(), accent, masterAlpha)
            }

            val lyrAlpha = (masterAlpha * lyricsAlpha).toInt().coerceIn(0, 255)
            if (lyrAlpha > 3 && lockLayouts.isNotEmpty()) {
                canvas.save()
                canvas.clipRect(lockLyricsClip)
                drawWindowedLyrics(
                    canvas = canvas,
                    layouts = lockLayouts,
                    lyrics = state.lyrics,
                    centerX = width * 0.08f,
                    centerY = height * 0.725f,
                    accentColor = accent,
                    masterAlpha = lyrAlpha,
                    frameTimeNanos = frameTimeNanos
                )
                canvas.restore()
            }
        }

        private fun drawHomeScreenDynamic(
            canvas: Canvas,
            state: ActiveTrackState,
            art: ProcessedArt?,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            val accent = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt()
            val primary = art?.primaryGlowColor ?: 0xFF38BDF8.toInt()
            val secondary = art?.secondaryGlowColor ?: 0xFF1E293B.toInt()
            val timeSec = frameTimeNanos / 1_000_000_000.0

            // 1. 17,280-Combo Procedural Background Animation (only while playing)
            if (state.isPlaying) {
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

            // 2. Interactive 120 Hz Touch Ripples
            proceduralAnimator.drawTouchRipples(canvas, timeSec, accent, width, masterAlpha)

            // 3. Mini HD Cover Header
            val headerFactor = if (state.isPlaying) 1f else 0.45f
            val headerAlpha = (masterAlpha * headerFactor).toInt().coerceIn(0, 255)
            val leftMargin = width * 0.085f
            val thumbSize = width * 0.135f
            val headerTop = height * 0.22f

            if (art != null) {
                miniArtRect.set(leftMargin, headerTop, leftMargin + thumbSize, headerTop + thumbSize)
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
            homeTitlePaint.textSize = width * 0.041f
            homeTitlePaint.alpha = headerAlpha
            homeArtistPaint.textSize = width * 0.032f
            homeArtistPaint.alpha = (headerAlpha * 0.78f).toInt()

            canvas.drawText(
                ellipsize(state.title, homeTitlePaint, maxTextW),
                textLeft,
                headerTop + thumbSize * 0.44f,
                homeTitlePaint
            )
            canvas.drawText(
                ellipsize(state.artist, homeArtistPaint, maxTextW),
                textLeft,
                headerTop + thumbSize * 0.80f,
                homeArtistPaint
            )

            if (state.isPlaying) {
                drawMiniHeaderBars(canvas, timeSec, width * 0.88f, headerTop + thumbSize * 0.5f, accent, headerAlpha)
            }

            if (state.durationMs > 0L) {
                val barTop = headerTop + thumbSize + height * 0.014f
                val barW = width * 0.83f
                drawProgressHairline(canvas, leftMargin, barTop, barW, state.progressFraction(), accent, headerAlpha)
            }

            val lyrAlpha = (masterAlpha * lyricsAlpha).toInt().coerceIn(0, 255)
            if (lyrAlpha > 3 && homeLayouts.isNotEmpty()) {
                canvas.save()
                canvas.clipRect(homeLyricsClip)
                drawWindowedLyrics(
                    canvas = canvas,
                    layouts = homeLayouts,
                    lyrics = state.lyrics,
                    centerX = leftMargin,
                    centerY = height * 0.52f,
                    accentColor = accent,
                    masterAlpha = lyrAlpha,
                    frameTimeNanos = frameTimeNanos
                )
                canvas.restore()
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

        private fun drawWindowedLyrics(
            canvas: Canvas,
            layouts: List<StaticLayout>,
            lyrics: List<LyricLine>,
            centerX: Float,
            centerY: Float,
            accentColor: Int,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            val lineStep = lineStepPx()
            val activeIdx = currentLineIndex.coerceAtLeast(0)
            val start = (activeIdx - 1).coerceAtLeast(0)
            val end = (activeIdx + 1).coerceAtMost(layouts.lastIndex)

            for (i in start..end) {
                val layout = layouts[i]
                val lineOffset = (i * lineStep) - springY
                val yPos = centerY + lineOffset - (layout.height / 2f)
                val dist = abs(lineOffset) / lineStep

                val isFocused = dist < 0.45f
                val alphaFactor = if (isFocused) 1f else 0.40f
                val lineAlpha = (masterAlpha * alphaFactor).toInt().coerceIn(0, 255)
                if (lineAlpha <= 2) continue

                canvas.save()
                canvas.translate(centerX, yPos)

                if (isFocused) {
                    val scale = 1.0f + (0.08f * (1f - (dist / 0.45f)))
                    val pivotX = if (layout.alignment == Layout.Alignment.ALIGN_CENTER) layout.width / 2f else 0f
                    canvas.scale(scale, scale, pivotX, layout.height / 2f)

                    if (lyrics[i].isInterlude) {
                        val pulse = 0.55f + 0.45f * sin((frameTimeNanos / 350_000_000.0)).toFloat()
                        layout.paint.color = accentColor
                        layout.paint.alpha = (lineAlpha * pulse).toInt().coerceIn(30, 255)
                        layout.paint.style = Paint.Style.FILL
                        layout.draw(canvas)
                    } else {
                        layout.paint.style = Paint.Style.STROKE
                        layout.paint.strokeWidth = 6f
                        layout.paint.color = accentColor
                        layout.paint.alpha = (lineAlpha * 0.34f).toInt()
                        layout.draw(canvas)

                        layout.paint.style = Paint.Style.FILL
                        layout.paint.color = Color.WHITE
                        layout.paint.alpha = lineAlpha
                        layout.draw(canvas)
                    }
                } else {
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
