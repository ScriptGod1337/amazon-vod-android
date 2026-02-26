# Amazon Prime Video API Map

Extracted from Kodi plugin at `/home/vscode/kodi-plugin/plugin.video.amazon-test/resources/lib/`

---

## Base URLs (per region)

| Region | ATVUrl | BaseUrl | MarketplaceID |
|--------|--------|---------|---------------|
| US | `https://atv-ps.amazon.com` | `https://www.amazon.com` | `ATVPDKIKX0DER` |
| UK | `https://atv-ps-eu.amazon.co.uk` | `https://www.amazon.co.uk` | `A1F83G8C2ARO7P` |
| DE | `https://atv-ps-eu.amazon.de` | `https://www.amazon.de` | `A1PA6795UKMFR9` |
| JP | `https://atv-ps-fe.amazon.co.jp` | `https://www.amazon.co.jp` | `A1VC38T7YXB528` |
| PV (US) | `https://atv-ps.primevideo.com` | `https://www.primevideo.com` | `ART4WZ8MWBX2Y` |
| PV (EU) | `https://atv-ps-eu.primevideo.com` | `https://www.primevideo.com` | `A3K6Y4MI8GDYMT` |
| PV (FE) | `https://atv-ps-fe.primevideo.com` | `https://www.primevideo.com` | `A15PK738MTQHSO` |

Source: `login.py:36-52`

---

## Device Identity

### Device Type IDs
- **Android**: `A43PXU4ZN2AL1` — used for token-based auth and the Android API (`common.py:55`)
- **Web**: `AOAGZA014O5RE` — used for cookie-based auth (`common.py:56`)

### Android Headers
```
Accept-Charset: utf-8
User-Agent: Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV RQ1A.210105.003)
X-Requested-With: com.amazon.avod.thirdpartyclient
x-gasc-enabled: true
```
Source: `common.py:57-58`

### Device Data (for token operations)
```json
{
  "domain": "DeviceLegacy",
  "device_type": "A43PXU4ZN2AL1",
  "device_serial": "<device_id from .device-token>",
  "app_name": "com.amazon.avod.thirdpartyclient",
  "app_version": "296016847",
  "device_model": "mdarcy/nvidia/SHIELD Android TV",
  "os_version": "NVIDIA/mdarcy/mdarcy:11/RQ1A.210105.003/7094531_2971.7725:user/release-keys"
}
```
Source: `login.py:551-560`

---

## Authentication

### Token Refresh
- **Endpoint**: `POST https://api.{sidomain}/auth/token`
- **Headers**:
  - All `headers_android` (see above)
  - `x-amzn-identity-auth-domain: api.{sidomain}`
  - `Accept-Language: en-US`
  - `x-amzn-requestid: <uuid4 without dashes>`
  - Remove `x-gasc-enabled` and `X-Requested-With` from headers before this call
- **Body** (form-encoded):
  - All fields from `deviceData()` (domain, device_type, device_serial, app_name, app_version, device_model, os_version)
  - `source_token_type`: `refresh_token`
  - `requested_token_type`: `access_token`
  - `source_token`: `<refresh_token from .device-token>`
- **Response**:
  ```json
  {
    "access_token": "Atna|...",
    "expires_in": 3600
  }
  ```
- **On success**: Update stored token's `access` field and recalculate `expires` as `now + expires_in`
- Source: `login.py:578-604`

### Auth Header for API Calls
When using token auth, all API requests include:
```
Authorization: Bearer <access_token>
```
Source: `login.py:574`

### Token Expiry Check
Before each API call, check `if current_time > token.expires`. If expired, call refresh.
Source: `login.py:563-575`

---

## Playback Endpoints

### 1. Get Stream Manifest (GetPlaybackResources)
- **Endpoint**: `GET {ATVUrl}/cdp/catalog/GetPlaybackResources`
- **Required params**:
  - `asin={content_asin}`
  - `deviceTypeID={dtid}` — use `A43PXU4ZN2AL1` for Android/token auth
  - `firmware=1`
  - `deviceID={device_id}`
  - `marketplaceID={marketplace_id}`
  - `format=json`
  - `version=2`
  - `gascEnabled=true` (for PrimeVideo endpoints)
- **Extra params (for streaming)**:
  - `resourceUsage=ImmediateConsumption`
  - `consumptionType=Streaming`
  - `deviceDrmOverride=CENC`
  - `deviceStreamingTechnologyOverride=DASH`
  - `deviceProtocolOverride=Https`
  - `deviceBitrateAdaptationsOverride=CVBR%2CCBR`
  - `audioTrackId=all`
  - `languageFeature=MLFv2`
  - `videoMaterialType=Feature` (or `Trailer`, `LiveStreaming`)
  - `desiredResources=PlaybackUrls,SubtitleUrls,ForcedNarratives,TransitionTimecodes`
  - `supportedDRMKeyScheme=DUAL_KEY`
- **Quality params (for L1 Widevine devices)**:
  - `deviceVideoCodecOverride=H264` (add `,H265` if supported)
  - `deviceHdrFormatsOverride=None` (or `DolbyVision`, `Hdr10`)
  - `deviceVideoQualityOverride=HD` (or `UHD`)
- **Auth**: `Authorization: Bearer <access_token>` + `headers_android`
- **Method**: POST with empty body (`postdata=''`)
- **Response structure**:
  ```json
  {
    "playbackUrls": {
      "defaultUrlSetId": "...",
      "urlSets": {
        "<id>": {
          "urls": {
            "manifest": {
              "url": "https://...manifest.mpd",
              "cdn": "Cloudfront"
            }
          }
        }
      }
    },
    "subtitles": [...],
    "transitionTimecodes": { "skipElements": [...] }
  }
  ```
  OR (older format):
  ```json
  {
    "audioVideoUrls": {
      "avCdnUrlSets": [
        {
          "avUrlInfoList": [{ "url": "https://...manifest.mpd" }],
          "cdn": "Cloudfront"
        }
      ]
    }
  }
  ```
- Source: `network.py:185-228`, `playback.py:88-158, 374-408`

### 2. Widevine License Request (GetPlaybackResources)
- **License server URL**: Same base endpoint but with `desiredResources=Widevine2License` and `retURL=True`
  - Full URL: `{ATVUrl}/cdp/catalog/GetPlaybackResources?asin={asin}&deviceTypeID={dtid}&firmware=1&deviceID={deviceID}&marketplaceID={MarketID}&format=json&version=2&gascEnabled=true&resourceUsage=ImmediateConsumption&consumptionType=Streaming&deviceDrmOverride=CENC&deviceStreamingTechnologyOverride=DASH&deviceProtocolOverride=Https&deviceBitrateAdaptationsOverride=CVBR%2CCBR&audioTrackId=all&languageFeature=MLFv2&videoMaterialType=Feature&desiredResources=Widevine2License&supportedDRMKeyScheme=DUAL_KEY`
- **License request body format**: `widevine2Challenge=<base64url_encoded_challenge_bytes>`
- **License request headers**:
  - `Authorization: Bearer <access_token>`
  - All `headers_android`
  - `Content-Type: application/octet-stream`
- **License response format**: JSON containing base64-encoded license:
  ```json
  {
    "widevine2License": {
      "license": "<base64_encoded_license_bytes>"
    }
  }
  ```
- **Processing**: Decode `widevine2License.license` from base64 → raw Widevine license bytes
- Source: `playback.py:414, 452-467`

### DRM Config Summary (for ExoPlayer porting)
```
License URL:      {ATVUrl}/cdp/catalog/GetPlaybackResources?...&desiredResources=Widevine2License
Request body:     widevine2Challenge=<base64url(challenge)>
Request headers:  Authorization: Bearer <token>, Content-Type: application/octet-stream, + headers_android
Response path:    $.widevine2License.license (base64-encoded)
Single session:   true (force_single_session)
```

---

## Catalog / Browse Endpoints (Android API)

Used by `android_api.py` — this is the preferred API for our Android app (data_source=1).

### Base URL Pattern
```
{ATVUrl}/cdp/mobile/getDataByTransform/v1/{transform_js}
{ATVUrl}/cdp/switchblade/android/getDataByJvmTransform/v1/{transform_kt}
```

### Default Parameters
```
deviceTypeID=A43PXU4ZN2AL1
&firmware=fmw:22-app:3.0.351.3955
&softwareVersion=351
&priorityLevel=2
&format=json
&featureScheme=mobile-android-features-v13-hdr
&deviceID={device_id}
&version=1
&screenWidth=sw1600dp
&osLocale={lang}
&uxLocale={lang}
&supportsPKMZ=false
&isLiveEventsV2OverrideEnabled=true
&swiftPriorityLevel=critical
&supportsCategories=true
```
Source: `android_api.py:40-53`

### Transform Endpoints

| Page | Path Type | Transform JS/KT |
|------|-----------|-----------------|
| Home | switchblade (p=2) | `dv-android/landing/initial/v1.kt` |
| Landing | switchblade (p=2) | `dv-android/landing/initial/v1.kt` |
| Browse | mobile (p=1) | `dv-android/browse/v2/browseInitial.js` |
| Detail | mobile (p=1) | `dv-android/detail/v2/user/v2.5.js` |
| Details | mobile (p=1) | `android/atf/v3.jstl` |
| Watchlist | mobile (p=1) | `dv-android/watchlist/watchlistInitial/v3.js` |
| Library | mobile (p=1) | `dv-android/library/libraryInitial/v2.js` |
| Find/Genres | mobile (p=1) | `dv-android/find/v1.js` |
| Search | mobile (p=1) | `dv-android/search/searchInitial/v3.js` |
| Profiles | switchblade (p=2) | `dv-android/profiles/listPrimeVideoProfiles/v1.kt` |

Source: `android_api.py:108-120`

### Pagination
- Initial requests use `...Initial...` in the path
- Next-page requests swap to `...Next...` in the path, with `startIndex` param
- Source: `android_api.py:133-134`

### Auth for Catalog Calls
- `Authorization: Bearer <access_token>` + `headers_android`
- Source: `android_api.py:137`

---

## Legacy Catalog Endpoint (ATV API)

Used by `atv_api.py` — alternative data source (data_source=2).

- **URL**: `{ATVUrl}/cdp/catalog/{pg_mode}?{deviceTypeID}&deviceID={deviceID}&format=json&version={version}&formatVersion=3&marketplaceId={MarketID}{query}`
- **Device Type IDs** (tried in fallback order):
  - `A3SSWQ04XYPXBH` (fmw:28)
  - `A1S15DUFSI8AUG` (fmw:26)
  - `A1FYY15VCM5WG1` (default)
- **Endpoints**: `GetASINDetails`, `GetCategoryList`, `GetSimilarities`, etc.
- Source: `network.py:242-286`

---

## Usage / Stream Reporting

### UpdateStream
- **Endpoint**: `GET {ATVUrl}/cdp/usage/UpdateStream`
- **Params**: `asin={asin}&event={START|PLAY|STOP}&timecode={position_seconds}` + standard params
- **Auth**: Same as playback (Bearer token or cookie)
- **Response**: Contains `statusCallbackIntervalSeconds` for update frequency
- Source: `playback.py:740-748`, `network.py:185`

---

## Watchlist Management

### Add to Watchlist
- **URL**: `{ATVUrl}/cdp/discovery/AddTitleToList?{defparam}&titleId={asin}`
- **Auth**: Bearer token + `headers_android`
- Source: `android_api.py:348-360`

### Remove from Watchlist
- **URL**: `{ATVUrl}/cdp/discovery/RemoveTitleFromList?{defparam}&titleId={asin}`
- **Auth**: Bearer token + `headers_android`
- Source: `android_api.py:348-360`

---

## Language Setting

### Set Device Preferred Language
- **URL**: `{ATVUrl}/lps/setDevicePreferredLanguage/v1?deviceTypeID={dtid}&firmware=...&priorityLevel=2&format=json&deviceID={deviceID}&locale={loc}&osLocale={loc}&uxLocale={loc}&version=1&preferenceType=IMPLICIT`
- **Method**: POST with empty body
- Source: `android_api.py:733-745`

---

## Territory / Startup Config

### GetAppStartupConfig
- **URL**: `https://atv-ps.amazon.com/cdp/usage/v3/GetAppStartupConfig?deviceTypeID=A28RQHJKHM2A2W&deviceID={deviceid}&firmware=1&version=1&supportedLocales={loc}&format=json`
- **Response**: `{ "territoryConfig": { "defaultVideoWebsite": ..., "avMarketplace": ... }, "customerConfig": { "homeRegion": ..., "locale": { "uxLocale": ... } } }`
- Source: `login.py:62-63`

---

## Error Handling

### Error Response Format
```json
{
  "error": {
    "errorCode": "InvalidRequest|NoAvailableStreams|NotOwned|InvalidGeoIP|TemporarilyUnavailable",
    "message": "Human-readable error message"
  }
}
```
OR:
```json
{
  "errorsByResource": {
    "PlaybackUrls": { "errorCode": "...", "message": "..." }
  }
}
```
Source: `network.py:28-42, 219-226`

### HTTP Status Codes
- **401**: Token expired → trigger refresh flow
- **403**: Authorization issue → re-examine auth logic
- Source: `CLAUDE.md` general rules
