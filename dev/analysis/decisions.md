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
