package com.scriptgod.fireos.avod.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
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
    }

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private var player: ExoPlayer? = null
    private lateinit var authService: AmazonAuthService
    private lateinit var apiService: AmazonApiService

    private val scope = CoroutineScope(Dispatchers.Main + Job())
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

        val asin = intent.getStringExtra(EXTRA_ASIN)
            ?: run { showError("No ASIN provided"); return }
        currentAsin = asin

        resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)

        val tokenFile = File("/data/local/tmp/.device-token")
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

        val mediaSource = DashMediaSource.Factory(dataSourceFactory)
            .setDrmSessionManagerProvider(drmSessionManager.asDrmSessionManagerProvider())
            .createMediaSource(MediaItem.fromUri(info.manifestUrl))

        val resumeMs = resumePrefs.getLong(currentAsin, 0L)

        player = ExoPlayer.Builder(this, renderersFactory)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.addListener(playerListener)
                exoPlayer.prepare()
                if (resumeMs > 10_000) {
                    exoPlayer.seekTo(resumeMs)
                    resumeSeeked = true
                }
                exoPlayer.playWhenReady = true
            }

        progressBar.visibility = View.GONE
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
                    // Finished watching — clear resume position
                    resumePrefs.edit().remove(currentAsin).apply()
                }
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!streamReportingStarted) return
            if (isPlaying) {
                // Resumed from pause — restart heartbeat
                startHeartbeat()
            } else if (player?.playbackState == Player.STATE_READY) {
                // Paused (not buffering/ended)
                sendProgressEvent("PAUSE")
                heartbeatJob?.cancel()
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
            // >= 90% watched — clear (fully watched)
            resumePrefs.edit().remove(currentAsin).apply()
        } else {
            resumePrefs.edit().putLong(currentAsin, posMs).apply()
        }
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
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
        player?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
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
