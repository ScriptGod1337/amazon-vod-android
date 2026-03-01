# UI Review Request

## Build version
`8a7b6a8` plus local redesign changes through 2026-03-01

## What was implemented
- [x] Dependencies (`material`, `shimmer`)
- [x] Drawable resources for cards, chips, nav, header, and player controls
- [x] Content card (`item_content.xml`): size, corners, badges, focus animation
- [x] Rail row (`item_rail.xml`): header typography and `See All` placeholder view
- [x] Shimmer loading placeholder
- [x] Main screen: two-row header, compact grouped filters, search toggle, no oversized hero
- [x] Browse screen: redesigned header panel, context/count chips, back action, and unified grid container
- [x] Watchlist: grouped `Availability` and `Type` controls, restored `Prime` overlay for supported titles
- [x] About screen: redesigned settings/status layout with app, token, playback, and session panels
- [x] Detail screen: redesigned action row with corrected focus styling
- [x] Player overlay: restyled audio/subtitle controls and adjusted safe top spacing
- [x] Shared consistency pass: unified page typography scale, consistent compact-control sizing, shared page open/close transitions, and normalized card focus timing
- [x] Search close behavior: closing search now clears the search summary card and restores the current page state
- [x] Header spacing pass: Home header stack is now fully top-aligned and the nav/filter rows are more compact
- [x] About copy pass: title and supporting copy now identify the app as `ScriptGod's AmazonVOD for FireOS`
- [x] Home density pass: rail headers, card heights, recycler padding, and inter-rail spacing were reduced so lower rows remain visible on the emulator
- [x] Card metadata cleanup: redundant `Included with Prime` subtitle text was removed in favor of badge-only Prime signaling
- [x] Home editorial pass: added a compact featured strip and stronger rail eyebrow taxonomy without reintroducing the oversized hero
- [x] Browse density pass: season and episode browse now use a shorter header and a denser 5-column landscape grid so cards are fully visible
- [x] Browse routing pass: selecting a season from `All Seasons` now opens that season's episode list directly, and Back returns to the season list
- [x] About focus fix: `Back` now takes initial focus reliably on About entry
- [x] Watchlist overlay redesign: MENU now opens a custom TV-style watchlist action overlay instead of the stock dialog
- [x] Episode card legibility pass: episode cards now preserve the bottom subtitle line in Browse
- [x] Prime badge rule fix: Prime badges are no longer inferred for every non-Freevee / non-live title
- [x] Final visual pass: Continue Watching is now visually verified on Home, season cards have their own Browse treatment, and Detail uses the same watchlist overlay system as Home/Browse
- [x] Metadata truthfulness pass: Prime detection now requires an explicit Prime signal from the payload instead of being inferred from `not Freevee`
- [x] Shared availability helpers: card badges and featured-strip metadata now use one `included with Prime / Freevee / Live` rule across Home, Browse, and Watchlist
- [x] Shared metadata mapping layer: payload parsing and UI metadata formatting now live in dedicated mapper/formatter classes instead of ad hoc per-screen logic
- [x] Detail metadata cleanup: empty metadata rows now collapse cleanly, and support text is more context-aware for seasons and episodes
- [x] Player overlay tuning pass: Fire TV-safe overlay margins were tightened and MENU now routes track focus to `Audio`
- [x] Player compactness pass: the track overlay is now narrower, denser, and hides the format pill when no real playback format is available
- [x] Continue Watching progress fix: Home progress cards now keep a visible progress indicator even when only local resume data exists, and the bar is anchored over the artwork so it remains visible in the lower viewport
- [x] Fallout browse parser fix: episode/season browse now tolerates object-shaped `badges` payloads instead of failing with a parser exception and showing `No content found`
- [x] Watched-state scope fix: green fully-watched progress is now limited to fully watched episodes and movies, not season/series shells
- [x] Normalized metadata layer: internal content kind, availability, season/episode numbering, and hierarchy IDs are now mapped explicitly so Browse and metadata formatting no longer depend on raw payload strings alone

## Known issues / deviations from spec
- `See All` remains intentionally hidden. The current app still has no safe full-collection browse contract for `collectionId` without API/model work.
- Fire TV verification can confirm entry into `PlayerActivity`, but the transient overlay may still be difficult to inspect with `uiautomator` on this box. Emulator verification remains the authoritative visual check if device-side dumps fail.

## Build / verification
- `./gradlew assembleRelease` completed successfully on March 1, 2026.
- `./gradlew testDebugUnitTest` completed successfully on March 1, 2026.
- Installed and verified on `emulator-5554` (`AOSP_TV_on_x86`) on March 1, 2026.
- Installed and spot-checked on Fire TV `192.168.0.12:5555` on March 1, 2026.
- Fire TV login bypass was re-enabled by pushing `.device-token` to `/data/local/tmp/.device-token`, fixing permissions, and updating the file timestamp.

## Verified behaviors
- Home no longer renders the oversized hero; rails start directly below the compact filter panel.
- Home shows only the `Type` controls. Watchlist shows separate `Availability` and `Type` groups.
- Header tabs are now top-aligned on Home, use underline-only for the active page, and leave more vertical room for content rails.
- Home now includes a compact non-focusable featured strip above the rails instead of a full hero banner.
- Focus states are differentiated for selected vs focused nav and filter controls.
- Browse uses a dedicated page header with `Back`, context chips, and item count, and `UP` from the first grid item returns to `Back`.
- Season and episode browse use a denser landscape grid, giving more visible card height and fitting five episode cards across on the emulator.
- Selecting a season from `All Seasons` now opens that season's episode list directly, and pressing Back from that list returns to `All Seasons`.
- Non-home surfaces now share the same page-title, supporting-text, panel-title, and compact-control scale.
- Detail, Browse, and About now use the same page transition helper for open/close navigation, and content-card focus animation timing is normalized.
- `UP` from the Home grid returns to `All Types`.
- `UP` from the Watchlist grid returns to `All`, and `UP` from watchlist filters returns to `Watchlist`.
- Detail action buttons now show the intended focus treatment.
- About uses the redesigned settings/status layout, enters non-touch focus cleanly on `Back`, and keeps both the playback quality row and `Sign Out` reachable via D-pad scroll.
- Closing search now removes the search summary card and restores the underlying page instead of leaving stale search state visible in Home or Watchlist.
- About now uses the explicit app title `ScriptGod's AmazonVOD for FireOS` in the header copy.
- About now takes initial non-touch focus on the `Back` button reliably.
- Home rails use a denser layout so lower sections like `Die Top 10 in Deutschland` and `Kürzlich hinzugefügt` have more room to remain visible on the emulator.
- Home rail eyebrows now distinguish editorial groups like `Featured Now`, `Top 10`, `Just Added`, and `Award Picks`.
- Prime titles no longer repeat `Included with Prime` in card subtitles when the Prime badge is already shown.
- Prime badges are now only shown when a title is actually marked Prime in the returned data, not simply because it is not ad-supported.
- Home featured metadata now follows the same availability truthfulness rules as card badges instead of using a separate Prime heuristic.
- MENU on cards now opens a custom watchlist action overlay with explicit `Add/Remove` and `Cancel` actions.
- The final Home state now includes a verified `Continue Watching` rail using the dedicated progress-first card presentation.
- Home `Continue Watching` cards now use the shared metadata layer and keep a visible progress indicator for resumed titles such as `Borderlands`.
- Emulator log validation confirmed the `Fallout - Staffel 2` browse failure was caused by a `ClassCastException` in the badge parser, and the parser now accepts object-shaped `badges` payloads instead of crashing the browse load.
- Fully watched state is now intentionally scoped away from season and series containers, so green completion bars remain reserved for fully watched episodes and movies.
- Browse and metadata formatting now consume normalized internal fields (`kind`, `availability`, `seasonNumber`, `episodeNumber`, hierarchy IDs) while keeping those IDs fully internal and out of the user-visible UI.
- Detail metadata now collapses empty rows cleanly and keeps season/episode support text more relevant to the current content type.
- Player overlay audio/subtitle controls use the updated redesign styling.
- Player overlay now uses a denser track panel with shorter hint copy, smaller buttons, and conditional video-format visibility.
- Fire TV validation confirms Home still renders with the compact top-aligned geometry on `192.168.0.12:5555` (`nav_bar [0,0][1920,96]`, `category_filter_row [64,104][1856,200]`).
- Emulator validation confirms the metadata/badge pass on `Borderlands` and `The Good Doctor - Staffel 7`, and Home still exposes the dedicated `Continue Watching` treatment.
- Emulator validation confirms the latest player run still reaches `PlayerActivity`; on DRM failure only the tuned error panel remains visible, without the unused track panel.

## Screenshots
- Captured under `dev/ui-redesign/review-screenshots/`:
  - `final_home_continue-watching.png`
  - `final_browse_all-seasons.png`
  - `final_detail_watchlist-overlay.png`
