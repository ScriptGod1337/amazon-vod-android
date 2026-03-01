# Architecture Decisions & Porting Notes

---

## Decision 1: Use Android API (data_source=1) as primary

The Kodi plugin has three data sources:
- **data_source=0**: Web API (`web_api.py`) — cookie-based, scrapes HTML/JSON from web pages
- **data_source=1**: Android API (`android_api.py`) — token-based, native JSON endpoints
- **data_source=2**: ATV/Legacy API (`atv_api.py`) — token-based, older catalog format

**Decision**: Use the **Android API** (data_source=1). It's the closest match to a native Android app, uses token auth (which we already have from `.device-token`), and returns clean JSON.

**File references**: `android_api.py:108-138` (endpoint routing), `common.py:140-147` (data source selection)

---

## Decision 2: Token auth only, no cookie auth

The plugin supports both cookie-based (web) and token-based (Android) authentication. Cookie auth requires browser login flow + cookie management.

**Decision**: Use token auth exclusively. We have a valid `.device-token` with `access_token` and `refresh_token`. The Android device type ID (`A43PXU4ZN2AL1`) is used for all API calls.

**File references**: `login.py:563-604` (token get/refresh), `playback.py:500-518` (_getPlaybackVars)

---

## Decision 3: Device identity — SHIELD Android TV fingerprint

The plugin impersonates an NVIDIA SHIELD Android TV. We must replicate this exactly.

**Key values**:
- `device_type`: `A43PXU4ZN2AL1` (`common.py:55`)
- `User-Agent`: `Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV RQ1A.210105.003)` (`common.py:57`)
- `X-Requested-With`: `com.amazon.avod.thirdpartyclient` (`common.py:58`)
- `app_name`: `com.amazon.avod.thirdpartyclient` (`login.py:556`)
- `device_model`: `mdarcy/nvidia/SHIELD Android TV` (`login.py:558`)
- `os_version`: `NVIDIA/mdarcy/mdarcy:11/RQ1A.210105.003/7094531_2971.7725:user/release-keys` (`login.py:559`)

**File references**: `common.py:55-58`, `login.py:551-560`

---

## Decision 4: Widevine license flow — custom HttpMediaDrmCallback

The Widevine license exchange is non-standard. Amazon wraps the challenge/response:
1. **Request**: body is `widevine2Challenge=<base64url(raw_challenge)>`
2. **Response**: JSON `{ "widevine2License": { "license": "<base64(raw_license)>" } }`

ExoPlayer's default `HttpMediaDrmCallback` won't handle this. We need a custom implementation:
- Wrap the DRM challenge bytes in `widevine2Challenge=<base64url>` form-encoded body
- Parse JSON response, extract `widevine2License.license`, base64-decode it
- Return raw license bytes to the DRM session

**File references**: `playback.py:452-467` (new ISA DRM config), `playback.py:512-516` (old ISA req_param format)

---

## Decision 5: Manifest format — DASH/MPD

Amazon serves DASH manifests (.mpd). ExoPlayer's `DashMediaSource` handles this natively. The manifest URL comes from `GetPlaybackResources` response at `playbackUrls.urlSets[defaultUrlSetId].urls.manifest.url`.

**CDN fallback logic**: The plugin tries multiple CDN hosts (Cloudfront preferred). For simplicity in v1, we'll use the `defaultUrlSetId` and fall back only on failure.

**File references**: `playback.py:88-158` (_ParseStreams)

---

## Decision 6: Token refresh as OkHttp Interceptor

Implement token refresh as an OkHttp `Interceptor`:
1. Attach `Authorization: Bearer <access_token>` to every request
2. On 401 response: call `POST https://api.{sidomain}/auth/token` with refresh data
3. Update `.device-token` file with new `access_token` and `expires_in`
4. Retry the original request

**Important headers for refresh call**:
- Remove `x-gasc-enabled` and `X-Requested-With` from headers
- Add `x-amzn-identity-auth-domain: api.{sidomain}`
- Add `Accept-Language: en-US`
- Add `x-amzn-requestid: <uuid4_no_dashes>`

**File references**: `login.py:578-604` (refreshToken), `login.py:591-594` (header manipulation)

---

## Decision 7: Region detection — dynamic territory detection

~~Initially hardcoded to US.~~ Now fully dynamic via `GetAppStartupConfig` with 3-layer detection matching Kodi `login.py:55-78`:

1. **Layer 1**: `avMarketplace` found in `TERRITORY_MAP` → use preset (atvUrl, marketplaceId, sidomain, lang)
2. **Layer 2**: Unknown marketplace + `defaultVideoWebsite` + `homeRegion` → construct URL dynamically
3. **Layer 3**: No marketplace but have `defaultVideoWebsite` → construct from URL alone
4. **Fallback**: US defaults (`atv-ps.amazon.com`, `ATVPDKIKX0DER`)

Key findings during implementation:
- `deviceTypeID` in the request MUST match the registered device type (our `A43PXU4ZN2AL1`, not Kodi's `A28RQHJKHM2A2W`)
- `supportedLocales` must include the user's locale (send 18 locales like Kodi)
- `homeRegion` is under `customerConfig`, not `territoryConfig`
- `uxLocale` can return error strings — validate with regex before using
- Token refresh must use territory-specific `api.{sidomain}` (e.g. `api.amazon.de` for DE)

The `.device-token` `device_id` is used as the `deviceID` parameter and as `device_serial` in device data.

**File references**: `login.py:31-79` (getTerritory), `login.py:62-63` (GetAppStartupConfig)

---

## Decision 8: Content browsing — switchblade JVM transforms

Catalog flow uses `getDataByJvmTransform` (Kotlin switchblade), not JS transforms:
1. **Home/Landing** → `dv-android/landing/initial/v2.kt` (structured rails) / `v1.kt` (flat fallback)
2. **Watchlist** → `dv-android/watchlist/initial/v1.kt` + `dv-android/watchlist/next/v1.kt`
3. **Search** → `dv-android/search/searchInitial/v3.js` with `phrase=` param
4. **Detail** → `android/atf/v3.jstl` with `itemId=` param

Response JSON: `collections[]` with `collectionItemList` items per collection; `paginationModel` for page pagination.

**Note**: JS transforms (`getDataByTransform`) only support initial page; `watchlistNext/v3.js` returns HTTP 500. JVM transforms support full pagination via `serviceToken` in `paginationModel.parameters`.

**File references**: `android_api.py:108-300` (getPage routing and parsing)

---

## Decision 9: Playback quality — request HD/H264 baseline

For the initial build:
- `deviceVideoCodecOverride=H264`
- `deviceVideoQualityOverride=HD`
- `deviceHdrFormatsOverride=None`

UHD/H265/HDR can be added later based on device capabilities.

**File references**: `network.py:209-213` (quality params)

---

## Decision 10: Stream reporting — implement UpdateStream

Amazon expects playback state reporting. Without it, playback may be rate-limited or flagged.

Events:
- `START` — when playback begins
- `PLAY` — periodic heartbeat (interval from response's `statusCallbackIntervalSeconds`, default ~60s)
- `STOP` — when playback ends

**File references**: `playback.py:740-751` (updateStream), `playback.py:664` (default interval=60)

---

---

## Decision 11: Home page rails — v2 landing API with watchlist progress merge

The v2 landing API (`landing/initial/v2.kt`) returns structured rails with `collections[]` per category. However, it does **not** include `remainingTimeInSeconds` in item data — watch progress is only available from the watchlist API.

**Watch progress data flow**:
1. At startup, `getWatchlistData()` loads all watchlist pages and builds both:
   - `Set<String>` of ASINs (for watchlist star indicators)
   - `Map<String, Pair<Long, Long>>` of ASIN → `(watchProgressMs, runtimeMs)` from `remainingTimeInSeconds`
2. In `showRails()` and `loadHomeRailsNextPage()`, watchlist progress is merged into rail items by ASIN lookup

**`remainingTimeInSeconds` semantics**:
- This is time **remaining**, not time **watched**: `watchProgressMs = runtimeMs - remainingSec * 1000`
- `remainSec == 0` is ambiguous — treat as "no data" (not "fully watched")
- `remainSec >= runtimeSec` → not started (includes credits buffer, e.g. Road House: 7428s remaining > 6985s runtime)
- `remainSec > 0 && remainSec < runtimeSec` → partial progress → show amber bar

**v1 vs v2 landing**:
- v1 (`landing/initial/v1.kt`): returns `remainingTimeInSeconds=0` for all items regardless of real progress
- v2 (`landing/initial/v2.kt`): returns structured rails with section headers; omits `remainingTimeInSeconds`
- Watchlist API: returns accurate `remainingTimeInSeconds` values

**Key pitfall**: Nested `RecyclerView` (rails within rails) does not render custom XML `progressDrawable` — must use `@android:style/Widget.ProgressBar.Horizontal` + `progressTintList` set programmatically.

**Bug fixed post-Phase 19**: `showItems()` (used by search/Freevee/Library/flat grids) was only merging local SharedPreferences resume positions, not `watchlistProgress`. Items could show a watchlist star (ASIN in `watchlistAsins`) but no progress bar (ASIN not checked in `watchlistProgress`). Fixed by applying the same three-way merge (`localResume ?: serverProgress?.first ?: item.watchProgressMs`) in `showItems()` that `showRails()` already used. `runtimeMs` is also populated from `serverProgress.second` when the catalog item has `runtimeMs == 0`.

**Items with star but no bar**: Legitimately unwatched bookmarks (in `watchlistAsins` but not `watchlistProgress` because `watchProgressMs == 0` or `runtimeMs == 0` in watchlist response). This is correct — `watchlistProgress` only stores items where both fields are > 0.

---

## Decision 12: Login HTTP client must send app-identity headers on all requests

Amazon's sign-in page detects whether the HTTP client is a browser or a native app via the
`X-Requested-With` and `x-gasc-enabled` request headers. Without them, Amazon serves a
browser-mode login page that relies on JavaScript to set CSRF cookies before the credential POST.
A plain HTTP client (no JS execution) therefore receives "Please Enable Cookies to Continue" on
every credential POST, regardless of how many real cookies are present.

The reference Python script (`dev/register_device.py`) sets these headers globally on its
`requests.Session`:
```python
'X-Requested-With': 'com.amazon.avod.thirdpartyclient'
'x-gasc-enabled':   'true'
```

**Fix**: The `LoginActivity` OkHttp client adds an application interceptor that injects both headers
into every request in the login flow (homepage, sign-in link, OAuth page, credential POST).

**Related to Decision 3** (device identity / SHIELD fingerprint) and **Decision 6** (headers
removed from token-refresh calls — `AmazonAuthService` must NOT send `x-gasc-enabled` or
`X-Requested-With` to the token refresh endpoint; only the login HTTP client needs them).

---

## Decision 13: Logout uses `logged_out_at` timestamp, not file deletion

The legacy dev token at `/data/local/tmp/.device-token` cannot be deleted by the app process
(directory owned by `shell`, app UID lacks write permission). A silent `File.delete()` failure
means `findTokenFile()` finds the old token and immediately bounces the user back to MainActivity.

**Approach**: Store `logged_out_at = System.currentTimeMillis()` in the `auth` SharedPreferences
on sign-out. `findTokenFile()` compares the legacy file's `lastModified()` (mtime) against this
timestamp:
- `mtime ≤ logged_out_at` → stale token from before logout → skip
- `mtime > logged_out_at` → file was written/touched after logout → accept (fresh dev push)
- `logged_out_at` absent (0L) → no logout recorded → accept

**`adb push` mtime pitfall**: `adb push` preserves the source file's mtime from the host. A token
file last modified 2026-02-26 appears older than a 2026-02-28 logout timestamp. Developers must
`adb shell touch` the file after pushing to update mtime to the current device time.

**`launchMain()`** clears `logged_out_at` from SharedPreferences on every successful login, so a
fresh in-app login fully resets the state and the legacy token is usable again on the next
cold start.

---

## Decision 14: Audio track selection — one entry per adaptation set, adaptive bitrate

Amazon's DASH manifests split audio into one `AdaptationSet` per bitrate variant (rather than grouping bitrates as `Representation` elements within one set). ExoPlayer maps each `AdaptationSet` to a separate `TrackGroup`. Naively iterating all groups × all tracks produces duplicate language entries (e.g. "German (Stereo)" three times at 64/128/192 kbps).

**Approach**:
1. One dialog entry per `TrackGroup` — bitrate selection is ExoPlayer's adaptive-bitrate responsibility
2. Representative format per group: currently-playing track, else highest-bitrate track
3. Base label = language + channel layout (`"German (5.1)"`, `"German (Stereo)"`, etc.)
4. Codec qualifier (`· Dolby` / `· AAC`) added **only** when two groups share the same base label but differ in codec (EC-3 vs AAC) — avoids clutter for the common case
5. Final label deduplication: if multiple per-bitrate groups still produce the same final label, keep the group with the highest bitrate (or the currently-selected group)
6. Selection: `TrackSelectionOverride(group.mediaTrackGroup, emptyList())` — empty list = ExoPlayer picks best bitrate within the chosen group adaptively

**codec detection** (`sampleMimeType`):
- `audio/ec-3` / `eac3` → `Dolby`
- `audio/ac-3` / `ac3` → `Dolby`
- `audio/mp4a` / `aac` → `AAC`

---

## Decision 15: Seekbar D-pad seek increment — fixed 10 s

`DefaultTimeBar` computes its key-press seek step as `duration ÷ 20`. On a 2-hour film this is ~360 s (6 min), making precise seeking impossible with a TV remote.

**Fix**: call `DefaultTimeBar.setKeyTimeIncrement(10_000L)` in `onCreate` after `setContentView`. This sets a fixed 10-second step per D-pad press. Holding the key repeats at the same increment. 10 s matches the Netflix / Prime Video app convention for Fire TV remotes.

Access via `playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)`.

---

## Decision 16: Video quality setting — user-selectable with H265 capability detection

Three quality presets, stored in SharedPreferences `"settings"` key `"video_quality"`:

| Preset key | `deviceVideoQualityOverride` | `deviceVideoCodecOverride` | `deviceHdrFormatsOverride` | Actual result |
|---|---|---|---|---|
| `"HD"` (default) | `HD` | `H264` | `None` | **720p H264 SDR** |
| `"HD_H265"` | `HD` | `H264,H265` | `None` | **720p H265 SDR** — same resolution as HD H264; H265 gives slightly better compression at 720p; no HDR display needed |
| `"UHD_HDR"` | `UHD` | `H264,H265` | `Hdr10,DolbyVision` | **4K H265 HDR** — requires H265 decoder + HDR display |

**Key finding (empirical)**: Amazon's `HD` quality tier was observed at **720p** for both
H264 and H265 SDR on two test titles. This is a CDN-side policy — the APK does not
encode a 720p resolution limit in client code. The 720p cap is likely correct in general
but is based on observation, not APK proof.

**No confirmed 1080p SDR option**: 1080p+ from `UHD+HDR` is confirmed. A hypothetical
`HD+Hdr10` → 1080p HDR path is **unconfirmed** — see `dev/analysis/quality-tier-analysis.md`.

**Kodi reference**: `network.py:210-212` and `supported_hdr()` function (`network.py:231-239`).

**Exact string values** confirmed in decompiled Prime APK:
- `VideoQuality` enum (`.../atvplaybackdevice/types/VideoQuality.smali`): `SD`, `HD`, `UHD`
- `HdrFormat` enum (`.../atvplaybackdevice/types/HdrFormat.smali`): `None`, `Hdr10`, `DolbyVision`
- `Codec` enum (`.../atvplaybackdevice/types/Codec.smali`): `H264`, `H265`
- `ThirdPartyProfileName` enum: `HD`, `HD_HEVC`, **`HDR`** (separate from `UHD_HDR`), `UHD_HDR`
- `QualityConfig$Values` enum: `GOOD`, `BETTER`, `BEST`, **`BEST_1080`**, `DATA_SAVER`

**Full APK quality tier analysis**: `dev/analysis/quality-tier-analysis.md`

**Codec param format**: `H264,H265` comma-separated — Kodi does `'H264' + (',H265' if _s.use_h265 else '')`.

**Fallback rule**: If `UHD_HDR` is selected but `MediaCodecList` finds no `video/hevc` hardware decoder on the device, the app automatically falls back to `HD_H265` at playback time. A Toast informs the user.

**Where it applies**: `getPlaybackInfo()` and `buildLicenseUrl()` in `AmazonApiService` — the license server validates quality claims, so both the manifest request and the license request must use identical quality params.

---

## Workarounds

### POST with empty body for catalog requests
The `getURLData` function passes `postdata=''` (empty string) which forces a POST request. The Android API catalog calls also use `postdata=''`. This is important — some endpoints reject GET requests.

**File reference**: `network.py:217-218`, `android_api.py:137`

### headers_android mutation
The Kodi plugin mutates `headers_android` dict in-place during refresh calls (removing `x-gasc-enabled`, `X-Requested-With`). In Kotlin, use a copy of the base headers for refresh calls to avoid side effects.

**File reference**: `login.py:591-593`

### gascEnabled parameter
Set to `true` for PrimeVideo domains, `false` for retail Amazon domains. Since we'll target PrimeVideo-style endpoints with token auth, default to `true`.

**File reference**: `network.py:198`

---

## Files Reference Map

| Kodi File | Purpose | Key Lines | Android Equivalent |
|-----------|---------|-----------|-------------------|
| `login.py` | Auth, token refresh, device registration | 551-604 | `AmazonAuthService.kt` |
| `common.py` | Device IDs, headers, globals | 55-58 | Constants in companion object |
| `network.py` | HTTP client, GetPlaybackResources, getATVData | 104-228 | `AmazonApiService.kt` |
| `playback.py` | Stream parsing, Widevine DRM config, player | 374-497 | `PlayerActivity.kt`, `AmazonLicenseService.kt` |
| `android_api.py` | Catalog browsing (Android-native API) | 108-300 | `AmazonApiService.kt` |
| `proxy.py` | MPD/subtitle proxy (not needed for native) | all | Not needed |
| `users.py` | User/account management | all | Simplified to `.device-token` |

---

## Decision 17: Audio track labelling — metadata-first, Amazon API over ExoPlayer flags

**Date**: Phase 25

**Problem**: ExoPlayer audio track metadata on Fire TV is unreliable — blank `format.label`,
repeated language groups, duplicate bitrate blocks, and no stable index. This caused:
- Audio Description (AD) tracks selected by default on some titles.
- AD label disappearing from the menu after an auto-switch (changing the representative track
  index changed the label to the non-AD variant of the same group).
- Human-readable names missing (language code shown instead of display name).

**Decision**: Build the audio menu from **Amazon's own audio track metadata** rather than
inferring everything from ExoPlayer live tracks.

Two API sources are merged at playback start:
1. Audio tracks from `GetPlaybackResources` response (playback metadata).
2. Audio tracks from the detail API (richer display names, `type` field).

Metadata is normalised into **audio families**: `main`, `ad`, `boost-medium`, `boost-high`.
Live ExoPlayer groups are mapped onto families by language + family kind, keeping the
best candidate (selected > highest bitrate) per family.

AD detection is metadata-first: `type == "descriptive"` from Amazon's API takes precedence
over `ROLE_FLAG_DESCRIBES_VIDEO` from ExoPlayer, which is often absent or mis-set on Fire TV.

Language matching uses base-code normalisation (`de-de` → `de`) so Fire TV tracks (which use
BCP-47 subtags) can be matched to API metadata (which may use just the two-letter code).

**Speed control — impossible on Fire TV**: `player.setPlaybackSpeed()` is intercepted by
Amazon's EMP (Extras Media Player) system service via a hidden MediaSession proxy, which
resets speed to 1.0× every ~80 ms on DRM content. Not implemented.

**Files**: `AmazonApiService.kt` (parse audio metadata), `PlayerActivity.kt` (merge, family
resolution, `buildAudioTrackOptions`, `normalizeInitialAudioSelection`).

---

## Decision 18: Player overlay visibility — follow exo_controller, not a separate timer

**Date**: Phase 25

**Problem**: The custom `track_buttons` overlay used its own `ControllerVisibilityListener` +
animation + timeout stack to show/hide alongside the Media3 controller. This drifted out of
sync when overlay buttons had focus (auto-hide timer fired, focus jumped to playerView,
controller re-showed, causing flicker) or when a dialog was dismissed.

**Decision**: Make `trackButtons` follow the **actual `exo_controller` view visibility** via a
polling runnable (`syncTrackButtonsRunnable`, 120 ms interval), not a separate timer or focus
listener. The overlay is visible if and only if `exo_controller` is visible.

- `controllerView` is resolved once via `playerView.post { playerView.findViewById(exo_controller) }`.
- On controller show: `syncTrackButtonsRunnable` starts polling.
- On controller hide: `hideTrackButtonsRunnable` fires immediately (no animation delay).
- Forced `controllerShowTimeoutMs` manipulation removed — Media3 manages its own timeout.
- MENU key calls `playerView.showController()` / `hideController()` as before; auto-focus on
  `btnAudio` delayed 120 ms to align with the poll cycle.

**Files**: `PlayerActivity.kt` (`syncTrackButtonsRunnable`, `hideTrackButtonsRunnable`,
`controllerView`, updated `ControllerVisibilityListener`, updated `onKeyDown`).
