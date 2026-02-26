package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.scriptgod.fireos.avod.R
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.model.TokenData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Login screen implementing Amazon's OAuth device registration flow.
 *
 * Flow: email/password → (optional MFA) → get authorization_code → register device → save tokens.
 *
 * The .device-token file at /data/local/tmp/.device-token is used as fallback for
 * development/debugging when the user skips login.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val OAUTH_URL = "https://api.amazon.com/ap/signin"
        private const val REGISTER_URL = "https://api.amazon.com/auth/register"
        private val TOKEN_FILE = File("/data/local/tmp/.device-token")
    }

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etMfa: EditText
    private lateinit var mfaContainer: LinearLayout
    private lateinit var btnLogin: Button
    private lateinit var btnSkip: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false) // We handle redirects manually for OAuth
        .build()

    // PKCE challenge
    private var codeVerifier: String = ""
    private var codeChallenge: String = ""

    // OAuth state tracking
    private var sessionId: String = ""
    private var needsMfa: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If token file already exists and is valid, skip login
        if (TOKEN_FILE.exists() && TOKEN_FILE.length() > 0) {
            try {
                val data = gson.fromJson(TOKEN_FILE.readText(), TokenData::class.java)
                if (data.accessToken.isNotEmpty() && data.refreshToken.isNotEmpty()) {
                    Log.w(TAG, "Valid token file found, skipping login")
                    launchMain()
                    return
                }
            } catch (_: Exception) { /* invalid token, show login */ }
        }

        setContentView(R.layout.activity_login)

        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        etMfa = findViewById(R.id.et_mfa)
        mfaContainer = findViewById(R.id.mfa_container)
        btnLogin = findViewById(R.id.btn_login)
        btnSkip = findViewById(R.id.btn_skip)
        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progress_bar)

        // Generate PKCE verifier and challenge
        generatePkce()

        btnLogin.setOnClickListener { onLoginClicked() }

        btnSkip.setOnClickListener {
            if (TOKEN_FILE.exists() && TOKEN_FILE.length() > 0) {
                launchMain()
            } else {
                showStatus("No .device-token file found at ${TOKEN_FILE.path}")
            }
        }

        // Only show skip button if token file exists
        btnSkip.visibility = if (TOKEN_FILE.exists()) View.VISIBLE else View.GONE
    }

    private fun generatePkce() {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
        codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun onLoginClicked() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            showStatus("Please enter email and password")
            return
        }

        if (needsMfa) {
            val mfaCode = etMfa.text.toString().trim()
            if (mfaCode.isEmpty()) {
                showStatus("Please enter MFA code")
                return
            }
            submitMfa(email, password, mfaCode)
        } else {
            startLogin(email, password)
        }
    }

    private fun startLogin(email: String, password: String) {
        showLoading(true)
        showStatus("")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    performOAuthLogin(email, password)
                }
                when {
                    result.needsMfa -> {
                        needsMfa = true
                        mfaContainer.visibility = View.VISIBLE
                        btnLogin.text = "Verify MFA"
                        showStatus("Enter the MFA code from your authenticator app")
                        tvStatus.setTextColor(0xFF00A8E0.toInt()) // Blue info color
                        etMfa.requestFocus()
                    }
                    result.authCode != null -> {
                        showStatus("Registering device...")
                        tvStatus.setTextColor(0xFF00A8E0.toInt())
                        tvStatus.visibility = View.VISIBLE
                        registerDevice(result.authCode)
                    }
                    else -> {
                        showStatus(result.error ?: "Login failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                showStatus("Login error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun submitMfa(email: String, password: String, mfaCode: String) {
        showLoading(true)
        showStatus("")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    performOAuthLoginWithMfa(email, password, mfaCode)
                }
                if (result.authCode != null) {
                    showStatus("Registering device...")
                    tvStatus.setTextColor(0xFF00A8E0.toInt())
                    tvStatus.visibility = View.VISIBLE
                    registerDevice(result.authCode)
                } else {
                    showStatus(result.error ?: "MFA verification failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MFA error", e)
                showStatus("MFA error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private data class OAuthResult(
        val authCode: String? = null,
        val needsMfa: Boolean = false,
        val error: String? = null
    )

    /**
     * Step 1: POST to Amazon sign-in with email/password.
     * Returns auth code on success, or indicates MFA is needed.
     */
    private fun performOAuthLogin(email: String, password: String): OAuthResult {
        sessionId = UUID.randomUUID().toString()

        val body = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .add("create", "0")
            .add("metadata1", "")
            .build()

        val request = Request.Builder()
            .url("$OAUTH_URL?openid.ns=http://specs.openid.net/auth/2.0" +
                "&openid.claimed_id=http://specs.openid.net/auth/2.0/identifier_select" +
                "&openid.identity=http://specs.openid.net/auth/2.0/identifier_select" +
                "&openid.assoc_handle=amzn_piv_android_v2_us" +
                "&openid.mode=checkid_setup" +
                "&openid.ns.oa2=http://www.amazon.com/ap/ext/oauth/2" +
                "&openid.oa2.client_id=device:${AmazonAuthService.DEVICE_TYPE_ID}" +
                "&openid.oa2.response_type=code" +
                "&openid.oa2.scope=device_auth_access" +
                "&openid.oa2.code_challenge=$codeChallenge" +
                "&openid.oa2.code_challenge_method=S256" +
                "&language=en_US" +
                "&pageId=amzn_piv_android_v2_us")
            .post(body)
            .header("User-Agent", AmazonAuthService.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return parseOAuthResponse(response.code, responseBody, response.header("Location"))
    }

    private fun performOAuthLoginWithMfa(email: String, password: String, mfaCode: String): OAuthResult {
        val body = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .add("otpCode", mfaCode)
            .add("mfaResponse", mfaCode)
            .add("create", "0")
            .add("metadata1", "")
            .build()

        val request = Request.Builder()
            .url("$OAUTH_URL?openid.ns=http://specs.openid.net/auth/2.0" +
                "&openid.claimed_id=http://specs.openid.net/auth/2.0/identifier_select" +
                "&openid.identity=http://specs.openid.net/auth/2.0/identifier_select" +
                "&openid.assoc_handle=amzn_piv_android_v2_us" +
                "&openid.mode=checkid_setup" +
                "&openid.ns.oa2=http://www.amazon.com/ap/ext/oauth/2" +
                "&openid.oa2.client_id=device:${AmazonAuthService.DEVICE_TYPE_ID}" +
                "&openid.oa2.response_type=code" +
                "&openid.oa2.scope=device_auth_access" +
                "&openid.oa2.code_challenge=$codeChallenge" +
                "&openid.oa2.code_challenge_method=S256" +
                "&language=en_US" +
                "&pageId=amzn_piv_android_v2_us")
            .post(body)
            .header("User-Agent", AmazonAuthService.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        return parseOAuthResponse(response.code, responseBody, response.header("Location"))
    }

    private fun parseOAuthResponse(statusCode: Int, body: String, location: String?): OAuthResult {
        // Check for redirect with authorization code
        val redirectUrl = location ?: ""
        if (redirectUrl.contains("openid.oa2.authorization_code=")) {
            val code = Regex("openid\\.oa2\\.authorization_code=([^&]+)")
                .find(redirectUrl)?.groupValues?.get(1)
            if (code != null) return OAuthResult(authCode = code)
        }

        // Check response body for authorization code
        val codeMatch = Regex("openid\\.oa2\\.authorization_code\"\\s*value=\"([^\"]+)\"").find(body)
        if (codeMatch != null) {
            return OAuthResult(authCode = codeMatch.groupValues[1])
        }

        // Check if MFA is required
        if (body.contains("otpCode") || body.contains("auth-mfa-form") ||
            body.contains("Two-Step Verification") || body.contains("Enter OTP")) {
            return OAuthResult(needsMfa = true)
        }

        // Check for error messages in HTML
        val errorMatch = Regex("auth-error-message[^>]*>\\s*<[^>]*>([^<]+)").find(body)
        if (errorMatch != null) {
            return OAuthResult(error = errorMatch.groupValues[1].trim())
        }

        // Check for captcha
        if (body.contains("auth-captcha-image") || body.contains("image-captcha")) {
            return OAuthResult(error = "Amazon is requesting a captcha. Please try again later or use a device token.")
        }

        return OAuthResult(error = "Unexpected response (HTTP $statusCode). Check email/password and try again.")
    }

    /**
     * Step 2: Register device with the authorization code to get tokens.
     * Mirrors Kodi plugin: login.py device registration flow.
     */
    private fun registerDevice(authCode: String) {
        showLoading(true)

        scope.launch {
            try {
                val tokenData = withContext(Dispatchers.IO) {
                    performDeviceRegistration(authCode)
                }
                // Save token to file
                TOKEN_FILE.writeText(gson.toJson(tokenData))
                Log.w(TAG, "Device registered and token saved")
                showStatus("Login successful!")
                tvStatus.setTextColor(0xFF00CC00.toInt()) // Green
                tvStatus.visibility = View.VISIBLE
                launchMain()
            } catch (e: Exception) {
                Log.e(TAG, "Device registration failed", e)
                showStatus("Registration failed: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun performDeviceRegistration(authCode: String): TokenData {
        val deviceSerial = UUID.randomUUID().toString().replace("-", "")

        val registrationData = JsonObject().apply {
            addProperty("domain", "Device")
            addProperty("device_type", AmazonAuthService.DEVICE_TYPE_ID)
            addProperty("device_serial", deviceSerial)
            addProperty("app_name", AmazonAuthService.APP_NAME)
            addProperty("app_version", AmazonAuthService.APP_VERSION)
            addProperty("device_model", AmazonAuthService.DEVICE_MODEL)
            addProperty("os_version", AmazonAuthService.OS_VERSION)
            addProperty("software_version", "351")
        }

        val authData = JsonObject().apply {
            addProperty("authorization_code", authCode)
            addProperty("code_verifier", codeVerifier)
            addProperty("code_algorithm", "SHA-256")
            addProperty("client_domain", "DeviceLegacy")
            addProperty("client_id", AmazonAuthService.DEVICE_TYPE_ID)
        }

        val requestJson = JsonObject().apply {
            add("registration_data", registrationData)
            add("auth_data", authData)
            add("requested_token_type", gson.toJsonTree(listOf(
                "bearer",
                "mac_dms",
                "store_authentication_cookie",
                "website_cookies"
            )))
            add("requested_extensions", gson.toJsonTree(listOf(
                "device_info",
                "customer_info"
            )))
        }

        val requestBody = gson.toJson(requestJson)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(REGISTER_URL)
            .post(requestBody)
            .header("User-Agent", AmazonAuthService.USER_AGENT)
            .header("Accept-Language", "en-US")
            .header("Content-Type", "application/json")
            .header("x-amzn-identity-auth-domain", "api.amazon.com")
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty registration response")

        if (!response.isSuccessful) {
            // Try to extract error message
            val errorJson = try {
                gson.fromJson(responseBody, JsonObject::class.java)
            } catch (_: Exception) { null }
            val errorMsg = errorJson
                ?.getAsJsonObject("response")
                ?.getAsJsonObject("error")
                ?.get("message")?.asString
                ?: "HTTP ${response.code}"
            throw RuntimeException("Registration failed: $errorMsg")
        }

        // Parse registration response
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val responseObj = json.getAsJsonObject("response")
            ?: throw RuntimeException("Missing 'response' in registration")

        val success = responseObj.getAsJsonObject("success")
            ?: throw RuntimeException("Registration not successful: $responseBody")

        val tokens = success.getAsJsonObject("tokens")
            ?: throw RuntimeException("No tokens in registration response")

        val bearer = tokens.getAsJsonObject("bearer")
            ?: throw RuntimeException("No bearer token in response")

        val accessToken = bearer.get("access_token")?.asString
            ?: throw RuntimeException("No access_token")
        val refreshToken = bearer.get("refresh_token")?.asString
            ?: throw RuntimeException("No refresh_token")
        val expiresIn = bearer.get("expires_in")?.asLong ?: 3600L

        val nowSecs = System.currentTimeMillis() / 1000

        return TokenData(
            accessToken = accessToken,
            refreshToken = refreshToken,
            deviceId = deviceSerial,
            expiresIn = expiresIn,
            expiresAt = nowSecs + expiresIn
        )
    }

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
    }

    private fun showStatus(message: String) {
        if (message.isEmpty()) {
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.text = message
            tvStatus.setTextColor(0xFFFF4444.toInt()) // Red by default
            tvStatus.visibility = View.VISIBLE
        }
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
