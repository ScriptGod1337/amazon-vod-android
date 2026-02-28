package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.model.TokenData
import java.io.File

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_about)

        // Version
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        findViewById<TextView>(R.id.tv_version).text = "Version $versionName"
        findViewById<TextView>(R.id.tv_version_value).text = versionName
        findViewById<TextView>(R.id.tv_package).text = packageName

        // Account info from token file
        val tokenFile = LoginActivity.findTokenFile(this)
        if (tokenFile != null) {
            try {
                val token = Gson().fromJson(tokenFile.readText(), TokenData::class.java)
                val deviceId = token.deviceId
                val masked = if (deviceId.length > 12) deviceId.take(8) + "…" + deviceId.takeLast(4)
                             else deviceId
                findViewById<TextView>(R.id.tv_device_id).text = masked
            } catch (e: Exception) {
                findViewById<TextView>(R.id.tv_device_id).text = "error reading token"
            }
            val location = if (tokenFile.path.startsWith(filesDir.path)) "internal storage" else "legacy (/data/local/tmp)"
            findViewById<TextView>(R.id.tv_token_location).text = location
        } else {
            findViewById<TextView>(R.id.tv_device_id).text = "no token"
            findViewById<TextView>(R.id.tv_token_location).text = "—"
        }

        // Sign out
        findViewById<Button>(R.id.btn_sign_out).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign Out")
                .setMessage("Sign out and return to the login screen?")
                .setPositiveButton("Sign Out") { _, _ -> performLogout() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun performLogout() {
        // Delete internal token
        File(filesDir, ".device-token").delete()
        // Delete legacy token if present (may silently fail — app lacks write permission on /data/local/tmp)
        File("/data/local/tmp/.device-token").delete()
        // Record logout time so findTokenFile() skips the legacy token that was present at
        // logout, but still accepts a fresh debug token pushed via adb after this timestamp.
        getSharedPreferences("auth", MODE_PRIVATE).edit().putLong("logged_out_at", System.currentTimeMillis()).apply()
        // Clear local resume positions
        getSharedPreferences("resume_positions", MODE_PRIVATE).edit().clear().apply()

        Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()

        // Return to login, clear back stack
        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
