package com.beatwall.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class ActiveTrackState(
    val trackKey: String,
    val packageName: String,
    val title: String,
    val artist: String,
    val processedArt: ProcessedArt?,
    val lyrics: List<LyricLine>,
    val isPlaying: Boolean,
    val basePositionMs: Long,
    val lastUpdateElapsedMs: Long,
    val playbackSpeed: Float
) {
    fun currentPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return basePositionMs
        val delta = (nowElapsedMs - lastUpdateElapsedMs).coerceAtLeast(0L)
        return (basePositionMs + (delta * playbackSpeed).toLong()).coerceAtLeast(0L)
    }
}

class MediaSessionTracker private constructor(private val appContext: Context) {

    interface Listener {
        fun onTrackStateChanged(state: ActiveTrackState?)
    }

    private val allowedPackages = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube"
    )
    private val youtubePkg = "com.google.android.youtube"
    private val pauseGraceMs = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val lrcClient = LrcLibClient(appContext)
    private val artProcessor = ArtEffectProcessor()

    private val listeners = LinkedHashSet<Listener>()
    private var activeController: MediaController? = null
    private var fetchJob: Job? = null
    private var currentTrackKey: String? = null
    private var currentState: ActiveTrackState? = null
    private var sessionsRegistered = false

    private val pauseTimeoutRunnable = Runnable {
        currentTrackKey = null
        currentState = null
        notifyListeners(null)
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectController(controllers ?: emptyList())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleControllerUpdate(activeController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateOnly(state)
        }

        override fun onSessionDestroyed() {
            refreshActiveSessions()
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

    private fun selectController(controllers: List<MediaController>) {
        val allowed = controllers.filter { it.packageName in allowedPackages }
        val chosen = allowed.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: allowed.firstOrNull()

        if (chosen?.sessionToken == activeController?.sessionToken) {
            if (chosen != null) handleControllerUpdate(chosen)
            return
        }

        activeController?.unregisterCallback(controllerCallback)
        activeController = chosen

        if (chosen == null) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            currentTrackKey = null
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
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            mainHandler.postDelayed(pauseTimeoutRunnable, pauseGraceMs)
        } else if (stateCode == PlaybackState.STATE_STOPPED || stateCode == PlaybackState.STATE_NONE) {
            mainHandler.removeCallbacks(pauseTimeoutRunnable)
            currentTrackKey = null
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
            currentState = null
            notifyListeners(null)
            return
        }

        val isPlaying = stateCode == PlaybackState.STATE_PLAYING
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
        val durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pkg = controller.packageName
        val trackKey = "$pkg|$rawArtist|$rawTitle"

        if (trackKey == currentTrackKey && currentState != null) {
            handlePlaybackStateOnly(pb)
            return
        }

        currentTrackKey = trackKey
        fetchJob?.cancel()

        val rawCover: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val clean = TitleSanitizer.clean(rawTitle, rawArtist)
        val basePos = (pb?.position ?: 0L).coerceAtLeast(0L)
        val updateElapsed = pb?.lastPositionUpdateTime?.takeIf { it > 0L }
            ?: SystemClock.elapsedRealtime()
        val speed = pb?.playbackSpeed?.takeIf { it > 0f } ?: 1.0f

        fetchJob = scope.launch {
            val lyrics = lrcClient.fetchSyncedLyrics(rawTitle, rawArtist, durationMs)
            if (trackKey != currentTrackKey) return@launch

            // YouTube Music-Only Rule: ignore lectures/non-music videos with no synced lyrics
            if (pkg == youtubePkg && lyrics.isEmpty()) {
                currentState = null
                notifyListeners(null)
                return@launch
            }

            val processed = kotlinx.coroutines.withContext(Dispatchers.Default) {
                rawCover?.let { artProcessor.process(it) }
            }
            if (trackKey != currentTrackKey) return@launch

            val nextState = ActiveTrackState(
                trackKey = trackKey,
                packageName = pkg,
                title = clean.title,
                artist = clean.artist,
                processedArt = processed,
                lyrics = lyrics,
                isPlaying = isPlaying,
                basePositionMs = basePos,
                lastUpdateElapsedMs = updateElapsed,
                playbackSpeed = speed
            )
            currentState = nextState
            notifyListeners(nextState)
        }
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
