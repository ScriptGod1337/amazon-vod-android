package com.scriptgod.fireos.avod.model

/**
 * Quality parameters sent to GetPlaybackResources (both manifest and license requests).
 * Mirrors Kodi network.py:210-212 and supported_hdr() (network.py:231-239).
 * Exact enum string values confirmed in decompiled Prime APK (decisions.md Decision 16).
 */
data class PlaybackQuality(
    val videoQuality: String,  // "HD" or "UHD"
    val codecOverride: String, // "H264" or "H264,H265"
    val hdrOverride: String    // "None" or "Hdr10,DolbyVision"
) {
    companion object {
        /** 1080p SDR H264 — safe default, works on all devices */
        val HD     = PlaybackQuality("HD",  "H264",      "None")
        /** 1080p SDR H265 — uses UHD quality tier to unlock 1080p H265 (HD tier caps at 720p H265) */
        val HD_H265 = PlaybackQuality("UHD", "H264,H265", "None")
        /** 4K HDR — requires H265 hardware decoder and compatible display */
        val UHD_HDR = PlaybackQuality("UHD", "H264,H265", "Hdr10,DolbyVision")

        const val PREF_KEY = "video_quality"

        fun fromPrefValue(value: String?) = when (value) {
            "HD_H265" -> HD_H265
            "UHD_HDR" -> UHD_HDR
            else       -> HD
        }

        fun toPrefValue(q: PlaybackQuality) = when (q) {
            HD_H265 -> "HD_H265"
            UHD_HDR -> "UHD_HDR"
            else    -> "HD"
        }
    }
}
