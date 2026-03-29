package com.scriptgod.fireos.avod.player

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.scriptgod.fireos.avod.model.PlaybackInfo

/**
 * Manages the seek-preview thumbnail card and D-pad seek accumulation.
 *
 * Call [showThumbnailAt] / [hideThumbnail] from the TimeBar scrub listener,
 * and [handleDpadSeek] from Activity.dispatchKeyEvent when the seek bar has focus.
 */
@UnstableApi
class SeekPreviewController(
    private val cardSeekThumbnail: CardView,
    private val ivSeekThumbnail: ImageView,
    private val tvSeekTime: TextView,
    private val bifThumbnailProvider: BifThumbnailProvider,
    private val playbackInfoProvider: () -> PlaybackInfo?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideThumbnail() }

    /** Accumulated D-pad seek position; -1 = not currently previewing. */
    private var previewPos: Long = -1L

    fun showThumbnailAt(posMs: Long) {
        val totalSec = posMs / 1000L
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        tvSeekTime.text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        cardSeekThumbnail.visibility = View.VISIBLE

        val info = playbackInfoProvider() ?: return
        if (!info.hasThumbnails || !bifThumbnailProvider.hasBifEntries()) return
        bifThumbnailProvider.getThumbnailAt(posMs, info.bifUrl) { bmp ->
            if (bmp != null) ivSeekThumbnail.setImageBitmap(bmp)
        }
    }

    fun hideThumbnail() {
        previewPos = -1L
        cardSeekThumbnail.visibility = View.GONE
        ivSeekThumbnail.setImageBitmap(null)
    }

    /**
     * Accumulates position and schedules thumbnail display for a D-pad seek key event.
     * The caller should still forward the event to `super.dispatchKeyEvent()` for ExoPlayer to seek.
     */
    fun handleDpadSeek(keyCode: Int, player: ExoPlayer?) {
        val dur = player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
        if (previewPos < 0) previewPos = player?.currentPosition ?: 0L
        previewPos = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            minOf(previewPos + 10_000L, dur) else maxOf(previewPos - 10_000L, 0L)
        val pos = previewPos
        handler.removeCallbacks(hideRunnable)
        handler.post { showThumbnailAt(pos) }
        handler.postDelayed(hideRunnable, 1500L)
    }

    fun cancelPendingHide() {
        handler.removeCallbacksAndMessages(null)
    }
}
