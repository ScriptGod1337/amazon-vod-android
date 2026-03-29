package com.scriptgod.fireos.avod.auth

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.scriptgod.fireos.avod.model.TokenData
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Coordinates the Amazon OAuth device-registration login flow.
 * Mirrors register_device.py exactly — no UI references.
 *
 * Flow:
 * 1. Generate PKCE verifier/challenge, device_id, client_id, cookies
 * 2. Open amazon.com → follow sign-in link → get OAuth URL
 * 3. Rebuild URL with PKCE params → load sign-in form
 * 4. Submit email/password → get auth code or MFA challenge
 * 5. POST /auth/register with auth code + PKCE verifier → get tokens
 */
class LoginFlow {

    data class Result(
        val authCode: String? = null,
        val needsMfa: Boolean = false,
        val mfaMessage: String? = null,
        val error: String? = null
    )

    companion object {
        private const val TAG = "LoginFlow"
    }

    private val gson = Gson()

    // Session cookies — deduped by name+domain
    private val cookieStore = mutableMapOf<String, Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) cookieStore["${c.domain}:${c.name}"] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore.values.filter { it.matches(url) }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .cookieJar(cookieJar)
        // Identify requests as coming from the Amazon app — matches what register_device.py sends.
        // Without these headers Amazon serves a browser-mode login page that requires JS cookies.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("X-Requested-With", AmazonAuthService.APP_NAME)
                .header("x-gasc-enabled", "true")
                .build())
        }
        .build()

    private val noRedirectClient: OkHttpClient = httpClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).build()

    // PKCE
    private var codeVerifier: String = ""
    private var codeChallenge: String = ""

    // Device identity (matching register_device.py exactly)
    var deviceId: String = ""; private set
    var clientId: String = ""; private set

    // OAuth state preserved across the performFullLogin → submitMfaCode boundary
    private var formAction: String = ""
    private var hiddenFields: Map<String, String> = emptyMap()
    private var domain: String = "amazon.com"

    init { refreshIdentity() }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the full login sequence for [email] / [password].
     * Refreshes session identity on each call (fresh PKCE, new device_id, cleared cookies).
     * Returns [Result.authCode] on success, [Result.needsMfa] when a second factor is required,
     * or [Result.error] on failure.
     */
    fun performFullLogin(email: String, password: String): Result {
        cookieStore.clear()
        refreshIdentity()
        setupFraudCookies()
        Log.w(TAG, "Fresh session: cookies cleared, new PKCE + device identity")

        // Step 1: Open amazon.com to establish session
        Log.w(TAG, "Step 1: Opening amazon.com...")
        val homeBody = httpClient.newCall(
            Request.Builder().url("https://www.amazon.com").get()
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("upgrade-insecure-requests", "1")
                .build()
        ).execute().also { Log.w(TAG, "Homepage: HTTP ${it.code}, cookies=${cookieStore.size}") }
            .body?.string() ?: ""

        // Step 2: Follow sign-in link to get the OAuth base URL
        val signInLink = Regex("""href="([^"]*nav-show-sign-in[^"]*|/ap/signin[^"]*)"[^>]*class="[^"]*nav-show-sign-in""")
            .find(homeBody)?.groupValues?.get(1)
            ?: Regex("""class="[^"]*nav-show-sign-in[^"]*"[^>]*href="([^"]+)"""").find(homeBody)?.groupValues?.get(1)
            ?: Regex("""href="([^"]*/ap/signin[^"]*)""").find(homeBody)?.groupValues?.get(1)

        var oauthBaseUrl: String
        if (signInLink != null) {
            val fullLink = if (signInLink.startsWith("http")) signInLink
                else "https://www.amazon.com${signInLink.replace("&amp;", "&")}"
            Log.w(TAG, "Step 2: Following sign-in link: ${fullLink.take(100)}")
            val resp = httpClient.newCall(Request.Builder().url(fullLink).get()
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml").build()).execute()
            oauthBaseUrl = resp.request.url.toString()
            resp.body?.string()
        } else {
            oauthBaseUrl = "https://www.amazon.com/ap/signin"
            Log.w(TAG, "Step 2: No sign-in link found, using default")
        }

        // Step 3: Rebuild URL with PKCE params (matching register_device.py)
        val uri = URI(oauthBaseUrl)
        val baseQuery = parseQueryString(uri.rawQuery ?: "")
        val origReturnTo = baseQuery["openid.return_to"] ?: ""
        val returnToUri = if (origReturnTo.isNotEmpty()) {
            val rt = URI(origReturnTo)
            URI(rt.scheme, rt.authority, "/ap/maplanding", null, null).toString()
        } else "https://www.amazon.com/ap/maplanding"

        val params = baseQuery.toMutableMap().apply {
            put("openid.assoc_handle", "amzn_piv_android_v2_us")
            put("openid.return_to", returnToUri)
            put("openid.oa2.response_type", "code")
            put("openid.oa2.code_challenge_method", "S256")
            put("openid.oa2.code_challenge", codeChallenge)
            put("pageId", "amzn_dv_ios_blue")
            put("openid.ns.oa2", "http://www.amazon.com/ap/ext/oauth/2")
            put("openid.oa2.client_id", "device:$clientId")
            put("openid.ns.pape", "http://specs.openid.net/extensions/pape/1.0")
            put("openid.oa2.scope", "device_auth_access")
            put("openid.mode", "checkid_setup")
            put("openid.identity", "http://specs.openid.net/auth/2.0/identifier_select")
            put("openid.ns", "http://specs.openid.net/auth/2.0")
            put("accountStatusPolicy", "P1")
            put("openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select")
            put("language", "en_US")
            put("disableLoginPrepopulate", "0")
            put("openid.pape.max_auth_age", "0")
        }
        val queryStr = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val signinUrl = URI(uri.scheme, uri.authority, uri.path, null, null).toString() + "?" + queryStr
        Log.w(TAG, "Step 3: Loading OAuth signin: ${signinUrl.take(120)}")

        val signinBody = httpClient.newCall(
            Request.Builder().url(signinUrl).get()
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("upgrade-insecure-requests", "1")
                .header("Host", uri.host)
                .build()
        ).execute().also { Log.w(TAG, "Sign-in page: HTTP ${it.code}") }.body?.string() ?: ""

        // Extract form action and hidden fields
        val actionMatch = Regex("""<form[^>]*name="signIn"[^>]*action="([^"]+)"""").find(signinBody)
            ?: Regex("""<form[^>]*action="([^"]*signin[^"]*)"[^>]*""").find(signinBody)
        formAction = actionMatch?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
        hiddenFields = extractHiddenFields(signinBody)
        Log.w(TAG, "Form action: ${formAction.take(100)}, fields: ${hiddenFields.keys}")

        if (formAction.isEmpty()) {
            if (signinBody.contains("captcha", ignoreCase = true))
                return Result(error = "Amazon is requesting a captcha. Try again later.")
            return Result(error = "Could not find sign-in form on page.")
        }

        // Step 4: Submit credentials without following redirects
        val formBody = FormBody.Builder().apply {
            for ((k, v) in hiddenFields) add(k, v)
            add("email", email); add("password", password)
            add("create", "0"); add("metadata1", "")
        }.build()
        val postUrl = if (formAction.startsWith("http")) formAction else "https://${uri.host}$formAction"
        Log.w(TAG, "Step 4: Submitting credentials to: ${postUrl.take(100)}, appAction=${hiddenFields["appAction"]}, cookies=${cookieStore.size}")

        val credResp = noRedirectClient.newCall(
            Request.Builder().url(postUrl).post(formBody)
                .header("User-Agent", AmazonAuthService.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Origin", "https://${uri.host}")
                .header("Referer", signinUrl)
                .build()
        ).execute()
        val credBody = credResp.body?.string() ?: ""
        val location = credResp.header("Location")
        Log.w(TAG, "Credential POST: HTTP ${credResp.code}, location=${location?.take(120)}, body=${credBody.length} chars")

        if (location != null) return followForAuthCode(location, uri.host)
        return parseResponseBody(credResp.code, credBody)
    }

    /**
     * Submits a second-factor code after [performFullLogin] returned [Result.needsMfa].
     * Re-uses the [formAction] and [hiddenFields] captured during the login sequence.
     */
    fun submitMfaCode(mfaCode: String): Result {
        val formBody = FormBody.Builder().apply {
            for ((k, v) in hiddenFields) add(k, v)
            add("otpCode", mfaCode); add("rememberDevice", "")
        }.build()
        val postUrl = when {
            formAction.startsWith("http") -> formAction
            formAction.startsWith("/")    -> "https://www.amazon.com$formAction"
            else                          -> "https://www.amazon.com/ap/cvf/$formAction"
        }
        Log.w(TAG, "Submitting verification to: ${postUrl.take(100)}")

        val resp = noRedirectClient.newCall(
            Request.Builder().url(postUrl).post(formBody)
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

    /** Exchanges an [authCode] for tokens by calling Amazon's /auth/register endpoint. */
    fun performDeviceRegistration(authCode: String): TokenData {
        val regData = mapOf(
            "domain"        to "DeviceLegacy",
            "device_type"   to AmazonAuthService.DEVICE_TYPE_ID,
            "device_serial" to deviceId,
            "app_name"      to AmazonAuthService.APP_NAME,
            "app_version"   to AmazonAuthService.APP_VERSION,
            "device_model"  to AmazonAuthService.DEVICE_MODEL,
            "os_version"    to AmazonAuthService.OS_VERSION
        )
        val authData = mapOf(
            "client_id"          to clientId,
            "authorization_code" to authCode,
            "code_verifier"      to codeVerifier,
            "code_algorithm"     to "SHA-256",
            "client_domain"      to "DeviceLegacy"
        )
        val payload = mapOf(
            "auth_data"              to authData,
            "registration_data"      to regData,
            "requested_token_type"   to listOf("bearer", "website_cookies"),
            "requested_extensions"   to listOf("device_info", "customer_info"),
            "cookies"                to mapOf("domain" to ".$domain", "website_cookies" to emptyList<String>())
        )

        val reqId = UUID.randomUUID().toString().replace("-", "")
        val response = httpClient.newCall(
            Request.Builder()
                .url("https://api.$domain/auth/register")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .header("User-Agent",                   AmazonAuthService.USER_AGENT)
                .header("Accept-Charset",               "utf-8")
                .header("X-Requested-With",             AmazonAuthService.APP_NAME)
                .header("x-gasc-enabled",               "true")
                .header("Accept-Language",              "en-US")
                .header("Content-Type",                 "application/json")
                .header("x-amzn-identity-auth-domain", "api.$domain")
                .header("x-amzn-requestid",             reqId)
                .build()
        ).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("Empty registration response")
        Log.w(TAG, "Registration: HTTP ${response.code}, body=${responseBody.length}")

        if (!response.isSuccessful) {
            val errorMsg = try {
                gson.fromJson(responseBody, JsonObject::class.java)
                    ?.getAsJsonObject("response")?.getAsJsonObject("error")
                    ?.get("message")?.asString
            } catch (_: Exception) { null } ?: "HTTP ${response.code}"
            throw RuntimeException(errorMsg)
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val respObj = json.getAsJsonObject("response") ?: throw RuntimeException("Missing 'response'")
        if (respObj.has("error"))
            throw RuntimeException(respObj.getAsJsonObject("error").get("message")?.asString ?: "Unknown error")

        val bearer = respObj.getAsJsonObject("success")
            ?.getAsJsonObject("tokens")?.getAsJsonObject("bearer")
            ?: throw RuntimeException("No bearer token in response")

        return TokenData(
            accessToken  = bearer.get("access_token")?.asString  ?: throw RuntimeException("No access_token"),
            refreshToken = bearer.get("refresh_token")?.asString ?: throw RuntimeException("No refresh_token"),
            deviceId     = deviceId,
            expiresIn    = bearer.get("expires_in")?.asLong ?: 3600L,
            expiresAt    = System.currentTimeMillis() / 1000 + (bearer.get("expires_in")?.asLong ?: 3600L)
        )
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun refreshIdentity() {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        codeVerifier  = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        codeChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray()))
        deviceId = UUID.randomUUID().toString().replace("-", "").lowercase()
        clientId = hexEncode("${deviceId}#A1MPSLFC7L5AFK")
    }

    private fun setupFraudCookies() {
        val frc = Base64.getEncoder().encodeToString(ByteArray(313).also { SecureRandom().nextBytes(it) })
        val mapMd = mapOf(
            "device_registration_data" to mapOf("software_version" to "130050002"),
            "app_identifier" to mapOf(
                "package"        to "com.amazon.avod.thirdpartyclient",
                "SHA-256"        to listOf("2f19adeb284eb36f7f07786152b9a1d14b21653203ad0b04ebbf9c73ab6d7625"),
                "app_version"    to "351003955",
                "app_version_name" to "3.0.351.3955",
                "app_sms_hash"   to "e0kK4QFSWp0",
                "map_version"    to "MAPAndroidLib-1.3.14913.0"
            ),
            "app_info" to mapOf(
                "auto_pv" to 0, "auto_pv_with_smsretriever" to 0,
                "smartlock_supported" to 0, "permission_runtime_grant" to 0
            )
        )
        val mapMdEncoded = Base64.getEncoder().encodeToString(gson.toJson(mapMd).toByteArray())
        val expiry = (System.currentTimeMillis() / 1000 + 86400) * 1000
        cookieStore["amazon.com:frc"]    = Cookie.Builder().domain("amazon.com").path("/").name("frc").value(frc).expiresAt(expiry).build()
        cookieStore["amazon.com:map-md"] = Cookie.Builder().domain("amazon.com").path("/").name("map-md").value(mapMdEncoded).expiresAt(expiry).build()
        cookieStore["amazon.com:sid"]    = Cookie.Builder().domain("amazon.com").path("/").name("sid").value("").expiresAt(expiry).build()
    }

    private fun followForAuthCode(startUrl: String, host: String): Result {
        var url = if (startUrl.startsWith("http")) startUrl else "https://$host$startUrl"
        repeat(15) {
            extractAuthCode(url)?.let { return Result(authCode = it).also { Log.w(TAG, "Auth code found in redirect URL!") } }
            Log.w(TAG, "Following redirect ${it + 1}: ${url.take(120)}")
            val resp = noRedirectClient.newCall(
                Request.Builder().url(url).get()
                    .header("User-Agent", AmazonAuthService.USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml").build()
            ).execute()
            val next = resp.header("Location") ?: run {
                val body = resp.body?.string() ?: ""
                Log.w(TAG, "End of redirect chain: HTTP ${resp.code}, body=${body.length}")
                return parseResponseBody(resp.code, body)
            }
            resp.body?.close()
            url = if (next.startsWith("http")) next else "https://$host$next"
        }
        return Result(error = "Too many redirects")
    }

    private fun parseResponseBody(statusCode: Int, body: String): Result {
        Regex("""authorization_code["']\s*value=["']([^"']+)""").find(body)
            ?.let { return Result(authCode = it.groupValues[1]) }

        if (body.contains("Please Enable Cookies", ignoreCase = true) ||
            body.contains("enable-cookies", ignoreCase = true)) {
            Log.w(TAG, "Cookie error — session was stale")
            return Result(error = "Session expired. Please try again.")
        }

        // CVF (Customer Verification Flow — email/push notification)
        if (body.contains("/ap/cvf") || body.contains("cvf-widget") ||
            body.contains("fwcim-form") || body.contains("verification-code-form")) {
            val action = (Regex("""<form[^>]*id="verification-code-form"[^>]*action="([^"]+)"""").find(body)
                ?: Regex("""<form[^>]*id="fwcim-form"[^>]*action="([^"]+)"""").find(body)
                ?: Regex("""<form[^>]*action="([^"]+)"[^>]*""").find(body))
                ?.groupValues?.get(1)?.replace("&amp;", "&")
            if (action != null) formAction = action
            hiddenFields = extractHiddenFields(body)
            Log.w(TAG, "CVF page, form=${formAction.take(80)}, fields=${hiddenFields.keys}")
            return Result(needsMfa = true, mfaMessage = "Amazon sent a verification code to your email. Enter it below.")
        }

        // TOTP MFA
        if (body.contains("auth-mfa-form") || body.contains("verifyOtp")) {
            val action = (Regex("""<form[^>]*id="auth-mfa-form"[^>]*action="([^"]+)"""").find(body)
                ?: Regex("""<form[^>]*action="([^"]+)"[^>]*""").find(body))
                ?.groupValues?.get(1)?.replace("&amp;", "&")
            if (action != null) formAction = action
            hiddenFields = extractHiddenFields(body)
            Log.w(TAG, "MFA page, form=${formAction.take(80)}, fields=${hiddenFields.keys}")
            return Result(needsMfa = true, mfaMessage = "Enter the MFA code from your authenticator app.")
        }

        Regex("""auth-error-message[^>]*>\s*<[^>]*>([^<]+)""").find(body)
            ?.let { return Result(error = it.groupValues[1].trim()) }
        Regex("""a-alert-content[^>]*>\s*([^<]+)""").find(body)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() && it.length < 200 }
            ?.let { return Result(error = it) }

        if (body.contains("captcha", ignoreCase = true))
            return Result(error = "Amazon is requesting a captcha. Try again later.")

        val title = Regex("<title>([^<]*)</title>").find(body)?.groupValues?.get(1) ?: "none"
        Log.w(TAG, "Unparsed page (HTTP $statusCode), title=$title")
        return Result(error = "Unexpected response (HTTP $statusCode). Check email/password and try again.")
    }

    private fun extractAuthCode(url: String): String? =
        Regex("openid\\.oa2\\.authorization_code=([^&]+)").find(url)?.groupValues?.get(1)

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
            URLDecoder.decode(parts[0], "UTF-8") to if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
        }
    }

    private fun hexEncode(s: String): String = s.toByteArray().joinToString("") { "%02x".format(it) }
}
