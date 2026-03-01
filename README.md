# ScriptGod's FireOS AmazonVOD

Native Android/Kotlin app for Fire TV that streams Amazon Prime Video content with Widevine L1 DRM.

## Features

- **In-app login** with Amazon email/password + MFA support (PKCE OAuth device registration)
- **Sign Out** via About screen (⚙ gear button) — clears tokens and returns to login
- **Continue Watching row** — first rail on the home screen, built from centralized `ProgressRepository` data; shows amber progress bars and remaining-time subtitles; hero strip overrides to "X% watched · Y min left" when CW is active; bypasses source/type filters. Server watchlist progress is loaded on refresh, and local in-progress ASINs can be backfilled into the row by fetching detail metadata when needed (see [Known limitations](#known-limitations))
- **Home page horizontal carousels** — categorised rails (Featured, Trending, Top 10, etc.) matching the real Prime Video home layout, with page-level infinite scroll for more rails
- **Content overview / detail page** — selecting any movie or series opens a full detail screen before playback: hero backdrop image, poster, year/runtime/age rating, quality badges (4K/HDR/5.1), IMDb rating, genres, synopsis, director credit
  - **▶ Play** button for movies
  - **▶ Trailer** button (shown only when `isTrailerAvailable: true`)
  - **Browse Episodes** + **All Seasons** buttons for series/seasons — All Seasons lets you jump to any other season without navigating back
  - **☆ / ★ Watchlist** toggle on every detail page
  - **Prime badge** — every detail page shows "✓ Included with Prime" (teal) or "✗ Not included with Prime" (grey), sourced from the ATF v3 detail API for accurate per-title, per-territory status
- **Watch progress bars** on content cards — amber for in-progress, sourced from centralized server-first progress state with immediate local updates during playback
- Browse home catalog, watchlist, and personal library
- Search with instant results
- Filter by source (All / Prime) and type (Movies / Series) — filters combine independently
- Series drill-down: show → detail page → seasons → detail page → episodes → play
- Widevine L1 hardware-secure playback (DASH/MPD)
- **Audio & subtitle track selection** during playback — labels sourced from Amazon's API metadata for correct display names, Audio Description tagging, and family grouping (main / AD / Dialogue Boost); channel layout suffix shown (`2.0`, `5.1`, `7.1`); MENU key or pause shows controls; overlay follows Media3 controller visibility exactly with no flicker
- **Video format label** in player overlay — shows active codec, resolution, and HDR status (e.g. `720p · H265 · SDR`, `4K · H265 · HDR10`), updated live as ABR ramps up
- **Video quality selection** in About screen — choose between HD H264 (720p), H265 (720p SDR), or 4K/DV HDR; device capability checks disable unavailable options; H265 fallback to H264 on CDN error
- **Audio passthrough toggle** in About screen — Off (default, PCM decode) / On (sends encoded AC3/EAC3 Dolby bitstream directly to AV receiver over HDMI); live HDMI capability badge shows supported formats; On button disabled when device output does not report passthrough support; one-time volume warning on first passthrough session
- **Seekbar seeking** — D-pad left/right seeks ±10 seconds per press (hold to repeat), matching standard Fire TV remote behaviour
- **Watch progress tracking** via UpdateStream API (START/PLAY/PAUSE/STOP)
- **Resume from last position** — automatically seeks to where you left off
- Watchlist management (long-press / hold SELECT to add/remove via styled action overlay)
- Library with pagination, sub-filters (Movies / TV Shows), and sort (Recent / A-Z / Z-A)
- Freevee section (territory-dependent)
- D-pad navigation optimized for Fire TV remote
- Automatic token refresh on 401/403
- **Polished TV UI** — animated focus ring + glow on cards, shimmer skeleton loading, page
  fade/slide transitions, pill-shaped nav bar, four card variants (portrait, landscape, episode,
  season), semi-transparent gradient player overlay, consistent colour palette and dimension tokens
- **CI/CD** via GitHub Actions with date-based versioning and automatic APK releases

## Architecture

```
com.scriptgod.fireos.avod
 +-- auth/
 |   +-- AmazonAuthService.kt      Token management, OkHttp interceptors (auth, headers, logging)
 +-- api/
 |   +-- AmazonApiService.kt       Catalog, search, detail, watchlist, library, playback, stream reporting
 |   +-- ContentItemParser.kt      Parses catalog/rail JSON responses into ContentItem model objects
 +-- data/
 |   +-- ProgressRepository.kt     Centralized server-first progress store with local fallback cache
 +-- drm/
 |   +-- AmazonLicenseService.kt   Widevine license: wraps challenge as widevine2Challenge, unwraps widevine2License
 +-- model/
 |   +-- ContentItem.kt            Content data model (asin, title, imageUrl, contentType, watchProgressMs, ...)
 |   +-- ContentRail.kt            Named row of ContentItems (headerText, items, collectionId)
 |   +-- DetailInfo.kt             Detail page data model (synopsis, heroImageUrl, imdbRating, genres, ...)
 |   +-- TokenData.kt              Token JSON model (access_token, refresh_token, device_id, expires_at)
 +-- ui/
     +-- LoginActivity.kt          Amazon login: email/password + MFA, PKCE OAuth, device registration
     +-- MainActivity.kt           Home screen: rails or grid, search, nav, filters, pagination
     +-- AboutActivity.kt          App info, video quality + audio passthrough settings, Sign Out
     +-- DetailActivity.kt         Content overview: hero image, metadata, IMDb, trailer, play/browse buttons
     +-- BrowseActivity.kt         Series detail: seasons / episodes grid
     +-- PlayerActivity.kt         ExoPlayer with DASH + Widevine DRM, track selection, resume
     +-- RailsAdapter.kt           Outer vertical adapter (one row per ContentRail)
     +-- ContentAdapter.kt         Inner horizontal adapter with poster, watchlist star, progress bar
     +-- ShimmerAdapter.kt         Skeleton placeholder adapter shown during API calls
     +-- CardPresentation.kt       Card focus/scale animation helpers
     +-- WatchlistActionOverlay.kt Styled action overlay for Add/Remove watchlist confirmation
     +-- DpadEditText.kt           EditText with Fire TV remote keyboard handling
     +-- UiMotion.kt               Entry animation helper (revealFresh)
     +-- UiTransitions.kt          Page transition helpers
     +-- UiMetadataFormatter.kt    Badge and chip label formatting (4K, HDR, 5.1, codec, etc.)
```

## Screenshots

Current redesigned UI, captured from the Android TV emulator:

| Home | Search Results |
|:---:|:---:|
| ![Home](screenshots/01_home_emulator.png) | ![Search Results](screenshots/02_search_emulator.png) |

| Watchlist | Library |
|:---:|:---:|
| ![Watchlist](screenshots/03_watchlist_emulator.png) | ![Library](screenshots/04_library_emulator.png) |

| About / Settings | Season Detail |
|:---:|:---:|
| ![About](screenshots/05_about_emulator.png) | ![Season Detail](screenshots/06_detail_season_emulator.png) |

| Season Picker | Episode Browse |
|:---:|:---:|
| ![Season Picker](screenshots/07_browse_seasons_emulator.png) | ![Episode Browse](screenshots/08_browse_episodes_emulator.png) |

| Player Controls Overlay | Continue Watching row |
|:---:|:---:|
| ![Player Controls](screenshots/09_player_overlay_emulator.png) | ![Continue Watching](screenshots/10_continue_watching_emulator.png) |

## Known limitations

### Watch progress — centralized cache with server-first refresh

The app now uses a centralized `ProgressRepository`:
- on startup / refresh, server progress from the watchlist API is loaded first
- local cached progress from `SharedPreferences("progress_cache")` is merged underneath it
- during playback, local progress is updated every 30 seconds and on pause/stop/seek/error
- the home screen can backfill missing Continue Watching items by resolving local-only ASINs through the detail API

This means:

| Scenario | Progress shown? |
|---|---|
| Title is in your watchlist **and** you have watched part of it via the official app | ✓ Yes |
| Title is **not** in your watchlist but you started it in this app and local progress exists | ✓ Yes — if the ASIN can be resolved through the detail API |
| Title is **not** in your watchlist and you started it only in the official app on another device | ✗ No local signal in this app |
| In-progress **episode** (not the series) started in this app | ✓ Yes — if the episode ASIN is in local progress and metadata resolves |
| In-progress **episode** started only in the official app / another device | ✗ Usually no — Amazon does not expose a general server-side episode progress feed |
| Progress set **within this app** during current session | ✓ Yes — local cache updates immediately |
| Progress set in the official app or on another device after this app has already started | △ Not immediately — visible after the next repository refresh / app restart |

**Why**: Amazon's official Prime Video app stores some in-progress playback state in a private local
SQLite database (`UserActivityHistory`) that is written during playback and read by the
`ContinueWatchingCarouselProvider`. Third-party clients cannot read that database. The only
server-readable progress signal available here is `remainingTimeInSeconds` on watchlist items.

**Current sync policy**:
- server progress wins when the app refreshes the repository
- local progress wins during the current playback session until the next refresh

**Known limitation**: there is no trustworthy backend `lastUpdatedAt` timestamp exposed by the
Amazon APIs used here. Because of that, the app cannot do true conflict resolution between:
- local progress written by this app
- newer progress written by the official app or another device on the same account

If the same account is used in multiple apps/devices at the same time, short-lived progress
differences can happen until this app refreshes from the server again.

**Practical result**:
- watchlisted titles are the most reliable cross-device source
- local playback in this app is reflected immediately
- local-only Continue Watching items can appear even when the title is not currently backed by the
  server watchlist progress set, as long as the app has a resumable ASIN and can resolve metadata

### Speed control unavailable

`player.setPlaybackSpeed()` is silently reset to 1.0× by Amazon's EMP (Extras Media Player)
system service via a hidden MediaSession proxy on Fire OS. Not implemented.

## Roadmap

See [dev/progress.md](dev/progress.md) for the full phase-by-phase build history and upcoming work.

**Recently completed:**
- **Phase 29** — Continue Watching row: first rail on the home screen built from server-side
  watchlist progress; amber progress bars + remaining-time subtitles; hero strip overrides to
  progress meta; bypasses source/type filters; `RailsAdapter` adapter-reuse fix eliminates
  first-item flicker; pool contamination fix in `ContentAdapter`
- **Phase 30** — Centralized `ProgressRepository`: single source of truth for all ASIN progress;
  server-first refresh + local fallback cache; periodic local writes during playback; no more
  progress intent chain; Home can backfill local-only Continue Watching items by ASIN
- **Phase 28** — Widevine L3/SD fallback: emulator playback enabled; L3 device detected at
  player-creation time and forced to SD quality (mirrors official APK `ConfigurablePlaybackSupportEvaluator`)
- **Phase 27** — AI code review (5 warnings + 4 info); all findings fixed — keep-screen-on flags,
  seek recovery via `onPositionDiscontinuity` + `onRenderedFirstFrame`, `h265FallbackAttempted`
  reset, `SharedPreferences` key hygiene, AppCompatButton consistency
- **Phase 26** — Configurable audio passthrough: Off (PCM decode) / On (AC3/EAC3 Dolby bitstream
  to AV receiver); live HDMI capability badge; `DefaultRenderersFactory.buildAudioSink()` override

**Next up:**
- Deeper cross-device progress conflict resolution if Amazon exposes a trustworthy backend
  progress timestamp in a future API path

## Requirements

- Android SDK (API 34, build-tools 34.0.0)
- Java 17
- A valid Amazon account (login in-app) or a `.device-token` file for development

## Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Versioned build (CI style)

```bash
./gradlew assembleRelease \
  -PversionNameOverride=2026.02.28.1 \
  -PversionCodeOverride=20260228
```

## Deploy to Fire TV

```bash
# Connect to Fire TV
adb connect <device-ip>:5555

# Install
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch
adb shell am start -n com.scriptgod.fireos.avod/.ui.LoginActivity
```

## Authentication

### In-app login (recommended)
Launch the app and sign in with your Amazon email, password, and verification code. The app performs PKCE OAuth authentication and registers the device with Amazon. Tokens are saved to the app's internal storage.

### Development token (debugging)
For development, you can pre-push a `.device-token` file:
```bash
adb push .device-token /data/local/tmp/.device-token
adb shell chmod 644 /data/local/tmp/.device-token
```
The app auto-detects an existing token and skips the login screen. Generate the token with `dev/register_device.py`.

**After signing out:** The app records a `logged_out_at` timestamp and skips the legacy token that was present at sign-out time. To resume development without logging in again, push a fresh token and `touch` it so its mtime is newer than the logout timestamp (`adb push` preserves the host file's mtime, so the touch is required):
```bash
adb push .device-token /data/local/tmp/.device-token
adb shell touch /data/local/tmp/.device-token
```
The app compares the file's mtime against `logged_out_at` — if the file is newer it accepts the token and clears the flag automatically.

## CI/CD

GitHub Actions builds APKs on version tags (`v*`), pull requests to `main`, and manual dispatch. Versioning uses the date format `YYYY.MM.DD.N` (e.g., `2026.02.27.5`).

[View recent builds](https://github.com/ScriptGod1337/amazon-vod-android/actions) | [Download latest release](https://github.com/ScriptGod1337/amazon-vod-android/releases/latest)

### Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded `release.keystore` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Generate the keystore secret:
```bash
base64 -w0 release.keystore | pbcopy  # macOS
base64 -w0 release.keystore           # Linux (pipe to clipboard)
```

Pushing a version tag (e.g., `git tag v2026.02.28.1 && git push --tags`) creates a GitHub Release with the signed APK attached.

## Emulator notes

An Android TV emulator (API 34) works for UI, API development, and login testing. Widevine DRM playback requires a physical Fire TV device (L1 hardware security).

## Development tooling

Agent instructions, build automation scripts, and API analysis are in the `dev/` folder. See `dev/README.md`.
