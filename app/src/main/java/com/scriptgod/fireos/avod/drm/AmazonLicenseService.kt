package com.scriptgod.fireos.avod.drm

import android.util.Base64
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

/**
 * Custom Widevine license callback for Amazon's non-standard DRM exchange.
 *
 * Amazon wraps the challenge/response:
 *   Request:  POST body = "widevine2Challenge=<base64url(challenge_bytes)>"
 *   Response: JSON { "widevine2License": { "license": "<base64(license_bytes)>" } }
 *
 * Mirrors playback.py:452-467 and decisions.md Decision 4.
 */
@UnstableApi
class AmazonLicenseService(
    private val authService: AmazonAuthService,
    private val licenseUrl: String
) : MediaDrmCallback {

    companion object {
        private const val TAG = "AmazonLicenseService"
    }

    // Authenticated client for Amazon license requests
    private val client: OkHttpClient = authService.buildAuthenticatedClient()
    // Plain client for Google Widevine provisioning — must NOT carry Amazon auth headers
    private val provisionClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /**
     * Handles Widevine key request — wraps the DRM challenge and decodes Amazon's response.
     */
    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
        val challengeBytes = request.data

        // Encode challenge as base64url (URL-safe, no padding) per playback.py:461
        val base64Challenge = Base64.encodeToString(
            challengeBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val bodyString = "widevine2Challenge=$base64Challenge"
        val requestBody = bodyString.toRequestBody("application/octet-stream".toMediaType())

        val httpRequest = Request.Builder()
            .url(licenseUrl)
            .post(requestBody)
            .header("Content-Type", "application/octet-stream")
            .build()

        val response = client.newCall(httpRequest).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty license response (HTTP ${response.code})")

        if (!response.isSuccessful) {
            throw RuntimeException("License request failed: HTTP ${response.code} — $responseBody")
        }

        // Parse JSON response: { "widevine2License": { "license": "<base64>" } }
        return try {
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val licenseBase64 = json
                .getAsJsonObject("widevine2License")
                ?.getAsJsonPrimitive("license")
                ?.asString
                ?: throw RuntimeException("No widevine2License.license field in response")

            // Standard base64 decode (not URL-safe) per playback.py:466
            Base64.decode(licenseBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse license response", e)
            throw RuntimeException("License parse error: ${e.message}", e)
        }
    }

    /**
     * Handles provisioning requests — mirrors ExoPlayer's HttpMediaDrmCallback exactly.
     * Standard Widevine provisioning: GET to defaultUrl + &signedRequest=<utf8(data)>.
     * Must use plain client — no Amazon auth headers go to Google's provisioning server.
     */
    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): ByteArray {
        val signedRequest = String(request.data)
        Log.d(TAG, "Provisioning request to: ${request.defaultUrl}")
        // Google's Certificate Provisioning API requires POST with JSON body
        val jsonBody = "{\"signedRequest\":\"$signedRequest\"}"
        val httpRequest = Request.Builder()
            .url(request.defaultUrl)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val response = provisionClient.newCall(httpRequest).execute()
        val body = response.body?.bytes()
            ?: throw RuntimeException("Empty provision response (HTTP ${response.code})")
        if (!response.isSuccessful) {
            Log.e(TAG, "Provision failed: HTTP ${response.code}")
            throw RuntimeException("Provision failed: HTTP ${response.code}")
        }
        return body
    }
}
