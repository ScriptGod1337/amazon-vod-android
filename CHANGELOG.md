# Changelog

All notable changes to ScriptGod's FireOS AmazonVOD are documented here.

## [Unreleased]

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
