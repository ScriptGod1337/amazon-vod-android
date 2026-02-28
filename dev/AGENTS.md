# UI Redesign — Multi-Agent System

Two agents collaborate to redesign the Amazon VOD Android app from a functional
prototype to a polished streaming TV experience. Agent 1 implements; Agent 2
reviews and requests fixes. They iterate until the reviewer approves all sections.

---

## Agents

| Agent | Instruction file | Model | Role |
|-------|-----------------|-------|------|
| UI Implementer | `dev/ui-implementer.md` | Sonnet | Rewrites layouts, adapters, styles; builds + installs on emulator; takes screenshots |
| UI Reviewer | `dev/ui-reviewer.md` | Sonnet | Reads screenshots + source; checks against design spec; writes structured report |

---

## Source of Truth

| File | What it defines |
|------|----------------|
| `dev/ui-design-spec.md` | Color system, dimensions, per-screen visual spec |
| `dev/progress.md` | Full build history — read to understand architecture context |
| `dev/analysis/decisions.md` | API-level decisions — do not break any listed workaround |
| `app/src/main/java/com/scriptgod/fireos/avod/` | All Kotlin source |
| `app/src/main/res/` | All layouts, drawables, values |

---

## Collaboration Model

```
Implementer
  → reads design spec
  → edits layouts / adapters / drawables
  → ./gradlew assembleRelease
  → adb -s emulator-5554 install -r …apk
  → takes screenshots of every screen
  → saves to dev/review-screenshots/
  → writes summary to dev/ui-review-request.md

Reviewer
  → reads design spec
  → reads dev/ui-review-request.md
  → reads every screenshot in dev/review-screenshots/
  → reads modified source files
  → writes dev/ui-review-report.md (PASS / FAIL per section)

Implementer (second pass)
  → reads dev/ui-review-report.md
  → fixes all FAIL items
  → retakes screenshots, repeats cycle

Repeat until Reviewer marks all sections PASS.
```

---

## Emulator Access

```bash
# Verify emulator is connected
adb devices                         # must show emulator-5554

# Push dev token (needed to skip login on emulator)
adb -s emulator-5554 push /home/vscode/amazon-vod-android/.device-token \
    /data/local/tmp/.device-token
adb -s emulator-5554 shell chmod 644 /data/local/tmp/.device-token

# Build
cd /home/vscode/amazon-vod-android
./gradlew assembleRelease

# Install (emulator only — NOT Fire TV)
adb -s emulator-5554 install -r \
    app/build/outputs/apk/release/app-release.apk

# Launch
adb -s emulator-5554 shell am start \
    -n com.scriptgod.fireos.avod/.ui.LoginActivity

# Screenshot
adb -s emulator-5554 shell screencap -p /sdcard/screen.png
adb -s emulator-5554 pull /sdcard/screen.png dev/review-screenshots/<name>.png
```

**DRM note**: The emulator uses Widevine L3 (software). Amazon streams require L1.
Playback will likely fail with a DRM error. This is expected — test navigation,
home screen, search, detail page, and the player overlay appearance (error state).
Do not spend time debugging DRM on the emulator.

---

## Constraints — Do Not Touch

These files must not be modified by either agent:

- `auth/AmazonAuthService.kt`
- `api/AmazonApiService.kt`
- `drm/AmazonLicenseService.kt`
- `model/*.kt`
- `ui/LoginActivity.kt` (login flow is separate concern)
- `ui/DpadEditText.kt`
- `.github/workflows/build.yml`

---

## Launch

```bash
# Run implementer
bash dev/start-ui-redesign.sh implementer

# After implementer finishes, run reviewer
bash dev/start-ui-redesign.sh reviewer
```
