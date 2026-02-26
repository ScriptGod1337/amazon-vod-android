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
- `detectTerritory()` returns `marketplace=null` on current device — GetAppStartupConfig response (174 bytes) doesn't contain `territoryConfig.avMarketplace`
- App falls back to US defaults (`atv-ps.amazon.com` / `ATVPDKIKX0DER`) — works for mobile/switchblade API endpoints which are global
- Catalog Browse endpoint (`/cdp/catalog/Browse`) returns 404 on global US URL — requires territory-specific URL (e.g. `atv-ps-eu.amazon.de`)
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
