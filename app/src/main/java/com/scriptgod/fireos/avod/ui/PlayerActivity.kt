package com.scriptgod.fireos.avod.ui

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
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
import com.scriptgod.fireos.avod.model.PlaybackQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaCodecList
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
        const val EXTRA_MATERIAL_TYPE = "extra_material_type"

        // Widevine UUID
        private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    }

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var trackButtons: LinearLayout
    private lateinit var tvPlaybackTitle: TextView
    private lateinit var tvPlaybackStatus: TextView
    private lateinit var tvVideoFormat: TextView
    private lateinit var tvPlaybackHint: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnSubtitle: Button

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var authService: AmazonAuthService
    private lateinit var apiService: AmazonApiService

    private val scopeJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var heartbeatJob: Job? = null
    private var currentAsin: String = ""
    private var currentMaterialType: String = "Feature"
    private var currentQuality: PlaybackQuality = PlaybackQuality.HD
    private var h265FallbackAttempted: Boolean = false
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
        tvPlaybackTitle = findViewById(R.id.tv_playback_title)
        tvPlaybackStatus = findViewById(R.id.tv_playback_status)
        tvVideoFormat = findViewById(R.id.tv_video_format)
        tvPlaybackHint = findViewById(R.id.tv_playback_hint)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitle = findViewById(R.id.btn_subtitle)
        tvPlaybackTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: "Now Playing"
        tvPlaybackHint.text = "Press MENU for tracks. Press Back to leave playback."
        applyDeviceOverlayTuning()

        // Fix D-pad seek increment: default is duration/20 (~6 min on a 2h film).
        // 10 s per key press matches standard TV remote behaviour.
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress
        )?.setKeyTimeIncrement(10_000L)

        // Sync trackButtons visibility with the PlayerView controller so they
        // always appear and disappear together.
        playerView.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    trackButtons.visibility = View.VISIBLE
                    UiMotion.revealFresh(trackButtons)
                    trackButtons.post {
                        if (!btnAudio.isFocused && !btnSubtitle.isFocused) {
                            btnAudio.requestFocus()
                        }
                    }
                } else {
                    trackButtons.animate().cancel()
                    trackButtons.animate()
                        .alpha(0f)
                        .translationY(-trackButtons.resources.getDimension(R.dimen.page_motion_offset) / 2f)
                        .setDuration(140L)
                        .withEndAction {
                            trackButtons.visibility = View.GONE
                            trackButtons.alpha = 1f
                            trackButtons.translationY = 0f
                        }
                        .start()
                }
            }
        )

        btnAudio.setOnClickListener { showTrackSelectionDialog(C.TRACK_TYPE_AUDIO) }
        btnSubtitle.setOnClickListener { showTrackSelectionDialog(C.TRACK_TYPE_TEXT) }
        btnAudio.nextFocusDownId = R.id.btn_subtitle
        btnSubtitle.nextFocusUpId = R.id.btn_audio

        val asin = intent.getStringExtra(EXTRA_ASIN)
            ?: run { showError("No ASIN provided"); return }
        currentAsin = asin

        resumePrefs = getSharedPreferences("resume_positions", MODE_PRIVATE)

        val tokenFile = LoginActivity.findTokenFile(this)
            ?: run { finish(); return }
        authService = AmazonAuthService(tokenFile)
        apiService = AmazonApiService(authService)

        // Default to "Feature"; caller may pass "Trailer" via EXTRA_MATERIAL_TYPE.
        // GTI-format ASINs (amzn1.dv.gti.*) reject "Episode" with PRSInvalidRequest.
        // "Feature" works for both movies and episodes with GTI ASINs.
        val materialType = intent.getStringExtra(EXTRA_MATERIAL_TYPE) ?: "Feature"
        loadAndPlay(asin, materialType)
    }

    /** Returns true if this device has any H265/HEVC video decoder. */
    private fun deviceSupportsH265(): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }

    /** Returns true if the connected display reports HDR support via HDMI EDID. */
    @Suppress("DEPRECATION")
    private fun displaySupportsHdr(): Boolean {
        val caps = windowManager.defaultDisplay.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.isNotEmpty()
    }

    /**
     * Resolves the effective PlaybackQuality for this playback session.
     * UHD_HDR requires both an H265 decoder and an HDR-capable display; falls back to HD.
     * HD_H265 (SDR) only needs an H265 decoder; falls back to HD on devices without HEVC.
     */
    private fun resolveQuality(): PlaybackQuality {
        val pref = getSharedPreferences("settings", MODE_PRIVATE)
            .getString(PlaybackQuality.PREF_KEY, null)
        val requested = PlaybackQuality.fromPrefValue(pref)
        if (requested == PlaybackQuality.UHD_HDR) {
            if (!deviceSupportsH265()) {
                android.widget.Toast.makeText(this, "H265 not supported — using HD H264", android.widget.Toast.LENGTH_LONG).show()
                return PlaybackQuality.HD
            }
            if (!displaySupportsHdr()) {
                android.widget.Toast.makeText(this, "Display does not support HDR — using HD H264", android.widget.Toast.LENGTH_LONG).show()
                return PlaybackQuality.HD
            }
        }
        if (requested == PlaybackQuality.HD_H265 && !deviceSupportsH265()) {
            android.widget.Toast.makeText(this, "H265 not supported — using HD H264", android.widget.Toast.LENGTH_LONG).show()
            return PlaybackQuality.HD
        }
        return requested
    }

    private fun loadAndPlay(asin: String, materialType: String = "Feature", qualityOverride: PlaybackQuality? = null) {
        progressBar.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvVideoFormat.text = ""   // clear stale label until new player reports its format
        playerView.useController = true

        val quality = qualityOverride ?: resolveQuality()
        currentMaterialType = materialType
        currentQuality = quality
        updatePlaybackStatus()
        if (qualityOverride == null) h265FallbackAttempted = false  // fresh start resets guard
        Log.i(TAG, "Playback quality: ${quality.videoQuality} codec=${quality.codecOverride} hdr=${quality.hdrOverride}")

        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    apiService.detectTerritory()
                    apiService.getPlaybackInfo(asin, materialType, quality)
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

        // Trailers always start from the beginning — don't restore the movie's resume position
        val resumeMs = if (currentMaterialType == "Trailer") 0L else resumePrefs.getLong(currentAsin, 0L)

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

    /**
     * Reads the format currently being decoded by the video renderer.
     * player.videoFormat reflects the live ABR tier, not just the initially selected track.
     * Called from onVideoSizeChanged (ABR switch) and onTracksChanged (track swap / fallback).
     */
    private fun updateVideoFormatLabel() {
        val fmt = player?.videoFormat ?: run { tvVideoFormat.text = ""; return }
        val res = when {
            fmt.height >= 2160 -> "4K"
            fmt.height >= 1080 -> "1080p"
            fmt.height >= 720  -> "720p"
            fmt.height > 0     -> "${fmt.height}p"
            else               -> ""
        }
        val codec = when {
            fmt.sampleMimeType?.contains("hevc", ignoreCase = true) == true -> "H265"
            fmt.sampleMimeType?.contains("avc",  ignoreCase = true) == true -> "H264"
            else -> ""
        }
        // Primary: ExoPlayer colorInfo (populated when MPD has colorimetry attributes).
        // Fallback: codec string profile — Amazon uses hvc1.2.* / hev1.2.* (HEVC Main 10)
        // exclusively for HDR10 content; Dolby Vision containers start with dvhe/dvav.
        val codecs = fmt.codecs ?: ""
        Log.i(TAG, "Video format: ${fmt.height}p mime=${fmt.sampleMimeType} codecs=$codecs colorTransfer=${fmt.colorInfo?.colorTransfer}")
        val hdr = when {
            fmt.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            fmt.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG    -> "HLG"
            codecs.startsWith("dvhe") || codecs.startsWith("dvav")  -> "DV"
            codecs.startsWith("hvc1.2") || codecs.startsWith("hev1.2") -> "HDR10"
            else -> "SDR"
        }
        tvVideoFormat.text = listOf(res, codec, hdr).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun updatePlaybackStatus() {
        val materialLabel = if (currentMaterialType == "Trailer") "Trailer" else "Playback"
        val qualityLabel = when (currentQuality) {
            PlaybackQuality.UHD_HDR -> "4K HDR preset"
            PlaybackQuality.HD_H265 -> "HD H265 preset"
            PlaybackQuality.HD -> "HD H264 preset"
            else -> "Playback preset"
        }
        tvPlaybackStatus.text = "$materialLabel  ·  $qualityLabel"
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> progressBar.visibility = View.VISIBLE
                Player.STATE_READY -> {
                    progressBar.visibility = View.GONE
                    tvError.visibility = View.GONE
                    playerView.useController = true
                    updateVideoFormatLabel()
                    if (!streamReportingStarted) {
                        streamReportingStarted = true
                        startStreamReporting()
                    }
                }
                Player.STATE_ENDED -> {
                    stopStreamReporting()
                    // Only mark as fully watched for real content, not trailers
                    if (currentMaterialType != "Trailer") {
                        resumePrefs.edit().putLong(currentAsin, -1L).apply()
                    }
                }
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!streamReportingStarted) return
            if (isPlaying) {
                startHeartbeat()
            } else if (player?.playbackState == Player.STATE_READY) {
                // Paused — PlayerView keeps its controller visible automatically
                sendProgressEvent("PAUSE")
                heartbeatJob?.cancel()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateVideoFormatLabel()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // Fires on every ABR bitrate switch — reflects the renderer's actual live format
            updateVideoFormatLabel()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.errorCodeName}", error)
            stopStreamReporting()

            // Amazon's CDN returns HTTP 400 for H265 segment URLs on some titles.
            // The UHD manifest's H264 tracks only reach 720p — must re-fetch using the HD
            // quality preset (H264-only manifest) to get 1080p H264. detectTerritory() is
            // cached so the only overhead is one GetPlaybackResources round-trip.
            if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                currentQuality.codecOverride.contains("H265") &&
                !h265FallbackAttempted) {
                h265FallbackAttempted = true
                val lastPos = player?.currentPosition ?: 0L
                Log.w(TAG, "H265 CDN returned 400 — re-fetching H264 manifest, resume at ${lastPos}ms")
                android.widget.Toast.makeText(
                    this@PlayerActivity,
                    "H265 not available for this title — switching to H264",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                // Save position so setupPlayer() can seek to it via resumePrefs
                if (lastPos > 10_000) resumePrefs.edit().putLong(currentAsin, lastPos).apply()
                player?.release()
                player = null
                streamReportingStarted = false
                watchSessionId = UUID.randomUUID().toString()
                loadAndPlay(currentAsin, currentMaterialType, PlaybackQuality.HD)
                return
            }

            showError("Playback error: ${error.errorCodeName}\n${error.message}")
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
        if (currentMaterialType == "Trailer") return  // trailers never affect watch progress
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

        // One entry per group — tracks within a group are bitrate variants of the
        // same content; ExoPlayer selects the best one adaptively.
        // Build a base label (language + channels) for each group first, then add a
        // codec qualifier only when two groups share the same base label (e.g.
        // "German (5.1)" via both AAC and Dolby Digital Plus).
        data class GroupInfo(val gi: Int, val repIdx: Int, val baseLabel: String, val codec: String)

        fun codecLabel(mimeType: String?): String = when {
            mimeType == null -> ""
            mimeType.contains("ec-3", ignoreCase = true) ||
            mimeType.contains("eac3", ignoreCase = true) -> "Dolby"
            mimeType.contains("ac-3", ignoreCase = true) ||
            mimeType.contains("ac3",  ignoreCase = true) -> "Dolby"
            mimeType.contains("mp4a", ignoreCase = true) ||
            mimeType.contains("aac",  ignoreCase = true) -> "AAC"
            else -> ""
        }

        val groupInfos = groups.mapIndexed { gi, group ->
            val repIdx = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                ?: (0 until group.length).maxByOrNull { group.getTrackFormat(it).bitrate }
                ?: 0
            val format = group.getTrackFormat(repIdx)
            val lang = format.language
                ?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
                ?: "Unknown"
            val baseLabel = when {
                format.label != null -> format.label!!
                trackType == C.TRACK_TYPE_AUDIO -> {
                    val channels = when (format.channelCount) {
                        6 -> "5.1"; 8 -> "7.1"; 2 -> "Stereo"; 1 -> "Mono"
                        else -> "${format.channelCount}ch"
                    }
                    "$lang ($channels)"
                }
                else -> lang
            }
            GroupInfo(gi, repIdx, baseLabel, codecLabel(format.sampleMimeType))
        }

        // Count how many groups share each base label — used to decide if a codec
        // qualifier is needed (e.g. "German (5.1) · Dolby" vs "German (5.1) · AAC")
        val labelCount = groupInfos.groupingBy { it.baseLabel }.eachCount()

        // Deduplicate by final label: keep the currently-selected group, or else
        // the highest-bitrate group — so ExoPlayer's adaptive pick is the best one.
        val bestByLabel = linkedMapOf<String, GroupInfo>()
        for (info in groupInfos) {
            val name = if (labelCount.getValue(info.baseLabel) > 1 && info.codec.isNotEmpty())
                "${info.baseLabel} · ${info.codec}" else info.baseLabel
            val existing = bestByLabel[name]
            val isSelected = groups[info.gi].isSelected
            val existingSelected = existing != null && groups[existing.gi].isSelected
            val betterBitrate = existing == null ||
                groups[info.gi].getTrackFormat(info.repIdx).bitrate >
                groups[existing.gi].getTrackFormat(existing.repIdx).bitrate
            if (existing == null || (isSelected && !existingSelected) || (!existingSelected && betterBitrate)) {
                bestByLabel[name] = info
            }
        }

        for ((name, info) in bestByLabel) {
            labels.add(name)
            trackIndices.add(Pair(info.gi, info.repIdx))
            if (groups[info.gi].isSelected) selectedIndex = labels.size - 1
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
                    // Enable track type; select the group adaptively (empty list =
                    // ExoPlayer picks the best bitrate within the group automatically)
                    val group = groups[gi]
                    val builder = p.trackSelectionParameters.buildUpon()
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, emptyList())
                    )
                    p.trackSelectionParameters = builder.build()
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Toggle both the player controller and trackButtons (synced via listener)
            if (playerView.isControllerFullyVisible) {
                playerView.hideController()
            } else {
                playerView.showController()
                btnAudio.post { btnAudio.requestFocus() }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyDeviceOverlayTuning() {
        val isAmazonDevice = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        if (!isAmazonDevice) return

        updateMargins(trackButtons, topDp = 20, endDp = 28)
        updateMargins(tvError, startDp = 36, bottomDp = 44)
        tvError.maxLines = 4
    }

    private fun updateMargins(view: View, startDp: Int? = null, topDp: Int? = null, endDp: Int? = null, bottomDp: Int? = null) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        startDp?.let { params.marginStart = (it * density).toInt() }
        topDp?.let { params.topMargin = (it * density).toInt() }
        endDp?.let { params.marginEnd = (it * density).toInt() }
        bottomDp?.let { params.bottomMargin = (it * density).toInt() }
        view.layoutParams = params
    }

    private fun showError(message: String) {
        progressBar.visibility = View.GONE
        playerView.hideController()
        playerView.useController = false
        trackButtons.visibility = View.GONE
        tvError.text = "Playback unavailable\n${friendlyError(message)}"
        tvError.visibility = View.VISIBLE
    }

    private fun friendlyError(message: String): String {
        return when {
            message.contains("DRM_LICENSE_ACQUISITION_FAILED") ->
                "The current stream could not retrieve a playback license. Go back and try another title or retry later."
            message.contains("No widevine2License.license field") ->
                "The service returned an incomplete license response for this title."
            else -> message
        }
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
