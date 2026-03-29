package com.scriptgod.fireos.avod.model

/**
 * Quality parameters sent to GetPlaybackResources (both manifest and license requests).
 * Mirrors Kodi network.py:210-212 and supported_hdr() (network.py:231-239).
 * Exact enum string values confirmed in decompiled Prime APK (decisions.md Decision 16).
 *
 * Amazon codec behavior (confirmed by manifest inspection):
 *   deviceVideoCodecOverride=H264      → H264-only manifest, max ~576p
 *   deviceVideoCodecOverride=H264,H265 → H265-only manifest, up to 872p on Fire TV
 * There is no 720p H264 tier; H265 is required for resolutions above 576p.
 */
data class PlaybackQuality(
    val videoQuality: String,   // "SD", "HD", or "UHD"
    val codecOverride: String,  // "H264" or "H264,H265"
    val hdrOverride: String     // "None" or "Hdr10,DolbyVision"
) {
    companion object {
        /**
         * 480p H264 SDR — Widevine L3 fallback tier.
         * Amazon's license server enforces: HD quality + Widevine L3 + no HDCP = denied.
         * Not exposed as a user-selectable option.
         */
        val SD      = PlaybackQuality("SD",  "H264",      "None")
        /**
         * ~576p H264 SDR — Amazon caps H264 manifests at ~576p regardless of quality tier.
         * Use when H265 is unavailable or undesired; expect lower resolution than HD_H265.
         */
        val HD      = PlaybackQuality("HD",  "H264",      "None")
        /**
         * ~872p H265 SDR — Amazon serves an H265-only manifest when H265 is in the codec
         * override, reaching 872p on Fire TV hardware. Recommended default for H265 devices.
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
