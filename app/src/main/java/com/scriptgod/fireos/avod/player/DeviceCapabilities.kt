package com.scriptgod.fireos.avod.player

import android.media.MediaCodecList
import android.media.MediaDrm
import android.util.Log
import java.util.UUID

/**
 * Device hardware capability queries used to select the right playback quality tier.
 *
 * Why this matters: Amazon's license server enforces
 *   HD quality + Widevine L3 + no HDCP → license DENIED
 *   SD quality + Widevine L3 + no HDCP → license GRANTED
 * Querying before player creation lets the caller select the right tier up-front
 * rather than hitting a license error mid-playback.
 */
object DeviceCapabilities {

    private const val TAG = "DeviceCapabilities"
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    /**
     * Queries the Widevine CDM for its security level.
     * Returns "L1" on real hardware with a TEE; "L3" on emulators / un-provisioned devices.
     */
    fun widevineSecurityLevel(): String = try {
        MediaDrm(WIDEVINE_UUID).use { it.getPropertyString("securityLevel") }
    } catch (e: android.media.UnsupportedSchemeException) {
        Log.w(TAG, "Widevine DRM not supported on this device — assuming L3")
        "L3"
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected MediaDrm error querying security level — safe-failing to L3: ${e.message}")
        "L3"
    }

    /** Returns true if this device has any H265/HEVC video decoder. */
    fun deviceSupportsH265(): Boolean =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
}
