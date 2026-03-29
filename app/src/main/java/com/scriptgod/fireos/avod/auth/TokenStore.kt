package com.scriptgod.fireos.avod.auth

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.scriptgod.fireos.avod.model.TokenData
import java.io.File

/**
 * Single source of truth for token persistence.
 *
 * Writes to app-internal storage (always writable) and mirrors to the legacy
 * /data/local/tmp path so adb-pushed debug tokens keep working.
 *
 * Logout is recorded as a timestamp so that a developer-pushed token
 * (lastModified > logged_out_at) is still accepted after a sign-out.
 */
class TokenStore(private val context: Context) {

    companion object {
        private const val TAG = "TokenStore"
        private const val TOKEN_FILENAME = ".device-token"
        private const val PREFS_AUTH = "auth"
        private const val KEY_LOGGED_OUT_AT = "logged_out_at"
        private val LEGACY_TOKEN_FILE = File("/data/local/tmp/.device-token")
        private val gson = Gson()

        /**
         * Finds the active token file: app-internal first, then legacy /data/local/tmp.
         * After an explicit logout the legacy file is skipped unless it was written after
         * the logout timestamp (developer-pushed debug token).
         */
        fun findTokenFile(context: Context): File? {
            val internal = File(context.filesDir, TOKEN_FILENAME)
            if (internal.exists() && internal.length() > 0) return internal
            if (LEGACY_TOKEN_FILE.exists() && LEGACY_TOKEN_FILE.length() > 0) {
                val prefs = context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                val loggedOutAt = prefs.getLong(KEY_LOGGED_OUT_AT, 0L)
                if (loggedOutAt == 0L || LEGACY_TOKEN_FILE.lastModified() > loggedOutAt) {
                    if (loggedOutAt != 0L) prefs.edit().remove(KEY_LOGGED_OUT_AT).apply()
                    return LEGACY_TOKEN_FILE
                }
            }
            return null
        }
    }

    private val tokenFile: File get() = File(context.filesDir, TOKEN_FILENAME)

    /** Returns true if a non-empty token with valid access and refresh tokens exists. */
    fun isValid(): Boolean {
        val file = findTokenFile(context) ?: return false
        return try {
            val data = gson.fromJson(file.readText(), TokenData::class.java)
            data.accessToken.isNotEmpty() && data.refreshToken.isNotEmpty()
        } catch (_: Exception) { false }
    }

    /** Persists token to app-internal storage and mirrors to the legacy path. */
    fun save(tokenData: TokenData) {
        val json = gson.toJson(tokenData)
        tokenFile.writeText(json)
        Log.w(TAG, "Token saved to ${tokenFile.path}")
        try { LEGACY_TOKEN_FILE.writeText(json) } catch (_: Exception) {}
    }

    /** Clears the logout timestamp so a re-login or dev-pushed token is treated as fresh. */
    fun clearLogoutTimestamp() {
        context.getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOGGED_OUT_AT).apply()
    }
}
