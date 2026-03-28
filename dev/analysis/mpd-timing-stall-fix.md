# MPD Segment Timing Stall — Root Cause and Fix

## Problem

Our app stalls permanently at ~56:07 (3367850ms) on certain long-form content.
Amazon's official app plays the same content smoothly. The stall occurs on both
the emulator (`c2.goldfish.h264.decoder`) and Fire TV (`OMX.amlogic.avc.decoder.awesome.secure`).

## Root cause (validated by experiment)

Amazon's MPD declares a fixed segment duration that doesn't match actual segment
durations in the content files.

The DASH manifest uses `<SegmentList timescale="96" duration="351">` (3.656s per segment),
but actual segment durations (from the sidx box in the content file) vary from 2.625s to
5.0s. Over a 2h7m movie (2087 segments), the cumulative difference reaches ~41 seconds
at the 56-minute mark:

| Segment | MPD-declared time (s) | Actual time from sidx (s) | Difference |
|---------|----------------------|---------------------------|------------|
| seg[0]  | 0.0                  | 0.0                       | 0.0s       |
| seg[10] | 36.6                 | 47.7                      | -11.1s     |
| seg[500]| 1828.1               | 1823.4                    | +4.8s      |
| seg[920]| 3363.8               | 3322.5                    | **+41.2s** |
| seg[933]| 3413.4               | 3367.9                    | +45.5s     |

ExoPlayer uses the MPD's fixed duration to calculate which segment to fetch for a given
time position. At 3367850ms it calculates segment index 920, but segment 920 actually
contains data for ~3322s (41s behind). The renderer drops all frames as too old. The
buffer fills with stale data but no renderable frames exist at the current position.

### Validation

- **Original MPD**: app stalls permanently at 3367850ms
- **Corrected MPD** (SegmentList replaced with SegmentBase+sidx): plays through smoothly
- Same title, same app, same device, same DRM, same audio, same quality settings
- Only variable changed: how ExoPlayer discovers segment timing

This confirms the manifest timing mismatch is the primary root cause in our player.

### Why Amazon's app doesn't stall

We don't have direct evidence for the specific mechanism. Possible explanations:
1. Amazon's player reads actual segment timing from sidx/tfdt rather than the MPD duration
2. Amazon's backend serves a different MPD variant to their own client
3. Amazon's player has a tolerance threshold for stale frames that ExoPlayer lacks

The decompiled APK contains both ExoPlayer symbols and VisualOn renderer components.
We cannot confirm which handles DASH segment selection.

## Primary fix: MpdTimingCorrector

**File:** `app/src/main/java/com/scriptgod/fireos/avod/player/MpdTimingCorrector.kt`

Converts the MPD's `SegmentList` (with inaccurate fixed `duration`) to `SegmentBase`
(with `indexRange` pointing to the sidx box). ExoPlayer natively reads sidx for
SegmentBase content, getting accurate per-segment timing and byte offsets.

### How it works

1. Downloads the MPD XML
2. Parses with DOM to find `<Representation>` elements using `<SegmentList duration="...">`
3. Probes ONE content file (8-byte HTTP Range request) to verify a sidx box exists
   after the init segment and get its size
4. For each representation: makes BaseURL absolute, replaces `<SegmentList>` with
   `<SegmentBase indexRange="..."><Initialization range="..."/></SegmentBase>`
5. Serves the corrected MPD via an in-memory DataSource interceptor (the MediaItem URI
   stays as the real CDN URL so BaseURL resolution works correctly)

### Before / After

**Before (original MPD):**
```xml
<Representation id="video=3001000" bandwidth="3001000">
  <BaseURL>d985eb3e-..._video_9.mp4</BaseURL>
  <SegmentList timescale="96" duration="351">
    <Initialization range="0-1612"/>
    <SegmentURL mediaRange="26689-473386"/>
    ... (2087 entries, 17MB of XML)
  </SegmentList>
</Representation>
```

**After (corrected MPD):**
```xml
<Representation id="video=3001000" bandwidth="3001000">
  <BaseURL>https://cdn.../d985eb3e-..._video_9.mp4</BaseURL>
  <SegmentBase indexRange="1613-26688">
    <Initialization range="0-1612"/>
  </SegmentBase>
</Representation>
```

### Integration

**File:** `PlayerActivity.kt`

Called in `loadAndPlay()` after fetching playback info, before `setupPlayer()`:
```kotlin
correctedMpdContent = withContext(Dispatchers.IO) {
    MpdTimingCorrector.correctMpd(info.manifestUrl, authClient)
}
```

The corrected MPD is served via a custom `DataSource` that intercepts the manifest URL
and returns the corrected content in-memory. All other requests (segments, init, sidx)
pass through to the normal HTTP data source. Falls back to the original MPD on any error.

### Assumptions

- sidx box exists immediately after the init segment (confirmed across multiple titles)
- sidx byte ranges match SegmentURL mediaRange values (verified for first 5 segments)
- All representations share the same sidx size (same segment count = same box size)
- Code falls back gracefully if any assumption fails (no sidx found, parse error, etc.)

## Secondary mitigation: StallRecoveryVideoRenderer

**File:** `app/src/main/java/com/scriptgod/fireos/avod/player/StallRecoveryVideoRenderer.kt`

Safety net in case the MPD correction doesn't cover all stall scenarios. Custom
`MediaCodecVideoRenderer` that detects when the decoder stops producing output for
800ms and triggers a 10s seek forward. Only fires when `playWhenReady=true`.

## Secondary mitigation: Stall Watchdog

**File:** `PlayerActivity.kt` (startStallWatchdog)

Coarser safety net at the player position level. Polls every 2s; if position is frozen
for 4s with >30s buffered, seeks forward 10s. Three bugs in the original implementation
were fixed (all caused by BUFFERING/READY oscillation resetting detection state).

## Files changed

| File | Change |
|------|--------|
| `app/.../player/MpdTimingCorrector.kt` | **New** — MPD SegmentList to SegmentBase converter |
| `app/.../player/StallRecoveryVideoRenderer.kt` | **New** — renderer-level stall detection |
| `app/.../ui/PlayerActivity.kt` | MPD correction, custom DataSource, renderer, watchdog fixes |

## Testing

| Test | Result |
|------|--------|
| MPD correction resolves stall (emulator) | **Pass** — plays through 56-min region |
| Pause/resume works with correction active | **Pass** (after fixing renderer firing while paused) |
| Watchdog fires on stall without MPD fix | Pass |
| StallRecoveryVideoRenderer fires on stall | Pass |
| Regression: other titles still play normally | **Pass** — confirmed on multiple titles (2026-03-28) |
| Fire TV device testing | **Pass** — confirmed on physical Fire TV Stick 4K (2026-03-28) |
