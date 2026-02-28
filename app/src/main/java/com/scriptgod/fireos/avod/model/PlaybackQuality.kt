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
        val HD      = PlaybackQuality("HD",  "H264",      "None")
        /**
         * H265 HDR10 — requests Hdr10 to unlock 1080p+ H265 streams.
         * Amazon serves H265 only up to 720p when HDR=None; Hdr10 is required for 1080p+.
         * Fire TV / TV tonemaps to SDR automatically if the display is not HDR-capable.
         */
        val HD_H265 = PlaybackQuality("UHD", "H264,H265", "Hdr10")
        /** 4K Dolby Vision / HDR10 — requires H265 decoder and HDR-capable display */
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
