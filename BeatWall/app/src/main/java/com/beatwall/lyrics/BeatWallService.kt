package com.beatwall.lyrics

import android.app.KeyguardManager
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
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Choreographer
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
        private val tracker = MediaSessionTracker.getInstance(applicationContext)
        private val choreographer = Choreographer.getInstance()

        private var visible = false
        private var surfaceReady = false
        private var width = 1080
        private var height = 2400

        private var activeState: ActiveTrackState? = null
        private var prevState: ActiveTrackState? = null
        private var crossfadeProgress = 1f
        private var crossfadeStartMs = 0L
        private val crossfadeDurationMs = 350L

        // Smooth 350ms fade for lyrics appear / disappear (keeps cover + colors steady)
        private var lyricsAlpha = 0f
        private var targetLyricsAlpha = 0f

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
            color = 0xCCFFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val homeTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        private val activeLyricPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

        private val clipPath = Path()
        private val artRect = RectF()
        private val screenRect = Rect()
        private val lockLyricsClip = RectF()
        private val homeLyricsClip = RectF()

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
            // Hard clip Lock Screen lyrics to 63%–82% (never touches Artist or emergency contact / Now Bar)
            lockLyricsClip.set(0f, height * 0.625f, width.toFloat(), height * 0.825f)
            // Hard clip Home Screen lyrics to 32%–72% (clear of top icons and bottom search/dock)
            homeLyricsClip.set(0f, height * 0.32f, width.toFloat(), height * 0.72f)
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

        override fun onTrackStateChanged(state: ActiveTrackState?) {
            val prevKey = activeState?.trackKey
            val newKey = state?.trackKey
            if (prevKey != newKey && prevKey != null) {
                prevState = activeState
                crossfadeProgress = 0f
                crossfadeStartMs = SystemClock.elapsedRealtime()
                currentLineIndex = -1
                springY = 0f
                springVelocity = 0f
                targetY = 0f
            }
            activeState = state
            targetLyricsAlpha = if (state?.showLyrics == true && state.lyrics.isNotEmpty()) 1f else 0f
            if (prevKey == null) {
                lyricsAlpha = targetLyricsAlpha
            }
            rebuildLayoutsIfNeeded(force = false)
            if (visible) {
                snapToCurrentPosition()
                drawFrame(System.nanoTime())
                updateLoopState()
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
            val homeWidth = (width * 0.82f).toInt().coerceAtLeast(200)
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
            if (!visible || !surfaceReady) return false
            if (crossfadeProgress < 1f) return true
            if (abs(targetLyricsAlpha - lyricsAlpha) > 0.01f) return true
            val st = activeState ?: return false
            if (!st.isPlaying || !st.showLyrics) return false
            val springMoving = abs(targetY - springY) > 0.4f || abs(springVelocity) > 1f
            if (springMoving) return true
            val idx = currentLineIndex
            if (idx in st.lyrics.indices && st.lyrics[idx].isInterlude) return true
            return st.lyrics.isNotEmpty()
        }

        private fun updateLoopState() {
            if (needsAnimation()) {
                val activeMotion = crossfadeProgress < 1f ||
                    abs(targetLyricsAlpha - lyricsAlpha) > 0.01f ||
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

            var drewMotion = false

            if (crossfadeProgress < 1f) {
                val elapsed = SystemClock.elapsedRealtime() - crossfadeStartMs
                crossfadeProgress = (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
                if (crossfadeProgress >= 1f) prevState = null
                drewMotion = true
            }

            val alphaDiff = targetLyricsAlpha - lyricsAlpha
            if (abs(alphaDiff) > 0.01f) {
                val step = (dtSec / 0.35f) * if (alphaDiff > 0) 1f else -1f
                lyricsAlpha = (lyricsAlpha + step).coerceIn(0f, 1f)
                if (abs(targetLyricsAlpha - lyricsAlpha) <= 0.02f) lyricsAlpha = targetLyricsAlpha
                drewMotion = true
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
                    drewMotion = true
                }
                val diff = targetY - springY
                if (abs(diff) > 0.35f || abs(springVelocity) > 1f) {
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
                canvas.drawColor(0xFF07080D.toInt())

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
            // 33%–52% height: Centered rounded-square HD album art
            if (art != null) {
                val artSize = width * 0.42f
                val left = (width - artSize) / 2f
                val top = height * 0.335f
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

            // 55%–59%: Title & Artist
            titlePaint.textSize = width * 0.044f
            titlePaint.alpha = masterAlpha
            artistPaint.textSize = width * 0.034f
            artistPaint.alpha = (masterAlpha * 0.80f).toInt()

            val titleY = height * 0.565f
            canvas.drawText(ellipsize(state.title, titlePaint, width * 0.82f), width / 2f, titleY, titlePaint)
            canvas.drawText(ellipsize(state.artist, artistPaint, width * 0.78f), width / 2f, titleY + width * 0.045f, artistPaint)

            // 62.5%–82.5%: Hard-clipped 120 Hz synced lyrics (fades smoothly to 0 when stopped)
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
                    accentColor = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt(),
                    masterAlpha = lyrAlpha,
                    frameTimeNanos = frameTimeNanos
                )
                canvas.restore()
            }
        }

        private fun drawHomeScreenMinimalist(
            canvas: Canvas,
            state: ActiveTrackState,
            art: ProcessedArt?,
            masterAlpha: Int,
            frameTimeNanos: Long
        ) {
            // When stopped/paused > 15s (lyricsAlpha == 0), keep Home Screen ultra-clean blurred ambient art
            val lyrAlpha = (masterAlpha * lyricsAlpha).toInt().coerceIn(0, 255)
            if (lyrAlpha <= 3 || homeLayouts.isEmpty()) return

            val leftMargin = width * 0.09f
            homeTagPaint.textSize = width * 0.033f
            homeTagPaint.alpha = (lyrAlpha * 0.82f).toInt()
            val tagText = ellipsize("♪  ${state.title} — ${state.artist}", homeTagPaint, width * 0.82f)
            canvas.drawText(tagText, leftMargin, height * 0.295f, homeTagPaint)

            canvas.save()
            canvas.clipRect(homeLyricsClip)
            drawWindowedLyrics(
                canvas = canvas,
                layouts = homeLayouts,
                lyrics = state.lyrics,
                centerX = leftMargin,
                centerY = height * 0.50f,
                accentColor = art?.lyricAccentColor ?: 0xFF7DD3FC.toInt(),
                masterAlpha = lyrAlpha,
                frameTimeNanos = frameTimeNanos
            )
            canvas.restore()
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
                val alphaFactor = if (isFocused) 1f else 0.48f
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
                        val glowColor = (accentColor and 0x00FFFFFF) or (0xB0 shl 24)
                        layout.paint.setShadowLayer(20f, 0f, 0f, glowColor)
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
