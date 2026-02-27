# Project Progress

## Phase 1: COMPLETE
- Analyzed all Kodi plugin source files in `/home/vscode/kodi-plugin/plugin.video.amazon-test/resources/lib/`
- Mapped all API endpoints: token refresh, GetPlaybackResources (manifest + license), catalog browsing (Android API), watchlist, search, stream reporting
- Documented auth flow: token-based with Bearer header, refresh via POST to `api.{domain}/auth/token`
- Documented device fingerprint: SHIELD Android TV identity (A43PXU4ZN2AL1), User-Agent, app metadata
- Documented Widevine license flow: custom challenge/response wrapping (widevine2Challenge → widevine2License.license)
- Output written to `analysis/api-map.md` and `analysis/decisions.md`

## Phase 2: COMPLETE
- Created Android project scaffold at `/home/vscode/amazon-vod-android/app/`
- Kotlin, minSdk 25, targetSdk 34
- Dependencies: Media3 ExoPlayer + DASH, OkHttp, Gson, Coroutines, Coil
- Activities: MainActivity (browse/search) → BrowseActivity (detail) → PlayerActivity
- Gradle wrapper (8.6), version catalog (libs.versions.toml)

## Phase 3: COMPLETE
- `AmazonAuthService.kt` — loads token from `.device-token`, OkHttp interceptor (auth + android headers), token refresh on 401
- `AmazonApiService.kt` — catalog browsing (home/search/watchlist/library/detail), GetPlaybackResources manifest fetch, UpdateStream reporting
- `AmazonLicenseService.kt` — custom MediaDrmCallback: wraps challenge as `widevine2Challenge=<base64url>`, parses `widevine2License.license` JSON response

## Phase 4: COMPLETE
- `PlayerActivity.kt` — Media3 ExoPlayer with DASH + DefaultDrmSessionManager (Widevine)
- Custom `AmazonLicenseService` wired as `MediaDrmCallback`
- Stream reporting (START/PLAY/STOP) via `UpdateStream`
- Release keystore generated at `/home/vscode/amazon-vod-android/release.keystore`

## Phase 5: COMPLETE
- `assembleRelease` BUILD SUCCESSFUL, APK signed with release keystore
- `adb install -r app-release.apk` — Success
- Token file pushed to `/data/local/tmp/.device-token` (world-readable, avoids external storage permission)
- App launches, territory detected (DE → atv-ps-eu.amazon.de / A1PA6795UKMFR9)

## Phase 6: COMPLETE
Bugs fixed during debug loop:
1. Catalog 404: changed POST → GET for catalog calls
2. German account: added `detectTerritory()` via GetAppStartupConfig
3. JSON parsing: unwrap `resource` wrapper, iterate `collections` as JsonArray
4. Widevine provisioning — 3-part fix:
   a. `executeProvisionRequest`: use plain OkHttpClient (no Amazon auth headers sent to Google)
   b. Google API requires POST with JSON body `{"signedRequest":"..."}`, not GET
   c. Device reboot needed so `amzn_drmprov` links new Widevine cert to Amazon account
5. License denial (`PRSWidevine2LicenseDeniedException`): added missing params to `buildLicenseUrl`
   (`deviceVideoQualityOverride=HD`, `deviceVideoCodecOverride=H264`, `deviceHdrFormatsOverride=None`)

Result: Video plays with Widevine L1 HW secure decode
- `secure.HW.video.avc` decoder active at up to 5830 kbps
- `secureSW.SW.audio.raw` decoder active at 640 kbps
- Adaptive bitrate streaming working
- Screenshot shows black (expected: `FLAG_SECURE` blocks captures of DRM content)

## Phase 7: COMPLETE
Extended search and browse to cover all available content categories:

### Content metadata added to ContentItem
- `isPrime` — parsed from model.isPrime / primeOnly / badgeInfo
- `isFreeWithAds` — parsed from model.isFreeWithAds / freeWithAds / badgeInfo
- `isLive` — parsed from contentType=="live" or liveInfo/liveState metadata fields
- `channelId` — parsed from playbackAction.channelId / station.id

### New AmazonApiService methods
- `ContentCategory` enum: ALL, PRIME, FREEVEE, CHANNELS, LIVE
- `getCategoryContent(category, query)` — unified entry point; routes to correct API + client-side filter
- `getChannelsPage()` — browse with channels pageId; falls back to find page filtered by channelId
- `getSearchSuggestions(query)` — debounced; returns first 8 titles from search (no dedicated Amazon suggestions endpoint found in Kodi plugin)
- `parseContentItems()` updated to extract all new metadata fields

### UI changes (MainActivity.kt / activity_main.xml)
- `EditText` replaced with `AutoCompleteTextView` — shows suggestion dropdown as user types
- 300ms debounce on keystroke calls `getSearchSuggestions()`; results populate dropdown
- Category filter row added: **All | Prime | Freevee | Channels | Live**
- Active category highlighted in blue (#00A8E0); inactive grey (#555)
- Category selection calls `getCategoryContent()` with current search query
- Nav buttons (Home/Watchlist/Library) reset category to ALL

## Phase 8: COMPLETE — Post-launch bug fixes

### Bug 1: Fire TV search keyboard unusable
**Symptom**: Search bar visible but user cannot type — keyboard never appeared or couldn't be dismissed.
**Root causes & fixes**:
1. `AutoCompleteTextView` replaced with custom `DpadEditText` (extends `AppCompatEditText`) — suggestion dropdown interfered with Fire TV IME
2. `DpadEditText.onKeyPreIme()` intercepts BACK key *before* the IME consumes it — `SHOW_FORCED` keyboard now dismisses properly on back press
3. `stateHidden` added to manifest `windowSoftInputMode` — prevents keyboard auto-showing on activity start
4. Keyboard shows only on explicit DPAD_CENTER click via `setOnClickListener` + `showSoftInput(SHOW_FORCED)`
5. `dismissKeyboardAndSearch()` hides keyboard, clears focus to RecyclerView, then triggers search

**Files changed**: `DpadEditText.kt` (new), `MainActivity.kt`, `activity_main.xml`, `AndroidManifest.xml`

### Bug 2: Search returned no results
**Symptom**: Search API called successfully but parser returned 0 items.
**Root causes & fixes**:
1. Search response uses `titles[0].collectionItemList` — parser only handled `collections[].collectionItemList` (home/browse format). Added `titles` array parsing branch.
2. `getAsJsonPrimitive()` throws `ClassCastException` on JSON null values. Added `safeString()` / `safeBoolean()` extension methods on `JsonObject` that return null for both missing and null fields.
3. Search model uses `id` field (not `titleId`) for ASIN. Added `id` as fallback in ASIN extraction chain.

**Files changed**: `AmazonApiService.kt`

### Bug 3: Search results missing poster images
**Symptom**: Only first row (from `collections` format) showed images; search results had blank posters.
**Root cause**: Search model stores images in `titleImageUrls` object (`BOX_ART`, `COVER`, `POSTER` keys) and `heroImageUrl`, not `image.url`.
**Fix**: Added image extraction chain: `image.url` → `imageUrl` → `titleImageUrls.{BOX_ART,COVER,POSTER,LEGACY,WIDE}` → `heroImageUrl` → `titleImageUrl` → `imagePack`
Also fixed `ContentAdapter` to clear images on recycled ViewHolders.

**Files changed**: `AmazonApiService.kt`, `ContentAdapter.kt`

### Bug 4: Player not fullscreen — title bar visible
**Symptom**: "Prime Video" app title bar visible at top during video playback.
**Root causes & fixes**:
1. PlayerActivity used default `Theme.FireTV` which inherits `Theme.AppCompat` (includes ActionBar). Switched to `Theme.FireTV.Player` with parent `Theme.AppCompat.NoActionBar`.
2. Added immersive sticky mode in `onCreate` and `onResume`: `SYSTEM_UI_FLAG_IMMERSIVE_STICKY | FULLSCREEN | HIDE_NAVIGATION`
3. Explicit `supportActionBar?.hide()` call.

**Files changed**: `PlayerActivity.kt`, `AndroidManifest.xml`, `themes.xml`

## Phase 9: COMPLETE — Watchlist functionality

### Features
- **Long-press to toggle watchlist**: Any content item can be added/removed from watchlist via long-press on the grid
- **Visual watchlist indicator**: Star icon overlay on each content card (filled = in watchlist, outline = not in watchlist)
- **Startup watchlist sync**: App fetches all watchlist ASINs on launch to mark items correctly
- **Optimistic UI**: Watchlist state updates immediately in the adapter after API confirms success
- **Toast feedback**: User sees "Adding to / Removing from / Added to / Removed from watchlist" messages

### API changes (`AmazonApiService.kt`)
- `addToWatchlist(asin)` — calls `AddTitleToList` endpoint
- `removeFromWatchlist(asin)` — calls `RemoveTitleFromList` endpoint
- `getWatchlistAsins()` — fetches watchlist page, extracts ASIN set

### Model changes (`ContentItem.kt`)
- Added `isInWatchlist: Boolean = false` field

### UI changes
- `item_content.xml` — poster wrapped in FrameLayout; added star icon overlay (`iv_watchlist`) at top-right
- `ContentAdapter.kt` — added `onItemLongClick` callback, `watchlistIcon` binding, star on/off based on `isInWatchlist`
- `MainActivity.kt` — added `watchlistAsins` cache, `toggleWatchlist()` method, startup fetch, `showItems()` marks items
- `BrowseActivity.kt` — updated `ContentAdapter` constructor call for new named parameter

### Verified
- 20 watchlist items loaded on startup
- 74 home content items displayed with correct watchlist indicators

## Phase 10: COMPLETE — Library functionality

### Features
- **Library sub-filters**: All / Movies / TV Shows filter chips shown when Library nav is active
- **Sort toggle**: Cycles through Recent / A→Z / Z→A on each click
- **Pagination**: Infinite scroll — loads next page via `libraryNext/v2.js` with `startIndex` when user scrolls near bottom
- **Nav button highlight**: Active nav button (Home/Watchlist/Library) highlighted in blue (#00A8E0)
- **Library-specific UI**: Library filter row shown only on Library page; category filter chips hidden
- **Empty library message**: Shows "Your library is empty. Rent or buy titles to see them here." when no purchased/rented content

### API changes (`AmazonApiService.kt`)
- Added `LibraryFilter` enum: ALL, MOVIES, TV_SHOWS
- Added `LibrarySort` enum: DATE_ADDED, TITLE_AZ, TITLE_ZA
- Added `getLibraryPage(startIndex, filter, sort)` — paginated, filtered, sorted library fetch
- Initial page: `libraryInitial/v2.js`; subsequent pages: `libraryNext/v2.js` with `startIndex` param
- Client-side filtering by contentType (Feature/Movie for Movies, Episode/Season/Series for TV Shows)
- Client-side sorting for title A-Z / Z-A; API default for date added

### UI changes
- `activity_main.xml`:
  - Added `library_filter_row` with `btn_lib_all`, `btn_lib_movies`, `btn_lib_shows`, `btn_lib_sort`
  - Added `id` to `category_filter_row` for visibility toggling
  - Added `nextFocusDown` on search field → `btn_home`
- `MainActivity.kt`:
  - Added `currentNavPage` tracking + `updateNavButtonHighlight()` + `updateFilterRowVisibility()`
  - Added library state: `libraryFilter`, `librarySort`, `libraryNextIndex`, `libraryLoading`
  - Added `setLibraryFilter()`, `cycleLibrarySort()`, `loadLibraryInitial()`, `loadLibraryNextPage()`
  - Added RecyclerView scroll listener for infinite scroll pagination
  - `loadNav("library")` resets filter/sort and calls `loadLibraryInitial()`

### Library API response format (documented)
- Response: `{"resource":{"pageTitle":"Video Library", "refineModel":{"filters":[...],"sorts":[...]}, "titles":[...], "dataWidgetModels":[...]}}`
- Filters from API: TV Shows / Movies / Pay-Per-View
- Sorts from API: Most Recent Addition / Title A-Z / Title Z-A
- Empty library returns `"titles":[]` and `dataWidgetModels` with `"textType":"EMPTY_CUSTOMER_LIST"`

### Verified
- Library endpoint called successfully
- Library sub-filter row (All/Movies/TV Shows/Sort) visible when Library active
- Category filter row hidden when Library active
- Empty library message displayed correctly (account has no purchased content)
- Nav button highlight working (Library button highlighted in blue)

## Phase 11: COMPLETE — Freevee nav + Channels/Live removal

### Changes
- **Freevee nav button added**: New "Freevee" button between Home and Watchlist in the nav row
- **Channels & Live removed**: Removed `btn_cat_channels`, `btn_cat_live`, `btn_cat_freevee` category chips from layout; `ContentCategory` enum simplified from `{ALL, PRIME, FREEVEE, CHANNELS, LIVE}` to `{ALL, PRIME}`; removed `getChannelsPage()` method

### Freevee API (`AmazonApiService.kt`)
- `getFreeveePage()` — tries catalog Browse endpoint with `OfferGroups=B0043YVHMY` (Kodi: common.py:186) for server-side Freevee filtering
- Catalog Browse uses Kodi device TypeIDs (`A3SSWQ04XYPXBH`, `A1S15DUFSI8AUG`, `A1FYY15VCM5WG1`) with `message.body.titles[]` response format
- Falls back to home page content when catalog Browse is unavailable (404 on global US endpoint)
- `parseCatalogBrowseItems()` — parses Kodi-style catalog response format

### Territory detection note
- Territory detection now fully functional — see "Territory Detection Fix" section below
- Freevee (Amazon's free ad-supported service) is not available in all territories (e.g. DE)

### UI changes
- `activity_main.xml`: Added `btn_freevee` nav button; removed Channels/Live/Freevee category chips; category row now only has All + Prime
- `MainActivity.kt`: Added `btnFreevee` binding and click handler; updated `updateNavButtonHighlight()` and `updateFilterRowVisibility()` for freevee page; both filter rows hidden for freevee

### Verified
- Build: `assembleRelease` SUCCESS
- Deploy: APK installed on Fire TV
- Home: 74 items displayed
- Freevee: Falls back to 74 home items (catalog Browse returns 404 in DE territory)
- Watchlist: 20 items displayed
- Library: 0 items (no purchases on account)
- All nav buttons functional with correct highlight

## Phase 12: COMPLETE — Movies/Series filter + Series drill-down

### Content type filtering
- Added `MOVIES` and `SERIES` to `ContentCategory` enum
- Added companion object helpers: `isMovieContentType()`, `isSeriesContentType()`, `isEpisodeContentType()`, `isPlayableType()`
- `getCategoryContent()` updated to filter by content type (MOVIE/Feature for Movies; Season/Series/Show for Series)
- Home page content types: ~49 MOVIE + ~25 SEASON items

### Series drill-down (BrowseActivity)
- Selecting a SEASON item from any page → opens `BrowseActivity` with series detail
- Detail page API: `android/atf/v3.jstl` with `itemId=` param → returns `{show, seasons, episodes, selectedSeason}`
- Parser updated to extract items from `seasons[]` and `episodes[]` arrays in detail response
- Season items: `[titleId, title, seasonNumber, badges, aliases]` — formatted as "Season N"
- Episode items: `[id, linkAction, title, episodeNumber, contentType, ...]` — formatted as "EN: Title"
- Multi-season shows: seasons list → select season → episodes list → select episode → play
- Single-season shows: episodes shown directly (skip season selection)

### ASIN extraction fix
- Episode items from detail page have `titleId` only inside `linkAction` object (not at top level)
- Added `linkAction.titleId` to ASIN extraction chain: `catalogId → compactGti → titleId → linkAction.titleId → id → asin`

### Episode playback fix
- GTI-format ASINs (`amzn1.dv.gti.*`) reject `videoMaterialType=Episode` with `PRSInvalidRequestException`
- `videoMaterialType=Feature` works for both movies AND episodes with GTI ASINs
- Changed PlayerActivity to always use `Feature` materialType

### UI changes
- `activity_main.xml`: Added Movies/Series filter buttons to category row with proper D-pad navigation (`nextFocusDown`)
- `activity_browse.xml`: Added `descendantFocusability="afterDescendants"` for D-pad focus
- `MainActivity.kt`: Added Movies/Series button bindings, series routing to BrowseActivity, grid focus management
- `BrowseActivity.kt`: Complete series drill-down with filter logic, grid focus after item load

### Verified
- Build: `assembleRelease` SUCCESS
- Movies filter: Shows ~49 MOVIE type items from home page
- Series filter: Shows ~25 SEASON type items from home page
- Series drill-down: "Wake Season 1" → detail page → 7 items (seasons + episodes)
- Episode playback: "E1: Wide Awake" plays with 1920x1080 HW secure decode, 2.5-5 Mbps video
- D-pad navigation: Grid items focusable, first child auto-focused after load

## Phase 13: COMPLETE — Watch Progress Tracking

### Implementation
Implemented full watch progress tracking per `dev/analysis/watch-progress-api.md`:

#### AmazonApiService.kt — dual API support
- **UpdateStream** (legacy GET) — enhanced with `titleId`, `timecodeChangeTime` (ISO 8601), `userWatchSessionId`; parses `callbackIntervalInSeconds` from response
- **PES V2** (modern POST) — `pesStartSession()`, `pesUpdateSession()`, `pesStopSession()` at `/cdp/playback/pes/`; ISO 8601 duration format (`PT1H23M45S`); session token management
- `secondsToIsoDuration()` helper for PES V2 timecode format

#### PlayerActivity.kt — stream reporting lifecycle
- `startStreamReporting()` — sends UpdateStream START + PES StartSession on first STATE_READY
- `startHeartbeat()` — periodic PLAY events at server-directed interval (min of UpdateStream and PES intervals)
- `sendProgressEvent()` — dual-API calls (UpdateStream + PES UpdateSession) with interval refresh
- `stopStreamReporting()` — sends STOP to both APIs on STATE_ENDED, onStop(), or player error
- PAUSE support via `onIsPlayingChanged` listener — pauses heartbeat, sends PAUSE event
- Server-directed heartbeat interval via `callbackIntervalInSeconds` response field

### PES V2 note
- PES V2 StartSession returns HTTP 400 — requires `playbackEnvelope` (encrypted playback authorization from Amazon's playback infrastructure) which is not available via GetPlaybackResources
- PES V2 methods are implemented but non-functional without the envelope; falls back gracefully
- **UpdateStream alone is sufficient** for "Continue Watching" / resume position syncing

### Verified on Fire TV (physical device)
- Build: `assembleRelease` SUCCESS
- Deploy: APK installed on Fire TV Stick 4K
- Widevine L1 HW secure playback active (secureSW.SW.audio.raw ~640kbps)
- **UpdateStream START**: `SUCCESS`, `canStream: true`, `statusCallbackIntervalSeconds: 180`
- **UpdateStream PLAY heartbeat**: fires every 60s, `SUCCESS`, position tracks correctly (t=59s)
- **UpdateStream PAUSE**: fires on HOME key press, `SUCCESS` (t=16s)
- **UpdateStream STOP**: fires from `onStop()` lifecycle, `SUCCESS` (t=16s)
- Server-directed interval (`statusCallbackIntervalSeconds: 180`) parsed and applied

## Phase 14: COMPLETE — Audio & Subtitle Track Selection

### Implementation

#### API changes (`AmazonApiService.kt`)
- `extractSubtitleTracks()` — parses `subtitleUrls[]` and `forcedNarratives[]` from GetPlaybackResources response
- Returns list of `SubtitleTrack(url, languageCode, type)` where type is "regular", "sdh", or "forced"
- Already requesting `desiredResources=PlaybackUrls,SubtitleUrls,ForcedNarratives` and `audioTrackId=all`

#### Model changes (`ContentItem.kt`)
- Added `SubtitleTrack` data class (url, languageCode, type)
- Added `subtitleTracks` field to `PlaybackInfo`

#### Player changes (`PlayerActivity.kt`)
- `DefaultTrackSelector` — replaces ExoPlayer's implicit selector; enables programmatic track overrides
- External subtitle tracks loaded via `SingleSampleMediaSource` + `MergingMediaSource` (TTML format) — `SubtitleConfiguration` on `MediaItem` is ignored by `DashMediaSource`
- Audio tracks automatically available from DASH manifest (ExoPlayer parses MPD Adaptation Sets)
- `showTrackSelectionDialog(trackType)` — builds AlertDialog listing available audio or text tracks
  - Audio: shows language + channel layout (5.1, Stereo, etc.)
  - Subtitles: shows language + type label (SDH, Forced); includes "Off" option
  - Applies selection via `TrackSelectionOverride` on `trackSelectionParameters`
- Audio/Subtitle buttons shown at top-right when playback starts (STATE_READY)

#### Layout changes (`activity_player.xml`)
- Added `track_buttons` LinearLayout at top-right with `btn_audio` and `btn_subtitle` buttons
- Semi-transparent black background, white text, D-pad focusable

### Verified
- Build: `assembleRelease` SUCCESS
- Deploy: APK installed on Fire TV Stick 4K
- Video plays with Widevine L1 (audio codec active ~640kbps)
- Track buttons visible during playback, D-pad navigable
- No crashes on button press or track selection

## Phase 15: COMPLETE — In-App Login

### Implementation

#### LoginActivity.kt (new)
- Full Amazon OAuth login flow: email + password → MFA (optional) → device registration → token save
- **PKCE challenge**: SHA-256 code verifier/challenge for OAuth security
- **OAuth sign-in**: POST to `api.amazon.com/ap/signin` with OpenID 2.0 + OAuth 2.0 extension params
- **MFA support**: Detects when Amazon returns MFA challenge; shows OTP input field; resubmits with `otpCode`/`mfaResponse`
- **Device registration**: POST to `api.amazon.com/auth/register` with authorization_code + code_verifier + device fingerprint
- **Token persistence**: Saves TokenData (access_token, refresh_token, device_id, expires_at) to `/data/local/tmp/.device-token`
- **Auto-skip**: If valid token file already exists, skips login and launches MainActivity directly
- **Skip button**: "Use Device Token" fallback for development/debugging when .device-token is pre-pushed via ADB

#### Layout (`activity_login.xml`)
- Email field (textEmailAddress input)
- Password field (textPassword input)
- MFA container (hidden initially, shown when 2FA required)
- Sign In button (blue #00A8E0)
- Status text (errors in red, info in blue, success in green)
- Progress spinner during network calls
- "Use Device Token" skip button (visible only when token file exists)

#### AndroidManifest changes
- `LoginActivity` is now the LAUNCHER activity (entry point)
- `MainActivity` changed to `exported="false"` (launched from LoginActivity after auth)
- Login flow: LoginActivity → (check token / perform login) → MainActivity

#### Compatibility
- `.device-token` file continues to work exactly as before for debugging
- Existing devices with pre-pushed tokens skip login automatically
- New devices show login screen, register via OAuth, then proceed to browse

## Phase 16: COMPLETE — GitHub Actions CI/CD

### Implementation

#### GitHub Actions workflow (`.github/workflows/build.yml`)
- **Triggers**: push to main, pull requests to main, manual workflow_dispatch
- **Build environment**: Ubuntu latest, JDK 17 (Temurin), Android SDK via android-actions/setup-android
- **Date-based versioning**: `YYYY.MM.DD_N` format (e.g., `2026.02.26_1`), auto-increments N per day based on existing git tags
- **Signing**: Decodes `release.keystore` from GitHub Secret (base64), configures signing via environment variables
- **APK output**: Renamed to `FireOS-AVOD-{version}.apk` and uploaded as artifact (90-day retention)
- **Auto-release**: On push to main, creates a GitHub Release with tag `v{version}` and attaches signed APK

#### Build config changes (`app/build.gradle.kts`)
- `versionName` reads from `-PversionNameOverride` Gradle property (default: `1.0-dev`)
- `versionCode` reads from `-PversionCodeOverride` Gradle property (default: `1`)
- Signing config reads keystore path/passwords from environment variables with local fallbacks
- Local development continues to work unchanged (env vars fall back to hardcoded dev values)

#### Required GitHub Secrets
- `RELEASE_KEYSTORE_BASE64` — base64-encoded release.keystore
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — key alias
- `RELEASE_KEY_PASSWORD` — key password

### README updated
- Added all new features (login, track selection, watch progress, resume, CI/CD)
- Updated architecture diagram with LoginActivity
- Added CI/CD section with secrets table and versioning explanation
- Updated deploy instructions with new LoginActivity entry point
- Added authentication section explaining both in-app login and dev token workflows

## Territory Detection Fix (post-Phase 16)

### Root causes found and fixed
1. **`deviceTypeID` mismatch**: `GetAppStartupConfig` was using Kodi's default `A28RQHJKHM2A2W` while our device was registered with `A43PXU4ZN2AL1`. API returned `CDP.Authorization: Device type id in request does not match.`
2. **`supportedLocales=en_US`** — only sent one locale; Kodi sends 18 locales. Amazon requires the user's locale to be listed to return territory info
3. **No `sidomain`** — token refresh was hardcoded to `api.amazon.com`. DE accounts need `api.amazon.de`
4. **`homeRegion` parsed from wrong parent** — was looking in `territoryConfig`, actually under `customerConfig`
5. **Invalid `uxLocale`** — API can return error strings like `LDS_ILLEGAL_ARGUMENT` instead of a valid locale

### Changes
- `AmazonApiService.kt`:
  - `TerritoryInfo` data class replaces `Pair<String, String>` — adds `sidomain` and `lang`
  - `TERRITORY_MAP` expanded with 8 entries including `A2MFUE2XK8ZSSY` (PV EU Alt)
  - `detectTerritory()` rewritten with 3-layer detection (Kodi `login.py:55-78`)
  - `buildDynamicTerritory()` helper for unknown marketplaces (constructs URL from `defaultVideoWebsite` + `homeRegion`)
  - `supportedLocales` sends 18 locales
  - `uxLocale` validated with regex `[a-z]{2}_[A-Z]{2}`
  - `deviceTypeID` uses `AmazonAuthService.DEVICE_TYPE_ID` instead of hardcoded Kodi value
- `AmazonAuthService.kt`:
  - Removed hardcoded `REFRESH_ENDPOINT` constant
  - Added `siDomain` field + `setSiDomain()` setter
  - Token refresh uses `https://api.$siDomain/auth/token`

### Verified on Fire TV
- Territory: `atvUrl=https://atv-ps-eu.amazon.de marketplace=A1PA6795UKMFR9 sidomain=amazon.de lang=de_DE`
- Catalog: 20 watchlist + 74 home items loaded from DE endpoint with `de_DE` locale
- No errors in logcat

## Subtitle, Watchlist & Sorting Fixes (post-Phase 16)

### Subtitle fix
- **Problem**: `DashMediaSource` ignores `MediaItem.SubtitleConfiguration` — external subtitles were added to the `MediaItem` but never loaded by the player
- **Fix**: Use `SingleSampleMediaSource` for each subtitle track, merged with DASH source via `MergingMediaSource`
- **Verified**: 2 TTML subtitle tracks extracted and loaded, `TtmlParser` confirming parsing in logcat

### Watchlist pagination
- **Problem**: `getWatchlistPage()` only loaded `watchlistInitial` (first ~20 items)
- **Fix**: Added `getWatchlistPage(startIndex)` with `watchlistNext` transform (matching library pagination pattern)
- Added `loadWatchlistInitial()` / `loadWatchlistNextPage()` in `MainActivity` with infinite scroll
- `getWatchlistAsins()` now loads all pages for complete watchlist indicators on startup

### Title sorting
- **Problem**: All content pages displayed in API return order (unsorted)
- **Fix**: `showItems()` now sorts all items by `title.lowercase()` before submitting to adapter
- Applies to home, search, watchlist, and freevee pages

## Phase 17: PENDING — AI Code Review

Full codebase review performed by an AI agent using `dev/REVIEW.md` as the checklist.

### Scope
- **Security audit**: Token handling, no credential logging, HTTPS-only, no hardcoded secrets
- **Login flow correctness**: PKCE generation, client_id format, cookie handling, CVF/MFA detection, device registration
- **DRM review**: Challenge wrapping, license unwrapping, provisioning flow
- **API layer**: Error handling, retry logic, response parsing robustness
- **Player lifecycle**: ExoPlayer setup/teardown, DRM session management, track selection, resume position
- **UI/UX**: D-pad navigation, focus handling, RecyclerView diffing, image loading/recycling
- **CI/CD**: Secret handling, build reproducibility, version monotonicity
- **Code quality**: Kotlin idioms, coroutine usage, resource leaks, null safety

### Deliverables
- List of findings categorized as Critical / Warning / Info
- Suggested fixes for any issues found
- Confirmation that security checklist passes

## Phase 18: PENDING — UI Redesign

Redesign the app UI from functional prototype to a polished, modern streaming experience.

### Goals
- Transform the current flat grid + button layout into a visually appealing TV-first interface
- Match the look and feel of modern streaming apps (hero banners, horizontal carousels, smooth animations)
- Maintain full D-pad/remote navigation support

### Planned changes

#### Home screen
- **Hero banner**: Large featured content banner at top with backdrop image, title, synopsis, and Play button
- **Horizontal carousels**: Replace single grid with categorized rows ("Continue Watching", "Trending", "Watchlist", "Recently Added")
- **Row headers**: Section titles with "See All" navigation
- **Card redesign**: Rounded corners, shadow/elevation, focus scale animation, gradient overlay with title

#### Navigation
- **Side rail / top bar**: Replace flat button row with a collapsible side navigation rail or a fixed top nav bar with icons
- **Smooth transitions**: Fade/slide animations between screens and content loads
- **Loading states**: Skeleton placeholders instead of blank screen while content loads

#### Series / Browse
- **Detail page**: Full-width backdrop, season tabs, episode list with thumbnails and descriptions
- **Parallax scrolling**: Backdrop image parallax on scroll

#### Player
- **Overlay controls**: Semi-transparent gradient overlay with transport controls, progress bar, and track info
- **Thumbnail preview**: Show frame previews on seek (if available)

#### General
- **Color palette**: Refine from flat cyan to a gradient/themed palette
- **Typography**: Consistent text hierarchy (title, subtitle, body, caption)
- **Focus states**: Animated scale + glow border instead of simple highlight
- **Shimmer loading**: Placeholder animations during API calls
