// TODO: remove — no longer wired; MPD correction (MpdTimingCorrector) is the primary stall fix
package com.scriptgod.fireos.avod.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Custom video renderer that detects decoder output stalls and signals a skip.
 *
 * When the hardware decoder silently stops producing frames (accepts input but no output),
 * this renderer detects it within [STALL_THRESHOLD_MS] and invokes [onDecoderStall].
 * The player then seeks past the bad region. Recovery is fast enough to feel like a
 * brief buffering pause rather than a hard stall.
 */
class StallRecoveryVideoRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    private val onDecoderStall: (positionUs: Long) -> Unit
) : MediaCodecVideoRenderer(
    context,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
) {

    companion object {
        private const val TAG = "StallRecovery"
        /** No output for this long = stall. Fast detection, minimal visible freeze. */
        private const val STALL_THRESHOLD_MS = 800L
        /** After a stall callback, wait this long for the seek to take effect before re-checking. */
        private const val COOLDOWN_MS = 1500L
        private const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }

    @Volatile private var lastOutputMs = SystemClock.elapsedRealtime()
    @Volatile private var lastStallCallbackMs = 0L
    @Volatile private var rendering = false

    override fun onStarted() {
        super.onStarted()
        if (!rendering) lastOutputMs = SystemClock.elapsedRealtime()
        rendering = true
    }

    override fun onStopped() {
        super.onStopped()
        // Don't clear rendering — BUFFERING↔READY oscillation calls onStopped/onStarted rapidly.
    }

    override fun onDisabled() {
        super.onDisabled()
        rendering = false
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        lastOutputMs = SystemClock.elapsedRealtime()
    }

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        lastOutputMs = SystemClock.elapsedRealtime()
    }

    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs, elapsedRealtimeUs)
        if (!rendering) return

        val now = SystemClock.elapsedRealtime()
        val silentMs = now - lastOutputMs
        val sinceLastCallback = now - lastStallCallbackMs

        if (silentMs > STALL_THRESHOLD_MS && sinceLastCallback > COOLDOWN_MS) {
            Log.w(TAG, "Decoder stall: no output for ${silentMs}ms at pos=${positionUs / 1000}ms")
            lastStallCallbackMs = now
            lastOutputMs = now
            onDecoderStall(positionUs)
        }
    }
}
