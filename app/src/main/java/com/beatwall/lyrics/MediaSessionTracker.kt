package com.beatwall.lyrics

import android.media.session.MediaSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

data class ActiveTrackState(
    val trackKey: String,
    val packageName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val processedArt: ProcessedArt?,
    val lyrics: List<LyricLine>,
    val isPlaying: Boolean,
    val showLyrics: Boolean,
    val basePositionMs: Long,
    val lastUpdateElapsedMs: Long,
    val playbackSpeed: Float,
    val isNotebookLm: Boolean = false,
    val isFetchingLyrics: Boolean = false
) {
    fun currentPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return basePositionMs
        val delta = (nowElapsedMs - lastUpdateElapsedMs).coerceAtLeast(0L)
        return (basePositionMs + (delta * playbackSpeed).toLong()).coerceAtLeast(0L)
    }

    fun progressFraction(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Float {
        if (durationMs <= 0L) return 0f
        return (currentPositionMs(nowElapsedMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

class MediaSessionTracker private constructor(private val appContext: Context) {

    interface Listener {
        fun onTrackStateChanged(state: ActiveTrackState?)
    }

    private val allowedPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube",
        "com.google.android.apps.labs.language.tailwind",
        "com.android.chrome",
        "com.google.android.googlequicksearchbox"
    )
    private val youtubePkg = "com.google.android.youtube"
    private val notebookLmPkg = "com.google.android.apps.labs.language.tailwind"
    private val browserAudioPkgs = setOf("com.android.chrome", "com.google.android.googlequicksearchbox")
    private val pauseGraceMs = 3_000L

    private fun isAllowedSession(controller: MediaController): Boolean {
        val pkg = controller.packageName
        if (pkg !in allowedPackages) return false
        if (pkg !in browserAudioPkgs) return true
        val meta = controller.metadata ?: return false
        val hay = listOf(
            meta.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
            meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ).filterNotNull().joinToString(" ").lowercase()
        return hay.contains("notebooklm") || hay.contains("audio overview") || hay.contains("deep dive")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val lrcClient = LrcLibClient(appContext)
    private val artProcessor = ArtEffectProcessor()
    private val artCacheDir = File(appContext.cacheDir, "art_cache").apply { mkdirs() }
    private val maxArtCacheFiles = 100

    private val listeners = LinkedHashSet<Listener>()
    private var activeController: MediaController? = null
    // Watch ALL active allowed sessions (Spotify, YT Music, NotebookLM, etc.) so starting ANY app
    // immediately switches focus even when another app was previously paused.
    private val watchedControllers = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var lyricsJob: Job? = null
    private var artJob: Job? = null
    private var currentTrackKey: String? = null
    private var artLoadedForTrackKey: String? = null
    private var currentArtSignature: String? = null
    private var currentState: ActiveTrackState? = null
    private var sessionsRegistered = false

    private fun resyncPositionFromController() {
        val ctrl = activeController ?: return
        val pb = ctrl.playbackState ?: return
        val existing = currentState ?: return
        val stateCode = pb.state
        if (stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE) return
        val freshPos = pb.position.coerceAtLeast(0L)
        val freshElapsed = pb.lastPositionUpdateTime.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val freshSpeed = pb.playbackSpeed.takeIf { it > 0f } ?: 1.0f
        val updated = existing.copy(
            isPlaying = stateCode == PlaybackState.STATE_PLAYING,
            basePositionMs = freshPos,
            lastUpdateElapsedMs = freshElapsed,
            playbackSpeed = freshSpeed
        )
        currentState = updated
        notifyListeners(updated)
    }

    private val earlyResyncRunnable = Runnable { resyncPositionFromController() }
    private val settledResyncRunnable = Runnable { resyncPositionFromController() }

    private fun scheduleEarlyPositionResync() {
        mainHandler.removeCallbacks(earlyResyncRunnable)
        mainHandler.removeCallbacks(settledResyncRunnable)
        mainHandler.postDelayed(earlyResyncRunnable, 1500L)
        mainHandler.postDelayed(settledResyncRunnable, 4000L)
    }

    private val pauseTimeoutRunnable = Runnable {
        lyricsJob?.cancel()
        artJob?.cancel()
        currentTrackKey = null
        artLoadedForTrackKey = null
        currentArtSignature = null
        currentState = null
        notifyListeners(null)
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers ?: emptyList())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            try { handleControllerUpdate(activeController) } catch (_: Throwable) {}
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            try { handlePlaybackStateOnly(state) } catch (_: Throwable) {}
        }

        override fun onSessionDestroyed() {
            try { refreshActiveSessions() } catch (_: Throwable) {}
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        ensureRegistered()
        listener.onTrackStateChanged(currentState)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun onListenerConnected() {
        ensureRegistered()
        refreshActiveSessions()
    }

    fun refreshActiveSessions() {
        if (!MediaListenerService.isEnabled(appContext)) return
        try {
            val list = mediaSessionManager.getActiveSessions(MediaListenerService.componentName(appContext))
            selectController(list)
        } catch (_: SecurityException) {
        }
    }

    private fun ensureRegistered() {
        if (sessionsRegistered || !MediaListenerService.isEnabled(appContext)) return
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                MediaListenerService.componentName(appContext),
                mainHandler
            )
            sessionsRegistered = true
            refreshActiveSessions()
        } catch (_: SecurityException) {
        }
    }

    private fun syncWatchedControllers(allowed: List<MediaController>) {
        val allowedTokens = allowed.map { it.sessionToken }.toSet()
        val stale = watchedControllers.keys.filter { it !in allowedTokens }
        for (tok in stale) {
            watchedControllers.remove(tok)?.let { (ctrl, cb) ->
                try { ctrl.unregisterCallback(cb) } catch (_: Exception) {}
            }
        }
        for (ctrl in allowed) {
            val tok = ctrl.sessionToken
            if (tok !in watchedControllers) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val code = state?.state ?: PlaybackState.STATE_NONE
                        if ((code == PlaybackState.STATE_PLAYING || code == PlaybackState.STATE_BUFFERING) &&
                            activeController?.sessionToken != tok
                        ) {
                            mainHandler.post { refreshActiveSessions() }
                        }
                    }
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        val code = ctrl.playbackState?.state ?: PlaybackState.STATE_NONE
                        if ((code == PlaybackState.STATE_PLAYING || code == PlaybackState.STATE_BUFFERING) &&
                            activeController?.sessionToken != tok
                        ) {
                            mainHandler.post { refreshActiveSessions() }
                        }
                    }
                }
                try {
                    ctrl.registerCallback(cb, mainHandler)
                    watchedControllers[tok] = ctrl to cb
                } catch (_: Exception) {}
            }
        }
    }

    private fun selectController(controllers: List<MediaController>) {
        val allowed = controllers.filter { isAllowedSession(it) }
        syncWatchedControllers(allowed)
        val playing = allowed.firstOrNull {
            val st = it.playbackState?.state
            st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        }
        val chosen = playing ?: if (currentState != null) {
            allowed.firstOrNull { it.sessionToken == activeController?.sessionToken }
        } else {
            // Never resurrect a paused session when already in Circadian Idle;
            // still keep the first allowed controller attached only to listen for STATE_PLAYING.
            allowed.firstOrNull()
        }

        if (chosen?.sessionToken == activeController?.sessionToken) {
            if (chosen != null) handleControllerUpdate(chosen)
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = chosen

        if (chosen == null) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            mainHandler.removeCallbacks(earlyResyncRunnable)
            mainHandler.removeCallbacks(settledResyncRunnable)
            currentTrackKey = null
            artLoadedForTrackKey = null
            currentArtSignature = null
            currentState = null
            notifyListeners(null)
            return
        }

        chosen.registerCallback(controllerCallback, mainHandler)
        handleControllerUpdate(chosen)
    }

    private fun handlePlaybackStateOnly(pb: PlaybackState?) {
        val controller = activeController ?: return
        val stateCode = pb?.state ?: PlaybackState.STATE_NONE
        val isPlaying = stateCode == PlaybackState.STATE_PLAYING ||
            stateCode == PlaybackState.STATE_BUFFERING

        if (isPlaying) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
        } else if (stateCode == PlaybackState.STATE_PAUSED) {
            if (currentState == null) {
                // Already in Circadian Idle; never resurrect paused track
                return
            }
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            mainHandler.postDelayed(pauseTimeoutRunnable, pauseGraceMs)
        } else if (stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            currentTrackKey = null
            artLoadedForTrackKey = null
            currentArtSignature = null
            currentState = null
            notifyListeners(null)
            return
        }

        val existing = currentState
        if (existing == null) {
            handleControllerUpdate(controller)
            return
        }

        val updated = existing.copy(
            isPlaying = stateCode == PlaybackState.STATE_PLAYING,
            showLyrics = existing.lyrics.isNotEmpty() &&
                (stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_BUFFERING || stateCode == PlaybackState.STATE_PAUSED),
            basePositionMs = (pb?.position ?: existing.basePositionMs).coerceAtLeast(0L),
            lastUpdateElapsedMs = pb?.lastPositionUpdateTime?.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime(),
            playbackSpeed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1.0f
        )
        currentState = updated
        notifyListeners(updated)
    }

    private fun handleControllerUpdate(controller: MediaController?) {
        if (controller == null) return
        val meta = controller.metadata ?: return
        val pb = controller.playbackState
        val stateCode = pb?.state ?: PlaybackState.STATE_NONE

        if (stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            currentTrackKey = null
            artLoadedForTrackKey = null
            currentArtSignature = null
            currentState = null
            notifyListeners(null)
            return
        }

        val isPlaying = stateCode == PlaybackState.STATE_PLAYING ||
            stateCode == PlaybackState.STATE_BUFFERING
        if (!isPlaying && currentState == null) {
            // In Circadian Idle and track is paused: ignore completely until Play is pressed
            return
        }

        if (isPlaying) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
        } else if (stateCode == PlaybackState.STATE_PAUSED) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            mainHandler.postDelayed(pauseTimeoutRunnable, pauseGraceMs)
        }

        val rawTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val pkg = controller.packageName
        val trackKey = "$pkg|$rawArtist|$rawTitle"

        val rawCover: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val artUri: String? = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)

        val validRawCover = rawCover?.takeUnless { isSpotifyTargetPlaceholder(it) }
        val artSig = "$trackKey|${validRawCover != null}|${artUri ?: ""}"

        if (trackKey == currentTrackKey && currentState != null &&
            (artLoadedForTrackKey == trackKey || artSig == currentArtSignature)
        ) {
            handlePlaybackStateOnly(pb)
            return
        }

        val isNewTrack = trackKey != currentTrackKey
        currentTrackKey = trackKey
        currentArtSignature = artSig
        if (isNewTrack) {
            artLoadedForTrackKey = null
        }

        val clean = TitleSanitizer.clean(rawTitle, rawArtist)
        val basePos = (pb?.position ?: 0L).coerceAtLeast(0L)
        val updateElapsed = pb?.lastPositionUpdateTime?.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        val speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1.0f

        val isNotebook = pkg == notebookLmPkg || pkg in browserAudioPkgs

        if (isNewTrack) {
            scheduleEarlyPositionResync()
            if (pkg != youtubePkg) {
                currentState = ActiveTrackState(
                    trackKey = trackKey,
                    packageName = pkg,
                    title = clean.title,
                    artist = if (isNotebook) clean.artist.ifBlank { "NotebookLM • Audio Overview" } else clean.artist,
                    durationMs = durationMs,
                    processedArt = if (isNotebook) artProcessor.processNotebookStudioFallback(clean.title) else if (currentState?.isNotebookLm == true) null else currentState?.processedArt,
                    lyrics = emptyList(),
                    isPlaying = stateCode == PlaybackState.STATE_PLAYING,
                    showLyrics = false,
                    basePositionMs = basePos,
                    lastUpdateElapsedMs = updateElapsed,
                    playbackSpeed = speed,
                    isNotebookLm = isNotebook,
                    isFetchingLyrics = !isNotebook
                )
                notifyListeners(currentState)
            }

            lyricsJob?.cancel()
            if (!isNotebook) {
            lyricsJob = scope.launch {
                val lyrics = lrcClient.fetchSyncedLyrics(rawTitle, rawArtist, durationMs)
                if (trackKey != currentTrackKey) return@launch
                if (pkg == youtubePkg && lyrics.isEmpty()) return@launch

                val latest = currentState?.takeIf { it.trackKey == trackKey } ?: ActiveTrackState(
                    trackKey = trackKey,
                    packageName = pkg,
                    title = clean.title,
                    artist = clean.artist,
                    durationMs = durationMs,
                    processedArt = currentState?.processedArt,
                    lyrics = emptyList(),
                    isPlaying = stateCode == PlaybackState.STATE_PLAYING,
                    showLyrics = false,
                    basePositionMs = basePos,
                    lastUpdateElapsedMs = updateElapsed,
                    playbackSpeed = speed
                )
                val livePb = activeController?.playbackState
                val syncedPos = (livePb?.position ?: latest.basePositionMs).coerceAtLeast(0L)
                val syncedElapsed = livePb?.lastPositionUpdateTime?.takeIf { it > 0L } ?: latest.lastUpdateElapsedMs
                val syncedSpeed = livePb?.playbackSpeed?.takeIf { it > 0f } ?: latest.playbackSpeed
                currentState = latest.copy(
                    lyrics = lyrics,
                    showLyrics = lyrics.isNotEmpty(),
                    isFetchingLyrics = false,
                    basePositionMs = syncedPos,
                    lastUpdateElapsedMs = syncedElapsed,
                    playbackSpeed = syncedSpeed
                )
                notifyListeners(currentState)
            }
            }
        }

        // Prefer ART_URI -> validRawCover -> iTunes HD Cover Search fallback
        artJob?.cancel()
        artJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val fromUri = loadBitmapFromUriCached(artUri)?.takeUnless { isSpotifyTargetPlaceholder(it) }
                fromUri ?: validRawCover ?: if (isNotebook) null else fetchItunesArtworkCached(clean.title, clean.artist)
            } ?: return@launch

            if (trackKey != currentTrackKey) return@launch

            val processed = withContext(Dispatchers.Default) {
                artProcessor.process(bmp)
            }
            if (trackKey != currentTrackKey) return@launch
            if (pkg == youtubePkg && (currentState?.trackKey != trackKey)) return@launch

            artLoadedForTrackKey = trackKey
            currentState = (currentState ?: ActiveTrackState(
                trackKey = trackKey,
                packageName = pkg,
                title = clean.title,
                artist = clean.artist,
                durationMs = durationMs,
                processedArt = null,
                lyrics = emptyList(),
                isPlaying = stateCode == PlaybackState.STATE_PLAYING,
                showLyrics = false,
                basePositionMs = basePos,
                lastUpdateElapsedMs = updateElapsed,
                playbackSpeed = speed
            )).copy(processedArt = processed)
            notifyListeners(currentState)
        }
    }

    /**
     * Detects Spotify's generic pure-grayscale concentric target placeholder icon.
     */
    private fun isSpotifyTargetPlaceholder(bmp: Bitmap): Boolean {
        return try {
            val w = bmp.width
            val h = bmp.height
            if (w < 16 || h < 16) return false
            var grayCount = 0
            for (sy in 0 until 5) {
                for (sx in 0 until 5) {
                    val px = bmp.getPixel((w * (sx + 1)) / 6, (h * (sy + 1)) / 6)
                    val r = Color.red(px)
                    val g = Color.green(px)
                    val b = Color.blue(px)
                    if (maxOf(abs(r - g), abs(g - b), abs(r - b)) < 10) {
                        grayCount++
                    }
                }
            }
            val center = bmp.getPixel(w / 2, h / 2)
            val cMax = maxOf(Color.red(center), Color.green(center), Color.blue(center))
            val cMin = minOf(Color.red(center), Color.green(center), Color.blue(center))
            grayCount >= 24 && (cMax - cMin) < 10
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchItunesArtworkCached(title: String, artist: String): Bitmap? {
        if (title.isBlank()) return null
        val cacheKey = sha256("itunes|$artist|$title")
        val cached = File(artCacheDir, "$cacheKey.jpg")
        if (cached.exists()) {
            cached.setLastModified(System.currentTimeMillis())
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }
        return try {
            val q = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$q&entity=song&limit=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val art100 = results.getJSONObject(0).optString("artworkUrl100")
            if (art100.isBlank()) return null
            val art600 = art100.replace("100x100bb", "600x600bb")
            val bmp = loadBitmapFromUriCached(art600) ?: return null
            FileOutputStream(cached).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun loadBitmapFromUriCached(uriStr: String?): Bitmap? {
        if (uriStr.isNullOrBlank()) return null
        val key = sha256(uriStr)
        val cached = File(artCacheDir, "$key.jpg")
        if (cached.exists()) {
            cached.setLastModified(System.currentTimeMillis())
            BitmapFactory.decodeFile(cached.absolutePath)?.let { return it }
        }

        return try {
            val rawBytes = if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                val conn = (URL(uriStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                conn.inputStream.use { it.readBytes() }
            } else {
                appContext.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
            } ?: return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            var inSample = 1
            while (bounds.outWidth / (inSample * 2) >= 720 &&
                bounds.outHeight / (inSample * 2) >= 720
            ) {
                inSample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null

            trimArtCacheIfNeeded()
            FileOutputStream(cached).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun trimArtCacheIfNeeded() {
        val files = artCacheDir.listFiles() ?: return
        if (files.size <= maxArtCacheFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxArtCacheFiles)
            .forEach { it.delete() }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun notifyListeners(state: ActiveTrackState?) {
        listeners.forEach { it.onTrackStateChanged(state) }
    }

    companion object {
        @Volatile private var instance: MediaSessionTracker? = null

        fun getInstance(context: Context): MediaSessionTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaSessionTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
