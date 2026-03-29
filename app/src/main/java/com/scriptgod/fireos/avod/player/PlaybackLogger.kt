package com.scriptgod.fireos.avod.player

import android.util.Log
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.scriptgod.fireos.avod.model.AudioTrack

/** Stateless diagnostic logging helpers for playback tracks. */
@UnstableApi
object PlaybackLogger {

    fun logAvailableAudioTracks(tag: String, prefix: String, tracks: List<AudioTrack>) {
        val summary = tracks.joinToString(" | ") {
            "name=${it.displayName}, lang=${it.languageCode}, type=${it.type}, index=${it.index}"
        }
        Log.i(tag, "$prefix: $summary")
    }

    /** Logs all video representations (codec + resolution + bitrate) for diagnosis. */
    fun logVideoTracks(tag: String, tracks: Tracks) {
        tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            .forEachIndexed { gi, group ->
                val info = (0 until group.length).joinToString(" | ") { i ->
                    val fmt = group.getTrackFormat(i)
                    val codec = when {
                        fmt.sampleMimeType?.contains("hevc", true) == true -> "H265"
                        fmt.sampleMimeType?.contains("avc", true) == true -> "H264"
                        else -> fmt.sampleMimeType ?: "?"
                    }
                    "${fmt.height}p $codec ${fmt.bitrate / 1000}kbps sel=${group.isTrackSelected(i)}"
                }
                Log.w(tag, "Video tracks group[$gi]: $info")
            }
    }
}
