# UI Review Report

**Reviewed by**: Codex acting as UI Reviewer
**Date**: 2026-03-01
**Screenshots reviewed**: `final_home_continue-watching.png`, `final_browse_all-seasons.png`, `final_detail_watchlist-overlay.png`

## Summary

| Section | Result | Critical failures |
|---------|--------|-------------------|
| A — Dependencies | PASS | none |
| B — Drawables | PASS | none |
| C — Content Card | PASS | none |
| D — Rail Row | PARTIAL | `See All` intentionally hidden pending a safe browse route |
| E — Shimmer | PASS | none |
| F — Main Screen | PASS | none |
| G — Detail Screen | PASS | none |
| H — D-pad Navigation | PASS | none |
| I — Regression | PASS | none |
| J — Player Overlay | PASS | none |

**Overall**: PARTIAL

---

## Findings

### PARTIAL items (should fix)

**[D3/D4] `See All` placeholder exists but remains intentionally hidden**
- **File**: `app/src/main/res/layout/item_rail.xml:26`
- **Found**: `tv_see_all` is still present in the layout, but the adapter keeps it hidden because the app does not expose a safe collection-browse route.
- **Expected**: The link should become visible only when a supported full-collection browse contract exists.
- **Fix**: Define a supported browse target for collection rails, then wire the action and unhide the control.

**[D3/D4] Adapter still suppresses unsupported `See All` actions**
- **File**: `app/src/main/java/com/scriptgod/fireos/avod/ui/RailsAdapter.kt:42`
- **Found**: `holder.seeAll.visibility = View.GONE` remains the active behavior during bind.
- **Expected**: This is acceptable only while collection browsing is unsupported.
- **Fix**: Keep it hidden until a browse contract exists, then replace it with a visible, focusable action.

### PASS items

`A1`, `A2`, `A3`, `A4`, `A5`, `B1`, `B2`, `B3`, `B4`, `B5`, `B6`, `B7`, `B8`, `C1`, `C2`, `C3`, `C4`, `C5`, `C6`, `C7`, `C8`, `C9`, `C10`, `D1`, `D2`, `D5`, `D6`, `E1`, `E2`, `E3`, `F1`, `F2`, `F3`, `F4`, `F5`, `F6`, `F7`, `F8`, `F9`, `F10`, `F11`, `F12`, `F13`, `F14`, `G1`, `G2`, `G3`, `H1`, `H2`, `H3`, `H4`, `H5`, `I1`, `I2`, `I3`, `I4`, `I5`, `J1`, `J2`

---

## Visual Assessment

- `current.png` and `home_rails.png` show the current Home treatment without the oversized hero. The compact header and filter stack leave more space for content and remain visually stable during focus changes.
- The latest header-spacing pass pushes the Home nav fully to the top edge and compresses the nav/filter stack, reclaiming vertical space without reintroducing overlap with emulator chrome.
- The latest Home editorial pass adds a compact featured strip above the rails and gives rail groups clearer eyebrows such as `Featured Now`, `Top 10`, `Just Added`, and `Award Picks`.
- `watchlist.png` confirms the grouped `Availability` and `Type` controls make the Watchlist filters more readable than the earlier stacked layout.
- `search.png`, `library.png`, and `browse.png` confirm the redesigned header language carries across secondary states without falling back under the emulator banner.
- The latest search-close pass removes the stale search summary card once search is dismissed, so Home and Watchlist return to their normal state instead of looking like lingering search views.
- The compact Home density pass shortens landscape rails, tightens inter-rail spacing, and removes redundant Prime subtitle copy so lower rows get more headroom on the emulator.
- `browse.png` specifically confirms Browse now reads as a dedicated destination with its own header panel, contextual chips, and framed grid surface instead of a bare recycler screen.
- The latest Browse density pass shortens the header, keeps episode browse at a 5-column landscape layout, and keeps season overview at a readable 4-column selector layout with span-filling cards.
- The latest Browse routing pass keeps `All Seasons` lightweight: selecting a season now opens the season's episode list directly, and the normal Back path returns to the season list instead of detouring through an extra overview screen.
- `about.png` confirms the old plain settings page has been replaced with a proper TV status/settings layout, with grouped app, token, playback, and session panels, and the header now names the app as `ScriptGod's AmazonVOD for FireOS`.
- The latest overlay pass replaces the stock watchlist dialog with a custom TV-style action sheet, and the About page now reliably lands initial focus on `Back`.
- `detail.png` confirms the detail action buttons now present their intended focus styling instead of default Material tint.
- `player_overlay.png` confirms the redesigned audio/subtitle controls match the newer control language used elsewhere in the UI.
- `final_home_continue-watching.png` confirms the dedicated progress-first `Continue Watching` card style is now live on Home.
- `final_browse_all-seasons.png` confirms season cards now read as navigational containers distinct from the utilitarian episode cards.
- The latest season-overview cleanup also removes the redundant `Open episode list` subtitle, leaving only the season label and title in the selector cards.
- `final_detail_watchlist-overlay.png` confirms Detail now uses the same custom watchlist overlay language as the rest of the redesign.
- The latest consistency pass keeps the page-title/supporting-text hierarchy aligned between Browse, About, Detail, and Player-adjacent UI, and removes the last obvious timing mismatch in card focus animation.
- The metadata truthfulness pass removes the last UI-side Prime inference. Card badges and the Home featured strip now rely on the same explicit availability rule, so Prime is only shown when the payload actually marks the title as Prime.
- The latest metadata refactor also moves payload parsing and UI metadata formatting into dedicated shared layers, which reduces the chance of Home, Browse, and Detail drifting apart again.
- The latest Detail cleanup also tightens support text so seasons and episodes carry contextual `From ...` copy only where it adds value, while empty metadata rows collapse cleanly instead of leaving dead space.
- The latest player pass reduces overlay width, trims padding/button height, shortens the hint copy, and hides the video-format pill when the player has no trustworthy live format to report.
- The latest Home progress fix keeps the Continue Watching indicator visible on resumed titles even when only local resume data is available, and it places the bar over the artwork so it remains visible when the row is only partially in view.
- The latest browse parser fix closes a real regression on `Fallout - Staffel 2`: object-shaped `badges` payloads no longer crash the detail-page item parser, so Browse no longer falls through to a false `No content found` state for that reason.
- The latest watched-state scope fix also keeps green completion bars reserved for fully watched episodes and movies instead of season/series containers.
- The latest normalized metadata pass adds internal content kind, availability, numbering, and hierarchy fields to the model so Browse and metadata rendering depend less on brittle raw payload strings.

## Device Validation Notes

- Emulator validation on `emulator-5554` covered the full visual pass and D-pad behavior checks.
- Emulator validation on March 1, 2026 confirmed the Home nav bar moved higher again while remaining clear of the emulator banner.
- Emulator validation on March 1, 2026 confirmed the final compact Home stack at `nav_bar [0,0][1920,96]`, `category_filter_row [64,104][1856,200]`, and `recycler_view [0,208][1920,1080]`.
- Emulator validation on March 1, 2026 confirmed the editorial Home variant at `nav_bar [0,0][1920,96]`, `category_filter_row [64,104][1856,200]`, `home_featured_strip [64,216][1856,384]`, and `recycler_view [0,392][1920,1080]`.
- Browse-page emulator validation confirmed `UP` from the first grid card lands on `Back`, preserving a clean escape route from the grid to the page header.
- Browse-page emulator validation also confirmed a real `Seasons -> Browse Episodes` flow with five visible landscape episode cards on the first row and improved card height (`Episodes` grid bounds `[120,439][1800,1024]`).
- Season-overview emulator validation confirmed the final readable state uses four span-filling season cards per row, without the redundant season subtitle line.
- Targeted emulator validation on March 1, 2026 confirmed `The Good Doctor -> All Seasons -> Season 2` now opens the `Season 2` episode list directly, Back returns to `All Seasons`, and episode subtitles like `S2 E1` remain fully visible.
- About-page emulator validation confirmed `Back` takes first focus in non-touch mode, the playback quality row is focusable and stateful, and the lower `Sign Out` action remains reachable through the scroll view.
- Search-state emulator validation confirmed `Results for ...` is shown only while search is active and disappears once search is dismissed.
- Emulator validation also confirmed `Included with Prime` no longer appears in card subtitles after the metadata cleanup.
- Emulator validation confirmed MENU now opens the custom watchlist overlay with `WATCHLIST`, `Add/Remove`, and `Cancel` actions.
- Final emulator validation confirmed a visible `Continue Watching` rail on Home, the new season-card Browse treatment, and the Detail watchlist overlay capture set.
- Consistency-pass emulator validation confirmed About still returns cleanly to Home through the new close transition helper, and Home/Browse/About all render with the updated shared type scale.
- Fire TV validation on `192.168.0.12:5555` confirmed login bypass via `.device-token`, successful entry to `MainActivity`, and a targeted overlay-validation attempt against `PlayerActivity`.
- Fire TV validation on `192.168.0.12:5555` also confirmed the compact Home geometry remains intact on-device, with `nav_bar [0,0][1920,96]` and `category_filter_row [64,104][1856,200]`.
- If Fire TV `uiautomator` still remains too limited to authoritatively inspect the transient player overlay controls, emulator screenshots remain the review source for that part of the UI.
- Emulator validation after the metadata pass confirmed:
  - `Borderlands` still renders the movie-detail metadata stack cleanly with IMDb and synopsis
  - `The Good Doctor - Staffel 7` renders the season-detail support line as `Drama  ·  From The Good Doctor`
  - Home still exposes the dedicated `Continue Watching` presentation with watchlist iconography and the shared badge rules
- Emulator validation during the latest Home fix reproduced the missing `Continue Watching` progress indicator on `Borderlands`, and the follow-up patch moved that indicator onto the artwork region so it is not lost at the bottom edge of the viewport.
- Emulator log validation on March 1, 2026 identified the `Fallout - Staffel 2` browse regression as a `ClassCastException` in `ContentItemParser` when `badges` arrived as a `JsonObject` instead of a `JsonArray`; that parser path is now guarded and covered by unit test.
- Unit-test validation on March 1, 2026 also confirmed the new normalized metadata layer populates Prime/Freevee/live availability and season/episode numbering without exposing internal IDs in the UI.
- Emulator validation after the latest player pass confirmed `PlayerActivity` still opens, and on DRM/license failure the bottom-left error panel is the only visible surface, which is the intended fallback behavior for failed playback.
