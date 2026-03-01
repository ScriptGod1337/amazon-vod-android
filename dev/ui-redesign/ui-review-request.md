# UI Review Request

## Build version
`1b2b6fc` plus local redesign changes through 2026-03-01

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

## Known issues / deviations from spec
- `See All` remains intentionally hidden. The current app still has no safe full-collection browse contract for `collectionId` without API/model work.
- Fire TV verification can confirm entry into `PlayerActivity`, but the transient overlay may still be difficult to inspect with `uiautomator` on this box. Emulator verification remains the authoritative visual check if device-side dumps fail.

## Build / verification
- `./gradlew assembleRelease` completed successfully on March 1, 2026.
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
- Non-home surfaces now share the same page-title, supporting-text, panel-title, and compact-control scale.
- Detail, Browse, and About now use the same page transition helper for open/close navigation, and content-card focus animation timing is normalized.
- `UP` from the Home grid returns to `All Types`.
- `UP` from the Watchlist grid returns to `All`, and `UP` from watchlist filters returns to `Watchlist`.
- Detail action buttons now show the intended focus treatment.
- About uses the redesigned settings/status layout, enters non-touch focus cleanly on `Back`, and keeps both the playback quality row and `Sign Out` reachable via D-pad scroll.
- Closing search now removes the search summary card and restores the underlying page instead of leaving stale search state visible in Home or Watchlist.
- About now uses the explicit app title `ScriptGod's AmazonVOD for FireOS` in the header copy.
- Home rails use a denser layout so lower sections like `Die Top 10 in Deutschland` and `Kürzlich hinzugefügt` have more room to remain visible on the emulator.
- Home rail eyebrows now distinguish editorial groups like `Featured Now`, `Top 10`, `Just Added`, and `Award Picks`.
- Prime titles no longer repeat `Included with Prime` in card subtitles when the Prime badge is already shown.
- Player overlay audio/subtitle controls use the updated redesign styling.

## Screenshots
- Captured under `dev/ui-redesign/review-screenshots/`:
  - `current.png`
  - `home_rails.png`
  - `search.png`
  - `watchlist.png`
  - `library.png`
  - `browse.png`
  - `about.png`
  - `detail.png`
  - `player_overlay.png`
