# UI Design Specification — Phase 22

Visual and interaction specification for the Amazon VOD Android TV UI redesign.
Both the Implementer and Reviewer agents treat this file as the source of truth.

---

## Design Principles

1. **TV-first**: designed for a 1080p display viewed from 3 m. All text ≥ 13sp.
   No touch targets; every interactive element must be D-pad reachable.
2. **Focus is king**: the focused element must always be obvious — scale + border.
3. **Black stays black**: background is near-black, not pure black, so card
   surfaces read as elevated.
4. **Preserve function**: navigation, API calls, D-pad key handling, and all
   workarounds in `dev/analysis/decisions.md` must continue to work unchanged.

---

## Color System

| Token | Hex | Usage |
|-------|-----|-------|
| `bg_page` | `#0D0D0D` | Activity background |
| `bg_surface` | `#1C1C1C` | Card background, nav bar |
| `bg_elevated` | `#252525` | Dialogs, overlays |
| `accent` | `#00A8E0` | Active nav tab underline, focused border, buttons |
| `text_primary` | `#FFFFFF` | Titles, body text |
| `text_secondary` | `#AAAAAA` | Rail headers, subtitles, metadata |
| `text_muted` | `#666666` | Hints, section labels |
| `progress_amber` | `#F5A623` | Watch-progress bar, watchlist star |
| `destructive` | `#C0392B` | Sign-out button (keep existing) |

---

## Typography

| Role | Size | Weight | Color |
|------|------|--------|-------|
| Hero title | 26sp | bold | `#FFFFFF` |
| Rail header | 18sp | bold | `#FFFFFF` |
| Section label (caps) | 11sp | normal | `#666666` |
| Card title | 13sp | normal | `#FFFFFF` |
| Card subtitle | 11sp | normal | `#AAAAAA` |
| Nav tab | 15sp | normal | active `#FFFFFF` / inactive `#888888` |
| Body / metadata | 14sp | normal | `#AAAAAA` |
| IMDb / badges | 13sp | normal | `#AAAAAA` |

---

## Dimensions

| Element | Value |
|---------|-------|
| Nav bar height | 56dp |
| Nav tab active underline | 3dp, `#00A8E0` |
| Hero banner height | 280dp |
| Content card width | 200dp |
| Content card image height | 240dp |
| Content card total height | 295dp (image + 55dp text area) |
| Card corner radius | 6dp |
| Card spacing (horizontal) | 8dp |
| Rail vertical spacing | 28dp |
| Rail header padding-top | 20dp |
| Focus scale factor | 1.08 |
| Focus border width | 2dp, `#00A8E0` |
| Focus elevation | 12dp |
| Progress bar height | 4dp |
| Page horizontal padding | 32dp |

---

## Screen Specifications

### 1 — Home Screen (`MainActivity`)

**Layout** (top to bottom):

```
┌─────────────────────────────────────────────────────────────────────┐
│  [Home] [Freevee] [Watchlist] [Library]            [🔍]  [⚙]      │  56dp nav bar
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  ░░░░░░░░░░░░    HERO BACKDROP IMAGE    ░░░░░░░░░░░░░░░░░░░░░░░░░  │  280dp
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  TITLE (26sp bold)                         [▶ Play] [+ Watchlist]  │
│  Year · Genre · Rating (14sp #AAAAAA)                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Featured                                                    See All  │
│  [card] [card] [card] [card] [card] [card]  →                        │
│                                                                       │
│  Continue Watching                                           See All  │
│  [card] [card] [card]  →                                             │
│                                                                       │
│  Top 10                                                              │
│  [card] [card] [card] [card] [card]  →                               │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

**Hero banner**:
- Source: the first item of the first rail. Use the best image already available
  on `ContentItem`; in the current codebase that is typically `imageUrl`. If a
  hero-specific image is already exposed without changing API/model contracts,
  prefer it; otherwise fall back to the poster image.
- Gradient overlay: transparent at top, `#CC000000` at bottom (60% black).
- Title and action buttons are overlaid on the bottom portion of the banner.
- `[▶ Play]` launches `DetailActivity` for that item (same as tapping the card).
- `[+ Watchlist]` toggles watchlist (use existing `toggleWatchlist()` logic).
- Hero only shown on the Home tab, hidden on Search / Watchlist / Library.

**Nav bar**:
- Active tab: white text + 3dp `#00A8E0` underline at bottom edge.
- Inactive tabs: `#888888` text, no underline.
- `[🔍]` icon button — expands an inline search bar that slides down below the
  nav bar (replaces the existing separate search row). Pressing 🔍 again or
  BACK collapses it.
- `[⚙]` opens `AboutActivity` (unchanged).
- Background: `#1C1C1C`.

**Category filter chips**:
- Keep existing All / Prime and All Types / Movies / Series filter rows.
- Style: pill-shaped chips (24dp corner radius), active = `#00A8E0` background
  with black text, inactive = `#2A2A2A` background with `#AAAAAA` text.
- Only visible when not on the Home tab (home shows rails, filters apply to flat-
  grid results).

**Rail rows**:
- Use `item_rail.xml` with an 18sp bold rail header and a "See All" `TextView`
  right-aligned when the current app already has a safe browse route for the
  rail. If a full-collection route is not supported without API/model work,
  leave "See All" hidden and document the limitation rather than inventing a
  new contract.
- Horizontal `RecyclerView` with `LinearLayoutManager(HORIZONTAL)` (already the
  case). Keep existing snap behaviour.

---

### 2 — Content Card (`item_content.xml` / `ContentAdapter.kt`)

```
┌──────────────────────┐   ← 200dp wide
│                      │
│     POSTER IMAGE     │   ← 240dp tall, #1C1C1C placeholder
│     (centerCrop)     │   ← top corners rounded 6dp
│                      │
│  [★]     [4K][HDR]   │   ← watchlist star top-left, badges top-right
│ ▓▓▓▓░░░░░░░░░░░░░░░ │   ← amber progress bar, 4dp, bottom of image
├──────────────────────┤
│ Title (13sp, 2 lines)│   ← 55dp text area, rounded bottom corners 6dp
│ Subtitle (11sp grey) │
└──────────────────────┘

FOCUSED state:
  scale(1.08), 2dp #00A8E0 border, elevation 12dp
  Animate: ObjectAnimator duration 120ms
```

**Focus animation**: in `ContentAdapter.kt`, in `onBindViewHolder`, set
`OnFocusChangeListener` on the root view:
```kotlin
itemView.setOnFocusChangeListener { v, hasFocus ->
    val scale = if (hasFocus) 1.08f else 1.0f
    v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    v.elevation = if (hasFocus) 12f.dp else 0f
}
```

**Badges** (`4K`, `HDR`, `Prime`) shown as small rounded TextViews overlaid on
the top-right of the poster image. Bind only from metadata already present on
`ContentItem`; do not add API/model parsing solely to synthesize cosmetic card
badges for this phase. In the current app, `Prime` is the most reliable badge.

**Rounded corners**: use a `MaterialCardView` wrapping or a custom
`OutlineProvider` — whichever is simpler given the existing view type.
If using `MaterialCardView`, add `material` dependency to `build.gradle.kts`:
```
implementation("com.google.android.material:material:1.12.0")
```

---

### 3 — Browse Screen (`BrowseActivity` / `activity_browse.xml`)

- Same card grid as home (reuses `ContentAdapter`).
- Header: title of the season/collection in 20sp bold, back-arrow at left.
- Background `#0D0D0D`, no hero banner.
- Loading state: 6 shimmer placeholder cards (see Shimmer section below).

---

### 4 — Detail Screen (`DetailActivity` / `activity_detail.xml`)

Current state is already decent. Improvements:

- Hero image height → 240dp (up from 200dp).
- Add a subtle scrim gradient (bottom 60% of hero goes to `#0D0D0D`).
- `[▶ Play]`, `[▶ Trailer]`, `[Browse Episodes]`, `[All Seasons]` buttons:
  style as pill buttons (24dp corner radius) using accent color (`#00A8E0`)
  for Play/primary actions, `#2A2A2A` for secondary actions.
- IMDb rating: add a small star glyph `★` in `#F5A623` before the number.
- Badges row: show `4K`, `HDR`, `5.1`, `Prime` as small rounded chips
  (same style as card badges).
- Watchlist button: use `☆` / `★` text (already done) — keep as-is.

---

### 5 — Player Screen (`PlayerActivity` / `activity_player.xml`)

**Do not restructure the player layout.** Minor polish only:

- Ensure the top-right overlay (`tv_video_format`, Audio, Subtitle buttons) has
  `#80000000` background pill/card behind it so it reads on bright video.
- No other changes — player is complex and tested; leave it alone.

---

## Shimmer Loading Placeholders

Replace the spinner (`ProgressBar`) in `MainActivity` and `BrowseActivity` with
shimmer card placeholders while content loads.

**Implementation** — use the Facebook Shimmer library:
```toml
# libs.versions.toml
shimmer = "0.5.0"

[libraries]
shimmer = { group = "com.facebook.shimmer", name = "shimmer", version.ref = "shimmer" }
```
```kotlin
// build.gradle.kts
implementation(libs.shimmer)
```

Create `res/layout/item_shimmer_card.xml`:
```xml
<com.facebook.shimmer.ShimmerFrameLayout ...>
    <LinearLayout ... 200dp × 295dp>
        <View ... 200dp × 240dp background="#2A2A2A"/>  <!-- image placeholder -->
        <View ... 120dp × 14dp margin="8dp" background="#2A2A2A"/>  <!-- title -->
        <View ... 80dp  × 11dp margin="4dp 8dp" background="#2A2A2A"/>  <!-- subtitle -->
    </LinearLayout>
</com.facebook.shimmer.ShimmerFrameLayout>
```

Show 6 shimmer cards in a `RecyclerView` `ShimmerAdapter` during loading.
Hide shimmer and show real content when the API call completes.

---

## D-pad Navigation Rules

These rules must all continue to work after redesign:

1. From nav bar → pressing DOWN focuses the first card in the first rail (home)
   or the first filter chip (search/watchlist/library).
2. From a card → pressing UP returns focus to nav bar.
3. LEFT/RIGHT within a rail: card-to-card within the row.
4. DOWN from a card: moves to the same horizontal position in the next rail
   (standard `RecyclerView` behaviour).
5. Search bar: after entering text, pressing the remote SELECT key submits.
6. All buttons (Play, Watchlist, filter chips) are `focusable="true"`.
7. The hero banner Play/Watchlist buttons are D-pad accessible from the nav bar.

---

## Files to Create or Modify

### New files
| File | Purpose |
|------|---------|
| `res/drawable/card_background.xml` | Shape with 6dp corner radius, `#1C1C1C` fill |
| `res/drawable/card_focused.xml` | Shape with 6dp corner radius, 2dp `#00A8E0` stroke |
| `res/drawable/card_selector.xml` | StateListDrawable: focused → `card_focused`, else → `card_background` |
| `res/drawable/chip_active.xml` | Pill shape, `#00A8E0` fill, 24dp corner radius |
| `res/drawable/chip_inactive.xml` | Pill shape, `#2A2A2A` fill, 24dp corner radius |
| `res/drawable/nav_active_indicator.xml` | Bottom-aligned 3dp `#00A8E0` rectangle |
| `res/layout/item_shimmer_card.xml` | Shimmer placeholder card |
| `res/values/dimens.xml` | All dimension constants |

### Modified files
| File | What changes |
|------|-------------|
| `res/layout/activity_main.xml` | Hero banner section + styled nav tabs + search overlay |
| `res/layout/activity_browse.xml` | Shimmer loading state + browse-screen polish |
| `res/layout/item_content.xml` | Larger card, rounded corners, badge overlays |
| `res/layout/item_rail.xml` | Rail header typography + See All link |
| `res/layout/activity_detail.xml` | Taller hero, pill buttons, badges |
| `res/layout/activity_player.xml` | Top-right overlay readability polish |
| `res/values/themes.xml` | Background color → `#0D0D0D` |
| `ui/BrowseActivity.kt` | Browse shimmer visibility and loading-state wiring |
| `ui/ContentAdapter.kt` | Focus scale animation, badge binding |
| `ui/DetailActivity.kt` | IMDb glyph + detail-screen binding updates |
| `ui/MainActivity.kt` | Hero banner data binding + search overlay toggle |
| `ui/RailsAdapter.kt` | See All button wiring |
| `ui/PlayerActivity.kt` | Only if needed for overlay/readability polish |
| `gradle/libs.versions.toml` | Add shimmer + material versions |
| `app/build.gradle.kts` | Add shimmer + material dependencies |
