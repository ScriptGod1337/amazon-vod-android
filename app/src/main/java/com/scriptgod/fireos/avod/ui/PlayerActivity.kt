package com.scriptgod.fireos.avod.ui

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.drm.AmazonLicenseService
import com.scriptgod.fireos.avod.model.PlaybackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Full-screen video player using Media3 ExoPlayer with Widevine DRM.
 * Implements stream reporting (UpdateStream) per decisions.md Decision 10.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_ASIN = "extra_asin"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT_TYPE = "extra_content_type"

        // Widevine UUID
        private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

        private const val OVERLAY_TIMEOUT_MS = 3_000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var trackButtons: LinearLayout
    private lateinit var btnAudio: Button
    private lateinit var btnSubtitle: Button

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var authService: AmazonAuthService
    private lateinit var apiService: AmazonApiService

    private val hideOverlayRunnable = Runnable { trackButtons.visibility = View.GONE }

    private val scopeJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var heartbeatJob: Job? = null
    private var currentAsin: String = ""
    private var watchSessionId: String = UUID.randomUUID().toString()
    private var pesSessionToken: String = ""
    private var heartbeatIntervalMs: Long = 60_000
    private var streamReportingStarted: Boolean = false
    private var resumeSeeked: Boolean = false
    private lateinit var resumePrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide action bar if present
        supportActionBar?.hide()

        // Immersive sticky fullscreen — hide status bar and navigation
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        trackButtons = findViewById(R.id.track_buttons)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitle = findViewById(R.id.btn_subtitle)

        btnAudio.setOnClickListener { showTrackSelectionDialog(C.TRACK_TYPE_AUDIO) }
        btnSubtitle.setOnClickListener { showTrackSelectionDialog(C.TRACK_TYPE_TEXT) }

        val asin = intent.getStringExtra(EXTRA_ASIN)
            ?: run { showError("No ASIN provided"); return }
        currentAsin = asin

        resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run { finish(); return }
        authService = AmazonAuthService(tokenFile)
        apiService = AmazonApiService(authService)

        // Always use "Feature" as videoMaterialType for GetPlaybackResources.
        // GTI-format ASINs (amzn1.dv.gti.*) reject "Episode" with PRSInvalidRequest.
        // "Feature" works for both movies and episodes with GTI ASINs.
        loadAndPlay(asin)
    }

    private fun loadAndPlay(asin: String, materialType: String = "Feature") {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE

        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    apiService.detectTerritory()
                    apiService.getPlaybackInfo(asin, materialType)
                }
                setupPlayer(info)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playback info: ${e.message}", e)
                showError("Playback error: ${e.message}")
            }
        }
    }

    /**
     * Sets up ExoPlayer with DASH + Widevine DRM.
     * Mirrors decisions.md Decision 4 and Phase 4 instructions.
     */
    private fun setupPlayer(info: PlaybackInfo) {
        if (isDestroyed || isFinishing) return
        val licenseCallback = AmazonLicenseService(authService, info.licenseUrl)

        val drmSessionManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(licenseCallback)

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            authService.buildAuthenticatedClient()
        )

        val mediaItem = MediaItem.Builder()
            .setUri(info.manifestUrl)
            .build()

        val dashSource = DashMediaSource.Factory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManager.asDrmSessionManagerProvider())
            .createMediaSource(mediaItem)

        // External subtitle tracks via SingleSampleMediaSource (DashMediaSource ignores SubtitleConfiguration)
        Log.w(TAG, "Subtitle tracks from API: ${info.subtitleTracks.size}")
        val subtitleSources = info.subtitleTracks.map { sub ->
            val subConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.url))
                .setMimeType(MimeTypes.APPLICATION_TTML)
                .setLanguage(sub.languageCode)
                .setLabel(buildSubtitleLabel(sub.languageCode, sub.type))
                .setSelectionFlags(if (sub.type == "forced") C.SELECTION_FLAG_FORCED else 0)
                .build()
            SingleSampleMediaSource.Factory(dataSourceFactory)
                .createMediaSource(subConfig, C.TIME_UNSET)
        }

        val mediaSource = if (subtitleSources.isNotEmpty()) {
            MergingMediaSource(dashSource, *subtitleSources.toTypedArray())
        } else {
            dashSource
        }

        val selector = DefaultTrackSelector(this)
        trackSelector = selector

        val resumeMs = resumePrefs.getLong(currentAsin, 0L)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.addListener(playerListener)
                exoPlayer.prepare()
                if (resumeMs > 10_000 && resumeMs > 0) {
                    exoPlayer.seekTo(resumeMs)
                    resumeSeeked = true
                }
                exoPlayer.playWhenReady = true
            }

        progressBar.visibility = View.GONE
    }

    private fun buildSubtitleLabel(langCode: String, type: String): String {
        val lang = java.util.Locale.forLanguageTag(langCode).displayLanguage
        return when (type) {
            "sdh" -> "$lang [SDH]"
            "forced" -> "$lang [Forced]"
            else -> lang
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> progressBar.visibility = View.VISIBLE
                Player.STATE_READY -> {
                    progressBar.visibility = View.GONE
                    if (!streamReportingStarted) {
                        streamReportingStarted = true
                        startStreamReporting()
                    }
                }
                Player.STATE_ENDED -> {
                    stopStreamReporting()
                    // Finished watching — save sentinel so cards show "fully watched"
                    resumePrefs.edit().putLong(currentAsin, -1L).apply()
                }
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!streamReportingStarted) return
            if (isPlaying) {
                // Resumed from pause — restart heartbeat and hide overlay
                startHeartbeat()
                hideOverlay()
            } else if (player?.playbackState == Player.STATE_READY) {
                // Paused — show overlay until play resumes
                sendProgressEvent("PAUSE")
                heartbeatJob?.cancel()
                showOverlay(autoHide = false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            showError("Playback error: ${error.errorCodeName}\n${error.message}")
            stopStreamReporting()
        }
    }

    private fun startStreamReporting() {
        val positionSecs = currentPositionSecs()
        scope.launch(Dispatchers.IO) {
            // UpdateStream START
            val interval = apiService.updateStream(currentAsin, "START", positionSecs, watchSessionId)
            heartbeatIntervalMs = interval * 1000L

            // PES V2 StartSession
            val (token, pesInterval) = apiService.pesStartSession(currentAsin, positionSecs)
            pesSessionToken = token
            // Use the shorter of the two intervals
            if (pesInterval > 0) {
                heartbeatIntervalMs = minOf(heartbeatIntervalMs, pesInterval * 1000L)
            }
            Log.w(TAG, "Stream reporting started, heartbeat=${heartbeatIntervalMs}ms")
        }
        startHeartbeat()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay(heartbeatIntervalMs)
            while (true) {
                sendProgressEvent("PLAY")
                delay(heartbeatIntervalMs)
            }
        }
    }

    private fun sendProgressEvent(event: String) {
        val positionSecs = currentPositionSecs()
        scope.launch(Dispatchers.IO) {
            // UpdateStream
            val interval = apiService.updateStream(currentAsin, event, positionSecs, watchSessionId)
            val newIntervalMs = interval * 1000L
            if (newIntervalMs != heartbeatIntervalMs) {
                heartbeatIntervalMs = newIntervalMs
                Log.w(TAG, "Heartbeat interval updated to ${heartbeatIntervalMs}ms")
            }

            // PES V2 UpdateSession
            if (pesSessionToken.isNotEmpty()) {
                val (token, _) = apiService.pesUpdateSession(pesSessionToken, event, positionSecs, currentAsin)
                if (token.isNotEmpty()) pesSessionToken = token
            }
        }
    }

    private fun stopStreamReporting() {
        heartbeatJob?.cancel()
        val positionSecs = currentPositionSecs()
        scope.launch(Dispatchers.IO) {
            apiService.updateStream(currentAsin, "STOP", positionSecs, watchSessionId)
            if (pesSessionToken.isNotEmpty()) {
                apiService.pesStopSession(pesSessionToken, positionSecs, currentAsin)
                pesSessionToken = ""
            }
        }
    }

    private fun currentPositionSecs(): Long = (player?.currentPosition ?: 0) / 1000

    private fun saveResumePosition() {
        val p = player ?: return
        val posMs = p.currentPosition
        val durMs = p.duration
        if (posMs < 10_000) return // too early, don't save
        if (durMs > 0 && posMs >= durMs * 9 / 10) {
            // >= 90% watched — save sentinel so cards show "fully watched"
            resumePrefs.edit().putLong(currentAsin, -1L).apply()
        } else {
            resumePrefs.edit().putLong(currentAsin, posMs).apply()
        }
    }

    private fun showTrackSelectionDialog(trackType: Int) {
        val p = player ?: return
        val tracks = p.currentTracks
        val typeName = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"

        val groups = tracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) {
            android.widget.Toast.makeText(this, "No $typeName tracks available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val labels = mutableListOf<String>()
        val trackIndices = mutableListOf<Pair<Int, Int>>() // groupIndex, trackIndex
        var selectedIndex = -1

        // "Off" option for subtitles
        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Off")
            trackIndices.add(Pair(-1, -1))
            // Check if all text tracks are disabled
            val anySelected = groups.any { it.isSelected }
            if (!anySelected) selectedIndex = 0
        }

        for ((gi, group) in groups.withIndex()) {
            for (ti in 0 until group.length) {
                val format = group.getTrackFormat(ti)
                val lang = format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage } ?: "Unknown"
                val label = format.label
                val name = when {
                    label != null -> label
                    trackType == C.TRACK_TYPE_AUDIO -> {
                        val channels = when (format.channelCount) {
                            6 -> "5.1"
                            8 -> "7.1"
                            2 -> "Stereo"
                            1 -> "Mono"
                            else -> "${format.channelCount}ch"
                        }
                        "$lang ($channels)"
                    }
                    else -> lang
                }
                labels.add(name)
                trackIndices.add(Pair(gi, ti))
                if (group.isTrackSelected(ti)) {
                    selectedIndex = labels.size - 1
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(typeName)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                val (gi, ti) = trackIndices[which]
                if (gi == -1) {
                    // "Off" — disable all text tracks
                    val builder = p.trackSelectionParameters.buildUpon()
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    p.trackSelectionParameters = builder.build()
                } else {
                    // Enable track type and select specific track
                    val group = groups[gi]
                    val builder = p.trackSelectionParameters.buildUpon()
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, ti)
                    )
                    p.trackSelectionParameters = builder.build()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showOverlay(autoHide: Boolean) {
        trackButtons.removeCallbacks(hideOverlayRunnable)
        trackButtons.visibility = View.VISIBLE
        if (autoHide) trackButtons.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun hideOverlay() {
        trackButtons.removeCallbacks(hideOverlayRunnable)
        trackButtons.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (trackButtons.visibility == View.VISIBLE) {
                hideOverlay()
            } else {
                showOverlay(autoHide = player?.isPlaying == true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        saveResumePosition()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        saveResumePosition()
        if (streamReportingStarted) {
            stopStreamReporting()
            streamReportingStarted = false
        }
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        trackButtons.removeCallbacks(hideOverlayRunnable)
        scopeJob.cancel()
        player?.release()
        player = null
    }
}

// Extension to convert DrmSessionManager to DrmSessionManagerProvider
@UnstableApi
private fun DefaultDrmSessionManager.asDrmSessionManagerProvider(): DrmSessionManagerProvider {
    val manager = this
    return DrmSessionManagerProvider { manager }
}
