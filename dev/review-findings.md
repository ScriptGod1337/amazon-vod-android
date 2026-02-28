# Code Review Findings

**Date**: 2026-02-28
**Reviewer**: AI Agent
**Version reviewed**: v2026.02.28.5
**Commit reviewed**: `3ddffeaaefaa4b0fa755b77364aaa4042b540550`

---

## Summary

| Severity | Count |
|----------|-------|
| 🔴 Critical | 0 |
| 🟡 Warning | 10 |
| 🔵 Info | 0 |
| ✅ OK | 47 |

47 of 53 checklist items passed. Four additional warnings below are outside the checklist.

---

## Findings

### FINDING-001 — Catalog requests ignore the documented POST-with-empty-body workaround
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/api/AmazonApiService.kt:553`
**Checklist item**: Not covered by checklist (conflicts with `dev/analysis/decisions.md` workaround)
**Description**: The catalog helpers send switchblade and mobile catalog requests as `GET`. The project’s own workaround says these Android catalog endpoints must use `postdata=''` because some endpoints reject `GET`. Keeping the request method wrong at the shared helper layer risks intermittent empty catalogs or territory-specific browse failures across home, watchlist, library, search, and detail pages.
**Evidence**:
```kotlin
private fun getSwitchbladePage(transform: String, extraParams: String = ""): List<ContentItem> {
    val params = baseParams() + if (extraParams.isNotEmpty()) "&$extraParams" else ""
    val url = "$atvUrl/cdp/switchblade/android/getDataByJvmTransform/v1/$transform?$params"
    return fetchAndParseContentItems(url)
}

private fun fetchAndParseContentItems(url: String): List<ContentItem> {
    val request = Request.Builder()
        .url(url)
        .get()
        .build()
```
**Suggested fix** (do not implement): Change the shared catalog request helpers to send a POST with an empty form body, then re-test home, search, watchlist, library, and detail endpoints against the documented workaround.

---

### FINDING-002 — Successful login leaves the password field populated
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt:632`
**Checklist item**: Security — Password cleared after use
**Description**: After device registration succeeds, the activity updates status text and navigates to `MainActivity`, but it never clears `etPassword`. If the activity remains in memory during configuration changes or backgrounding, the plaintext password stays in the UI state longer than necessary.
**Evidence**:
```kotlin
private fun registerDevice(authCode: String) {
    showLoading(true)
    scope.launch {
        try {
            val tokenData = withContext(Dispatchers.IO) { performDeviceRegistration(authCode) }
            val tokenJson = gson.toJson(tokenData)
            tokenFile.writeText(tokenJson)
            try { LEGACY_TOKEN_FILE.writeText(tokenJson) } catch (_: Exception) {}
            showStatus("Login successful!")
            tvStatus.setTextColor(0xFF00CC00.toInt())
            tvStatus.visibility = View.VISIBLE
            launchMain()
```
**Suggested fix** (do not implement): Clear the password field immediately after a successful credential submission or at least before navigating away on successful device registration.

---

### FINDING-003 — Login-only headers are added to every authenticated API client
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/auth/AmazonAuthService.kt:145`
**Checklist item**: Security — `x-gasc-enabled` / `X-Requested-With` scope
**Description**: `buildAuthenticatedClient()` adds `AndroidHeadersInterceptor()` to every authenticated client, so catalog, playback, watchlist, PES, and DRM license requests all carry the login-app identity headers. The review guidance explicitly scopes these headers to the login client only. Even though refresh calls use a separate client, the shared API client still violates that boundary and can change how Amazon classifies non-login requests.
**Evidence**:
```kotlin
fun buildAuthenticatedClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor())
        .addInterceptor(AndroidHeadersInterceptor())
        .addInterceptor(NetworkLogInterceptor())
        .build()
}
```
**Suggested fix** (do not implement): Keep `X-Requested-With` and `x-gasc-enabled` on the dedicated login client only, and use a separate authenticated client for catalog/playback requests that sends the normal auth/device headers without the login-flow app-identity pair.

---

### FINDING-004 — PlayerActivity launches uncancelled work that can outlive the activity
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:73`
**Checklist item**: Player — No crash if user navigates back during DRM provisioning or manifest load; Architecture & Code Quality — Coroutine scope is tied to lifecycle
**Description**: `PlayerActivity` uses its own `CoroutineScope(Dispatchers.Main + Job())`, launches manifest/loading work on it, and never cancels that job in `onDestroy()`. If the user backs out while territory detection, playback resource loading, or DRM setup is still running, the coroutine can resume after the activity has been destroyed and create a new player against stale view/activity references. That is both a lifecycle leak and a real exit-time stability risk.
**Evidence**:
```kotlin
private val scope = CoroutineScope(Dispatchers.Main + Job())

private fun loadAndPlay(asin: String, materialType: String = "Feature") {
    progressBar.visibility = View.VISIBLE
    tvError.visibility = View.GONE

    scope.launch {
        try {
            val info = withContext(Dispatchers.IO) {
                apiService.detectTerritory()
                apiService.getPlaybackInfo(asin, materialType)
            }
            setupPlayer(info)
```
**Suggested fix** (do not implement): Use `lifecycleScope` or store the parent `Job` and cancel it in `onDestroy()`. Guard the post-load path so `setupPlayer()` does not run once the activity is finishing or destroyed.

---

### FINDING-005 — CI workflow never deletes the decoded release keystore
**Severity**: 🟡 Warning
**File**: `.github/workflows/build.yml:73`
**Checklist item**: CI/CD — Keystore file decoded from base64 and used, then deleted after build
**Description**: The workflow writes `release.keystore` into the workspace and exports its path into `GITHUB_ENV`, but there is no cleanup step after the APK is built and renamed. That leaves the decrypted keystore on disk for the rest of the job lifetime and any later step running in the same workspace.
**Evidence**:
```yaml
- name: Decode release keystore
  run: |
    echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 -d > release.keystore

- name: Set up signing config
  run: |
    echo "RELEASE_STORE_FILE=${{ github.workspace }}/release.keystore" >> $GITHUB_ENV
    echo "RELEASE_STORE_PASSWORD=${{ secrets.RELEASE_STORE_PASSWORD }}" >> $GITHUB_ENV
    echo "RELEASE_KEY_ALIAS=${{ secrets.RELEASE_KEY_ALIAS }}" >> $GITHUB_ENV
    echo "RELEASE_KEY_PASSWORD=${{ secrets.RELEASE_KEY_PASSWORD }}" >> $GITHUB_ENV
```
**Suggested fix** (do not implement): Add a cleanup step guarded with `if: always()` that removes `release.keystore` after signing/building completes, regardless of success or failure.

---

### FINDING-006 — Date-only versionCode is not strictly monotonic
**Severity**: 🟡 Warning
**File**: `.github/workflows/build.yml:87`
**Checklist item**: CI/CD — Version code is monotonically increasing
**Description**: The workflow derives `versionCodeOverride` from `$(date -u +%Y%m%d)`. Any two releases built on the same UTC day will get the same Android `versionCode`, which breaks the “strictly increasing” requirement for upgradeability and makes same-day rebuilds ambiguous.
**Evidence**:
```yaml
- name: Build release APK
  run: |
    gradle assembleRelease \
      -PversionNameOverride=${{ steps.version.outputs.version }} \
      -PversionCodeOverride=$(date -u +%Y%m%d)
```
**Suggested fix** (do not implement): Include an intra-day increment in `versionCode` as well, for example a date-plus-sequence scheme or a timestamp that still fits Android’s integer limits.

---

### FINDING-007 — LoginActivity trims the password before submitting it
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt:239`
**Checklist item**: Not covered by checklist
**Description**: The login form strips leading and trailing whitespace from the password before sending it to Amazon. Passwords are opaque secrets, so mutating user input can cause valid credentials to fail unexpectedly for accounts whose password intentionally starts or ends with whitespace.
**Evidence**:
```kotlin
private fun onLoginClicked() {
    val email = etEmail.text.toString().trim()
    val password = etPassword.text.toString().trim()

    if (email.isEmpty() || password.isEmpty()) {
        showStatus("Please enter email and password")
```
**Suggested fix** (do not implement): Trim the email field if desired, but pass the password exactly as entered.

---

### FINDING-008 — Login coroutines are not tied to the activity lifecycle
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt:96`
**Checklist item**: Not covered by checklist
**Description**: `LoginActivity` uses its own `CoroutineScope(Dispatchers.Main + Job())` and launches network work on it, but never cancels that scope when the activity is destroyed. If the user backs out, rotates, or the activity is recreated during login, the coroutine can still resume and call UI methods or `launchMain()` against a dead activity instance.
**Evidence**:
```kotlin
private val scope = CoroutineScope(Dispatchers.Main + Job())

private fun doLogin(email: String, password: String) {
    showLoading(true)
    showStatus("")

    scope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                performFullLogin(email, password)
```
**Suggested fix** (do not implement): Replace the custom scope with `lifecycleScope` or keep the parent `Job` and cancel it in `onDestroy()`.

---

### FINDING-009 — MainActivity re-sorts every flat grid alphabetically and overrides intended ordering
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:766`
**Checklist item**: Not covered by checklist
**Description**: `showItems()` sorts every flat-grid result by title before displaying it. That silently overrides server-provided ordering for search, watchlist, and Freevee, and it also defeats `LibrarySort.DATE_ADDED` and `LibrarySort.TITLE_ZA`, because the already-sorted library results are forced back into ascending A-Z order in the UI.
**Evidence**:
```kotlin
private fun showItems(items: List<ContentItem>) {
    progressBar.visibility = View.GONE
    if (items.isEmpty()) {
        tvError.text = "No content found"
        tvError.visibility = View.VISIBLE
    } else {
        val markedItems = items
            .map { item ->
                item.copy(
                    isInWatchlist = watchlistAsins.contains(item.asin),
                    watchProgressMs = progressMs,
                    runtimeMs = runtimeMs
                )
            }
            .sortedBy { it.title.lowercase() }
        adapter.submitList(markedItems)
```
**Suggested fix** (do not implement): Preserve the incoming list order by default, and only apply client-side sorting in the specific flows that explicitly request it.

---

### FINDING-010 — Player stops itself in `onStop()` but does not re-prepare on resume
**Severity**: 🟡 Warning
**File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:428`
**Checklist item**: Not covered by checklist
**Description**: `onStop()` calls `player?.stop()` for every stop transition, but `onResume()` only calls `player?.play()`. After a background/foreground cycle, the player can be left in an idle, stopped state without being prepared again, so playback may fail to resume automatically.
**Evidence**:
```kotlin
override fun onResume() {
    super.onResume()
    player?.play()
}

override fun onStop() {
    super.onStop()
    saveResumePosition()
    if (streamReportingStarted) {
        stopStreamReporting()
        streamReportingStarted = false
    }
    player?.stop()
}
```
**Suggested fix** (do not implement): Pause in `onStop()` instead of stopping, or explicitly call `prepare()` again on resume before attempting to play.

---

## Checklist Results

### Security
- [x] No token logging — ✅ OK
- [x] No hardcoded credentials — ✅ OK
- [x] Token file permissions — ✅ OK
- [x] HTTPS only — ✅ OK
- [ ] Password cleared after use — ❌ FINDING-002
- [x] PKCE verifier ephemeral — ✅ OK
- [x] Release keystore not committed with real credentials — ✅ OK
- [ ] `x-gasc-enabled` / `X-Requested-With` scope — ❌ FINDING-003
- [x] No sensitive data in SharedPreferences — ✅ OK
- [x] Intent extras — ✅ OK

### Login Flow
- [x] PKCE: 32 random bytes → base64url verifier, SHA-256 → base64url challenge — ✅ OK
- [x] `client_id` format: `hex(device_id)#A1MPSLFC7L5AFK` — ✅ OK
- [x] Cookie jar: map-based, deduplicated by `domain:name`, cleared on each attempt — ✅ OK
- [x] FRC + map-md cookies set before first request — ✅ OK
- [x] OkHttp interceptor adds `X-Requested-With` + `x-gasc-enabled` to every request on `httpClient` (login flow client) — ✅ OK
- [x] OAuth URL follows sign-in link and injects PKCE params — ✅ OK
- [x] Credential POST includes `Origin` header, does NOT follow redirects — ✅ OK
- [x] CVF (email code) handled separately from TOTP MFA — ✅ OK
- [x] Device registration POST sends auth code + PKCE verifier — ✅ OK
- [x] Token stored to `filesDir/.device-token`; falls back to legacy path — ✅ OK

### Token File Resolution (`findTokenFile()`)
- [x] Internal `filesDir/.device-token` checked first — ✅ OK
- [x] `logged_out_at` pref read from `auth` SharedPreferences — ✅ OK
- [x] Legacy token: `lastModified() > logged_out_at` → accept (and clear the pref) — ✅ OK
- [x] Legacy token: `lastModified() ≤ logged_out_at` → skip (stale, return null) — ✅ OK
- [x] No logout recorded (`loggedOutAt == 0L`) → accept legacy token — ✅ OK
- [x] Returns `null` if neither file exists → LoginActivity shows login form — ✅ OK

### Logout (`AboutActivity.performLogout()`)
- [x] Deletes `filesDir/.device-token` — ✅ OK
- [x] Attempts to delete `/data/local/tmp/.device-token` (may fail silently — that is OK) — ✅ OK
- [x] Stores `logged_out_at = System.currentTimeMillis()` in `auth` prefs — ✅ OK
- [x] Clears `resume_positions` prefs — ✅ OK
- [x] Starts `LoginActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` — ✅ OK

### DRM (`AmazonLicenseService.kt`)
- [x] License challenge wrapped as `widevine2Challenge=<base64url>` form body — ✅ OK
- [x] Response parsed as JSON: extracts `widevine2License.license`, base64-decoded to raw bytes — ✅ OK
- [x] License URL includes `deviceVideoQualityOverride=HD` and `deviceVideoCodecOverride=H264` — ✅ OK
- [x] Provisioning (if any) uses a plain OkHttp client without Amazon auth headers — ✅ OK

### Player (`PlayerActivity.kt`)
- [x] `DefaultDrmSessionManager` configured with Widevine UUID — ✅ OK
- [x] DASH manifest loaded via `OkHttpDataSource` with auth headers — ✅ OK
- [x] Track selection dialog covers audio (5.1/Stereo) and subtitles (SDH/Forced/Regular) — ✅ OK
- [x] Stream reporting: `START`, `PLAY` (periodic), `PAUSE`, `STOP` events sent to UpdateStream API — ✅ OK
- [x] Resume: position saved to `resume_positions` SharedPreferences; seeks on next play; cleared at ≥90% or `STATE_ENDED` — ✅ OK
- [x] ExoPlayer released in `onDestroy()` to prevent leaks — ✅ OK
- [ ] No crash if user navigates back during DRM provisioning or manifest load — ❌ FINDING-004

### Architecture & Code Quality
- [x] No blocking calls on the main thread — ✅ OK
- [ ] Coroutine scope is tied to lifecycle — ❌ FINDING-004
- [x] Memory leaks: adapters and listeners cleared in `onDestroy()` / `onDestroyView()` — ✅ OK
- [x] `ContentAdapter` RecyclerView DiffUtil implemented correctly (no full-list refreshes) — ✅ OK
- [x] `RailsAdapter` inner RecyclerViews share a `RecycledViewPool` — ✅ OK
- [x] Error states: all API calls have `try/catch`; user sees error message, not crash — ✅ OK
- [x] Null-safety: force-unwrap operators (`!!`) used only where a null would be a programming error, not a runtime condition — ✅ OK

### CI/CD (`.github/workflows/build.yml`)
- [x] Secrets not echoed in logs — ✅ OK
- [ ] Keystore file decoded from base64 and used, then deleted after build — ❌ FINDING-005
- [ ] Version code is monotonically increasing (date-based format ensures this) — ❌ FINDING-006
- [x] APK artifact attached to GitHub Release — ✅ OK
