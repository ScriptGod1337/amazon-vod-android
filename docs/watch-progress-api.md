
## Watch Progress & Bookmarking APIs

Firebat uses three layered APIs to track playback progress and sync it with Amazon's servers. These APIs are what keep "Continue Watching" / resume positions in sync across devices.

### Base URL Resolution

All CDP (Consumer Device Platform) endpoints use a dynamically resolved base URL:

| Region | Base URL |
|--------|----------|
| US (default) | `https://atv-ext.amazon.com` |
| EU | `https://atv-ext-eu.amazon.com` |
| FE (Japan/Asia) | `https://atv-ext-fe.amazon.com` |

The `baseDomainSuffix` is fetched from AppStartupConfig. Default fallback: `api.amazonvideo.com`. The full service URL is constructed as `https://atv-ext{-region}.amazon.com` + path.

### Authentication

All APIs use **TokenKey-based authentication** via `SessionContextFactory.buildFromTokenKey()`:

```
Headers:
  accountDirectedId: <amazon-account-directed-id>    (required)
  profileDirectedId: <amazon-profile-directed-id>    (optional, for multi-profile households)
```

TokenKey is obtained from `TokenKeyProvider`:
- `TokenKeyProvider.forAccount(accountDirectedId)` — account-level
- `TokenKeyProvider.forProfile(accountDirectedId, profileDirectedId)` — profile-scoped

For the WatchNext/Recommendation subsystem, a separate **OAuth 2.0 bearer token** is used:
```
Headers:
  Authorization: Bearer <access_token>
```
Token source: stored in SharedPreferences under key `RECOMMENDATION_PARAMETERS`, path `$.credentials.oauth-2.0-v1.access_token.latest`.

---

### API 1: UpdateStream (Legacy Bookmarking)

The primary heartbeat/bookmark endpoint. Fires periodically during playback and on state changes.

**Endpoint:** `GET /cdp/usage/UpdateStream`

**Priority:** CRITICAL (no retry)

#### Query Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `titleId` | String | Yes | Content ASIN (e.g., `B08FFD91KG`) |
| `event` | String | Yes | Event type (see below) |
| `timecode` | Integer | Conditional | Playback position in **seconds** (for VOD) |
| `epochUtc` | Long | Conditional | Current UTC timestamp in **milliseconds** (for live) |
| `timecodeChangeTime` | String | Yes | ISO 8601 UTC timestamp when position was recorded (`yyyy-MM-dd'T'HH:mm:ss'Z'`) |
| `userWatchSessionId` | String | No | Session tracking UUID |
| `sessionToken` | String | No | Session auth token (when `shouldSendSessionTokenOnHeartbeat` config is true) |
| `streamIntent` | String | No | `"AUTOPLAY"` when autoplay-initiated |
| `watchedPositionUTC` | Long | No | Validated watched position UTC (must be >= 0) |
| `tuneInTimeEpoch` | Long | No | Live stream tune-in timestamp (must be >= 0) |
| `scheduleInfo` | String (JSON) | No | URL-encoded JSON for live streams (see below) |

**Event Types** (`UpdateStreamEventType` enum):

| Enum Value | Sent As | Description |
|------------|---------|-------------|
| `START` | `"START"` | Initial stream start |
| `PLAY` | `"PLAY"` | Playback resumed / periodic heartbeat |
| `PAUSE` | `"PAUSE"` | Playback paused |
| `ONLINE_STOP` | `"STOP"` | Normal playback stop |
| `OFFLINE_STOP` | `"OFFLINE_STOP"` | Offline playback stop |

**scheduleInfo JSON** (for live streams only):
```json
{
  "scheduleStartTimeEpoch": 1706000000,
  "scheduleEndTimeEpoch": 1706003600,
  "fragmentTitleId": "B0EXAMPLE"
}
```

#### Example Request

```http
GET /cdp/usage/UpdateStream?titleId=B08FFD91KG&event=PLAY&timecode=342&timecodeChangeTime=2026-02-25T10:30:45Z&userWatchSessionId=a1b2c3d4-e5f6-7890 HTTP/1.1
Host: atv-ext.amazon.com
accountDirectedId: amzn1.account.AEXAMPLE
profileDirectedId: amzn1.profile.PEXAMPLE
x-atv-session-id: session-uuid
```

#### Response

```json
{
  "callbackIntervalInSeconds": 60
}
```

| Field | Type | Description |
|-------|------|-------------|
| `callbackIntervalInSeconds` | Integer | Server-requested interval (seconds) until next heartbeat. Client uses this to reschedule the next UpdateStream call. |

#### Heartbeat Scheduling

- **Default interval:** 2 minutes (from `PlaybackConfig.mUpdateStreamPeriod`)
- **Server override:** Response `callbackIntervalInSeconds` replaces the default for subsequent calls
- **Skip conditions:** Not sent during ad playback (`mIsAdPlaying == false`), during downloads, or before rights validation completes

---

### API 2: PES V2 (Playback Event Service V2)

The modern session-based progress tracking system. Uses POST with JSON bodies.

**Endpoints:**

| Action | Method | Path |
|--------|--------|------|
| Start Session | `POST` | `/cdp/playback/pes/StartSession` |
| Update Session | `POST` | `/cdp/playback/pes/UpdateSession` |
| Stop Session | `POST` | `/cdp/playback/pes/StopSession` |

**Content-Type:** `application/json`

**Priority:** CRITICAL

#### StartSession Request

```json
{
  "sessionHandoff": "<handoff-token-from-previous-service>",
  "playbackEnvelope": "<encrypted-playback-authorization>",
  "streamInfo": {
    "eventType": "START",
    "vodProgressInfo": {
      "currentProgressTime": "PT5M30S",
      "timeFormat": "ISO8601DURATION"
    },
    "streamIntent": "AUTOPLAY",
    "streamExperience": "RAPIDRECAP"
  }
}
```

#### UpdateSession Request

```json
{
  "sessionToken": "<token-from-StartSession-response>",
  "streamInfo": {
    "eventType": "PLAY",
    "vodProgressInfo": {
      "currentProgressTime": "PT12M45S",
      "timeFormat": "ISO8601DURATION"
    }
  }
}
```

#### StopSession Request

```json
{
  "sessionToken": "<token-from-StartSession-response>",
  "streamInfo": {
    "eventType": "STOP",
    "vodProgressInfo": {
      "currentProgressTime": "PT45M20S",
      "timeFormat": "ISO8601DURATION"
    }
  }
}
```

#### StreamInfo Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventType` | String | Yes | One of: `START`, `PLAY`, `PAUSE`, `STOP`, `OFFLINE_STOP` |
| `vodProgressInfo` | Object | Conditional | For on-demand content |
| `liveProgressInfo` | Object | Conditional | For live content |
| `streamIntent` | String | No | Playback intent (e.g. content autoplay context) |
| `streamExperience` | String | No | `"AUTOPLAY"` or `"RAPIDRECAP"` |

**vodProgressInfo** (VOD content):

| Field | Type | Description |
|-------|------|-------------|
| `currentProgressTime` | String | ISO 8601 duration (e.g., `"PT1H23M45S"` = 1h 23m 45s) |
| `timeFormat` | String | Always `"ISO8601DURATION"` |

**liveProgressInfo** (Live content):

| Field | Type | Description |
|-------|------|-------------|
| `currentProgressTime` | String | ISO 8601 datetime (e.g., `"2026-02-25T10:30:00Z"`) |
| `timeFormat` | String | Always `"ISO8601DATETIME"` |

#### Response (all three endpoints)

```json
{
  "sessionToken": "<session-token>",
  "callbackIntervalInSeconds": 60
}
```

| Field | Type | Description |
|-------|------|-------------|
| `sessionToken` | String | Session token (returned from StartSession, echoed back on Update/Stop) |
| `callbackIntervalInSeconds` | Integer | Server-requested callback interval |

#### Error Response

```json
{
  "errorCode": "<PesErrorCode>",
  "message": "<human-readable-error>",
  "httpCode": 400
}
```

---

### API 3: WatchNext / Continue Watching

This is a **local-first** system that syncs with the Android TV Provider and Amazon's recommendation backend.

**Backend domain:** `{region-prefix}.api.amazonvideo.com`

**Auth:** OAuth 2.0 Bearer token (see Authentication section above)

#### WatchNext Data Model

Data pushed to the Android TV Provider (`content://android.media.tv/watch_next_program`):

| Field | Type | Description |
|-------|------|-------------|
| `content_id` | String | Content ASIN |
| `type` | Integer | Android TV program type |
| `watch_next_type` | Integer | Android WatchNextType constant |
| `last_engagement_time_utc_millis` | Long | Last interaction timestamp (ms) |
| `title` | String | Content title |
| `short_description` | String | Content description |
| `duration_millis` | Long | Total content duration (ms) |
| `last_playback_position_millis` | Long | Resume position (ms) |

#### WatchNext JSON Format (from backend)

```json
{
  "mediaType": "MOVIE",
  "watchNextType": "CONTINUE",
  "lastEngagementTimeInUtcMs": 1706000000000,
  "title": "Movie Title",
  "description": "Short description",
  "imageUrl": "https://m.media-amazon.com/images/...",
  "totalTimeInMs": 7200000,
  "elapsedTimeInMs": 3600000,
  "contentId": "B08FFD91KG"
}
```

#### Background Workers

| Worker | Class | Purpose |
|--------|-------|---------|
| Update | `UpdateWatchNextWorker` | Periodic sync of WatchNext entries from server to local TV Provider |
| Clear | `ClearWatchNextWorker` | Clears stale WatchNext entries |

---

### TitleBookmark Data Model

Internal bookmark representation used across all three APIs:

```java
class TitleBookmark {
    Optional<String> titleId;       // Content ASIN
    Optional<Long>   timecodeSec;   // Position in seconds
    Optional<Date>   epochUtc;      // UTC timestamp of the position
    Optional<Date>   updateTime;    // When bookmark was last updated
}
```

### ConsumptionDetails Data Model

Rich consumption metadata (used by Title Action Aggregation Service):

```java
class ConsumptionDetails {
    Optional<String>            playbackConsumptionType;  // e.g., "STREAMING"
    Optional<Bookmark>          bookmark;                 // Position data
    Optional<Boolean>           isLinear;                 // Live channel flag
    Optional<VideoQuality>      videoQuality;             // HD, UHD, etc.
    Optional<VideoMaterialType> videoMaterialType;        // HDR10, DV, etc.
}
```

### API Interaction Timeline

```
User presses Play
    │
    ├─► PES V2: POST /cdp/playback/pes/StartSession
    │     Body: { sessionHandoff, playbackEnvelope, streamInfo: { eventType: "START", vodProgressInfo: { currentProgressTime: "PT0S" } } }
    │     Response: { sessionToken: "tok-xxx", callbackIntervalInSeconds: 60 }
    │
    ├─► UpdateStream: GET /cdp/usage/UpdateStream?titleId=B0XXX&event=START&timecode=0&timecodeChangeTime=...
    │     Response: { callbackIntervalInSeconds: 120 }
    │
    │  ┌─── Every ~60s (PES) / ~120s (UpdateStream) ───┐
    │  │                                                 │
    │  ├─► PES V2: POST /cdp/playback/pes/UpdateSession  │
    │  │     Body: { sessionToken: "tok-xxx", streamInfo: { eventType: "PLAY", vodProgressInfo: { currentProgressTime: "PT5M30S" } } }
    │  │                                                 │
    │  ├─► UpdateStream: GET /cdp/usage/UpdateStream?titleId=B0XXX&event=PLAY&timecode=330&...
    │  │                                                 │
    │  └─────────────────────────────────────────────────┘
    │
User pauses
    ├─► PES V2: UpdateSession { eventType: "PAUSE", vodProgressInfo: { currentProgressTime: "PT22M15S" } }
    ├─► UpdateStream: ?event=PAUSE&timecode=1335
    │
User resumes → PLAY events resume
    │
User stops / exits
    ├─► PES V2: POST /cdp/playback/pes/StopSession
    │     Body: { sessionToken: "tok-xxx", streamInfo: { eventType: "STOP", vodProgressInfo: { currentProgressTime: "PT45M20S" } } }
    ├─► UpdateStream: ?event=STOP&timecode=2720
    │
    └─► WatchNext: UpdateWatchNextWorker syncs resume position to TV Provider
```

### Configuration Flags

Key `PlaybackConfig` values that control heartbeat behavior:

| Config Key | Type | Default | Description |
|------------|------|---------|-------------|
| `mUpdateStreamPeriod` | Integer | 2 (minutes) | UpdateStream heartbeat interval |
| `mShouldSendSessionTokenOnHeartbeat` | Boolean | varies | Include session token in UpdateStream |
| `mIncludeChannelInfoInUpdateStream` | Boolean | varies | Include scheduleInfo for live |
| `mShouldIncludeAutoPlayFlagInUpdateStreamCalls` | Boolean | varies | Add streamIntent=AUTOPLAY |
| `mShouldReportUpdateStreamEventAtAdBreakEnd` | Boolean | varies | Report position at ad break boundaries |
| `mDelayForReportingUpdateStreamEventAtAdBreakEndInSeconds` | Integer | varies | Delay after ad break before reporting |
| `mPlaybackPersistenceUpdateIntervalMillis` | Long | varies | Local bookmark persistence interval |