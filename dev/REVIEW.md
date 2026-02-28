# AI Review & Testing Guide

Instructions for an AI agent reviewing or testing the ScriptGod's FireOS AmazonVOD app.

---

## Project Overview

Native Android/Kotlin app for Fire TV that streams Amazon Prime Video with Widevine L1 DRM. Built by porting the Kodi plugin (`plugin.video.amazon-test`) to Android.

**Package**: `com.scriptgod.fireos.avod`
**Min SDK**: 25 (Android 7.1) — **Target SDK**: 34 (Android 14)
**Entry point**: `LoginActivity` (launcher) → `MainActivity` → `BrowseActivity` / `PlayerActivity`

---

## Build Verification

```bash
./gradlew assembleRelease
```

**Expected**: BUILD SUCCESSFUL, signed APK at `app/build/outputs/apk/release/app-release.apk`

Check for:
- No Kotlin compile errors or warnings (minor deprecation warnings are acceptable)
- APK is signed (release keystore at project root)
- No hardcoded secrets in source (tokens are read from file at runtime)

---

## Code Review Checklist

### Architecture (`app/src/main/java/com/scriptgod/fireos/avod/`)

| Layer | File | Review Focus |
|-------|------|-------------|
| Auth | `auth/AmazonAuthService.kt` | Token never logged, refresh on 401/403, OkHttp interceptors |
| API | `api/AmazonApiService.kt` | Territory detection, catalog parsing, error handling |
| DRM | `drm/AmazonLicenseService.kt` | Challenge wrapping (widevine2Challenge), license unwrapping |
| Model | `model/ContentItem.kt` | Data class completeness, serialization |
| Model | `model/TokenData.kt` | Token fields, `expires_at` calculation |
| UI | `ui/LoginActivity.kt` | PKCE OAuth flow, cookie handling, CVF/MFA support |
| UI | `ui/MainActivity.kt` | Navigation, filters, search, watchlist |
| UI | `ui/BrowseActivity.kt` | Series → season → episode drill-down |
| UI | `ui/PlayerActivity.kt` | ExoPlayer lifecycle, DRM setup, track selection, resume |
| UI | `ui/RailsAdapter.kt` | Outer vertical ListAdapter for home carousels; inner ContentAdapter per rail |
| UI | `ui/ContentAdapter.kt` | RecyclerView diffing, image loading, watchlist star, progress bar tinting |
| UI | `ui/DpadEditText.kt` | Fire TV remote keyboard handling |
| Model | `model/ContentRail.kt` | Rail data class (headerText, items, collectionId, paginationParams) |

### Security Review

- [ ] **No token logging**: `AmazonAuthService` must never log `access_token` or `refresh_token` values
- [ ] **No hardcoded credentials**: all secrets come from `.device-token` file or environment variables
- [ ] **Token file permissions**: app writes to internal storage (`filesDir`), not world-readable locations
- [ ] **HTTPS only**: `usesCleartextTraffic="false"` in AndroidManifest.xml
- [ ] **No credential persistence in code**: LoginActivity clears password after use, PKCE verifier is ephemeral
- [ ] **Release keystore not committed with real credentials**: default keystore uses dev passwords

### Login Flow (`LoginActivity.kt`)

The login mirrors `dev/register_device.py` exactly. Verify:

1. **PKCE generation**: 32 random bytes → base64url (verifier), SHA-256 → base64url (challenge)
2. **client_id format**: hex-encoded `{device_id}#A1MPSLFC7L5AFK` (NOT just device type)
3. **Cookie handling**: Map-based cookie jar (deduplicates by `domain:name`), cleared on each attempt
4. **FRC + map-md cookies**: fraud risk cookie (313 random bytes), app metadata cookie set before first request
5. **OAuth URL construction**: opens amazon.com → follows sign-in link → modifies URL with PKCE params
6. **Form submission**: extracts hidden fields + form action, POSTs credentials without following redirects
7. **CVF handling**: detects Customer Verification Flow (email code) separately from TOTP MFA
8. **Device registration**: POST to `/auth/register` with auth code + PKCE verifier → bearer tokens
9. **Token storage**: writes to app-internal `filesDir/.device-token`, tries legacy `/data/local/tmp/` as fallback

### Token File Resolution

All activities use `LoginActivity.findTokenFile(context)`:
1. Check `filesDir/.device-token` (app-internal, written by in-app login)
2. Fall back to `/data/local/tmp/.device-token` (legacy, pushed via ADB for development)
3. Return `null` if neither exists → redirect to LoginActivity

### DRM Review (`AmazonLicenseService.kt`)

- License challenge is wrapped as `widevine2Challenge=<base64url>` form body
- Response is JSON: extract `widevine2License.license` field, base64-decode to raw license bytes
- License URL includes quality/codec overrides: `deviceVideoQualityOverride=HD`, `deviceVideoCodecOverride=H264`
- Provisioning uses plain OkHttpClient (no Amazon auth headers to Google's servers)

### Player Review (`PlayerActivity.kt`)

- ExoPlayer with `DefaultDrmSessionManager` (Widevine UUID)
- DASH manifest loaded via `OkHttpDataSource` with auth headers
- Track selection: audio (5.1/Stereo) and subtitle (SDH/Forced/Regular) via dialog
- Stream reporting: UpdateStream API calls at START/PLAY/PAUSE/STOP
- Resume: saves position to SharedPreferences, seeks on next play, clears at ≥90% or STATE_ENDED

---

## Testing Guide

### Prerequisites

- Android TV emulator (API 34) or physical Fire TV device
- ADB connected (`adb devices` shows device)
- Either: valid Amazon account credentials, OR `.device-token` file

### Test Matrix

#### 1. Login Flow (emulator OK)

| Test | Steps | Expected |
|------|-------|----------|
| Fresh login | Clear app data, launch, enter email/password | CVF or MFA prompt → verification code → "Login successful" → MainActivity |
| Token persistence | Login, force-stop app, relaunch | Skips login, goes directly to MainActivity |
| Skip button | Push `.device-token` via ADB, launch | "Use Device Token" button visible, clicking it goes to MainActivity |
| Invalid credentials | Enter wrong password | Error message displayed, can retry |
| Retry after failure | Fail login, try again | Fresh cookies, no "Please Enable Cookies" error |
| Show password | Check "Show password" checkbox | Password becomes visible |

#### 2. Browse & Navigation (emulator OK)

| Test | Steps | Expected |
|------|-------|----------|
| Home catalog | Launch app (with token) | Grid of content items with poster images |
| Search | Type query in search field, press Enter | Results update, keyboard dismisses |
| Source filter | Tap "Prime" filter | Only Prime content shown |
| Type filter | Tap "Movies" or "Series" | Filtered by content type |
| Combined filters | Select "Prime" + "Movies" | Both filters apply simultaneously |
| Watchlist | Navigate to Watchlist tab | User's watchlist items displayed |
| Library | Navigate to Library tab | Purchased/rented content, pagination on scroll |
| Library filters | Tap Movies/TV Shows in library | Sub-filtered library |
| Library sort | Tap sort button | Cycles: Recent → A-Z → Z-A |
| Freevee | Navigate to Freevee tab | Free ad-supported titles (territory-dependent) |
| Series drill-down | Click a series | BrowseActivity: seasons → episodes |
| Watchlist toggle | Long-press any item | Toast: "Adding to/Removing from watchlist" |
| Home rails | Launch app, view Home tab | Horizontal carousels with section headers (Featured, Trending, etc.) |
| Rail navigation | D-pad left/right within a rail | Focus scrolls horizontally within carousel |
| Rail scroll | D-pad down past last visible rail | More rails load (page-level pagination) |
| Progress bar — watchlist | Navigate to Watchlist, find partially-watched movie | Amber progress bar visible at bottom of card |
| Progress bar — search | Search for a partially-watched title | Amber progress bar visible in search results |
| Progress bar — home rail | View home rails, find watched title | Amber progress bar visible in rail card |
| Watchlist star — home | View home rails | Bookmarked items show filled star; unwatched bookmarks have star but no bar |

#### 3. Playback (Fire TV device required for DRM)

| Test | Steps | Expected |
|------|-------|----------|
| Movie playback | Select a movie, click play | Video plays with audio |
| Episode playback | Drill into series, select episode | Episode plays |
| Track selection | Press menu/select during playback | Audio and subtitle track picker dialog |
| Audio tracks | Switch between 5.1 and Stereo | Audio changes without interruption |
| Subtitles | Enable subtitles | Subtitles render on screen |
| Pause/resume | Press pause, wait, resume | Playback resumes from same position |
| Resume position | Play 2+ minutes, back out, re-enter | Seeks to last position |
| Watch to end | Let video finish | Resume position cleared for next play |
| Stream reporting | Play/pause/stop, check logs | UpdateStream calls logged (START/PLAY/PAUSE/STOP) |

#### 4. D-pad Navigation (emulator or Fire TV)

| Test | Steps | Expected |
|------|-------|----------|
| Grid navigation | D-pad arrows on content grid | Focus moves between items with visual highlight |
| Search focus | D-pad up to search field | Field highlights; press center to open keyboard |
| Keyboard dismiss | Press back while keyboard open | Keyboard closes, focus returns to grid |
| Button navigation | D-pad between nav buttons | Focus ring visible on each button |

#### 5. Edge Cases

| Test | Steps | Expected |
|------|-------|----------|
| No network | Disable network, launch | Error message, no crash |
| Token expired | Wait for token to expire, browse | Auto-refresh via interceptor, no user action needed |
| Empty search | Search for gibberish | "No content found" message |
| Empty library | Account with no purchases | "Your library is empty" message |
| Rapid navigation | Quickly switch between tabs | No crashes, loading states shown |

### Log Monitoring

```bash
# General app logs
adb logcat | grep -E "LoginActivity|MainActivity|PlayerActivity|AmazonAuth|AmazonApi|ExoPlayer|Widevine|DRM"

# Login-specific (uses Log.w for visibility on release builds)
adb logcat | grep "LoginActivity"

# DRM issues
adb logcat | grep -E "DRM|Widevine|License|drm"
```

### Known Limitations

- **Widevine L1 requires physical Fire TV**: Emulator only supports L3 (software), which Amazon rejects
- **Territory-dependent content**: Catalog varies by Amazon account region (US, DE, UK, etc.)
- **Freevee availability**: Not available in all territories
- **CVF verification**: Amazon may require email verification on new device logins — this is expected
- **Captcha**: Amazon may show captcha after repeated login attempts — wait and retry later

---

## CI/CD Review (`.github/workflows/build.yml`)

- Triggers: push to `main`, pull requests, manual dispatch
- Uses JDK 17 Temurin + Android SDK setup
- Keystore decoded from `RELEASE_KEYSTORE_BASE64` secret
- Date-based versioning: `YYYY.MM.DD_N` with auto-increment
- APK uploaded as artifact + GitHub Release on push to main

Verify:
- [ ] Secrets are not echoed in logs
- [ ] Keystore file is cleaned up after build
- [ ] Version code is monotonically increasing
- [ ] APK artifact is attached to release

---

## File Reference

| Path | Purpose |
|------|---------|
| `app/build.gradle.kts` | Dependencies, signing config, version overrides |
| `app/src/main/AndroidManifest.xml` | Activities, permissions, leanback config |
| `app/src/main/res/layout/` | XML layouts for all activities |
| `app/src/main/res/values/` | Strings, styles, themes |
| `dev/register_device.py` | Reference Python implementation of OAuth flow |
| `dev/analysis/api-map.md` | All Amazon API endpoints documented |
| `dev/analysis/decisions.md` | Architecture decisions and workarounds |
| `dev/progress.md` | Phase-by-phase build history |
| `.github/workflows/build.yml` | CI/CD pipeline |
