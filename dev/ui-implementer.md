# Agent Instructions — UI Implementer

You are the **UI Implementer** for the Amazon VOD Android app UI redesign.
Your job is to rewrite layouts, adapters, and style resources to match the
design specification, build the APK, install it on the Android TV emulator,
and take screenshots for the reviewer.

---

## Before You Start — Read These

In order:
1. `dev/AGENTS.md` — multi-agent overview, constraints, emulator commands
2. `dev/ui-design-spec.md` — the complete visual specification (source of truth)
3. `dev/progress.md` — understand current architecture (Phase 22 section)
4. `dev/analysis/decisions.md` — API workarounds you must not break

Then read the source files you will modify before touching them:
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/item_content.xml`
- `app/src/main/res/layout/item_rail.xml`
- `app/src/main/res/layout/activity_detail.xml`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/ContentAdapter.kt`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt`
- `app/src/main/java/com/scriptgod/fireos/avod/ui/RailsAdapter.kt`
- `app/src/main/res/values/themes.xml`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`

---

## Target Device

**Emulator only. Do NOT use Fire TV.**

```bash
# Verify emulator is available
adb devices   # must show emulator-5554

# Push dev token so the app can skip login
adb -s emulator-5554 push /home/vscode/amazon-vod-android/.device-token \
    /data/local/tmp/.device-token
adb -s emulator-5554 shell chmod 644 /data/local/tmp/.device-token
```

---

## Implementation Order

Work through the sections in this order. Build and test after each section,
so regressions are caught early.

---

### Step 1 — Dependencies

Add to `gradle/libs.versions.toml`:
```toml
[versions]
material = "1.12.0"
shimmer  = "0.5.0"

[libraries]
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
shimmer  = { group = "com.facebook.shimmer",        name = "shimmer",  version.ref = "shimmer"  }
```

Add to `app/build.gradle.kts` `dependencies {}`:
```kotlin
implementation(libs.material)
implementation(libs.shimmer)
```

Update `res/values/themes.xml`: change the parent theme to
`Theme.MaterialComponents.NoActionBar` (required for MaterialCardView).
Keep all existing `<item>` entries. Add:
```xml
<item name="android:windowBackground">#0D0D0D</item>
<item name="android:colorBackground">#0D0D0D</item>
```

Run `./gradlew assembleRelease` to confirm the build still succeeds before
continuing.

---

### Step 2 — Drawable Resources

Create the following files. All are pure XML — no Kotlin needed for this step.

**`res/drawable/card_background.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="6dp"/>
    <solid android:color="#1C1C1C"/>
</shape>
```

**`res/drawable/card_focused.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="6dp"/>
    <solid android:color="#1C1C1C"/>
    <stroke android:width="2dp" android:color="#00A8E0"/>
</shape>
```

**`res/drawable/card_selector.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_focused="true"  android:drawable="@drawable/card_focused"/>
    <item android:drawable="@drawable/card_background"/>
</selector>
```

**`res/drawable/chip_active.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="24dp"/>
    <solid android:color="#00A8E0"/>
</shape>
```

**`res/drawable/chip_inactive.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="24dp"/>
    <solid android:color="#2A2A2A"/>
</shape>
```

**`res/drawable/nav_active_indicator.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:gravity="bottom">
        <shape>
            <size android:height="3dp"/>
            <solid android:color="#00A8E0"/>
        </shape>
    </item>
</layer-list>
```

**`res/drawable/badge_bg.xml`** (for 4K/HDR/Prime chip overlays on cards)
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <corners android:radius="3dp"/>
    <solid android:color="#CC000000"/>
</shape>
```

**`res/values/dimens.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <dimen name="page_padding">32dp</dimen>
    <dimen name="card_width">200dp</dimen>
    <dimen name="card_image_height">240dp</dimen>
    <dimen name="card_corner_radius">6dp</dimen>
    <dimen name="card_spacing">8dp</dimen>
    <dimen name="nav_bar_height">56dp</dimen>
    <dimen name="hero_height">280dp</dimen>
    <dimen name="rail_header_size">18sp</dimen>
    <dimen name="rail_vertical_spacing">28dp</dimen>
</resources>
```

---

### Step 3 — Content Card (`item_content.xml` + `ContentAdapter.kt`)

**`item_content.xml`** — rewrite to:
- Root: `FrameLayout` 200dp × 295dp, `background="@drawable/card_selector"`,
  `focusable="true"`, `clickable="true"`.
- Image `FrameLayout` child: 200dp × 240dp, holds poster + badges + progress bar.
  - `ImageView id=iv_poster` fills the frame, `scaleType="centerCrop"`.
  - Watchlist star `ImageView id=iv_watchlist` at top-left (4dp margin), 20dp×20dp.
  - Badge `LinearLayout id=ll_badges` at top-right (4dp margin), horizontal, holds
    up to 3 `TextView` badge chips (11sp, `@drawable/badge_bg`, padding 2dp 4dp).
  - Progress `ProgressBar id=pb_watch_progress` at bottom of frame, 4dp height,
    style horizontal, `progressTint="#F5A623"`, `visibility="gone"`.
- Text area `LinearLayout` below the image frame: 200dp × 55dp, `#1C1C1C` bg,
  padding 8dp horizontal, 6dp vertical.
  - `TextView id=tv_title` 13sp white, 2 lines, ellipsize end.
  - `TextView id=tv_subtitle` 11sp `#AAAAAA`, 1 line, ellipsize end.

**`ContentAdapter.kt`** — after reading the existing file, add:

1. Focus scale animation in `onBindViewHolder`:
```kotlin
itemView.setOnFocusChangeListener { v, hasFocus ->
    val scale = if (hasFocus) 1.08f else 1.0f
    v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    v.elevation = if (hasFocus) resources.displayMetrics.density * 12 else 0f
}
```

2. Badge binding: populate `ll_badges` from `item.badges` (check `ContentItem.kt`
   for the field name). Clear existing badge views before adding new ones.
   Show at most 3 badges (e.g. `4K`, `HDR`, `Prime`).

3. Keep all existing click, long-click, watchlist-star, and progress-bar logic
   exactly as-is — only add focus animation and badge binding on top.

---

### Step 4 — Rail Row (`item_rail.xml` + `RailsAdapter.kt`)

**`item_rail.xml`**:
- Root `LinearLayout`, vertical, `paddingBottom="@dimen/rail_vertical_spacing"`.
- Header `LinearLayout` (horizontal, 48dp height):
  - `TextView id=tv_rail_header` — 0dp weight 1, 18sp bold white,
    `paddingStart="@dimen/page_padding"`.
  - `TextView id=tv_see_all` — "See All", 13sp `#00A8E0`, `paddingEnd=32dp`,
    `focusable="true"`, `visibility="gone"`.
- `RecyclerView id=rv_rail_items` — unchanged.

**`RailsAdapter.kt`**: show `tv_see_all` and set its `OnClickListener` when
`rail.collectionId` is non-empty. The click should start `BrowseActivity` with
that `collectionId`. Read the existing `BrowseActivity` extras to pass the right
Intent data.

---

### Step 5 — Shimmer Loading

Create **`res/layout/item_shimmer_card.xml`**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<com.facebook.shimmer.ShimmerFrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="200dp"
    android:layout_height="295dp"
    android:layout_marginEnd="8dp">

    <LinearLayout
        android:layout_width="200dp"
        android:layout_height="295dp"
        android:orientation="vertical"
        android:background="@drawable/card_background">

        <View
            android:layout_width="200dp"
            android:layout_height="240dp"
            android:background="#2A2A2A"/>

        <View
            android:layout_width="120dp"
            android:layout_height="13dp"
            android:layout_margin="8dp"
            android:background="#2A2A2A"/>

        <View
            android:layout_width="80dp"
            android:layout_height="11dp"
            android:layout_marginStart="8dp"
            android:background="#2A2A2A"/>

    </LinearLayout>

</com.facebook.shimmer.ShimmerFrameLayout>
```

Create a `ShimmerAdapter` inner class (or top-level class in a new file
`ui/ShimmerAdapter.kt`) that shows 6 shimmer cards in a horizontal
`RecyclerView`.

In `MainActivity.kt` and `BrowseActivity.kt`: replace the spinner with a shimmer
`RecyclerView` row during loading. Start shimmer when the API call begins, stop
and hide it when data arrives.

---

### Step 6 — Main Screen (`activity_main.xml` + `MainActivity.kt`)

This is the most complex step. Read the existing `activity_main.xml` and
`MainActivity.kt` fully before making any changes.

**`activity_main.xml`** new structure (top to bottom):

```
FrameLayout (root, match_parent × match_parent, bg #0D0D0D)
  │
  ├── LinearLayout (vertical, match_parent, orientation=vertical)
  │     │
  │     ├── Nav bar LinearLayout (match_parent × 56dp, bg #1C1C1C)
  │     │     ├── Tab: Home       (id=btn_home,      15sp, focusable)
  │     │     ├── Tab: Freevee    (id=btn_freevee,   15sp, focusable)
  │     │     ├── Tab: Watchlist  (id=btn_watchlist, 15sp, focusable)
  │     │     ├── Tab: Library    (id=btn_library,   15sp, focusable)
  │     │     ├── spacer (weight=1)
  │     │     ├── Search icon btn (id=btn_search_icon, 48dp×48dp, "🔍")
  │     │     └── Settings btn    (id=btn_about,      48dp×48dp, "⚙")
  │     │
  │     ├── Search bar row LinearLayout (id=search_row, GONE by default)
  │     │     ├── DpadEditText (id=et_search, weight=1)
  │     │     └── Button "Search" (id=btn_search)
  │     │
  │     ├── Hero banner FrameLayout (id=hero_section, 280dp, GONE by default)
  │     │     ├── ImageView (id=iv_hero_backdrop, centerCrop)
  │     │     ├── View (gradient overlay, @drawable/hero_gradient)
  │     │     └── LinearLayout (vertical, gravity=bottom, padding=32dp)
  │     │           ├── TextView (id=tv_hero_title,    26sp bold white)
  │     │           ├── TextView (id=tv_hero_meta,     14sp #AAAAAA)
  │     │           └── LinearLayout (horizontal)
  │     │                 ├── Button (id=btn_hero_play,      "▶ Play")
  │     │                 └── Button (id=btn_hero_watchlist, "+ Watchlist")
  │     │
  │     ├── Library / category filter rows (keep existing ids, update styles)
  │     │
  │     ├── ProgressBar (id=progress_bar, keep but hide under shimmer)
  │     ├── TextView   (id=tv_error, unchanged)
  │     └── RecyclerView (id=recycler_view, unchanged)
  │
  └── (no floating overlays needed on main screen)
```

**Nav tab active state in `MainActivity.kt`**:
Define a helper:
```kotlin
private fun setActiveTab(activeBtn: Button) {
    val tabs = listOf(btnHome, btnFreevee, btnWatchlist, btnLibrary)
    tabs.forEach { btn ->
        btn.setTextColor(if (btn == activeBtn) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
        btn.background = if (btn == activeBtn)
            ContextCompat.getDrawable(this, R.drawable.nav_active_indicator)
        else null
    }
}
```
Call `setActiveTab(btn)` wherever the current tab changes.

**Search toggle**:
```kotlin
btnSearchIcon.setOnClickListener {
    if (searchRow.visibility == View.VISIBLE) {
        searchRow.visibility = View.GONE
        hideKeyboard()
    } else {
        searchRow.visibility = View.VISIBLE
        etSearch.requestFocus()
        showKeyboard()
    }
}
```

**Hero banner**:
Load hero data in `loadHome()` after rails are fetched. Use the first item from
the first non-empty rail:
```kotlin
val heroItem = rails.firstOrNull { it.items.isNotEmpty() }?.items?.firstOrNull()
if (heroItem != null) {
    heroSection.visibility = View.VISIBLE
    Coil.imageLoader(this).enqueue(
        ImageRequest.Builder(this)
            .data(heroItem.heroImageUrl ?: heroItem.imageUrl)
            .target(ivHeroBackdrop)
            .build()
    )
    tvHeroTitle.text = heroItem.title
    tvHeroMeta.text  = listOfNotNull(heroItem.year?.toString(), heroItem.contentType)
                           .joinToString(" · ")
    btnHeroPlay.setOnClickListener { onItemSelected(heroItem) }
    // Watchlist toggle uses existing toggleWatchlist() or similar
}
```

**Hide hero** when switching away from Home tab.

---

### Step 7 — Detail Screen Polish (`activity_detail.xml`)

Only small changes:
1. Hero height → 240dp.
2. Action buttons: change `android:backgroundTint` to use `@color/accent` (`#00A8E0`)
   for Play/primary, and `#2A2A2A` for secondary. Set `android:cornerRadius="24dp"`
   on all buttons (or wrap in `style`).
3. Add IMDb star glyph: change `tv_imdb` text in `DetailActivity.kt` to prefix
   with `"★ "`.

---

### Step 8 — Build, Install, Screenshot

```bash
cd /home/vscode/amazon-vod-android
./gradlew assembleRelease 2>&1 | tail -20   # must end in BUILD SUCCESSFUL

adb -s emulator-5554 install -r \
    app/build/outputs/apk/release/app-release.apk

adb -s emulator-5554 shell am start \
    -n com.scriptgod.fireos.avod/.ui.LoginActivity

# Wait ~5 seconds for the app to start, then screenshot each screen:
mkdir -p dev/review-screenshots

for screen in home_rails hero_banner search watchlist library detail player_overlay; do
    # Navigate to the screen manually via adb input or describe the steps below
    echo "Navigate to $screen, then press Enter"
    read
    adb -s emulator-5554 shell screencap -p /sdcard/screen_${screen}.png
    adb -s emulator-5554 pull /sdcard/screen_${screen}.png \
        dev/review-screenshots/${screen}.png
done
```

Navigate to each screen in this order:
1. **home_rails** — app just launched (shows rails)
2. **hero_banner** — scroll up so hero is visible at top
3. **search** — press Search icon in nav bar
4. **watchlist** — press Watchlist tab
5. **library** — press Library tab
6. **detail** — press any movie card from home rails
7. **player_overlay** — press Play on a detail page (player will likely show DRM
   error; screenshot the error state so the reviewer can see the overlay layout)

---

### Step 9 — Write Review Request

Create/overwrite `dev/ui-review-request.md`:

```markdown
# UI Review Request

## Build version
<paste git log --oneline -1>

## What was implemented
- [ ] Dependencies (material, shimmer)
- [ ] Drawable resources (card_background, card_focused, card_selector, chips, nav indicator)
- [ ] Content card (item_content.xml): size, corners, badges, focus animation
- [ ] Rail row (item_rail.xml): header typography, See All link
- [ ] Shimmer loading placeholder
- [ ] Main screen: styled nav bar, search overlay, hero banner
- [ ] Detail screen: taller hero, pill buttons, IMDb star
- [ ] Player overlay: semi-transparent background on video format label

## Known issues / deviations from spec
<list anything that didn't work as expected>

## Screenshots
All screenshots are in dev/review-screenshots/:
- home_rails.png
- hero_banner.png
- search.png
- watchlist.png
- library.png
- detail.png
- player_overlay.png
```

---

## Rules

- **Read before editing.** Use the Read tool on every file before changing it.
- **Do not modify** any file listed in `dev/AGENTS.md` under "Constraints".
- **Do not change** any API call, OkHttp header, or DRM logic.
- **Run `./gradlew assembleRelease`** after every step that touches Kotlin or
  build files. Fix compile errors before moving to the next step.
- **If a step fails**: diagnose from the Gradle error output, fix the root cause,
  and retry. Do not skip a step.
- After all steps complete, update `dev/progress.md` with a new sub-section
  under "Phase 22" documenting what was implemented.
