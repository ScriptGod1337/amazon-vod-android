# Amazon Fire TV Player

Native Android/Kotlin app for Fire TV that streams Amazon Prime Video content with Widevine L1 DRM.

## Features

- Browse home catalog, watchlist, and personal library
- Search with instant results
- Filter by source (All / Prime) and type (Movies / Series) — filters combine independently
- Series drill-down: show -> seasons -> episodes -> play
- Widevine L1 hardware-secure playback (DASH/MPD)
- Watchlist management (long-press to add/remove)
- Library with pagination, sub-filters (Movies / TV Shows), and sort (Recent / A-Z / Z-A)
- Freevee section (territory-dependent)
- D-pad navigation optimized for Fire TV remote
- Automatic token refresh on 401/403

## Architecture

```
com.firetv.player
 +-- auth/
 |   +-- AmazonAuthService.kt      Token management, OkHttp interceptors (auth, headers, logging)
 +-- api/
 |   +-- AmazonApiService.kt       Catalog browsing, search, detail pages, watchlist, library, playback
 +-- drm/
 |   +-- AmazonLicenseService.kt   Widevine license: wraps challenge as widevine2Challenge, unwraps widevine2License
 +-- model/
 |   +-- ContentItem.kt            Content data model (asin, title, imageUrl, contentType, isPrime, ...)
 |   +-- TokenData.kt              Token JSON model (access_token, refresh_token, device_id, expires_at)
 +-- ui/
     +-- MainActivity.kt           Home screen: search, nav, filters, content grid
     +-- BrowseActivity.kt         Series detail: seasons / episodes grid
     +-- PlayerActivity.kt         ExoPlayer with DASH + Widevine DRM
     +-- ContentAdapter.kt         RecyclerView adapter with poster images (Coil), watchlist star
     +-- DpadEditText.kt           EditText with Fire TV remote keyboard handling
```

## Requirements

- Android SDK (API 34, build-tools 34.0.0)
- Java 17
- A valid `.device-token` file (see dev/register_device.py)

## Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Deploy

```bash
# Connect to Fire TV or emulator
adb connect <device-ip>:5555

# Push token and install
adb push .device-token /data/local/tmp/.device-token
adb shell chmod 644 /data/local/tmp/.device-token
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch
adb shell am start -n com.firetv.player/.ui.MainActivity
```

## Token

The app reads its auth token from `/data/local/tmp/.device-token` at runtime. Generate it once with `dev/register_device.py` (requires Amazon email, password, and MFA). The app auto-refreshes expired tokens via the Amazon auth API.

## Emulator notes

An Android TV emulator (API 34) works for UI and API development. Widevine DRM playback requires a physical Fire TV device (L1 hardware security).

## Development tooling

Agent instructions, build automation scripts, and API analysis are in the `dev/` folder. See `dev/README.md`.
