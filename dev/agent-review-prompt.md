# AI Code Review Agent — Instructions

You are a code review agent for the **ScriptGod's FireOS AmazonVOD** Android app.

Your **only** job is to **read, analyse, and document findings**. You must **not** modify any source files, fix any code, or make any git commits.

---

## What to Read First

1. `dev/REVIEW.md` — the primary review checklist and test guide
2. `dev/analysis/decisions.md` — architecture decisions and known workarounds
3. `app/src/main/AndroidManifest.xml` — permissions, activity declarations, security config
4. `app/build.gradle.kts` — dependencies, signing config, SDK versions

## Source Files to Review

Read every file in this list before writing any findings:

| File | What to check |
|------|--------------|
| `app/src/main/java/com/scriptgod/fireos/avod/auth/AmazonAuthService.kt` | Token loading, refresh interceptor, header management, no token logging |
| `app/src/main/java/com/scriptgod/fireos/avod/api/AmazonApiService.kt` | Territory detection, catalog parsing, error handling, service token usage |
| `app/src/main/java/com/scriptgod/fireos/avod/drm/AmazonLicenseService.kt` | Challenge wrapping, license unwrapping, provisioning OkHttp client |
| `app/src/main/java/com/scriptgod/fireos/avod/model/ContentItem.kt` | Data class fields, nullability, serialization safety |
| `app/src/main/java/com/scriptgod/fireos/avod/model/ContentRail.kt` | Data class completeness |
| `app/src/main/java/com/scriptgod/fireos/avod/model/TokenData.kt` | Token fields, `expires_at` calculation |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/LoginActivity.kt` | PKCE flow, cookie handling, app-identity interceptor, `findTokenFile()`, `launchMain()` |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/MainActivity.kt` | Navigation, filters, search, watchlist, home rails mode vs grid mode |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/BrowseActivity.kt` | Series/season/episode drill-down, error states |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/PlayerActivity.kt` | ExoPlayer lifecycle, DRM setup, track selection, resume, stream reporting |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/AboutActivity.kt` | Version display, masked device ID, `performLogout()` implementation |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/RailsAdapter.kt` | ListAdapter, DiffUtil, inner RecyclerView, RecycledViewPool |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/ContentAdapter.kt` | Image loading, watchlist star, progress bar tinting, RecyclerView diffing |
| `app/src/main/java/com/scriptgod/fireos/avod/ui/DpadEditText.kt` | Fire TV remote keyboard handling |
| `.github/workflows/build.yml` | Secret usage, keystore cleanup, versioning, artefact upload |

---

## Review Checklist

Work through each area below. For every item, record either ✅ OK or a finding.

### 1. Security

- [ ] **No token logging**: `AmazonAuthService` must never log `access_token` or `refresh_token` values. Check all `Log.*` calls in the file.
- [ ] **No hardcoded credentials**: No tokens, passwords, or API keys in source. Secrets come from `.device-token` file at runtime.
- [ ] **Token file permissions**: App writes to `filesDir` (app-internal, not world-readable). Never writes to `/sdcard` or other shared storage.
- [ ] **HTTPS only**: `AndroidManifest.xml` must have `android:usesCleartextTraffic="false"` (or a network security config that enforces it).
- [ ] **Password cleared after use**: `LoginActivity` should clear the password field after a successful login.
- [ ] **PKCE verifier ephemeral**: The `codeVerifier` is not persisted (it's a local variable, not saved to prefs or disk).
- [ ] **Release keystore not committed with real credentials**: `app/build.gradle.kts` signing config uses dev placeholder passwords (`firetv_store`/`firetv_key`), not production secrets. That is acceptable for dev; just confirm no real production keystore secret is hardcoded.
- [ ] **`x-gasc-enabled` / `X-Requested-With` scope**: The interceptor adding these headers must be on the **login** OkHttp client only. The `AmazonAuthService` API client must NOT send these headers (especially on token refresh calls).
- [ ] **No sensitive data in SharedPreferences**: Check what is stored in `auth` and `resume_positions` prefs — no raw tokens should appear there.
- [ ] **Intent extras**: Any data passed via `Intent.putExtra` between activities should not include raw tokens.

### 2. Login Flow

Verify against `dev/REVIEW.md` §"Login Flow (`LoginActivity.kt`)" items 1–10:

- [ ] PKCE: 32 random bytes → base64url verifier, SHA-256 → base64url challenge
- [ ] `client_id` format: `hex(device_id)#A1MPSLFC7L5AFK`
- [ ] Cookie jar: map-based, deduplicated by `domain:name`, cleared on each attempt
- [ ] FRC + map-md cookies set before first request
- [ ] OkHttp interceptor adds `X-Requested-With` + `x-gasc-enabled` to every request on `httpClient` (login flow client)
- [ ] OAuth URL follows sign-in link and injects PKCE params
- [ ] Credential POST includes `Origin` header, does NOT follow redirects
- [ ] CVF (email code) handled separately from TOTP MFA
- [ ] Device registration POST sends auth code + PKCE verifier
- [ ] Token stored to `filesDir/.device-token`; falls back to legacy path

### 3. Token File Resolution (`findTokenFile()`)

- [ ] Internal `filesDir/.device-token` checked first
- [ ] `logged_out_at` pref read from `auth` SharedPreferences
- [ ] Legacy token: `lastModified() > logged_out_at` → accept (and clear the pref)
- [ ] Legacy token: `lastModified() ≤ logged_out_at` → skip (stale, return null)
- [ ] No logout recorded (`loggedOutAt == 0L`) → accept legacy token
- [ ] Returns `null` if neither file exists → LoginActivity shows login form

### 4. Logout (`AboutActivity.performLogout()`)

- [ ] Deletes `filesDir/.device-token`
- [ ] Attempts to delete `/data/local/tmp/.device-token` (may fail silently — that is OK)
- [ ] Stores `logged_out_at = System.currentTimeMillis()` in `auth` prefs
- [ ] Clears `resume_positions` prefs
- [ ] Starts `LoginActivity` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK`

### 5. DRM (`AmazonLicenseService.kt`)

- [ ] License challenge wrapped as `widevine2Challenge=<base64url>` form body
- [ ] Response parsed as JSON: extracts `widevine2License.license`, base64-decoded to raw bytes
- [ ] License URL includes `deviceVideoQualityOverride=HD` and `deviceVideoCodecOverride=H264`
- [ ] Provisioning (if any) uses a plain OkHttp client without Amazon auth headers

### 6. Player (`PlayerActivity.kt`)

- [ ] `DefaultDrmSessionManager` configured with Widevine UUID
- [ ] DASH manifest loaded via `OkHttpDataSource` with auth headers
- [ ] Track selection dialog covers audio (5.1/Stereo) and subtitles (SDH/Forced/Regular)
- [ ] Stream reporting: `START`, `PLAY` (periodic), `PAUSE`, `STOP` events sent to UpdateStream API
- [ ] Resume: position saved to `resume_positions` SharedPreferences; seeks on next play; cleared at ≥90% or `STATE_ENDED`
- [ ] ExoPlayer released in `onDestroy()` to prevent leaks
- [ ] No crash if user navigates back during DRM provisioning or manifest load

### 7. Architecture & Code Quality

- [ ] No blocking calls on the main thread (all network calls in IO coroutine dispatcher)
- [ ] Coroutine scope is tied to lifecycle (no fire-and-forget GlobalScope)
- [ ] Memory leaks: adapters and listeners cleared in `onDestroy()` / `onDestroyView()`
- [ ] `ContentAdapter` RecyclerView DiffUtil implemented correctly (no full-list refreshes)
- [ ] `RailsAdapter` inner RecyclerViews share a `RecycledViewPool`
- [ ] Error states: all API calls have `try/catch`; user sees error message, not crash
- [ ] Null-safety: force-unwrap operators (`!!`) used only where a null would be a programming error, not a runtime condition

### 8. CI/CD (`.github/workflows/build.yml`)

- [ ] Secrets not echoed in logs (no `echo $SECRET` or similar)
- [ ] Keystore file decoded from base64 and used, then deleted after build
- [ ] Version code is monotonically increasing (date-based format ensures this)
- [ ] APK artifact attached to GitHub Release

---

## Output Format

Write your findings to **`dev/review-findings.md`**.

Use this structure:

```markdown
# Code Review Findings

**Date**: YYYY-MM-DD
**Reviewer**: AI Agent
**Version reviewed**: (read from app/build.gradle.kts)

---

## Summary

| Severity | Count |
|----------|-------|
| 🔴 Critical | N |
| 🟡 Warning | N |
| 🔵 Info | N |
| ✅ OK | N |

---

## Findings

### FINDING-001 — [Short title]
**Severity**: 🔴 Critical / 🟡 Warning / 🔵 Info
**File**: `path/to/File.kt:line`
**Checklist item**: [Which item from the checklist above]
**Description**: What the code does and why it is a problem.
**Evidence**: Paste the relevant code snippet (≤20 lines).
**Suggested fix** (do not implement): Describe what should be changed.

---

### FINDING-002 — ...
(repeat for each finding)

---

## Checklist Results

(For each checklist item above, mark ✅ OK or ❌ FINDING-NNN)

### Security
- [x] No token logging — ✅ OK
- [ ] HTTPS only — ❌ FINDING-001
...

### Login Flow
...

(etc.)
```

**Severity guide:**
- 🔴 **Critical** — Security vulnerability, data leak, crash, or correctness bug that breaks core functionality
- 🟡 **Warning** — Code smell, potential edge-case bug, missing error handling, or non-idiomatic pattern
- 🔵 **Info** — Minor improvement opportunity, style issue, or observation that may or may not need action

---

## Rules

1. **Read every file listed before writing any findings.**
2. **Do not modify any source file, layout, manifest, Gradle file, or workflow file.**
3. **Do not create a git commit.**
4. **Write findings only to `dev/review-findings.md`.**
5. If you find something noteworthy not covered by the checklist, add it as a finding anyway.
6. If a checklist item is clearly satisfied, mark it ✅ OK — do not skip it silently.
