# Changelog

All notable changes to ScriptGod's FireOS AmazonVOD are documented here.

## [Unreleased]

## [2026.02.28.16] - 2026-02-28

### Fixed
- Detail page action buttons (Play, Trailer, Browse, Watchlist) now always visible — moved to a fixed bottom bar that renders independently of synopsis/metadata scroll height; previously buttons could be cut off when the description text was long

## [2026.02.28.15] - 2026-02-28

### Added
- **Content Overview / Detail Page** (`DetailActivity`) — selecting any movie or season/series now opens a full detail screen before playback:
  - **Hero backdrop image** (16:9) loaded from Amazon's `detailPageHeroImageUrl` field
  - **Poster thumbnail**, title, year, runtime, age rating, quality badges (4K / HDR / 5.1)
  - **IMDb rating** displayed in gold when available (from `imdbRating` field — e.g. `IMDb  5.7 / 10`)
  - **Genres** (sub-genre entries containing `>` are suppressed)
  - **Synopsis** (full description text)
  - **Director** credit line
  - **▶ Play** button for movies/features → launches `PlayerActivity`
  - **▶ Trailer** button (visible only when `isTrailerAvailable: true`) → plays trailer via `GetPlaybackResources?videoMaterialType=Trailer` with the same GTI ASIN
  - **Browse Episodes / Browse Seasons** button for series/seasons → launches `BrowseActivity`
  - **☆ / ★ Watchlist** toggle button (updates via Add/RemoveTitleFromList API)
- `PlayerActivity` now accepts `EXTRA_MATERIAL_TYPE` (default `"Feature"`) so trailer playback can be started without code duplication
- `model/DetailInfo.kt` — data class holding all detail-page fields
- `api/AmazonApiService.getDetailInfo(asin)` — fetches `android/atf/v3.jstl` and parses rich metadata; for SEASON ASINs, reads data from `resource.selectedSeason` rather than `resource` directly

### Changed
- All content item clicks in `MainActivity` now route through `DetailActivity` first (movies, series, seasons) — no more direct jump to `PlayerActivity` or `BrowseActivity` from the home/search/watchlist grids
- Season selection in `BrowseActivity` now routes through `DetailActivity` (season overview + "Browse Episodes" button) instead of jumping directly to a nested episode `BrowseActivity`

### Technical
- API analysis for the detail endpoint documented in `dev/analysis/detail-page-api.md`
- `dev/analysis/detail-atf-v3-movie.json` and `detail-atf-v3-season.json` — raw API response samples
- Trailer playback confirmed working with `videoMaterialType=Trailer` on the same GTI ASIN as the feature

## [2026.02.28.14] - 2026-02-28

### Fixed
- Watchlist context menu now triggers on **long press SELECT** (hold D-pad center ~0.5 s) — the Alexa Voice Remote on Fire TV Stick 4K has no physical Menu button, so the previous KEYCODE_MENU-only trigger never fired; long press is the standard Fire TV gesture for context menus

## [2026.02.28.13] - 2026-02-28

### Changed
- Watchlist toggle redesigned as a **context menu** (`AlertDialog`) instead of immediate silent toggle — long press SELECT on any focused content card to see "Add to Watchlist" / "Remove from Watchlist" with confirmation; works on home rails, all flat grid tabs (Watchlist, Library, Search, Freevee), and in BrowseActivity (seasons and episodes)
- KEYCODE_MENU (physical Menu button on older / 3rd-party remotes) also opens the context menu as a secondary trigger

### Added
- `BrowseActivity` now supports watchlist management — series seasons and episodes can be added/removed from the watchlist; `watchlistAsins` state is passed from MainActivity via Intent and propagated through nested season → episode BrowseActivity stacks
- Watchlist star indicator on BrowseActivity items reflects live membership state

### Technical
- `ContentAdapter`: item views tagged with `ContentItem` for Activity-level key lookup; `onItemLongClick` → `onMenuKey` callback
- `MainActivity` / `BrowseActivity`: `onKeyDown(KEYCODE_MENU)` + `recyclerView.findFocus()` tag walk handles flat grid and nested rails uniformly
- `MainActivity.toggleWatchlist()` updates both flat-grid adapter and rails adapter on change

## [2026.02.28.12] - 2026-02-28

### Fixed
- Audio track menu no longer lists the same language multiple times — Amazon's DASH manifests split each bitrate into its own adaptation set; the dialog now shows one entry per unique language + channel-layout + codec combination
- Audio track selection now uses adaptive bitrate within the chosen group (`TrackSelectionOverride` with empty track list) — ExoPlayer picks the best bitrate automatically after the user selects a language
- Codec qualifier (`· Dolby` / `· AAC`) added to audio entries only when a title offers both codecs for the same language and channel count — otherwise labels stay clean (e.g. just `German (5.1)`)
- Seekbar D-pad seek increment fixed from `duration ÷ 20` (≈ 6 min on a 2-hour film) to a fixed **10 seconds** per key press, matching standard TV remote behaviour; holding the key repeats at the same step

## [2026.02.28.8] - 2026-02-28

### Fixed
- Player controls (seekbar, play/pause) and AUDIO/SUBTITLES buttons now always show and hide together — synced via `PlayerView.ControllerVisibilityListener` so MENU key toggles all controls as a single unit
- AUDIO/SUBTITLES buttons now properly receive D-pad focus and are selectable when controls are visible (previously buttons appeared but were unreachable because the player controller was hidden independently)
- Removed separate `showOverlay`/`hideOverlay` helpers and manual auto-hide runnable — `PlayerView` manages auto-hide timing; track buttons follow via the visibility listener

## [2026.02.28.7] - 2026-02-28

### Fixed
- AUDIO/SUBTITLES overlay buttons no longer permanently visible during playback — they were set to `VISIBLE` in the `STATE_READY` handler and never hidden
- Overlay now hidden during active playback; shown automatically on pause; shown/hidden on MENU key press with 3 s auto-hide if playing

## [2026.02.28.6] - 2026-02-28

### Fixed
- `showItems()` now preserves server-provided content ordering — removed forced A-Z sort that was silently overriding search results, watchlist ordering, and library sort preferences (F-009)
- Player `onStop()` now pauses instead of stopping the player, preventing a "player stopped but not re-prepared" failure on resume (F-010)
- Password field cleared after successful login so plaintext credential does not linger in UI state (F-002)
- Password no longer trimmed before being sent to Amazon — passwords are opaque secrets and must not be mutated (F-007)

### Security
- `x-gasc-enabled` header removed from the authenticated API client (`AmazonAuthService.AndroidHeadersInterceptor`) — it is a login-flow-only header and must not be sent on catalog, playback, watchlist, or DRM requests (F-003)

### Architecture
- `PlayerActivity` coroutine scope tied to activity lifecycle via named `scopeJob` — cancelled in `onDestroy()`; `setupPlayer()` guarded with `isDestroyed || isFinishing` check (F-004)
- `LoginActivity` coroutine scope tied to activity lifecycle via named `scopeJob` — added `onDestroy()` to cancel it (F-008)

### CI/CD
- Release keystore deleted after APK signing completes (`if: always()` step) — decoded key no longer persists in the workspace after the build (F-005)
- `versionCode` now derived from the full `YYYY.MM.DD.N` version string (e.g. `2026022806`) instead of the date alone, guaranteeing strict monotonicity for multiple same-day builds (F-006)

## [2026.02.28.5] - 2026-02-28

### Added
- **About screen** (⚙ gear button in top-right of nav bar): shows app version, package name, masked device ID, and token file location
- **Sign Out** button on About screen (with confirmation dialog): deletes internal token file, records `logged_out_at` timestamp, clears resume positions, and returns to the login screen

### Fixed
- In-app login "Please Enable Cookies to Continue" after signing out — Amazon requires `X-Requested-With: com.amazon.avod.thirdpartyclient` and `x-gasc-enabled: true` on every login request; these headers are now added by an OkHttp interceptor on the login client
- Logout now correctly blocks the stale legacy `/data/local/tmp/.device-token` by comparing its `lastModified()` against the `logged_out_at` timestamp — re-pushing a fresh token (plus `adb shell touch`) resumes development without re-logging in
- `adb push` preserves host file mtime, so a bare push of an old token would still appear stale — documented the required `adb shell touch` step

## [2026.02.27.5] - 2026-02-27

### Added
- Home page horizontal carousels — vertically stacked rails per content category (Featured, Trending, Top 10, etc.) matching the real Prime Video home layout
- `ContentRail` model, `RailsAdapter` outer adapter, `item_rail.xml` row layout — outer vertical RecyclerView contains inner horizontal RecyclerVIew per rail
- v2 landing API integration (`dv-android/landing/initial/v2.kt` + `landing/next/v2.kt`) with `PRIME_SERVICE_TOKEN` — structured rails with section headers
- Rail-level pagination: more rails load automatically as user scrolls to the bottom of the home page
- Movies/Series type filter chips now work on rails view (filters items within each rail)
- Watch progress bars on home screen rails — amber bar for partially watched movies (progress data sourced from watchlist API and merged into rails)
- `getWatchlistData()` API method returns both ASIN set and watch progress map in a single pass; used to merge `remainingTimeInSeconds` data into home rails

### Changed
- Home tab now shows horizontal carousels instead of flat alphabetical grid; all other tabs (Watchlist, Library, Freevee, Search) retain flat grid
- `parseItemsFromArray()` extracted as shared helper used by both flat-list and rails parsers
- Progress bar renderer changed from custom XML drawable (failed in nested RecyclerView) to default `Widget.ProgressBar.Horizontal` style with `progressTintList` (8dp height)
- `remainingTimeInSeconds` treated as "remaining" not "watched" — `watchProgressMs = runtimeMs - remainingSec*1000`; value of 0 treated as ambiguous (no data), not fully watched

### Fixed
- Home page watch progress bars were always green/100% — caused by `remainingTimeInSeconds=0` being incorrectly treated as "fully watched" (v1 API returns 0 for items with real progress in v2/watchlist API)
- Watch progress not showing on home screen — v2 rails API omits `remainingTimeInSeconds`; now sourced from watchlist data and merged at display time

## [2026.02.27.4] - 2026-02-27

### Added
- Watch progress bars on content cards — amber bar for in-progress, green bar for fully watched
- Server-side watch history: progress bars reflect data from Amazon API (`remainingTimeInSeconds`, `timecodeSeconds`) so content watched in the official Prime Video app shows progress too
- New drawable `watch_progress_bar.xml` for card progress indicator
- `runtimeMs` and `watchProgressMs` fields on `ContentItem` data model

### Fixed
- Watchlist showing only ~20 items: now eagerly loads all pages via `getAllWatchlistItems()` instead of relying on infinite scroll
- Episode detail pages missing progress bars: added parsing of `timecodeSeconds` + `completedAfterSeconds` (detail API format) in addition to `remainingTimeInSeconds` (home/watchlist API format)
- Series/season cards showing inconsistent progress bars between home and watchlist: progress bars now correctly skipped for series-level items (only shown on movies and episodes)
- Resume position not saved when navigating back from player: moved `saveResumePosition()` to `onPause()` to fix Android lifecycle ordering (PlayerActivity.onPause → MainActivity.onResume → PlayerActivity.onStop)

### Changed
- Version schema: `YYYY.MM.DD.N` (was `YYYY.MM.DD_N`)
- Watchlist load strategy: all pages fetched upfront (removed incremental scroll-based pagination)
- Fully watched sentinel: `SharedPreferences` stores `-1L` instead of removing the key, enabling "fully watched" vs "never watched" distinction
- `BrowseActivity` now refreshes watch progress on `onResume()` when returning from player

## [2026.02.27_3] - 2026-02-27

### Fixed
- Watchlist showing only 20 items: switched from `getDataByTransform` (JS) to `getDataByJvmTransform` (Kotlin) switchblade endpoints matching Prime Video 3.0.438 decompilation
- Watchlist pagination infinite loop: `watchlistInitial` ignored `startIndex`; now uses `watchlist/next/v1.kt` with `serviceToken` from `paginationModel.parameters`
- Duplicate movies in content grid: added `distinctBy { asin }` deduplication in `parseContentItems()`
- Pagination `ClassCastException` on `JsonNull` in `paginationModel`

### Changed
- Watchlist endpoints: `initial/v1.kt` + `next/v1.kt` via switchblade (was `watchlistInitial/v3.js` via mobile transform)
- Root-level `collectionItemList` now parsed (switchblade next-page response format)

## [2026.02.27_2] - 2026-02-27

### Fixed
- Subtitles always showing "no tracks available": external subtitle tracks now loaded via `MergingMediaSource` instead of `SubtitleConfiguration` (which `DashMediaSource` ignores)

### Changed
- All content pages (home, search, watchlist) now sorted alphabetically by title

## [2026.02.27_1] - 2026-02-27

### Fixed
- Territory/region detection: DE account now correctly resolves to `atv-ps-eu.amazon.de` / `A1PA6795UKMFR9` / `amazon.de` instead of falling back to US defaults
- Token refresh endpoint: now uses territory-specific `api.{sidomain}` (e.g. `api.amazon.de` for DE) instead of hardcoded `api.amazon.com`
- GetAppStartupConfig device type ID mismatch: was sending Kodi's `A28RQHJKHM2A2W` instead of our registered `A43PXU4ZN2AL1`
- Locale detection: `supportedLocales` now sends 18 locales (matching Kodi) instead of only `en_US`
- Invalid `uxLocale` values (e.g. `LDS_ILLEGAL_ARGUMENT`) rejected with regex validation; falls back to territory default

### Changed
- `TERRITORY_MAP` expanded with `TerritoryInfo` data class (adds `sidomain` and `lang` per territory)
- Added `A2MFUE2XK8ZSSY` (PV EU Alt) territory entry
- Territory detection now uses 3-layer fallback matching Kodi `login.py:55-78`

## [2026.02.26_2] - 2026-02-26

### Added
- CHANGELOG.md
- "This title requires purchase" message when content is not included in Prime
- Track selection button highlight on D-pad focus

### Fixed
- Audio & subtitle buttons hard to see / no focus feedback in player

## [2026.02.26_1] - 2026-02-26

### Added
- App screenshots in README (login, home, search, watchlist, library, filters, series, episodes)
- Roadmap phases 17 (AI Code Review) and 18 (UI Redesign) in progress.md
- Links to recent builds and latest release in README
- AI review and testing guide (`dev/REVIEW.md`)
- App icon with ScriptGod's AVOD branding and TV banner

### Changed
- CI triggers: build on version tags (`v*`), PRs, and manual dispatch only (no longer on every push)
- Release keystore regenerated with scriptgod.de identity
- CI uses `gradle/actions/setup-gradle` + system Gradle (aligned local and CI)

### Fixed
- Login cookie jar pollution causing "Please Enable Cookies" on retry
- Token file EACCES on API 34 (now writes to app-internal storage with legacy fallback)
- False MFA detection when Amazon shows CVF (Customer Verification Flow)
- CVF form URL resolution for relative action URLs
- Dev scripts using hardcoded paths (now use relative paths)
- TV launcher icon/banner clipping in Apps view

## [2026.02.25_1] - 2026-02-25

### Added
- In-app login with Amazon email/password + MFA/CVF support (PKCE OAuth)
- GitHub Actions CI/CD with date-based versioning and automatic APK releases
- Audio & subtitle track selection during playback (5.1/Stereo, SDH/Forced)
- Watch progress tracking via UpdateStream API (START/PLAY/PAUSE/STOP)
- Resume from last watched position (SharedPreferences, clears at 90%)

## [2026.02.24_1] - 2026-02-24

### Added
- Library with pagination, sub-filters (Movies/TV Shows), and sort (Recent/A-Z/Z-A)
- Freevee navigation tab (territory-dependent)
- Movies/Series content type filters
- Series drill-down: show -> seasons -> episodes -> play
- Watchlist management (long-press to add/remove, star indicator)

### Fixed
- Search keyboard unusable on Fire TV (custom DpadEditText)
- Search returning no results (titles[] response format)
- Search results missing poster images (titleImageUrls extraction)
- Player not fullscreen (title bar visible)

## [2026.02.23_1] - 2026-02-23

### Added
- Initial release
- Home catalog browsing with content grid
- Search with instant results
- Widevine L1 hardware-secure playback (DASH/MPD)
- Token-based authentication with automatic refresh on 401/403
- D-pad navigation optimized for Fire TV remote

### Fixed
- Catalog 404 (POST -> GET)
- German account territory detection
- Widevine provisioning (plain OkHttpClient for Google, POST with JSON body)
- License denial (missing quality/codec override params)
