package com.scriptgod.fireos.avod.model

/**
 * Quality parameters sent to GetPlaybackResources (both manifest and license requests).
 * Mirrors Kodi network.py:210-212 and supported_hdr() (network.py:231-239).
 * Exact enum string values confirmed in decompiled Prime APK (decisions.md Decision 16).
 */
data class PlaybackQuality(
    val videoQuality: String,  // "SD", "HD", or "UHD"
    val codecOverride: String, // "H264" or "H264,H265"
    val hdrOverride: String    // "None" or "Hdr10,DolbyVision"
) {
    companion object {
        /**
         * 480p H264 SDR — Widevine L3 fallback tier.
         *
         * Amazon's license server enforces: HD quality + Widevine L3 + no HDCP = denied.
         * L3 devices (Android emulators, un-provisioned hardware) must request SD so the
         * license server grants the session. This mirrors the official Amazon APK's
         * ConfigurablePlaybackSupportEvaluator behaviour: it detects HDCP=NONE and falls
         * back to "SD" automatically. Not exposed as a user-selectable option.
         */
        val SD      = PlaybackQuality("SD",  "H264",      "None")
        /** 720p H264 SDR — safe default, works on all devices and displays */
        val HD      = PlaybackQuality("HD",  "H264",      "None")
        /**
         * 720p H265 SDR — same resolution as HD H264 but H265 codec where available,
         * giving better compression (lower bandwidth for similar quality).
         * No HDR requested so this works on non-HDR displays too.
         * Amazon's HD tier caps at 720p for both codecs; 1080p requires HDR (UHD_HDR).
         */
        val HD_H265 = PlaybackQuality("HD",  "H264,H265", "None")
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
