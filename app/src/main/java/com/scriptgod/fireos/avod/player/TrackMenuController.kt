package com.scriptgod.fireos.avod.player

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Owns the audio/subtitle track selection dialogs and the visibility sync between
 * the track-button bar and the PlayerView controller overlay.
 */
@UnstableApi
class TrackMenuController(
    private val context: Context,
    private val trackButtons: LinearLayout,
    private val btnAudio: Button,
    private val btnSubtitle: Button,
    private val audioTrackResolver: AudioTrackResolver,
    private val playerProvider: () -> ExoPlayer?
) {
    private var currentTrackDialog: AlertDialog? = null
    private var controllerVisible = false
    private val handler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable {
        trackButtons.clearFocus()
        trackButtons.visibility = View.GONE
        currentTrackDialog?.dismiss()
        currentTrackDialog = null
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (controllerVisible) {
                if (trackButtons.visibility != View.VISIBLE) {
                    trackButtons.alpha = 1f
                    trackButtons.visibility = View.VISIBLE
                }
                handler.postDelayed(this, 120L)
            } else {
                hideRunnable.run()
            }
        }
    }

    fun onControllerVisibilityChanged(visible: Boolean) {
        controllerVisible = visible
        handler.removeCallbacks(syncRunnable)
        if (visible) syncRunnable.run() else hideRunnable.run()
    }

    fun updateTrackButtonLabels(tracks: Tracks? = playerProvider()?.currentTracks) {
        val t = tracks ?: run { btnAudio.text = "Audio"; btnSubtitle.text = "Subtitles"; return }
        val selectedAudio    = audioTrackResolver.buildTrackOptions(C.TRACK_TYPE_AUDIO, t).firstOrNull { it.isSelected }
        val selectedSubtitle = audioTrackResolver.buildTrackOptions(C.TRACK_TYPE_TEXT,  t).firstOrNull { it.isSelected }
        btnAudio.text    = selectedAudio?.let    { "Audio: ${it.label}" }    ?: "Audio"
        btnSubtitle.text = selectedSubtitle?.let { "Subtitles: ${it.label}" } ?: "Subtitles: Off"
    }

    fun showTrackSelectionDialog(trackType: Int) {
        val p = playerProvider() ?: return
        val typeName = if (trackType == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
        val options = audioTrackResolver.buildTrackOptions(trackType, p.currentTracks)
        if (options.isEmpty() && trackType != C.TRACK_TYPE_TEXT) {
            Toast.makeText(context, "No $typeName tracks available", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = mutableListOf<String>()
        val indices = mutableListOf<Pair<Int, Int>>() // groupIndex, trackIndex
        var selectedIndex = -1

        if (trackType == C.TRACK_TYPE_TEXT) {
            labels += "Off"; indices += Pair(-1, -1)
            if (!options.any { it.isSelected }) selectedIndex = 0
        }
        options.forEach { opt ->
            labels += opt.label; indices += Pair(opt.groupIndex, opt.trackIndex)
            if (opt.isSelected && selectedIndex == -1) selectedIndex = labels.size - 1
        }

        currentTrackDialog?.dismiss()
        currentTrackDialog = AlertDialog.Builder(context)
            .setTitle(typeName)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                val (gi, ti) = indices[which]
                val builder = p.trackSelectionParameters.buildUpon()
                if (gi == -1) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val group = options.first { it.groupIndex == gi && it.trackIndex == ti }.group
                    builder.setTrackTypeDisabled(trackType, false)
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(ti)))
                }
                p.trackSelectionParameters = builder.build()
                dialog.dismiss(); currentTrackDialog = null
            }
            .setOnDismissListener { currentTrackDialog = null }
            .show()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        currentTrackDialog?.dismiss()
        currentTrackDialog = null
    }
}
