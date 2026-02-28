# Amazon Fire TV Player — Agent Instructions

## Goal
Build a native Android/Kotlin APK for Fire TV that plays Amazon Prime Video streams,
by porting the existing Kodi plugin logic to Android.

---

## Reference Sources

Two reference codebases are available at `/home/vscode/` for API analysis and porting:

| Path | Contents |
|------|----------|
| `/home/vscode/kodi-plugin/plugin.video.amazon-test/resources/lib/` | Kodi Prime Video plugin (Python) — primary porting reference; `network.py`, `login.py`, `playback.py`, `atv_api.py`, `common.py` |
| `/home/vscode/prime-3.0.412.2947-smali/` | Decompiled Prime Video APK 3.0.412 (smali/baksmali) — use for confirming exact API parameter names, enum string values, and request formats |
| `/home/vscode/prime-3.0.412.2947/` | Same APK decompiled with apktool (resources + smali) |

When implementing a new feature or investigating an API issue, always check `network.py` first, then cross-reference with the smali for exact enum/constant values.

---

## Device Access

Fire TV is connected via ADB. IP is in `.env` as `FIRETV_IP`.
```bash
adb connect $FIRETV_IP:5555
```

---

## Authentication

A valid device token is at `/home/vscode/amazon-vod-android/.device-token` (created before agents start).

### Token Usage
```kotlin
// Read at runtime — never hardcode
val token = Gson().fromJson(File("/home/vscode/amazon-vod-android/.device-token").readText(), TokenData::class.java)

// Structure:
// { "access_token": "...", "refresh_token": "...", "device_id": "...", "expires_in": 3600 }
```

### Token Refresh
On 401: POST to `https://api.{sidomain}/auth/token` with `refresh_token` + device data.
`sidomain` is set per territory (e.g. `amazon.de` for DE, `amazon.com` for US) by `AmazonApiService.detectTerritory()`.
Update `.device-token` with the new token. Implement as an OkHttp interceptor in `AmazonAuthService.kt`.

### Security Rules
- NEVER log, print, or write token values anywhere
- NEVER re-register the device or call registration endpoints
- `.device-token` is chmod 600 — do not change permissions

---

## State Persistence

After EVERY phase write to `/home/vscode/amazon-vod-android/progress.md`:
- Mark complete phases as `Phase N: COMPLETE`
- Note any blockers or decisions

Phase 1 also writes:
- `/home/vscode/amazon-vod-android/analysis/api-map.md` — endpoints, auth flow, license URL
- `/home/vscode/amazon-vod-android/analysis/decisions.md` — file references, workarounds

## Resume Behavior
On start, ALWAYS:
1. Read `progress.md`
2. For each phase: if `progress.md` contains `Phase N: COMPLETE`, skip that phase entirely — do not re-run, re-create, or re-verify it
3. Find the first phase NOT marked `COMPLETE` and start there
4. Read all files in `analysis/` before writing any code

---

## Phase 1 — Analyze Kodi Plugin

Source: `/home/vscode/kodi-plugin/plugin.video.amazon-test/resources/lib/`

Key files:
- `login.py` — `refreshToken()` (line 578), `getToken()` (line 563), `deviceData()` (line 551)
- `common.py` — `dtid_android = 'A43PXU4ZN2AL1'` (line 55), Android headers (line 57)
- `playback.py`, `atv_api.py` — stream manifest and license endpoint logic

Tasks:
- Map all API endpoints (manifest, license server, catalog, search)
- Document the token refresh flow and device fingerprint
- Output to `analysis/api-map.md` and `analysis/decisions.md`
- Mark `Phase 1: COMPLETE` in `progress.md`

---

## Phase 2 — Scaffold Android Project

- Create project in `/home/vscode/amazon-vod-android/app/`
- Kotlin, minSdk 25, targetSdk 34
- Dependencies: Media3 ExoPlayer, OkHttp, Gson, Coroutines
- Activities: content browser → player
- Mark `Phase 2: COMPLETE` in `progress.md`

---

## Phase 3 — Port Auth & API Layer

- `AmazonAuthService.kt` — load token from `.device-token`, OkHttp refresh interceptor
- `AmazonApiService.kt` — catalog browsing, stream manifest fetching
- `AmazonLicenseService.kt` — Widevine license request handler

Mirror Kodi plugin logic exactly (Python → Kotlin). Mark `Phase 3: COMPLETE` in `progress.md`.

---

## Phase 4 — ExoPlayer + Widevine Integration

- `PlayerActivity.kt` using Media3 ExoPlayer
- `DefaultDrmSessionManager` with Widevine L1
- License server from `api-map.md`, DASH/MPD manifest loading

### Self-Signing (required for Widevine L1)
Debug builds fall back to L3 and will fail on L1 streams. Generate keystore once:
```bash
keytool -genkeypair -v \
  -keystore /home/vscode/amazon-vod-android/release.keystore \
  -alias firetv -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=FireTV Dev, O=Dev, C=US" \
  -storepass firetv_store -keypass firetv_key
```

`app/build.gradle.kts`:
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

Mark `Phase 4: COMPLETE` in `progress.md`.

---

## Phase 5 — Build & Deploy

```bash
cd /home/vscode/amazon-vod-android/app
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.firetv.player/.MainActivity
```
Mark `Phase 5: COMPLETE` in `progress.md`.

---

## Phase 6 — Debug Loop

```bash
adb logcat | grep -E "ExoPlayer|Widevine|Amazon|DRM|ERROR"
```
Fix errors. Repeat build → install → logcat until video plays without DRM errors.
Mark `Phase 6: COMPLETE` in `progress.md`.

---

## Phase 7 — Extended Search Content

Expand search and browse to cover all available content categories beyond Prime Video movies/shows.

### Content types to add
- **Amazon Channels** — premium add-on channels (e.g. Paramount+, Discovery+); use `GetPrimeVideoChannels` or equivalent catalog node
- **Freevee / AVOD** — free ad-supported titles; filter catalog by `freeWithAds` or `Prime` badge absence
- **Live TV** — live channel listings if available in the territory; endpoint in `api-map.md`
- **Rentals / Purchases** — user's purchased/rented library (already partially in watchlist; ensure `VideoAggregateDetail` includes transactional titles)
- **Search suggestions** — call the autocomplete/suggestions endpoint (check `api-map.md`; add to `AmazonApiService.kt` as `getSearchSuggestions(query)`)

### UI changes (`MainActivity.kt` / `BrowseActivity.kt`)
- Add filter chips or a category drawer: All / Prime / Freevee / Channels / My Library
- Pass selected category as a parameter to `AmazonApiService.searchCatalog()`
- Search bar should call `getSearchSuggestions()` on each keystroke (debounced 300 ms) and show a dropdown

### API guidance
- Reuse existing `GetSearchResults` endpoint; add `contentType` or `primeOnly` params as documented in `api-map.md`
- If a content type has no dedicated endpoint, filter results client-side by the `isPrime`, `isFreeWithAds`, or `channelId` fields in the catalog response

Rebuild and reinstall after changes:
```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
Mark `Phase 7: COMPLETE` in `progress.md`.

---

## General Rules
- Complete each phase before starting the next
- On 401/403: re-examine Kodi plugin auth logic first
- Never hardcode credentials — read from `.device-token` and `.env`
