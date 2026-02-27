# Changelog

All notable changes to ScriptGod's FireOS AmazonVOD are documented here.

## [Unreleased]

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
