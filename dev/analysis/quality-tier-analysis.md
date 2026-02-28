# Video Quality Tier Analysis

**Date**: 2026-02-28
**Trigger**: User question: "Was the 720p HD cap confirmed by APK analysis?"

---

## What was confirmed by APK analysis

### Enums present in the decompiled APK

| Enum class | Values | Smali path |
|-----------|--------|------------|
| `atvplaybackdevice.types.VideoQuality` | `SD`, `HD`, `UHD` | `smali_classes3/…/VideoQuality.smali` |
| `atvplaybackdevice.types.HdrFormat` | `Hdr10`, `DolbyVision`, `None` | `smali_classes3/…/HdrFormat.smali` |
| `atvplaybackresource.types.SupportedVideoQuality` | `SD`, `HD`, `UHD` | `smali_classes3/…/SupportedVideoQuality.smali` |
| `media.framework.config.profiles.VideoQualityProfile` | `SD`, `HD`, `UHD` | `smali_classes3/…/VideoQualityProfile.smali` |
| `media.framework.config.profiles.ThirdPartyProfileName` | see below | `smali_classes3/…/ThirdPartyProfileName.smali` |
| `avod.settings.QualityConfig$Values` | `GOOD`, `BETTER`, `BEST`, `BEST_1080`, `DATA_SAVER` | `smali_classes6/…/QualityConfig$Values.smali` |

### ThirdPartyProfileName — full list

These are the internal client-side playback profiles. They map combinations of quality +
codec + HDR to a single named profile sent to the CDN / license server:

| Profile name | Inferred meaning |
|-------------|-----------------|
| `SD` | SD H264 |
| `SD_MCMD_L3_HEVC` | SD H265 software (L3 Widevine) |
| `SD_MEDIACODEC_SOFTWARE_PLAYREADY` | SD PlayReady |
| `HD` | HD H264 SDR |
| `HD_HEVC` | HD H265 SDR |
| `HD_AV1` | HD AV1 SDR |
| **`HDR`** | **Unknown — likely 1080p HDR (not 4K)** |
| **`HDR_AV1`** | **Unknown — likely 1080p HDR AV1** |
| `UHD_HDR` | 4K H265 HDR |
| `UHD_HDR_AV1` | 4K AV1 HDR |

**Key observation**: `HDR` is a separate profile from `UHD_HDR`. The real Prime Video
app distinguishes between `HDR` (probably 1080p HDR) and `UHD_HDR` (4K HDR). Our app
has never requested this profile.

### QualityConfig$Values — BEST_1080

The real Prime Video app has a user-facing quality setting called `BEST_1080`
(`qualityConfigDataUse1080P`). This is a named 1080p quality cap, distinct from `BEST`
(uncapped). This confirms that 1080p is a real tier the server understands — it is not
simply equivalent to `UHD`.

### The "1080p" string in PlaybackUrlsCommonParams (line 1153)

The string `"1080p"` appears in a method that returns the device's **screen resolution
declaration** (`getScreenResolutionString()`), which calls `isHdSupported()`. This tells
the server what the device can display — it is NOT a quality cap. Returns `"576p"` on
non-HD devices.

---

## What was NOT confirmed by APK analysis

**The claim "Amazon's HD tier caps at 720p" was empirical, not code-proven.**

- Observed: two test titles played at 720p when using `deviceVideoQualityOverride=HD`
- The APK client encodes no resolution numbers for the HD tier — the manifest content is
  decided entirely server-side
- The 720p observation is very likely correct as a general CDN policy, but it is a
  sample observation, not a guaranteed constant

---

## Untested hypothesis: HDR profile = 1080p HDR

The existence of `ThirdPartyProfileName.HDR` (separate from `UHD_HDR`) suggests there
may be an intermediate tier:

| Request params | Hypothesised result |
|---------------|-------------------|
| `quality=HD` + `hdr=None` | 720p SDR (confirmed) |
| `quality=HD` + `hdr=Hdr10` | **unknown — possibly 1080p HDR?** |
| `quality=UHD` + `hdr=Hdr10,DolbyVision` | 4K HDR (confirmed) |

To test: request `deviceVideoQualityOverride=HD` + `deviceHdrFormatsOverride=Hdr10`
and observe the manifest representations from logcat. If this returns 1080p streams it
confirms the intermediate tier. Requires an HDR-capable display.

**Risk**: The license server validates quality claims and may reject mismatched params.
If `HD+Hdr10` is not a recognised combination, expect a licence denial error.

---

## Impact on current presets

The current three presets in `PlaybackQuality.kt` remain correct and safe:

| Preset | Params | Confirmed result |
|--------|--------|-----------------|
| HD H264 | `HD` + `H264` + `None` | 720p H264 SDR ✓ |
| H265 | `HD` + `H264,H265` + `None` | 720p H265 SDR ✓ |
| 4K / DV HDR | `UHD` + `H264,H265` + `Hdr10,DolbyVision` | 4K H265 HDR ✓ |

A potential fourth preset (`HD + Hdr10` → 1080p HDR) is unconfirmed and untested.
It should not be added until the hypothesis is validated on a real device with an
HDR display + logcat confirmation.

---

## References

- `dev/analysis/decisions.md` — Decision 16 (quality presets)
- `prime-3.0.412.2947-smali/smali_classes3/com/amazon/atvplaybackdevice/types/VideoQuality.smali`
- `prime-3.0.412.2947-smali/smali_classes3/com/amazon/atvplaybackdevice/types/HdrFormat.smali`
- `prime-3.0.412.2947-smali/smali_classes3/com/amazon/atvplaybackresource/types/SupportedVideoQuality.smali`
- `prime-3.0.412.2947-smali/smali_classes3/com/amazon/avod/media/framework/config/profiles/ThirdPartyProfileName.smali`
- `prime-3.0.412.2947-smali/smali_classes6/com/amazon/avod/settings/QualityConfig$Values.smali`
- `prime-3.0.412.2947-smali/smali_classes5/com/amazon/avod/media/service/prsv2/PlaybackUrlsCommonParams.smali`
