# Watchlist API (Prime Video Android `3.0.438.2347`)

This is a static reverse-engineering map for **raw HTTP usage** based on decompiled smali in:

- `/home/scriptgod/Documents/Projects/amazon-vod-noads/analysis/decompiled/prime-3.0.438.2347-smali`

## 1) Confirmed endpoints

### Browse watchlist (initial page)
- `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/initial/v1.kt`
- Evidence:
  - `.../watchlist/cache/WatchlistPageCache$Companion.smali` (`dv-android/watchlist/initial/v1.kt`)
  - `.../core/remotetransform/SwitchbladeTransformRequestFactory.smali` (base path `/cdp/switchblade/android/getDataByJvmTransform/v1/`)

### Browse watchlist (next pages)
- `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/next/v1.kt`
- Evidence:
  - `.../watchlist/cache/WatchlistPageCache$Companion.smali` (`dv-android/watchlist/next/v1.kt`)

### Add item to watchlist
- `GET /cdp/discovery/AddTitleToList`
- Required query params include:
  - `titleId=<ASIN>`
  - `version=2`
- Evidence:
  - `.../watchlist/service/ModifyWatchlistServiceClient.smali` (`AddTitleToList`, `titleId`, `version=2`)

### Remove item from watchlist
- `GET /cdp/discovery/RemoveTitleFromList`
- Required query params include:
  - `titleId=<ASIN>`
  - `version=2`
- Evidence:
  - `.../watchlist/service/ModifyWatchlistServiceClient.smali` (`RemoveTitleFromList`, `titleId`, `version=2`)

## 2) Required request shape (raw client)

The app uses `ATVRequestBuilder`, which auto-injects common params/headers. For raw calls, include equivalents:

### Required auth
- `Authorization: Bearer <access_token>`

### Important headers
- `Accept: application/json`
- `x-atv-page-type: ATVWatchlist` (used by watchlist browse + modify flows)

### Common query params (auto-added by builder)
- `format=json`
- `version=1` (except modify watchlist explicitly uses `version=2`)
- `deviceTypeID=<device type>`
- `deviceId=<device id>`
- `firmware=fmw:<sdk>-app:<appVersion>`
- `clientName=<top-level client>`
- `priorityLevel=2` (for CRITICAL requests)
- `swiftPriorityLevel=critical`
- `osLocale=<locale>`
- `uxLocale=<locale>` (when locale appending is enabled)
- `screenWidth=<bucket>`
- `screenDensity=<bucket>`
- `supportsPKMZ=<true|false>`
- `softwareVersion=<major>`
- Evidence:
  - `.../http/ATVRequestBuilder.smali` (`appendAtvUrlParams`, `setRequestPriority`)
  - `.../http/DiscoveryRequestPriority.smali` (`DISCOVERY_FOREGROUND => "2"`)

## 3) Watchlist browse params

In addition to common params, watchlist page requests include static params from `WatchlistConfig`:

- `featureScheme=mobile-android-features-v9`
- `supportsLiveBadging=true`
- `isWatchModalEnabled=<bool>`
- `isLiveEventsV2OverrideEnabled=true`
- `isPlaybackEnvelopeSupported=<bool>`
- `useMessagePresentationV2=<bool>`
- (plus one dynamic live-badging feature flag key/value)

Evidence:
- `.../watchlist/WatchlistConfig.smali` (`getStaticRequestParameters`)
- `.../client/activity/BasePaginationActivity.smali` (merges static params + adds `x-atv-page-type`)

## 4) Pagination behavior

- Initial request uses `.../watchlist/initial/v1.kt`
- Next requests use `.../watchlist/next/v1.kt`
- Next-request params come from `paginationModel.parameters` in the previous response
- App also forces `pageSize=<Watchlist_max_page_size>` (default `20`)

Evidence:
- `.../watchlist/cache/WatchlistPageCache$Companion.smali`
- `.../mystuff/cache/CollectionPageCache.smali`
- `.../page/pagination/CollectionPaginationRequest.smali` (merges `paginationModel.parameters` + `pageSize`)
- `.../watchlist/WatchlistConfig.smali` (`Watchlist_max_page_size=20`)

## 5) Minimal raw examples

Use your resolved API host (example: `https://atv-ps.amazon.com` or your terminator host).

### Browse initial
```bash
curl -sS -G "$ATV/cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/initial/v1.kt" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "format=json" \
  --data-urlencode "version=1" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "firmware=$FIRMWARE" \
  --data-urlencode "clientName=$CLIENT_NAME" \
  --data-urlencode "osLocale=$LOCALE" \
  --data-urlencode "uxLocale=$LOCALE" \
  --data-urlencode "screenWidth=$SCREEN_WIDTH" \
  --data-urlencode "screenDensity=$SCREEN_DENSITY" \
  --data-urlencode "supportsPKMZ=false" \
  --data-urlencode "softwareVersion=$SOFTWARE_VERSION" \
  --data-urlencode "priorityLevel=2" \
  --data-urlencode "swiftPriorityLevel=critical" \
  --data-urlencode "featureScheme=mobile-android-features-v9" \
  --data-urlencode "pageType=watchlist" \
  --data-urlencode "pageId=Watchlist"
```

`pageId` may be omitted on some flows; if server rejects, pass the current navigation page ID.

### Add
```bash
curl -sS -G "$ATV/cdp/discovery/AddTitleToList" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "titleId=$ASIN" \
  --data-urlencode "version=2" \
  --data-urlencode "format=json" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "firmware=$FIRMWARE" \
  --data-urlencode "clientName=$CLIENT_NAME" \
  --data-urlencode "osLocale=$LOCALE" \
  --data-urlencode "uxLocale=$LOCALE" \
  --data-urlencode "softwareVersion=$SOFTWARE_VERSION"
```

### Remove
```bash
curl -sS -G "$ATV/cdp/discovery/RemoveTitleFromList" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "titleId=$ASIN" \
  --data-urlencode "version=2" \
  --data-urlencode "format=json" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "firmware=$FIRMWARE" \
  --data-urlencode "clientName=$CLIENT_NAME" \
  --data-urlencode "osLocale=$LOCALE" \
  --data-urlencode "uxLocale=$LOCALE" \
  --data-urlencode "softwareVersion=$SOFTWARE_VERSION"
```

### Browse next page
Take `paginationModel.parameters` from the previous response and replay them on the `next` transform:

```bash
curl -sS -G "$ATV/cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/next/v1.kt" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "format=json" \
  --data-urlencode "version=1" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "pageSize=20" \
  --data-urlencode "startIndex=$START_INDEX" \
  --data-urlencode "serviceToken=$SERVICE_TOKEN"
```

Also forward any other keys present in `paginationModel.parameters` (for example `swiftId`, `pageType`, `pageId`, etc.).

## 6) Notes for automation

- Modify-watchlist parser reads `statusCode` and `body` fields, but operationally treats request as success when HTTP call succeeds (no transport exception).
- Queueing/retry exists in app logic (default retry limit `3`, max age `72h`) but is client-side; raw clients must implement their own retries.
- Evidence:
  - `.../watchlist/ModifyWatchlistResponseParser.smali`
  - `.../watchlist/WatchlistQueueProcessor.smali`
  - `.../watchlist/WatchlistQueuedActionConfig.smali`
