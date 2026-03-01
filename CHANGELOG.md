# Changelog

All notable changes to ScriptGod's FireOS AmazonVOD are documented here.

## [Unreleased]

## [2026.03.01.5] - 2026-03-01

### Fixed (Phase 27 review findings)
- Search query value no longer logged — log shows character count only (privacy)
- `LoginActivity`: removed raw auth response body from logcat; token file write moved to `Dispatchers.IO` (was blocking main thread)
- `DetailActivity`: ASIN extra blank-checked before use; error message includes ASIN in log and clearer user-facing text
- `UiMotion.revealFresh`: no longer hard-resets alpha on already-visible views — eliminates flash when the same view is re-revealed
- `ContentAdapter`: `scaleX`, `scaleY`, `elevation` reset in `onBindViewHolder` and `onViewRecycled` — recycled cards can no longer appear permanently oversized after fast scrolling
- `ShimmerAdapter`: shimmer stopped in `onViewRecycled` as well as `onViewDetachedFromWindow`
- `AboutActivity`: `PREF_AUDIO_PASSTHROUGH` constant added (was inline string literal); `save(true)` guarded by `supportsAny` so pref cannot be written `true` on unsupported outputs
- `PlayerActivity`: `loadAndPlay()` cancels previous `playbackJob` before launching a new one, preventing stale in-flight coroutines on H265 fallback; `widevineSecurityLevel()` now distinguishes `UnsupportedSchemeException` (expected, `W`) from unexpected `MediaDrm` failures (`E`)
- `activity_about.xml`: `btn_about_back` converted to `AppCompatButton` for drawable state-list consistency

## [2026.03.01.4] - 2026-03-01

### Added
- **Widevine L3 / SD quality fallback** (Phase 28) — `PlayerActivity` now queries
  `MediaDrm.getPropertyString("securityLevel")` before player creation; if the result is not
  `"L1"` (e.g. Android emulator, un-provisioned hardware), quality is forced to
  `PlaybackQuality.SD` regardless of the user's quality setting.  This mirrors the official
  Amazon APK's `ConfigurablePlaybackSupportEvaluator` behaviour: Amazon's license server
  rejects `HD + L3 + no HDCP` but grants `SD + L3 + no HDCP`.  A one-time Toast informs the
  user on first detection, gated by `"widevine_l3_warned"` pref.
- `PlaybackQuality.SD` preset (`"SD"`, `"H264"`, `"None"`) — 480p H264 SDR, not exposed as a
  user-selectable option; used only by the automatic L3 fallback path.

### Fixed
- Playback on Android emulators and Widevine L3 devices now succeeds (was previously denied
  by the license server with `PRSWidevine2LicenseDeniedException` due to HD quality + no HDCP)
- Player overlay quality label now shows `"SD (Widevine L3)"` when the fallback is active
- Cleaned up redundant `android.widget.Toast` fully-qualified references in `resolveQuality()`

## [2026.03.01.3] - 2026-03-01

### Added
- **Audio passthrough toggle** in About screen (PLAYBACK panel) — Off / On buttons with live HDMI capability badge (`AC3 + EAC3 capable` / `Passthrough unavailable`); On button greyed out when device output does not report Dolby support; setting persisted as `"audio_passthrough"` in `SharedPreferences("settings")`
- **Passthrough-aware renderer** in `PlayerActivity` — when enabled, overrides `DefaultRenderersFactory.buildAudioSink()` to inject a `DefaultAudioSink` built with `AudioCapabilities.getCapabilities(context)`, allowing encoded AC3/EAC3 bitstreams to pass directly to the HDMI output / AV receiver
- One-time volume warning Toast on first passthrough-enabled playback session gated by `"audio_passthrough_warned"` pref
- Video format label in player overlay — shows active codec, resolution, and HDR status (e.g. `720p · H265 · SDR`, `4K · H265 · HDR10`), updated live as ABR ramps up

### Fixed
- Search icon in the top-right header was clipped/off-center on Fire TV due to emoji (`🔍`) glyph metrics — replaced with an `ImageButton` + `ic_search.xml` vector drawable
- About screen quality and passthrough buttons showed identical appearance for all states despite distinct drawables — root cause was `MaterialButton` applying `colorPrimary` tint over the drawable; fixed by switching all option buttons to `<androidx.appcompat.widget.AppCompatButton>` (Decision 19)
- About screen: D-pad Up from passthrough buttons jumped to Back instead of the quality row — fixed `nextFocusUp` on both passthrough buttons
- Player overlay (`track_buttons`) no longer flickers or lingers after Media3 controller auto-hides — overlay visibility now driven by polling actual `exo_controller` view
- Audio Description (AD) tracks no longer selected by default — `DefaultTrackSelector` configured with `ROLE_FLAG_MAIN` preference; `normalizeInitialAudioSelection()` switches away from AD on first track load
- Native ExoPlayer `exo_subtitle` and `exo_settings` buttons re-hidden after every `onTracksChanged`
- Trailers always start from position 0 — `setupPlayer()` no longer loads the movie's resume position into trailers
- Watching or finishing a trailer no longer marks the movie as watched — resume writes skipped when `currentMaterialType == "Trailer"`
- Format label no longer shows stale codec from previous session when H265→H264 fallback fires
- H265 CDN fallback now re-fetches the HD H264 manifest instead of restricting the track selector — UHD manifests only include H264 up to 720p; HD manifest provides the full H264 tier
- H265 / 4K presets fall back to HD H264 when display reports no HDR support via HDMI EDID, preventing blank-screen output on SDR TVs
- Watchlist badge and `isInWatchlist` state refreshed from `watchlistAsins` on `BrowseActivity` resume
- Accurate Prime badge on detail page sourced from ATF v3 API; Featured rail filtered to exclude non-Prime titles when Prime filter is active

### Changed
- Audio track menu now built from **Amazon's API metadata** (merged from `GetPlaybackResources` + detail API) — correct human-readable names, proper AD labelling, and family grouping (main / AD / Dialogue Boost) on all titles; channel layout suffix (`2.0`, `5.1`, `7.1`) appended when ExoPlayer exposes `channelCount`; Dialogue Boost filtered out by default
- **Quality presets finalised**: HD H264 (`HD + H264 + None`) → 720p H264 SDR; H265 (`HD + H264,H265 + None`) → 720p H265 SDR; 4K/DV HDR (`UHD + H264,H265 + Hdr10,DolbyVision`) → 4K HDR; H265 button enabled on non-HDR displays; only 4K/DV HDR greyed out when no HDR display detected
- Settings button visual states redesigned: rest = near-black, selected = vivid teal fill `#1B7A9E`, focused = dark body + 3dp vivid ring, focused+selected = bright teal + white ring
- MENU key auto-focus on `btnAudio` uses 120 ms guard so it only fires once overlay is confirmed visible
- Launcher and header icons polished

## [2026.02.28.18] - 2026-02-28

### Added
- **Video quality setting** in the About screen (⚙) — three presets selectable per device:
  - **HD (H264)** — 720p SDR, H264 only (safe default, works everywhere)
  - **H265** — 720p SDR with H265/HEVC streams included in the DASH manifest
  - **4K / HDR** — UHD with H265, HDR10 and Dolby Vision streams
- Device H265/HEVC capability indicator — H265 and 4K/HDR buttons greyed-out when device has no HEVC decoder
- Auto-fallback: if 4K/HDR selected but device has no H265 decoder at playback time, falls back to HD+H265 with Toast notification
- `model/PlaybackQuality.kt` — data class holding the three API override params; stored in `SharedPreferences("settings")` / `"video_quality"`

### Changed
- `AmazonApiService.getPlaybackInfo()` and `buildLicenseUrl()` now accept a `PlaybackQuality` parameter — quality params sent consistently to both manifest request and Widevine license request (Decision 16)

## [2026.02.28.17] - 2026-02-28

### Added
- **"All Seasons" button** on season detail pages — navigates back to the full season list for the parent show using `show.titleId` ASIN from the detail API

## [2026.02.28.16] - 2026-02-28

### Fixed
- Detail page action buttons (Play, Trailer, Browse, Watchlist) now always visible — moved to fixed bottom bar that renders independently of synopsis/metadata scroll height

## [2026.02.28.15] - 2026-02-28

### Added
- **Content Overview / Detail Page** (`DetailActivity`) — hero backdrop image, poster, year, runtime, age rating, quality badges, IMDb rating, genres, synopsis, director credit
- **▶ Trailer** button (visible only when `isTrailerAvailable: true`)
- **☆ / ★ Watchlist** toggle on detail page
- `PlayerActivity` now accepts `EXTRA_MATERIAL_TYPE` for trailer playback without code duplication
- `model/DetailInfo.kt` and `api/AmazonApiService.getDetailInfo(asin)`

### Changed
- All content item clicks now route through `DetailActivity` first
- Season selection in `BrowseActivity` routes through `DetailActivity`

## [2026.02.28.14] - 2026-02-28

### Fixed
- Watchlist context menu now triggers on **long press SELECT** — Alexa Voice Remote on Fire TV Stick 4K has no physical Menu button; long press is the standard Fire TV gesture for context menus
- Audio track menu no longer lists the same language multiple times — one entry per unique language + channel-layout + codec combination
- Audio track selection uses adaptive bitrate within the chosen group (`TrackSelectionOverride` with empty track list)
- Seekbar D-pad seek increment fixed from `duration ÷ 20` to a fixed **10 seconds** per key press

### Changed
- Watchlist toggle redesigned as a **context menu** (`AlertDialog`) — long press SELECT or KEYCODE_MENU to open; works on all grids and in BrowseActivity

## [2026.02.28.8] - 2026-02-28

### Fixed
- Player controls and AUDIO/SUBTITLES buttons now always show and hide together via `PlayerView.ControllerVisibilityListener`
- AUDIO/SUBTITLES buttons properly receive D-pad focus when controls are visible
- AUDIO/SUBTITLES overlay no longer permanently visible during playback — shown on pause, shown/hidden on MENU key with 3 s auto-hide
- `showItems()` now preserves server-provided content ordering — removed forced A-Z sort
- Player `onStop()` now pauses instead of stopping, preventing re-prepare failure on resume
- Password not trimmed before being sent to Amazon
- `x-gasc-enabled` header removed from the authenticated API client (login-flow-only header)

### Architecture
- `PlayerActivity` coroutine scope tied to lifecycle via named `scopeJob`, cancelled in `onDestroy()`

### CI/CD
- Release keystore deleted after APK signing (if: always() step)
- `versionCode` derived from full `YYYY.MM.DD.N` string for strict monotonicity

## [2026.02.28.5] - 2026-02-28

### Added
- **About screen** (⚙ gear button): app version, package name, masked device ID, token file location
- **Sign Out** button with confirmation dialog: deletes token, records `logged_out_at`, clears resume positions, returns to login

### Fixed
- In-app login "Please Enable Cookies" — added `X-Requested-With` and `x-gasc-enabled` headers to login OkHttp client
- Logout now correctly blocks stale legacy token by comparing `lastModified()` against `logged_out_at`

## [2026.02.27.5] - 2026-02-27

### Added
- Home page horizontal carousels — vertically stacked rails per content category (Featured, Trending, Top 10, etc.)
- Rail-level pagination: more rails load as user scrolls to the bottom
- Watch progress bars on home screen rails (amber bar for partially watched)
- `getWatchlistData()` merges `remainingTimeInSeconds` into home rails

### Changed
- Home tab now shows horizontal carousels; all other tabs retain flat grid
- `remainingTimeInSeconds` treated as remaining (not watched); 0 treated as no data

### Fixed
- Home page watch progress always green/100% due to `remainingTimeInSeconds=0` misinterpretation

## [2026.02.27.4] - 2026-02-27

### Added
- Watch progress bars on content cards — amber for in-progress, synced with server-side data
- `runtimeMs` and `watchProgressMs` fields on `ContentItem`

### Fixed
- Watchlist showing only ~20 items — now eagerly loads all pages
- Resume position not saved on back-navigation — moved `saveResumePosition()` to `onPause()`
- Series/season cards incorrectly showing progress bars

### Changed
- Version schema: `YYYY.MM.DD.N` (was `YYYY.MM.DD_N`)

## [2026.02.27_3] - 2026-02-27

### Fixed
- Watchlist showing only 20 items — switched to `getDataByJvmTransform` switchblade endpoints
- Watchlist pagination infinite loop — now uses `watchlist/next/v1.kt` with `serviceToken`
- Duplicate movies in content grid — added `distinctBy { asin }` deduplication

## [2026.02.27_2] - 2026-02-27

### Fixed
- Subtitles always showing "no tracks available" — external subtitle tracks now loaded via `MergingMediaSource`

## [2026.02.27_1] - 2026-02-27

### Fixed
- Territory detection: DE account correctly resolves to `atv-ps-eu.amazon.de` / `A1PA6795UKMFR9`
- Token refresh endpoint uses territory-specific `api.{sidomain}`
- GetAppStartupConfig device type ID mismatch
- Invalid `uxLocale` values rejected with regex, falls back to territory default

## [2026.02.26_2] - 2026-02-26

### Added
- CHANGELOG.md
- "This title requires purchase" message for non-Prime content
- Track selection button highlight on D-pad focus
- App screenshots in README; AI review guide (`dev/REVIEW.md`); app icon with TV banner
- In-app login with Amazon email/password + MFA/CVF support (PKCE OAuth)
- GitHub Actions CI/CD with date-based versioning and automatic APK releases
- Audio & subtitle track selection during playback
- Watch progress tracking via UpdateStream API; resume from last position
- Library with pagination, sub-filters, and sort; Freevee tab; Movies/Series filters
- Series drill-down: show → seasons → episodes → play
- Watchlist management (long-press to add/remove)
- Home catalog browsing, search with instant results
- Widevine L1 hardware-secure playback (DASH/MPD)
- Token-based authentication with automatic refresh on 401/403
- D-pad navigation optimized for Fire TV remote

### Fixed
- Audio & subtitle buttons hard to see / no focus feedback in player
- Login cookie jar pollution; token file EACCES on API 34; false MFA detection
- Search keyboard unusable on Fire TV (`DpadEditText`); search returning no results
- Player not fullscreen; content ordering overridden by forced A-Z sort
- Catalog 404 (POST → GET); German account territory detection
- Widevine provisioning; license denial (missing quality/codec override params)

### Changed
- CI triggers: build on version tags, PRs, and manual dispatch only
