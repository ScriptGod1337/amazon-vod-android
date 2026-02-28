# UI Review Request

## Build version
`1b2b6fc` plus local redesign changes through 2026-02-28

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

## Known issues / deviations from spec
- `See All` remains intentionally hidden. The current app still has no safe full-collection browse contract for `collectionId` without API/model work.
- Fire TV verification reached `PlayerActivity`, but the transient overlay could not be fully dumped with `uiautomator`; emulator verification remains the authoritative visual check for the overlay.

## Build / verification
- `./gradlew assembleRelease` completed successfully on February 28, 2026.
- Installed and verified on `emulator-5554` (`AOSP_TV_on_x86`) on February 28, 2026.
- Installed and spot-checked on Fire TV `192.168.0.12:5555` on February 28, 2026.
- Fire TV login bypass was re-enabled by pushing `.device-token` to `/data/local/tmp/.device-token`, fixing permissions, and updating the file timestamp.

## Verified behaviors
- Home no longer renders the oversized hero; rails start directly below the compact filter panel.
- Home shows only the `Type` controls. Watchlist shows separate `Availability` and `Type` groups.
- Header tabs remain visible below the emulator banner and use underline-only for the active page.
- Focus states are differentiated for selected vs focused nav and filter controls.
- Browse uses a dedicated page header with `Back`, context chips, and item count, and `UP` from the first grid item returns to `Back`.
- Non-home surfaces now share the same page-title, supporting-text, panel-title, and compact-control scale.
- Detail, Browse, and About now use the same page transition helper for open/close navigation, and content-card focus animation timing is normalized.
- `UP` from the Home grid returns to `All Types`.
- `UP` from the Watchlist grid returns to `All`, and `UP` from watchlist filters returns to `Watchlist`.
- Detail action buttons now show the intended focus treatment.
- About uses the redesigned settings/status layout, enters non-touch focus cleanly on `Back`, and keeps both the playback quality row and `Sign Out` reachable via D-pad scroll.
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
