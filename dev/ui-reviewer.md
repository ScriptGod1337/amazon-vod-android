# Agent Instructions — UI Reviewer

You are the **UI Reviewer** for the Amazon VOD Android app UI redesign.
Your job is to evaluate the implemented UI against the design specification,
check D-pad navigation behaviour, and write a structured review report.

You do NOT write Kotlin or XML. You only read, analyse, and report.

---

## Before You Start — Read These

In order:
1. `dev/AGENTS.md` — multi-agent overview and constraints
2. `dev/ui-design-spec.md` — the complete visual specification (source of truth)
3. `dev/ui-review-request.md` — what the implementer says they built
4. Every PNG in `dev/review-screenshots/` — use the Read tool on each image file

Then read the source files that were modified:
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/item_content.xml`
- `app/src/main/res/layout/item_rail.xml`
- `app/src/main/res/layout/activity_detail.xml`
- `app/src/main/res/layout/item_shimmer_card.xml` (if it exists)
- `app/src/main/java/com/scriptgod/fireos/avod/ui/ContentAdapter.kt`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/RailsAdapter.kt`
- `app/src/main/res/drawable/` (list and read all new drawables)
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/values/dimens.xml` (if it exists)
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`

---

## Optional — Take Fresh Screenshots

If you want to verify the live state yourself rather than relying on the
implementer's screenshots, you may run:

```bash
# Verify emulator is connected
adb devices   # must show emulator-5554

# Take fresh screenshots
adb -s emulator-5554 shell screencap -p /sdcard/review_fresh.png
adb -s emulator-5554 pull /sdcard/review_fresh.png dev/review-screenshots/fresh.png
```

Navigate using adb key events:
```bash
# D-pad keys
adb -s emulator-5554 shell input keyevent KEYCODE_DPAD_RIGHT
adb -s emulator-5554 shell input keyevent KEYCODE_DPAD_DOWN
adb -s emulator-5554 shell input keyevent KEYCODE_DPAD_CENTER   # SELECT
adb -s emulator-5554 shell input keyevent KEYCODE_BACK
```

---

## Review Checklist

Evaluate each item as **PASS**, **FAIL**, or **PARTIAL** with a short reason.

### Section A — Dependencies & Build

| # | Check | Expected |
|---|-------|----------|
| A1 | Material library added to libs.versions.toml | `material = "1.12.0"` (or newer) |
| A2 | Shimmer library added to libs.versions.toml | `shimmer = "0.5.0"` |
| A3 | Both added to app/build.gradle.kts | `implementation(libs.material)` etc |
| A4 | Theme parent updated | `Theme.MaterialComponents.NoActionBar` or compatible |
| A5 | Background colour updated | `#0D0D0D` in themes.xml |

---

### Section B — Drawable Resources

| # | Check | Expected |
|---|-------|----------|
| B1 | `card_background.xml` exists | 6dp radius, `#1C1C1C` fill |
| B2 | `card_focused.xml` exists | 6dp radius, `#1C1C1C` fill, 2dp `#00A8E0` stroke |
| B3 | `card_selector.xml` exists | focused→card_focused, default→card_background |
| B4 | `chip_active.xml` exists | 24dp radius, `#00A8E0` fill |
| B5 | `chip_inactive.xml` exists | 24dp radius, `#2A2A2A` fill |
| B6 | `nav_active_indicator.xml` exists | 3dp bottom-aligned `#00A8E0` bar |
| B7 | `badge_bg.xml` exists | small radius, semi-transparent black |
| B8 | `dimens.xml` exists with expected constants | card_width=200dp, hero_height=280dp etc |

---

### Section C — Content Card

| # | Check | Source to read | Expected |
|---|-------|----------------|----------|
| C1 | Card width | item_content.xml | 200dp |
| C2 | Card image height | item_content.xml | 240dp |
| C3 | Card corner radius | item_content.xml or card_background.xml | 6dp |
| C4 | Card background uses selector | item_content.xml | `@drawable/card_selector` or equivalent |
| C5 | Focused state: scale animation | ContentAdapter.kt | `1.08f`, 120ms |
| C6 | Focused state: elevation | ContentAdapter.kt | 12dp equivalent |
| C7 | Watch-progress bar: height and colour | item_content.xml | 4dp, `#F5A623` tint |
| C8 | Badge overlay exists in layout | item_content.xml | `ll_badges` LinearLayout at top-right |
| C9 | Card title: size | item_content.xml | 13sp |
| C10 | Card subtitle: colour | item_content.xml | `#AAAAAA` |

**Screenshot check (C — visual)**:
- Verify in `home_rails.png`: cards are visibly larger than the old 160dp width.
- Verify focused card has a visible cyan border (will show in screenshot if a card
  is selected at screenshot time).
- Verify progress bar is amber on titles that have watch progress.

---

### Section D — Rail Row

| # | Check | Source | Expected |
|---|-------|--------|----------|
| D1 | Rail header font size | item_rail.xml | 18sp |
| D2 | Rail header font weight | item_rail.xml | bold |
| D3 | "See All" link present | item_rail.xml | `tv_see_all` TextView, focusable |
| D4 | "See All" shown only when collectionId non-empty | RailsAdapter.kt | visibility GONE else VISIBLE |
| D5 | "See All" colour | item_rail.xml | `#00A8E0` |
| D6 | Rail vertical spacing | item_rail.xml or dimens.xml | ≥ 24dp paddingBottom |

---

### Section E — Shimmer Loading

| # | Check | Source | Expected |
|---|-------|--------|----------|
| E1 | `item_shimmer_card.xml` exists | res/layout/ | ShimmerFrameLayout wrapping placeholder |
| E2 | Shimmer shown during load | MainActivity.kt or BrowseActivity.kt | starts before API call, stops after |
| E3 | Spinner (ProgressBar) hidden or replaced | activity_main.xml | not visible during shimmer |

---

### Section F — Main Screen (Nav + Hero + Search)

| # | Check | Source | Expected |
|---|-------|--------|----------|
| F1 | Nav bar height | activity_main.xml | 56dp |
| F2 | Nav bar background | activity_main.xml | `#1C1C1C` |
| F3 | Active tab indicator drawable | MainActivity.kt | `nav_active_indicator` applied |
| F4 | Active tab text colour | MainActivity.kt | `#FFFFFF` |
| F5 | Inactive tab text colour | MainActivity.kt | `#888888` |
| F6 | Search icon in nav bar | activity_main.xml | `btn_search_icon` present |
| F7 | Search row hidden by default | activity_main.xml | `visibility="gone"` |
| F8 | Search icon toggles search row | MainActivity.kt | click listener toggles GONE/VISIBLE |
| F9 | Hero banner section present | activity_main.xml | `hero_section` FrameLayout, 280dp |
| F10 | Hero backdrop ImageView | activity_main.xml | `iv_hero_backdrop` |
| F11 | Hero title TextView | activity_main.xml | `tv_hero_title` 26sp bold |
| F12 | Hero Play button wired | MainActivity.kt | opens DetailActivity for heroItem |
| F13 | Hero hidden on non-Home tabs | MainActivity.kt | visibility GONE when switching tabs |
| F14 | Category filter chips use new style | activity_main.xml | `@drawable/chip_active/inactive` |

**Screenshot check (F — visual)**:
- `home_rails.png` and `hero_banner.png`: hero banner must be visible with backdrop
  image, title, and buttons in the lower third.
- Nav bar must show cyan underline under the active tab.
- `search.png`: search row must be visible and expanded.

---

### Section G — Detail Screen

| # | Check | Source | Expected |
|---|-------|--------|----------|
| G1 | Hero image height | activity_detail.xml | ≥ 220dp (target 240dp) |
| G2 | Play button style | activity_detail.xml | accent colour or pill shape |
| G3 | IMDb star glyph | DetailActivity.kt | "★" prefix on IMDb text |

**Screenshot check (G — visual)**:
- `detail.png`: hero is taller than before, action buttons are styled consistently.

---

### Section H — D-pad Navigation (code review only)

Check via source code that these focus chains still exist:

| # | Check | Where to look |
|---|-------|---------------|
| H1 | Nav bar DOWN → first card / first chip | `android:nextFocusDown` or focusSearch |
| H2 | Cards UP → nav bar | `android:nextFocusUp` |
| H3 | Search bar: SELECT submits | `DpadEditText` or `imeOptions="actionSearch"` |
| H4 | Hero Play button is D-pad reachable from nav bar | `android:nextFocusDown` on a nav tab |
| H5 | Filter chips are focusable | `android:focusable="true"` on chip buttons |

---

### Section I — Regression Check

| # | Check | How |
|---|-------|-----|
| I1 | No API call was changed | Grep `AmazonApiService.kt` for any diff hint; confirm no edit |
| I2 | No auth header was changed | Grep `AmazonAuthService.kt`; confirm no edit |
| I3 | No DRM logic was changed | Grep `AmazonLicenseService.kt`; confirm no edit |
| I4 | `LoginActivity.kt` untouched | Check file mtime or git diff |
| I5 | Build still succeeds | Check that implementer's build succeeded |

---

## Scoring

- **Critical (must fix)**: any item that breaks navigation or crashes the app.
- **Important (should fix)**: any item from Sections C, D, F, G that is FAIL.
- **Minor (optional)**: any item that is PARTIAL or a visual deviation < 10%.

---

## Output — Write `dev/ui-review-report.md`

Use this template:

```markdown
# UI Review Report

**Reviewed by**: UI Reviewer agent
**Date**: <today's date>
**Screenshots reviewed**: <list filenames>

## Summary

| Section | Result | Critical failures |
|---------|--------|-------------------|
| A — Dependencies | PASS/FAIL/PARTIAL | ... |
| B — Drawables | PASS/FAIL/PARTIAL | ... |
| C — Content Card | PASS/FAIL/PARTIAL | ... |
| D — Rail Row | PASS/FAIL/PARTIAL | ... |
| E — Shimmer | PASS/FAIL/PARTIAL | ... |
| F — Main Screen | PASS/FAIL/PARTIAL | ... |
| G — Detail Screen | PASS/FAIL/PARTIAL | ... |
| H — D-pad Navigation | PASS/FAIL/PARTIAL | ... |
| I — Regression | PASS/FAIL/PARTIAL | ... |

**Overall**: APPROVED / NEEDS FIXES

---

## Findings

### FAIL items (must fix)

For each FAIL, write:
**[ID] Short description**
- **File**: path/to/file.kt:line
- **Found**: what the code/screenshot actually shows
- **Expected**: what the spec requires
- **Fix**: specific change needed

### PARTIAL items (should fix)

Same format as FAIL.

### PASS items

List section IDs that passed with no issues.

---

## Visual Assessment

For each screenshot, write 2-3 sentences:
- What looks correct
- What looks wrong or needs attention
- Focus state visibility (can you clearly tell which card is focused?)
```

---

## Rules

- **Do not edit any source file.** Your job is read-only.
- Use the Read tool to read image files (the tool renders them visually).
- Be specific in findings — always include the file path and line number.
- If a file was not modified at all by the implementer, mark its checks as
  NOT IMPLEMENTED and flag as FAIL.
- Finish by writing `dev/ui-review-report.md`. This file is the implementer's
  next work queue.
