# Amazon Fire TV Player — Agent Instructions

## Goal
Build a native Android/Kotlin APK for Fire TV that plays Amazon Prime Video streams,
by porting the existing Kodi plugin logic to Android.

---

## Device Access

A Fire TV is connected via ADB. Verify with:
```bash
adb devices
adb connect $FIRETV_IP:5555
```

---

## Authentication

Device registration is handled BEFORE the agents start.
A valid device token is available at `/home/vscode/amazon-vod-android/.device-token`.

### NEVER
- Ask for or handle AMAZON_PASSWORD
- Re-register the device
- Call any registration endpoints

### Token Usage
Read the token at runtime:
```python
import json
with open('/home/vscode/amazon-vod-android/.device-token') as f:
    token = json.load(f)

# Token structure (from Kodi plugin registration output):
# {
#   "access_token":    "...",   # short-lived, use for API calls
#   "refresh_token":  "...",   # long-lived, use to renew access_token
#   "device_id":      "...",   # your registered device ID
#   "expires_in":     3600
# }
```

### Token Refresh
When API calls return 401:
1. Use `refresh_token` to get a new `access_token` via Amazon's token endpoint
2. Write updated token back to `/home/vscode/amazon-vod-android/.device-token`
3. Retry the original request

Implement this in `AmazonAuthService.kt` as an OkHttp interceptor so all API calls
refresh transparently.

### Security Rules
- NEVER log, print, or write token values to any markdown or progress file
- Document token *structure* only, never actual values
- `.device-token` is chmod 600 — do not change permissions

---

## State Persistence Rules

After EVERY phase, before stopping, you MUST write:

### /home/vscode/amazon-vod-android/progress.md
Update with:
- Which phases are complete (mark as `Phase N: COMPLETE`)
- Current phase status
- Any blockers or decisions made

### /home/vscode/amazon-vod-android/analysis/api-map.md (Phase 1 output)
- All discovered API endpoints
- Auth flow step-by-step
- Device fingerprint values
- Token refresh logic
- License server URL

### /home/vscode/amazon-vod-android/analysis/decisions.md
- Any workarounds chosen and why
- Which Kodi plugin files contain relevant logic (with line numbers)
- Known Amazon API quirks discovered

## Resume Behavior
When starting, ALWAYS:
1. Read `/home/vscode/amazon-vod-android/progress.md` first
2. Check which phases are already complete
3. Skip completed phases
4. Resume from the last incomplete phase
5. Read all files in `/home/vscode/amazon-vod-android/analysis/` before coding anything

---

## Security Rules — NEVER VIOLATE
- NEVER read, print, log, or include the contents of `.env` in any output
- NEVER write credentials, tokens, or passwords to any markdown file
- NEVER include auth tokens in progress.md or api-map.md
- In api-map.md, document token *structure* and *flow* only — never actual values
- If you need credentials, read them via: `source /home/vscode/amazon-vod-android/kspace/.env && echo $AMAZON_EMAIL`
  but never echo AMAZON_PASSWORD

---

## Phase 1 — Analyze Kodi Plugin
Source is in `/home/vscode/amazon-vod-android/kodi-plugin/` (cloned from https://github.com/Sandmann79/xbmc)

Tasks:
- Read and summarize the auth flow (login, device registration, token refresh)
- Map all Amazon API endpoints used (manifest URLs, license server, search, catalog)
- Document the device fingerprint/identity used to register with Amazon
- Output findings to `/home/vscode/amazon-vod-android/analysis/api-map.md`
- Output decisions and file references to `/home/vscode/amazon-vod-android/analysis/decisions.md`
- Mark `Phase 1: COMPLETE` in `/home/vscode/amazon-vod-android/progress.md` when done

---

## Phase 2 — Scaffold Android Project
- Create a new Android project in `/home/vscode/amazon-vod-android/app/`
- Use Kotlin, minSdk 25 (Fire TV gen 2+), targetSdk 34
- Add dependencies: ExoPlayer (media3), OkHttp, Gson, Coroutines
- Basic activity structure: Login screen → content browser → player
- Mark `Phase 2: COMPLETE` in `/home/vscode/amazon-vod-android/progress.md` when done

---

## Phase 3 — Port Auth & API Layer
Based on Phase 1 analysis, implement in Kotlin:
- `AmazonAuthService.kt` — token loading from `.device-token` + refresh interceptor
- `AmazonApiService.kt` — catalog browsing, stream manifest fetching
- `AmazonLicenseService.kt` — Widevine license request handler

Mirror the logic from the Kodi plugin exactly, translating Python → Kotlin idioms.
Mark `Phase 3: COMPLETE` in `/home/vscode/amazon-vod-android/progress.md` when done.

---

## Phase 4 — ExoPlayer + Widevine Integration
- Implement `PlayerActivity.kt` using Media3 ExoPlayer
- Configure `DefaultDrmSessionManager` with Widevine
- Point license server to Amazon's endpoint (from Phase 1 / api-map.md)
- Load DASH/MPD manifest from stream URL
- Target device is Fire TV Stick 4K — assume Widevine L1

### Self-Signing (required for Widevine L1)
Widevine L1 requires a release-signed APK — debug builds fall back to L3 and will fail
on L1-only streams. Generate a self-signed keystore once and use it for all release builds:

```bash
# Generate keystore (run once, store at /home/vscode/amazon-vod-android/release.keystore)
keytool -genkeypair -v \
  -keystore /home/vscode/amazon-vod-android/release.keystore \
  -alias firetv -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=FireTV Dev, O=Dev, C=US" \
  -storepass firetv_store -keypass firetv_key
```

Add a `signingConfigs` block to `app/build.gradle.kts`:
```kotlin
signingConfigs {
    create("release") {
        storeFile = file("/home/vscode/amazon-vod-android/release.keystore")
        storePassword = "firetv_store"
        keyAlias = "firetv"
        keyPassword = "firetv_key"
    }
}
buildTypes {
    getByName("release") {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = false
    }
}
```

- Mark `Phase 4: COMPLETE` in `/home/vscode/amazon-vod-android/progress.md` when done

---

## Phase 5 — Build & Deploy
```bash
cd /home/vscode/amazon-vod-android/app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.firetv.player/.MainActivity
```
Mark `Phase 5: COMPLETE` in `/home/vscode/amazon-vod-android/progress.md` when done.

---

## Phase 6 — Debug Loop
Monitor logs:
```bash
adb logcat | grep -E "ExoPlayer|Widevine|Amazon|DRM|ERROR"
```
Fix any errors found. Repeat build → install → logcat until stream plays.
Mark `Phase 6: COMPLETE` in `/home/vscode/amazon-vod-android/progress.md` when done.

---

## General Rules
- Work phase by phase, complete each before starting the next
- After each phase write a summary to `/home/vscode/amazon-vod-android/progress.md`
- If an API call fails with 403/401, re-examine the Kodi plugin auth logic before changing other things
- Never hardcode credentials — read from `/home/vscode/amazon-vod-android/.device-token` for tokens and `/home/vscode/amazon-vod-android/.env` for non-sensitive config (email, Fire TV IP)