package com.scriptgod.fireos.avod.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Login screen implementing Amazon's OAuth device registration flow.
 * Mirrors the working register_device.py exactly.
 *
 * Flow:
 * 1. Generate PKCE verifier/challenge, device_id, client_id, cookies
 * 2. Open amazon.com → follow sign-in link → get OAuth URL
 * 3. Modify OAuth URL with PKCE params → load sign-in form
 * 4. Submit email/password → get auth code (or MFA challenge)
 * 5. POST /auth/register with auth code + PKCE verifier → get tokens
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        private const val REGISTER_URL = "https://api.amazon.com/auth/register"
        private const val TOKEN_FILENAME = ".device-token"
        private val LEGACY_TOKEN_FILE = File("/data/local/tmp/.device-token")

        /** Find the token file: app-internal first, then legacy /data/local/tmp/.
         *  After an explicit logout the legacy fallback is skipped for the token that was
         *  present at logout time. A freshly-pushed debug token (lastModified > logged_out_at)
         *  is still accepted, which lets developers push .device-token via adb after signing out. */
        fun findTokenFile(context: android.content.Context): File? {
            val internal = File(context.filesDir, TOKEN_FILENAME)
            if (internal.exists() && internal.length() > 0) return internal
            if (LEGACY_TOKEN_FILE.exists() && LEGACY_TOKEN_FILE.length() > 0) {
                val prefs = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
                val loggedOutAt = prefs.getLong("logged_out_at", 0L)
                // Accept the legacy file if we have never logged out, or if the file was
                // written after the logout (developer pushed a fresh debug token).
                if (loggedOutAt == 0L || LEGACY_TOKEN_FILE.lastModified() > loggedOutAt) {
                    if (loggedOutAt != 0L) prefs.edit().remove("logged_out_at").apply()
                    return LEGACY_TOKEN_FILE
                }
            }
            return null
        }
    }

    private val tokenFile: File by lazy { File(filesDir, TOKEN_FILENAME) }

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etMfa: EditText
    private lateinit var mfaContainer: LinearLayout
    private lateinit var cbShowPassword: CheckBox
    private lateinit var btnLogin: Button
    private lateinit var btnSkip: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val gson = Gson()

    // Cookie jar to maintain session across requests — deduplicates by name+domain
    private val cookieStore = mutableMapOf<String, Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                cookieStore["${cookie.domain}:${cookie.name}"] = cookie
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore.values.filter { it.matches(url) }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookieJar)
        // Identify all requests as coming from the Amazon app — matches what register_device.py sends.
        // Without these headers Amazon serves a browser-mode login page that requires JS cookies.
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("X-Requested-With", AmazonAuthService.APP_NAME)
                .header("x-gasc-enabled", "true")
                .build()
            chain.proceed(req)
        }
        .build()

    // Non-following client shares the same connection pool and cookie jar
    private val noRedirectClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // PKCE
    private var codeVerifier: String = ""
    private var codeChallenge: String = ""

    // Device identity (matching register_device.py exactly)
    private var deviceId: String = ""
    private var clientId: String = ""

    // OAuth state
    private var needsMfa: Boolean = false
    private var formAction: String = ""
    private var hiddenFields: Map<String, String> = emptyMap()
    private var domain: String = "amazon.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existingToken = findTokenFile(this)
        if (existingToken != null) {
            try {
                val data = gson.fromJson(existingToken.readText(), TokenData::class.java)
                if (data.accessToken.isNotEmpty() && data.refreshToken.isNotEmpty()) {
                    Log.w(TAG, "Valid token file found at ${existingToken.path}, skipping login")
                    launchMain()
                    return
                }
            } catch (_: Exception) {}
        }

        setContentView(R.layout.activity_login)

        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        etMfa = findViewById(R.id.et_mfa)
        mfaContainer = findViewById(R.id.mfa_container)
        cbShowPassword = findViewById(R.id.cb_show_password)
        btnLogin = findViewById(R.id.btn_login)
        btnSkip = findViewById(R.id.btn_skip)
        tvStatus = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progress_bar)

        // Generate identity (matching register_device.py)
        deviceId = UUID.randomUUID().toString().replace("-", "").lowercase()
        clientId = hexEncode("${deviceId}#A1MPSLFC7L5AFK")
        generatePkce()
        setupFraudCookies()

        cbShowPassword.setOnCheckedChangeListener { _, isChecked ->
            val cursorPos = etPassword.selectionEnd
            etPassword.transformationMethod = if (isChecked)
                HideReturnsTransformationMethod.getInstance()
            else
                PasswordTransformationMethod.getInstance()
            etPassword.setSelection(cursorPos.coerceAtMost(etPassword.text.length))
        }

        btnLogin.setOnClickListener { onLoginClicked() }
        btnSkip.setOnClickListener {
            if (findTokenFile(this) != null) launchMain()
            else showStatus("No .device-token file found")
        }
        btnSkip.visibility = if (findTokenFile(this) != null) View.VISIBLE else View.GONE
    }

    private fun hexEncode(s: String): String {
        return s.toByteArray().joinToString("") { "%02x".format(it) }
    }

    private fun generatePkce() {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codeChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray()))
    }

    private fun setupFraudCookies() {
        val frc = Base64.getEncoder().encodeToString(ByteArray(313).also { SecureRandom().nextBytes(it) })
        val mapMd = mapOf(
            "device_registration_data" to mapOf("software_version" to "130050002"),
            "app_identifier" to mapOf(
                "package" to "com.amazon.avod.thirdpartyclient",
                "SHA-256" to listOf("2f19adeb284eb36f7f07786152b9a1d14b21653203ad0b04ebbf9c73ab6d7625"),
                "app_version" to "351003955",
                "app_version_name" to "3.0.351.3955",
                "app_sms_hash" to "e0kK4QFSWp0",
                "map_version" to "MAPAndroidLib-1.3.14913.0"
            ),
            "app_info" to mapOf(
                "auto_pv" to 0, "auto_pv_with_smsretriever" to 0,
                "smartlock_supported" to 0, "permission_runtime_grant" to 0
            )
        )
        val mapMdEncoded = Base64.getEncoder().encodeToString(gson.toJson(mapMd).toByteArray())

        // Add cookies for amazon.com
        val expiry = (System.currentTimeMillis() / 1000 + 86400) * 1000
        cookieStore["amazon.com:frc"] = Cookie.Builder().domain("amazon.com").path("/").name("frc").value(frc).expiresAt(expiry).build()
        cookieStore["amazon.com:map-md"] = Cookie.Builder().domain("amazon.com").path("/").name("map-md").value(mapMdEncoded).expiresAt(expiry).build()
        cookieStore["amazon.com:sid"] = Cookie.Builder().domain("amazon.com").path("/").name("sid").value("").expiresAt(expiry).build()
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
            doMfa(mfaCode)
        } else {
            doLogin(email, password)
        }
    }

    private fun doLogin(email: String, password: String) {
        showLoading(true)
        showStatus("")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    performFullLogin(email, password)
                }
                handleResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "Login error: ${e.message}", e)
                showStatus("Login error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun doMfa(mfaCode: String) {
        showLoading(true)
        showStatus("")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { submitMfaCode(mfaCode) }
                handleResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "MFA error: ${e.message}", e)
                showStatus("MFA error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun handleResult(result: OAuthResult) {
        when {
            result.needsMfa -> {
                needsMfa = true
                mfaContainer.visibility = View.VISIBLE
                btnLogin.text = "Verify"
                showStatus(result.mfaMessage ?: "Enter verification code")
                tvStatus.setTextColor(0xFF00A8E0.toInt())
                etMfa.requestFocus()
            }
            result.authCode != null -> {
                showStatus("Registering device...")
                tvStatus.setTextColor(0xFF00A8E0.toInt())
                tvStatus.visibility = View.VISIBLE
                registerDevice(result.authCode)
            }
            else -> showStatus(result.error ?: "Login failed")
        }
    }

    private data class OAuthResult(
        val authCode: String? = null,
        val needsMfa: Boolean = false,
        val mfaMessage: String? = null,
        val error: String? = null
    )

    /**
     * Mirrors register_device.py flow exactly:
     * 1. Open amazon.com homepage
     * 2. Follow sign-in link to get the OAuth base URL
     * 3. Rebuild URL with PKCE params
     * 4. Load sign-in form, extract hidden fields
     * 5. Submit credentials
     * 6. Extract authorization_code from redirect URL
     */
    private fun performFullLogin(email: String, password: String): OAuthResult {
        // Clear stale cookies and regenerate fresh identity for each attempt
        cookieStore.clear()
        generatePkce()
        deviceId = UUID.randomUUID().toString().replace("-", "").lowercase()
        clientId = hexEncode("${deviceId}#A1MPSLFC7L5AFK")
        setupFraudCookies()
        Log.w(TAG, "Fresh session: cookies cleared, new PKCE + device identity")

        // Step 1: Open amazon.com to establish session
        Log.w(TAG, "Step 1: Opening amazon.com...")
        val homeReq = Request.Builder()
            .url("https://www.amazon.com")
            .get()
            .header("User-Agent", AmazonAuthService.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("upgrade-insecure-requests", "1")
            .build()
        val homeResp = httpClient.newCall(homeReq).execute()
        val homeBody = homeResp.body?.string() ?: ""
        Log.w(TAG, "Homepage: HTTP ${homeResp.code}, cookies=${cookieStore.size}")

        // Step 2: Find sign-in link
        val signInLink = Regex("""href="([^"]*nav-show-sign-in[^"]*|/ap/signin[^"]*)"[^>]*class="[^"]*nav-show-sign-in""")
            .find(homeBody)?.groupValues?.get(1)
            ?: Regex("""class="[^"]*nav-show-sign-in[^"]*"[^>]*href="([^"]+)"""").find(homeBody)?.groupValues?.get(1)
            ?: Regex("""href="([^"]*/ap/signin[^"]*)""").find(homeBody)?.groupValues?.get(1)

        var oauthBaseUrl: String
        if (signInLink != null) {
            val fullLink = if (signInLink.startsWith("http")) signInLink
                else "https://www.amazon.com${signInLink.replace("&amp;", "&")}"
            Log.w(TAG, "Step 2: Following sign-in link: ${fullLink.take(100)}")
            val signinResp = httpClient.newCall(
                Request.Builder().url(fullLink).get()
                    .header("User-Agent", AmazonAuthService.USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            ).execute()
            oauthBaseUrl = signinResp.request.url.toString()
            signinResp.body?.string() // consume body
        } else {
            oauthBaseUrl = "https://www.amazon.com/ap/signin"
            Log.w(TAG, "Step 2: No sign-in link found, using default")
        }

        // Step 3: Rebuild URL with PKCE params (matching register_device.py)
        val uri = URI(oauthBaseUrl)
        val baseQuery = parseQueryString(uri.rawQuery ?: "")

        // Get return_to and modify it to use /ap/maplanding
        val origReturnTo = baseQuery["openid.return_to"] ?: ""
        val returnToUri = if (origReturnTo.isNotEmpty()) {
            val rt = URI(origReturnTo)
            URI(rt.scheme, rt.authority, "/ap/maplanding", null, null).toString()
        } else {
            "https://www.amazon.com/ap/maplanding"
        }

        val params = baseQuery.toMutableMap()
        params["openid.assoc_handle"] = "amzn_piv_android_v2_us"
        params["openid.return_to"] = returnToUri
        params["openid.oa2.response_type"] = "code"
        params["openid.oa2.code_challenge_method"] = "S256"
        params["openid.oa2.code_challenge"] = codeChallenge
        params["pageId"] = "amzn_dv_ios_blue"
        params["openid.ns.oa2"] = "http://www.amazon.com/ap/ext/oauth/2"
        params["openid.oa2.client_id"] = "device:$clientId"
        params["openid.ns.pape"] = "http://specs.openid.net/extensions/pape/1.0"
        params["openid.oa2.scope"] = "device_auth_access"
        params["openid.mode"] = "checkid_setup"
        params["openid.identity"] = "http://specs.openid.net/auth/2.0/identifier_select"
        params["openid.ns"] = "http://specs.openid.net/auth/2.0"
        params["accountStatusPolicy"] = "P1"
        params["openid.claimed_id"] = "http://specs.openid.net/auth/2.0/identifier_select"
        params["language"] = "en_US"
        params["disableLoginPrepopulate"] = "0"
        params["openid.pape.max_auth_age"] = "0"

        val queryStr = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val signinUrl = URI(uri.scheme, uri.authority, uri.path, null, null).toString() + "?" + queryStr
        Log.w(TAG, "Step 3: Loading OAuth signin: ${signinUrl.take(120)}")

        val signinPageResp = httpClient.newCall(
            Request.Builder().url(signinUrl).get()
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("upgrade-insecure-requests", "1")
                .header("Host", uri.host)
                .build()
        ).execute()
        val signinBody = signinPageResp.body?.string() ?: ""
        Log.w(TAG, "Sign-in page: HTTP ${signinPageResp.code}, body=${signinBody.length} chars")

        // Extract form action and hidden fields
        val actionMatch = Regex("""<form[^>]*name="signIn"[^>]*action="([^"]+)"""").find(signinBody)
            ?: Regex("""<form[^>]*action="([^"]*signin[^"]*)"[^>]*""").find(signinBody)
        formAction = actionMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""

        val fields = extractHiddenFields(signinBody)
        hiddenFields = fields
        Log.w(TAG, "Form action: ${formAction.take(100)}, fields: ${fields.keys}")

        if (formAction.isEmpty()) {
            if (signinBody.contains("captcha", ignoreCase = true)) {
                return OAuthResult(error = "Amazon is requesting a captcha. Try again later.")
            }
            return OAuthResult(error = "Could not find sign-in form on page.")
        }

        // Step 4: Submit credentials (without following redirects)
        val formBuilder = FormBody.Builder()
        for ((k, v) in fields) formBuilder.add(k, v)
        formBuilder.add("email", email)
        formBuilder.add("password", password)
        formBuilder.add("create", "0")
        formBuilder.add("metadata1", "")

        val postUrl = if (formAction.startsWith("http")) formAction
            else "https://${uri.host}$formAction"
        val origin = "https://${uri.host}"
        Log.w(TAG, "Step 4: Submitting credentials to: ${postUrl.take(100)}, appAction=${fields["appAction"]}, cookies=${cookieStore.size}")

        val credResp = noRedirectClient.newCall(
            Request.Builder().url(postUrl)
                .post(formBuilder.build())
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", origin)
                .header("Referer", signinUrl)
                .build()
        ).execute()

        val credBody = credResp.body?.string() ?: ""
        val location = credResp.header("Location")
        Log.w(TAG, "Credential POST: HTTP ${credResp.code}, location=${location?.take(120)}, body=${credBody.length}")
        if (credBody.length < 5000) {
            Log.w(TAG, "Short response body: $credBody")
        }

        // Follow redirect chain to find auth code
        if (location != null) {
            return followForAuthCode(location, uri.host)
        }

        // No redirect — parse response body
        return parseResponseBody(credResp.code, credBody)
    }

    private fun followForAuthCode(startUrl: String, host: String): OAuthResult {
        var url = if (startUrl.startsWith("http")) startUrl else "https://$host$startUrl"
        var redirectCount = 0

        while (redirectCount < 15) {
            // Check if URL contains auth code
            val code = extractAuthCode(url)
            if (code != null) {
                Log.w(TAG, "Auth code found in redirect URL!")
                return OAuthResult(authCode = code)
            }

            Log.w(TAG, "Following redirect ${redirectCount + 1}: ${url.take(120)}")

            val resp = noRedirectClient.newCall(
                Request.Builder().url(url).get()
                    .header("User-Agent", AmazonAuthService.USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
            ).execute()

            val nextLocation = resp.header("Location")
            if (nextLocation == null) {
                // Final page — parse body
                val body = resp.body?.string() ?: ""
                Log.w(TAG, "End of redirect chain: HTTP ${resp.code}, body=${body.length}")
                return parseResponseBody(resp.code, body)
            }

            resp.body?.close()
            url = if (nextLocation.startsWith("http")) nextLocation else "https://$host$nextLocation"
            redirectCount++
        }

        return OAuthResult(error = "Too many redirects")
    }

    private fun submitMfaCode(mfaCode: String): OAuthResult {
        val formBuilder = FormBody.Builder()
        for ((k, v) in hiddenFields) formBuilder.add(k, v)
        formBuilder.add("otpCode", mfaCode)
        formBuilder.add("rememberDevice", "")

        val postUrl = when {
            formAction.startsWith("http") -> formAction
            formAction.startsWith("/") -> "https://www.amazon.com$formAction"
            else -> "https://www.amazon.com/ap/cvf/$formAction"
        }
        Log.w(TAG, "Submitting verification to: ${postUrl.take(100)}")

        val resp = noRedirectClient.newCall(
            Request.Builder().url(postUrl)
                .post(formBuilder.build())
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
        ).execute()

        val body = resp.body?.string() ?: ""
        val location = resp.header("Location")
        Log.w(TAG, "MFA POST: HTTP ${resp.code}, location=${location?.take(120)}, body=${body.length}")

        if (location != null) return followForAuthCode(location, "www.amazon.com")
        return parseResponseBody(resp.code, body)
    }

    private fun extractAuthCode(url: String): String? {
        return Regex("openid\\.oa2\\.authorization_code=([^&]+)").find(url)?.groupValues?.get(1)
    }

    private fun parseResponseBody(statusCode: Int, body: String): OAuthResult {
        // Check for auth code in body
        val codeMatch = Regex("""authorization_code["']\s*value=["']([^"']+)""").find(body)
        if (codeMatch != null) return OAuthResult(authCode = codeMatch.groupValues[1])

        // Check for cookie error (Amazon rejects when cookies are stale/missing)
        if (body.contains("Please Enable Cookies", ignoreCase = true) ||
            body.contains("enable-cookies", ignoreCase = true)) {
            Log.w(TAG, "Cookie error detected — session was stale")
            return OAuthResult(error = "Session expired. Please try again.")
        }

        // Check for CVF (Customer Verification Flow) — Amazon sends email/push notification
        if (body.contains("/ap/cvf") || body.contains("cvf-widget") ||
            body.contains("fwcim-form") || body.contains("verification-code-form")) {
            val actionMatch = Regex("""<form[^>]*id="verification-code-form"[^>]*action="([^"]+)"""").find(body)
                ?: Regex("""<form[^>]*id="fwcim-form"[^>]*action="([^"]+)"""").find(body)
                ?: Regex("""<form[^>]*action="([^"]+)"[^>]*""").find(body)
            if (actionMatch != null) formAction = actionMatch.groupValues[1].replace("&amp;", "&")
            hiddenFields = extractHiddenFields(body)
            Log.w(TAG, "CVF verification page, form=${formAction.take(80)}, fields=${hiddenFields.keys}")
            return OAuthResult(needsMfa = true, mfaMessage = "Amazon sent a verification code to your email. Enter it below.")
        }

        // Check for TOTP MFA
        if (body.contains("auth-mfa-form") || body.contains("verifyOtp")) {
            val actionMatch = Regex("""<form[^>]*id="auth-mfa-form"[^>]*action="([^"]+)"""").find(body)
                ?: Regex("""<form[^>]*action="([^"]+)"[^>]*""").find(body)
            if (actionMatch != null) formAction = actionMatch.groupValues[1].replace("&amp;", "&")
            hiddenFields = extractHiddenFields(body)
            Log.w(TAG, "MFA page detected, form=${formAction.take(80)}, fields=${hiddenFields.keys}")
            return OAuthResult(needsMfa = true, mfaMessage = "Enter the MFA code from your authenticator app.")
        }

        // Check for errors
        val errorMatch = Regex("""auth-error-message[^>]*>\s*<[^>]*>([^<]+)""").find(body)
        if (errorMatch != null) return OAuthResult(error = errorMatch.groupValues[1].trim())

        val alertMatch = Regex("""a-alert-content[^>]*>\s*([^<]+)""").find(body)
        if (alertMatch != null) {
            val msg = alertMatch.groupValues[1].trim()
            if (msg.isNotEmpty() && msg.length < 200) return OAuthResult(error = msg)
        }

        if (body.contains("captcha", ignoreCase = true))
            return OAuthResult(error = "Amazon is requesting a captcha. Try again later.")

        // Log title for debugging
        val title = Regex("<title>([^<]*)</title>").find(body)?.groupValues?.get(1) ?: "none"
        Log.w(TAG, "Unparsed page (HTTP $statusCode), title=$title")

        return OAuthResult(error = "Unexpected response (HTTP $statusCode). Check email/password and try again.")
    }

    private fun extractHiddenFields(html: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        Regex("""<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*/?>""")
            .findAll(html).forEach { fields[it.groupValues[1]] = it.groupValues[2] }
        Regex("""<input[^>]*name="([^"]+)"[^>]*type="hidden"[^>]*value="([^"]*)"[^>]*/?>""")
            .findAll(html).forEach { fields[it.groupValues[1]] = it.groupValues[2] }
        return fields
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    }

    // --- Device Registration ---

    private fun registerDevice(authCode: String) {
        showLoading(true)
        scope.launch {
            try {
                val tokenData = withContext(Dispatchers.IO) { performDeviceRegistration(authCode) }
                val tokenJson = gson.toJson(tokenData)
                // Write to app-internal storage (always writable)
                tokenFile.writeText(tokenJson)
                Log.w(TAG, "Token saved to ${tokenFile.path}")
                // Also try legacy path for backward compatibility
                try { LEGACY_TOKEN_FILE.writeText(tokenJson) } catch (_: Exception) {}
                Log.w(TAG, "Device registered and token saved")
                showStatus("Login successful!")
                tvStatus.setTextColor(0xFF00CC00.toInt())
                tvStatus.visibility = View.VISIBLE
                launchMain()
            } catch (e: Exception) {
                Log.w(TAG, "Registration failed: ${e.message}", e)
                showStatus("Registration failed: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }

    private fun performDeviceRegistration(authCode: String): TokenData {
        val regData = mapOf(
            "domain" to "DeviceLegacy",
            "device_type" to AmazonAuthService.DEVICE_TYPE_ID,
            "device_serial" to deviceId,
            "app_name" to AmazonAuthService.APP_NAME,
            "app_version" to AmazonAuthService.APP_VERSION,
            "device_model" to AmazonAuthService.DEVICE_MODEL,
            "os_version" to AmazonAuthService.OS_VERSION
        )

        val authData = mapOf(
            "client_id" to clientId,
            "authorization_code" to authCode,
            "code_verifier" to codeVerifier,
            "code_algorithm" to "SHA-256",
            "client_domain" to "DeviceLegacy"
        )

        val payload = mapOf(
            "auth_data" to authData,
            "registration_data" to regData,
            "requested_token_type" to listOf("bearer", "website_cookies"),
            "requested_extensions" to listOf("device_info", "customer_info"),
            "cookies" to mapOf("domain" to ".$domain", "website_cookies" to emptyList<String>())
        )

        val requestBody = gson.toJson(payload)
            .toRequestBody("application/json".toMediaType())

        val reqId = UUID.randomUUID().toString().replace("-", "")
        val request = Request.Builder()
            .url("https://api.$domain/auth/register")
            .post(requestBody)
            .header("User-Agent", AmazonAuthService.USER_AGENT)
            .header("Accept-Charset", "utf-8")
            .header("X-Requested-With", AmazonAuthService.APP_NAME)
            .header("x-gasc-enabled", "true")
            .header("Accept-Language", "en-US")
            .header("Content-Type", "application/json")
            .header("x-amzn-identity-auth-domain", "api.$domain")
            .header("x-amzn-requestid", reqId)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty registration response")

        Log.w(TAG, "Registration: HTTP ${response.code}, body=${responseBody.length}")

        if (!response.isSuccessful) {
            val errorJson = try { gson.fromJson(responseBody, JsonObject::class.java) } catch (_: Exception) { null }
            val errorMsg = errorJson?.getAsJsonObject("response")?.getAsJsonObject("error")
                ?.get("message")?.asString ?: "HTTP ${response.code}"
            throw RuntimeException(errorMsg)
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val respObj = json.getAsJsonObject("response")
            ?: throw RuntimeException("Missing 'response'")
        if (respObj.has("error")) {
            throw RuntimeException(respObj.getAsJsonObject("error").get("message")?.asString ?: "Unknown error")
        }

        val bearer = respObj.getAsJsonObject("success")
            ?.getAsJsonObject("tokens")?.getAsJsonObject("bearer")
            ?: throw RuntimeException("No bearer token in response")

        val accessToken = bearer.get("access_token")?.asString ?: throw RuntimeException("No access_token")
        val refreshToken = bearer.get("refresh_token")?.asString ?: throw RuntimeException("No refresh_token")
        val expiresIn = bearer.get("expires_in")?.asLong ?: 3600L

        return TokenData(
            accessToken = accessToken,
            refreshToken = refreshToken,
            deviceId = deviceId,
            expiresIn = expiresIn,
            expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        )
    }

    // --- UI helpers ---

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
    }

    private fun showStatus(message: String) {
        if (message.isEmpty()) {
            tvStatus.visibility = View.GONE
        } else {
            tvStatus.text = message
            tvStatus.setTextColor(0xFFFF4444.toInt())
            tvStatus.visibility = View.VISIBLE
        }
    }

    private fun launchMain() {
        // Clear logout timestamp — legacy token is fully usable again after a real login
        getSharedPreferences("auth", MODE_PRIVATE).edit().remove("logged_out_at").apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
