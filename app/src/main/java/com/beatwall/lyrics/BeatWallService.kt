package com.beatwall.lyrics

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
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
        private val idleStore = IdleWallpaperStore(applicationContext)
        private val tracker = MediaSessionTracker.getInstance(applicationContext)
        private val choreographer = Choreographer.getInstance()

        private var visible = false
        private var surfaceReady = false
        private var width = 1080
        private var height = 2400
        private var homePageOffset = 0f

        private var activeState: ActiveTrackState? = null
        private var prevState: ActiveTrackState? = null
        private var crossfadeProgress = 1f
        private var crossfadeStartMs = 0L
        private val crossfadeDurationMs = 350L

        // Pre-measured StaticLayouts for <0.3ms windowed draw
        private var lockLayouts: List<StaticLayout> = emptyList()
        private var homeLayouts: List<StaticLayout> = emptyList()
        private var measuredTrackKey: String? = null

        // 120 Hz spring physics for lyric vertical glide
        private var currentLineIndex = -1
        private var springY = 0f
        private var springVelocity = 0f
        private var targetY = 0f
        private var lastFrameNanos = 0L
        private var is120HzRequested = false
        private var frameScheduled = false

        // Reusable Paints (zero per-frame allocations)
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.TRANSPARENT
            setShadowLayer(42f, 0f, 18f, 0xAA000000.toInt())
        }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB8FFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val homeTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        private val activeLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        private val inactiveLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66FFFFFF
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private val clipPath = Path()
        private val artRect = RectF()
        private val screenRect = Rect()

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (visible) {
                    snapToCurrentPosition()
                    drawFrame(System.nanoTime())
                    updateLoopState()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
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
            rebuildLayoutsIfNeeded(force = true)
            if (visible) drawFrame(System.nanoTime())
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            stopLoop()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                tracker.refreshActiveSessions()
                snapToCurrentPosition()
                drawFrame(System.nanoTime())
                updateLoopState()
            } else {
                stopLoop()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            homePageOffset = xOffset
            if (visible && !keyguardManager.isKeyguardLocked) {
                drawFrame(System.nanoTime())
            }
        }

        override fun onTrackStateChanged(state: ActiveTrackState?) {
            val prevKey = activeState?.trackKey
            val newKey = state?.trackKey
            if (prevKey != newKey) {
                prevState = activeState
                crossfadeProgress = 0f
                crossfadeStartMs = SystemClock.elapsedRealtime()
                currentLineIndex = -1
                springY = 0f
                springVelocity = 0f
                targetY = 0f
            }
            activeState = state
            rebuildLayoutsIfNeeded(force = false)
            if (visible) {
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
            val lineStep = height * 0.068f
            targetY = idx.coerceAtLeast(0) * lineStep
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
            val homeWidth = (width * 0.82f).toInt().coerceAtLeast(200)
            activeLyricPaint.textSize = width * 0.056f

            lockLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, lockWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(6f, 1.05f)
                    .setIncludePad(true)
                    .setMaxLines(2)
                    .build()
            }
            homeLayouts = st.lyrics.map { line ->
                StaticLayout.Builder.obtain(line.text, 0, line.text.length, activeLyricPaint, homeWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setLineSpacing(6f, 1.05f)
                    .setIncludePad(true)
                    .setMaxLines(2)
                    .build()
            }
        }

        private fun needsAnimation(): Boolean {
            if (!visible || !surfaceReady) return false
            if (crossfadeProgress < 1f) return true
            val st = activeState ?: return false
            if (!st.isPlaying) return false
            val springMoving = abs(targetY - springY) > 0.4f || abs(springVelocity) > 1f
            if (springMoving) return true
            val idx = currentLineIndex
            if (idx in st.lyrics.indices && st.lyrics[idx].isInterlude) return true
            // Keep light check loop only while playing lyrics so line transitions fire at exact ms
            return st.lyrics.isNotEmpty()
        }

        private fun updateLoopState() {
            if (needsAnimation()) {
                val activeMotion = crossfadeProgress < 1f ||
                    abs(targetY - springY) > 0.4f ||
                    (currentLineIndex in (activeState?.lyrics?.indices ?: IntRange.EMPTY) &&
                        activeState?.lyrics?.getOrNull(currentLineIndex)?.isInterlude == true)
                setSurface120Hz(activeMotion)
                if (!frameScheduled) {
                    frameScheduled = true
                    lastFrameNanos = System.nanoTime()
                    choreographer.postFrameCallback(this)
                }
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
            if (!visible || !surfaceReady) {
                setSurface120Hz(false)
                return
            }

            val dtSec = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos

            // Update 350ms crossfade
            if (crossfadeProgress < 1f) {
                val elapsed = SystemClock.elapsedRealtime() - crossfadeStartMs
                crossfadeProgress = (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
                if (crossfadeProgress >= 1f) prevState = null
            }

            // Update lyric index & critically-damped 120 Hz spring
            val st = activeState
            val lineStep = height * 0.068f
            var drewMotion = crossfadeProgress < 1f

            if (st != null && st.lyrics.isNotEmpty()) {
                val newIdx = LrcParser.findLineIndex(st.lyrics, st.currentPositionMs())
                if (newIdx != currentLineIndex) {
                    currentLineIndex = newIdx
                    targetY = newIdx.coerceAtLeast(0) * lineStep
                    drewMotion = true
                }
                val diff = targetY - springY
                if (abs(diff) > 0.35f || abs(springVelocity) > 1f) {
                    // Critically damped spring (omega = 15 rad/s)
                    val omega = 15f
                    val accel = omega * omega * diff - 2f * omega * springVelocity
                    springVelocity += accel * dtSec
                    springY += springVelocity * dtSec
                    drewMotion = true
                } else {
                    springY = targetY
                    springVelocity = 0f
                }

                if (currentLineIndex in st.lyrics.indices && st.lyrics[currentLineIndex].isInterlude) {
                    drewMotion = true
                }
            }

            if (drewMotion) {
                setSurface120Hz(true)
                drawFrame(frameTimeNanos)
            } else {
                // Resting between lyric lines: drop LTPO request to 0Hz until next line
                setSurface120Hz(false)
            }

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
                canvas.drawColor(0xFF040407.toInt())

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

            if (state == null) {
                val idleBmp = if (isLock) idleStore.getLockBitmap() else idleStore.getHomeBitmap()
                if (idleBmp != null) {
                    drawCenterCropBitmap(canvas, idleBmp, masterAlpha)
                }
                return
            }

            val art = state.processedArt
            if (art != null) {
                bmpPaint.alpha = masterAlpha
                canvas.drawBitmap(art.blurredBackdrop, null, screenRect, bmpPaint)
            }

            if (isLock) {
                drawLockScreenAppleMusic(canvas, state, art, masterAlpha, frameTimeNanos)
            } else {
                drawHomeScreenMinimalist(canvas, state, art, masterAlpha, frameTimeNanos)
            }
        }

        private fun drawLockScreenAppleMusic(
            canvas: Canvas,
            state: ActiveTrackState,
            art: ProcessedArt?,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            // 36%–56% height: Centered rounded-square album art
            if (art != null) {
                val artSize = width * 0.44f
                val left = (width - artSize) / 2f
                val top = height * 0.355f
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
            }

            // 58%–62%: Title & Artist
            titlePaint.textSize = width * 0.044f
            titlePaint.alpha = masterAlpha
            artistPaint.textSize = width * 0.034f
            artistPaint.alpha = (masterAlpha * 0.75f).toInt()

            val titleY = height * 0.595f
            canvas.drawText(ellipsize(state.title, titlePaint, width * 0.82f), width / 2f, titleY, titlePaint)
            canvas.drawText(ellipsize(state.artist, artistPaint, width * 0.78f), width / 2f, titleY + width * 0.046f, artistPaint)

            // 65%–85%: 5-line windowed 120 Hz synced lyrics
            drawWindowedLyrics(
                canvas = canvas,
                layouts = lockLayouts,
                lyrics = state.lyrics,
                centerX = width * 0.08f,
                centerY = height * 0.745f,
                accentColor = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt(),
                masterAlpha = masterAlpha,
                frameTimeNanos = frameTimeNanos
            )
        }

        private fun drawHomeScreenMinimalist(
            canvas: Canvas,
            state: ActiveTrackState,
            art: ProcessedArt?,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            // Page-aware auto-dim: full opacity on Page 1 (xOffset <= 0.15), ~15% on Page 2+
            val pageDim = (1f - (homePageOffset * 2.5f).coerceIn(0f, 0.85f))
            val effectiveAlpha = (masterAlpha * pageDim).toInt().coerceIn(0, 255)
            if (effectiveAlpha <= 4) return

            // Subtle dark scrim so icons stay crisp
            val homeScrim = Paint().apply { color = (0x44000000).toInt() }
            canvas.drawRect(screenRect, homeScrim)

            // 28%–68% universal safe zone: whisper-quiet ♪ Title — Artist + minimalist lyrics
            val leftMargin = width * 0.09f
            homeTagPaint.textSize = width * 0.032f
            homeTagPaint.alpha = (effectiveAlpha * 0.65f).toInt()
            val tagText = ellipsize("♪  ${state.title} — ${state.artist}", homeTagPaint, width * 0.82f)
            canvas.drawText(tagText, leftMargin, height * 0.305f, homeTagPaint)

            drawWindowedLyrics(
                canvas = canvas,
                layouts = homeLayouts,
                lyrics = state.lyrics,
                centerX = leftMargin,
                centerY = height * 0.49f,
                accentColor = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt(),
                masterAlpha = effectiveAlpha,
                frameTimeNanos = frameTimeNanos
            )
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
            if (layouts.isEmpty()) return
            val lineStep = height * 0.068f
            val activeIdx = currentLineIndex.coerceAtLeast(0)
            val start = (activeIdx - 2).coerceAtLeast(0)
            val end = (activeIdx + 2).coerceAtMost(layouts.lastIndex)

            for (i in start..end) {
                val layout = layouts[i]
                val lineOffset = (i * lineStep) - springY
                val yPos = centerY + lineOffset - (layout.height / 2f)
                val dist = abs(lineOffset) / lineStep

                val isFocused = dist < 0.45f
                val alphaFactor = when {
                    dist < 0.45f -> 1f
                    dist < 1.5f -> 0.42f
                    else -> 0.16f
                }
                val lineAlpha = (masterAlpha * alphaFactor).toInt().coerceIn(0, 255)
                if (lineAlpha <= 2) continue

                canvas.save()
                canvas.translate(centerX, yPos)

                if (isFocused) {
                    val scale = 1.0f + (0.055f * (1f - (dist / 0.45f)))
                    val pivotX = if (layout.alignment == Layout.Alignment.ALIGN_CENTER) layout.width / 2f else 0f
                    canvas.scale(scale, scale, pivotX, layout.height / 2f)

                    if (lyrics[i].isInterlude) {
                        val pulse = 0.55f + 0.45f * sin((frameTimeNanos / 350_000_000.0)).toFloat()
                        layout.paint.color = accentColor
                        layout.paint.alpha = (lineAlpha * pulse).toInt().coerceIn(30, 255)
                        layout.paint.clearShadowLayer()
                    } else {
                        layout.paint.color = Color.WHITE
                        layout.paint.alpha = lineAlpha
                        val glowColor = (accentColor and 0x00FFFFFF) or (0x99 shl 24)
                        layout.paint.setShadowLayer(18f, 0f, 0f, glowColor)
                    }
                } else {
                    layout.paint.color = Color.WHITE
                    layout.paint.alpha = lineAlpha
                    layout.paint.clearShadowLayer()
                }

                layout.draw(canvas)
                canvas.restore()
            }
        }

        private fun drawCenterCropBitmap(canvas: Canvas, bmp: Bitmap, alpha: Int) {
            val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            bmpPaint.alpha = alpha
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), bmpPaint)
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
