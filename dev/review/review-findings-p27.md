## Phase 27 Summary

- Scope reviewed: all files listed in `dev/progress.md` Phase 27, plus `ContentAdapter.kt` because the checklist explicitly depends on its focus / card behavior.
- Result: **0 Critical**, **5 Warning**, **4 Info**.
- Phase 27 is **COMPLETE** — all Warnings and actionable Info items fixed.
- Fix commit(s): `faa44ed` (v2026.03.01.5)

## [Warning] Search queries are logged verbatim
File: app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:414
Issue: `performSearch()` logs `query='$query'` at `INFO`. Phase 27 explicitly requires that typed content from `DpadEditText` is not logged. Search text is user-entered content and should be treated like other typed input.
Suggestion: Remove the query value from logs entirely, or log only structural state such as query length / search trigger source.

## [Warning] DetailActivity trusts and swallows too much around its launch extras and API failure path
File: app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:93
Issue: `EXTRA_ASIN` is only null-checked, not blank-checked, and `EXTRA_TITLE` is written directly into the UI before the detail fetch validates anything. Separately, `loadDetail()` catches all exceptions, logs them, returns `null`, and then shows the generic message `Could not load details` without surfacing any real cause. This fails the Phase 27 requirements for extra validation and complete API error surfacing.
Suggestion: Reject blank / malformed ASINs up front, avoid trusting title/image extras as authoritative content, and surface at least a user-meaningful error state while preserving the detailed failure context in logs.

## [Warning] `UiMotion.revealFresh()` guarantees flicker for already-visible views
File: app/src/main/java/com/scriptgod/fireos/avod/ui/UiMotion.kt:31
Issue: `revealFresh()` forces every target view to `alpha = 0f` before calling `reveal()`. On already-visible UI this creates a hard flash instead of a guarded reveal. That directly conflicts with the Phase 27 checklist item requiring `revealFresh` to handle already-visible views without flicker.
Suggestion: Only reset views that are entering fresh, or add a guard that skips the hard reset when the target is already visible and settled.

## [Warning] H265 fallback recreates playback without tearing down the existing activity coroutine scope
File: app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:527
Issue: On H265 HTTP 400 fallback, the player is released and `loadAndPlay()` is called again, but the activity-wide `scopeJob` is not cancelled or rebuilt. Existing in-flight coroutines started from the previous attempt can continue running against the same activity instance while a new playback session is being created. Phase 27 explicitly called out this path.
Suggestion: Split playback-session work from the activity lifetime scope, cancel the per-session job before fallback recreation, and only then start the replacement fetch / setup path.

## [Warning] Card focus scale is not reset on recycle, so reused views can remain oversized
File: app/src/main/java/com/scriptgod/fireos/avod/ui/ContentAdapter.kt:161
Issue: Focus scaling is applied in `setOnFocusChangeListener`, but `onBindViewHolder()` never resets `scaleX` / `scaleY`, and there is no `onViewRecycled()` cleanup. A previously focused card can therefore be rebound in a scaled-up state after fast scrolling or aggressive recycling. This is exactly the failure mode called out by the Phase 27 checklist for `CardPresentation`.
Suggestion: Reset scale / elevation / focus affordances in `onBindViewHolder()` and `onViewRecycled()`, not only via the focus listener.

## [Info] Shimmer cleanup still only happens on detach
File: app/src/main/java/com/scriptgod/fireos/avod/ui/ShimmerAdapter.kt:25
Issue: The adapter starts shimmer in `onViewAttachedToWindow()` and stops it in `onViewDetachedFromWindow()`, but there is still no cleanup in `onViewRecycled()` or `onDetachedFromRecyclerView()`. This is probably fine in the current simple list, but it does not fully satisfy the lifecycle checklist.
Suggestion: Stop shimmer in `onViewRecycled()` as well, and optionally stop all holders when the adapter detaches from the RecyclerView.

## [Info] Audio passthrough preference key hygiene is inconsistent and the save path is not capability-guarded
File: app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:189
Issue: `AboutActivity` still uses the inline string literal `"audio_passthrough"` for both read and write, while `PlayerActivity` defines a constant for the same key. Also, `save(true)` writes the pref without re-checking `supportsAny`; the disabled button protects the normal UI path, but the persistence rule is not enforced in the save function itself.
Suggestion: Promote the passthrough pref key to a shared constant and guard `save(true)` against unsupported outputs before writing.

## [Info] AboutActivity still uses a raw `<Button>` for a state-list-driven control
File: app/src/main/res/layout/activity_about.xml:59
Issue: `btn_about_back` is still declared as `<Button>` while the newer quality / passthrough / sign-out controls use `AppCompatButton`. This leaves the About screen inconsistent with the rest of the redesigned controls and does not satisfy the Phase 27 drawable consistency goal.
Suggestion: Convert `btn_about_back` to `androidx.appcompat.widget.AppCompatButton` for consistency with the rest of the screen.

## [Info] The reviewed code still carries a lot of inline strings / colors / one-off UI values
File: app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:32
Issue: The Phase 27 maintainability goal asked for low reliance on magic values, but `AboutActivity`, `DetailActivity`, `MainActivity`, and `PlayerActivity` still contain many inline display strings, colors, and small UI constants. This does not look broken, but it does make future tuning and localization harder.
Suggestion: Continue the cleanup toward string / color / dimension resources and shared constants instead of embedding presentation values in Kotlin.

## Checklist Assessment

### 1. Security & auth

| Item | Status | Evidence |
| --- | --- | --- |
| No token or credential values logged at any level in new activities or adapters | OK | Manual review of the scoped activities/adapters found no token / credential value logging. |
| `SharedPreferences("auth")` not accessed outside `LoginActivity` / `AboutActivity` | OK | Scoped grep found `auth` access only in `AboutActivity.performLogout()` (`app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:226`). |
| `DpadEditText` typed content not logged (search queries, login fields) | Warning | `MainActivity.performSearch()` logs the full query string (`app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:414-416`). |
| `DetailActivity` intent extras validated before use (ASIN, title not trusted as safe) | Warning | `DetailActivity` only null-checks `EXTRA_ASIN` and writes `EXTRA_TITLE` immediately (`app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:93-100`). |
| `AboutActivity` Sign Out cannot be triggered accidentally (confirmation dialog present) | OK | Confirm dialog is present before logout (`app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:67-73`). |

### 2. Memory & lifecycle

| Item | Status | Evidence |
| --- | --- | --- |
| `RailsAdapter` / `ContentAdapter` — no anonymous `Handler` or `Runnable` retaining `Activity` context after detach | OK | `RailsAdapter` only stores the outer `RecyclerView` reference and clears it on detach (`app/src/main/java/com/scriptgod/fireos/avod/ui/RailsAdapter.kt:32-42`); `ContentAdapter` has no long-lived handler / runnable fields. |
| `ShimmerAdapter` — animation drawables / animators released in `onViewRecycled` or `onDetachedFromRecyclerView` | Info | Cleanup exists only in `onViewDetachedFromWindow()` (`app/src/main/java/com/scriptgod/fireos/avod/ui/ShimmerAdapter.kt:25-33`). |
| `UiMotion` / `UiTransitions` — animators cancelled when target view is detached | Info | `UiMotion` cancels the next animation before starting a new one (`app/src/main/java/com/scriptgod/fireos/avod/ui/UiMotion.kt:19`), but there is still no detach-driven cancellation hook. |
| `PlayerActivity` — coroutine `scopeJob` cancelled before re-creating player on H265 fallback path | Warning | Fallback recreates playback without cancelling / rebuilding the activity scope (`app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:535-553`). |
| `DetailActivity` — coroutine scope cancelled in `onDestroy`; image loading cancelled on destroy | OK | `DetailActivity` uses `lifecycleScope`, so the coroutine work is lifecycle-bound (`app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:109-130`). Residual risk: no explicit Coil cancellation hook was found. |
| `PlayerActivity` — no stale reference to released `ExoPlayer` instance after `releasePlayer()` | OK | Fallback and destroy both null out the player after release (`app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:548-552`, `1275-1281`). |

### 3. Network & API

| Item | Status | Evidence |
| --- | --- | --- |
| No network calls dispatched on the main thread in any new code path | OK | Reviewed `MainActivity`, `BrowseActivity`, `DetailActivity`, and `PlayerActivity`; network work is wrapped in `withContext(Dispatchers.IO)` or `scope.launch(Dispatchers.IO)`. |
| Pagination / infinite scroll guards against duplicate in-flight requests | OK | `libraryLoading` and `homePageLoading` gates are used before additional fetches (`app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:246-250`, `646-649`, `773-776`). |
| `DetailActivity` — all API errors surfaced to the user; no silent swallowing | Warning | Exceptions are collapsed to `null` and then shown as `Could not load details` (`app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:109-125`). |
| `ShimmerAdapter` shimmer hidden on both success and error (no infinite skeleton) | OK | `BrowseActivity.hideLoadingState()` hides shimmer on both success and error paths (`app/src/main/java/com/scriptgod/fireos/avod/ui/BrowseActivity.kt:118`, `170`, `187-190`); `MainActivity.showItems()` / `showError()` do the same (`app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:910-912`, `960-962`). |
| `RailsAdapter` — pagination token not reused after end-of-feed | OK | `homeNextPageParams` is cleared when no more rails arrive (`app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:807-810`). |

### 4. D-pad / TV UX

| Item | Status | Evidence |
| --- | --- | --- |
| All new interactive views declare `android:focusable="true"` and `android:focusableInTouchMode="false"` | Info | Many controls rely on default button focusability, while `DpadEditText` and some recycler views still use `focusableInTouchMode="true"` (`app/src/main/res/layout/activity_main.xml:150-155`, `588-589`; `app/src/main/res/layout/activity_browse.xml:177-179`). |
| `DpadEditText` — correct `imeOptions` and `inputType` for TV on-screen keyboard | OK | Search field uses `imeOptions="actionSearch"` and `inputType="text"` (`app/src/main/res/layout/activity_main.xml:153-154`). |
| `RailsAdapter` rail items — `nextFocusDown` from last rail row leads somewhere sensible (not into void) | OK | Vertical movement is handled explicitly between rails via `moveFocusBetweenRails(...)`; leaving the last rail simply returns `false` so default focus search continues (`app/src/main/java/com/scriptgod/fireos/avod/ui/RailsAdapter.kt:126-144`). |
| `DetailActivity` — every interactive element reachable by D-pad (metadata scroll, play, trailer, watchlist, seasons) | OK | The action buttons are all explicit `Button`s and the browse / seasons controls are conditionally shown from `bindDetail()` (`app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:217-240`). No unreachable path was identified in code review. |
| `CardPresentation` — focus scale animation leaves no views in a permanently scaled-up state after fast scrolling or rapid focus changes | Warning | `ContentAdapter` animates scale on focus but does not reset it in bind / recycle (`app/src/main/java/com/scriptgod/fireos/avod/ui/ContentAdapter.kt:161-188`). |
| `AboutActivity` — full D-pad traversal: Back → quality buttons → passthrough buttons → Sign Out, all reachable; Up from quality row goes to Back; Up from passthrough row goes to quality row | OK | The focus chain is wired in `activity_about.xml`, and `AboutActivity` requests initial focus on `Back` (`app/src/main/res/layout/activity_about.xml:59-73`, `404-527`, `588`; `app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:239-244`). |

### 5. Correctness & edge cases

| Item | Status | Evidence |
| --- | --- | --- |
| `PlaybackQuality.fromPrefValue` — safe fallback for unknown or null pref values | OK | Unknown or null values fall back to `HD` (`app/src/main/java/com/scriptgod/fireos/avod/model/PlaybackQuality.kt:28-32`). |
| `PlayerActivity` — `h265FallbackAttempted` flag reset on each `setupPlayer()` call so it does not persist across content items in the same session | OK | Fresh `loadAndPlay()` calls reset the guard before setup unless this is the deliberate fallback replay (`app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:282-293`). |
| `MainActivity` — `activeSourceFilter` / `activeTypeFilter` preserved or reset correctly on back-stack pop | OK | Filters are reset on nav changes and preserved across `onResume()` refreshes (`app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt:429-456`, `1055-1085`). |
| `UiMotion.revealFresh` — handles views already `VISIBLE` without flicker | Warning | `revealFresh()` forces `alpha = 0f` before revealing (`app/src/main/java/com/scriptgod/fireos/avod/ui/UiMotion.kt:31-36`). |
| `PREF_AUDIO_PASSTHROUGH` read inside `setupPlayer()` at player-creation time, not cached at activity start | OK | The pref is read inside `setupPlayer()` (`app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:318-329`). |
| Volume warning Toast fires at most once across all sessions (gated by `PREF_AUDIO_PASSTHROUGH_WARNED`) | OK | Warning toast is gated by the pref and then persisted (`app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:401-409`). |
| `AboutActivity` — passthrough On button correctly disabled and dimmed when `supportsAny == false`; pref not saved as `true` when capability is absent | Info | UI disable/dimming is correct (`app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:181-183`), but `save(true)` still writes without a capability guard (`198-200`). |
| `settings_quality_option_background.xml` — state order correct | OK | Disabled precedes focused+selected, selected, focused, default (`app/src/main/res/drawable/settings_quality_option_background.xml:4-47`). |

### 6. Maintainability

| Item | Status | Evidence |
| --- | --- | --- |
| Naming: classes, functions, and variables convey intent | OK | Names such as `loadHomeRails()`, `applyBrowseHeader()`, `showItemMenu()`, `PlaybackQuality.fromPrefValue()` are generally clear across the scope. |
| Separation of concerns: UI logic not mixed into adapters; API parsing not mixed into Activities | OK | The current state largely keeps parsing in `AmazonApiService` / formatter helpers and UI wiring in activities/adapters. |
| Dead code: no commented-out blocks, unused functions, unreachable branches, or stale TODOs left in production files | OK | Scoped grep found no TODO / FIXME / commented-out production blocks in the reviewed files. |
| Consistency: similar problems solved the same way throughout | Info | Preference keys and button widget types are still handled inconsistently between `AboutActivity`, `PlayerActivity`, and the XML layouts. |
| Magic values: no unexplained hardcoded strings, numbers, or colours inline in Kotlin | Info | Inline UI strings / colors remain in `AboutActivity`, `DetailActivity`, `MainActivity`, and `PlayerActivity` (for example `app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:32`, `203-205`; `app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:155-160`). |
| Error messages: user-facing error messages and logs give enough context | Warning | `DetailActivity` still collapses fetch failures to `Could not load details` (`app/src/main/java/com/scriptgod/fireos/avod/ui/DetailActivity.kt:122-124`). |
| Complexity: no function longer than ~60 lines or hard to test mentally | Info | `PlayerActivity` still contains several large, multi-responsibility methods (`loadAndPlay()`, `setupPlayer()`, audio track resolution helpers). |
| Logging: sufficient at key decision points, but not noisy / redundant | Info | Player logging is very good for debugging, but `MainActivity.performSearch()` logs user-entered queries and `PlayerActivity` now emits a high volume of track-debug lines. |
| SharedPreferences key hygiene: all pref keys defined as constants | Info | `AboutActivity` still uses inline `"audio_passthrough"` while `PlayerActivity` defines a constant (`app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt:189`, `199`; `app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:75-76`). |
| Drawable / resource consistency: `AppCompatButton` used consistently for state-list-driven buttons | Info | `activity_about.xml` still uses a raw `<Button>` for `btn_about_back` (`app/src/main/res/layout/activity_about.xml:59-73`). |

## Additional General Review Findings

## [Warning] Login flow still logs raw authentication response bodies
File: app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt:473
Issue: After credential submission, the login flow logs the response size and then logs the full response body whenever it is shorter than 5000 characters. That body can contain account-specific auth HTML, hidden fields, challenge state, or identifiers that should not be retained in release logcat.
Suggestion: Remove raw auth body logging from production paths. If this needs to survive for debugging, gate it behind a debug-only flag and aggressively redact auth state before logging.

## [Warning] Device-token persistence happens on the main thread after registration
File: app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt:633
Issue: `registerDevice()` switches to `Dispatchers.IO` only for `performDeviceRegistration()`, but the subsequent `tokenFile.writeText(tokenJson)` and legacy token write run back on the main dispatcher. That is blocking disk I/O in the login success path.
Suggestion: Keep token serialization and file persistence inside `Dispatchers.IO`, and return to the main thread only for UI updates and navigation.

## [Info] LoginActivity remains a very large multi-responsibility class
File: app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt:1
Issue: `LoginActivity` still owns token discovery, OAuth bootstrap, credential form parsing, MFA handling, redirect following, device registration, token persistence, and UI state. It works, but it is hard to reason about and expensive to debug for anyone new to the codebase.
Suggestion: Extract the auth flow into smaller components or coordinator classes so parsing, HTTP flow, and UI state are not all coupled in one activity.

## [Info] Phase 28 L3 fallback intentionally degrades to SD on any MediaDrm query failure
File: app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt:257
Issue: `widevineSecurityLevel()` catches any exception from `MediaDrm(...).getPropertyString("securityLevel")` and treats it as `"L3"`. That is a defensible safe-fail for emulator support, but it also means a transient CDM initialization failure on a real L1 device will silently downgrade the entire session to SD instead of surfacing the CDM problem.
Suggestion: Keep the fallback, but consider logging the failure more prominently and distinguishing expected unsupported-device cases from unexpected MediaDrm initialization failures.
