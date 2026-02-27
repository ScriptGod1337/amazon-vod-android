# Prime Video Watchlist API: Results + Usage Guide

This document summarizes the reverse-engineered watchlist API behavior from Prime Video Android `3.0.438.2347` and shows how to use it with raw HTTP requests.

Source analyzed:
- `/home/scriptgod/Documents/Projects/amazon-vod-noads/analysis/decompiled/prime-3.0.438.2347-smali`

## 1) What was confirmed

### Watchlist browse endpoints
- Initial page:
  - `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/initial/v1.kt`
- Next pages:
  - `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/next/v1.kt`

### Watchlist modify endpoints
- Add item:
  - `GET /cdp/discovery/AddTitleToList`
  - Required: `titleId=<ASIN>`, `version=2`
- Remove item:
  - `GET /cdp/discovery/RemoveTitleFromList`
  - Required: `titleId=<ASIN>`, `version=2`

## 2) Required auth and request shape

### Required header
- `Authorization: Bearer <access_token>`

### Important headers
- `Accept: application/json`
- `x-atv-page-type: ATVWatchlist`

### Core query params commonly expected
- `format=json`
- `deviceTypeID=<device type>`
- `deviceId=<device id>`
- `firmware=<fmw string>`
- `clientName=<client name>`
- `priorityLevel=2`
- `swiftPriorityLevel=critical`
- `osLocale=<locale>`
- `uxLocale=<locale>`
- `softwareVersion=<major version>`
- `version=1` for browse transforms
- `version=2` for add/remove endpoints

## 3) Watchlist browse parameters

The app adds static watchlist parameters (from `WatchlistConfig`), notably:
- `featureScheme=mobile-android-features-v9`
- `supportsLiveBadging=true`
- `isWatchModalEnabled=<bool>`
- `isLiveEventsV2OverrideEnabled=true`
- `isPlaybackEnvelopeSupported=<bool>`
- `useMessagePresentationV2=<bool>`

Common page context keys used:
- `pageType=watchlist`
- `pageId=Watchlist` (or current nav page ID depending flow)

## 4) Pagination behavior

- First call uses `.../watchlist/initial/v1.kt`
- Response includes a `paginationModel` with `parameters`
- Next calls use `.../watchlist/next/v1.kt`
- Replay `paginationModel.parameters` into query for next page
- Include `pageSize` (default app value: `20`)

## 5) How to use the API (step-by-step)

### Step 1: Set environment variables
```bash
export ATV="https://atv-ps.amazon.com"
export ACCESS_TOKEN="..."
export DEVICE_TYPE_ID="A43PXU4ZN2AL1"
export DEVICE_ID="..."
export FIRMWARE="fmw:34-app:3.0.438.2347"
export CLIENT_NAME="android-avod"
export LOCALE="en_US"
export SOFTWARE_VERSION="438"
```

### Step 2: Browse watchlist (first page)
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
  --data-urlencode "softwareVersion=$SOFTWARE_VERSION" \
  --data-urlencode "priorityLevel=2" \
  --data-urlencode "swiftPriorityLevel=critical" \
  --data-urlencode "featureScheme=mobile-android-features-v9" \
  --data-urlencode "pageType=watchlist" \
  --data-urlencode "pageId=Watchlist"
```

### Step 3: Fetch next pages
- Read `paginationModel.parameters` from previous response.
- Call `.../watchlist/next/v1.kt` with those params.

Example:
```bash
curl -sS -G "$ATV/cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/next/v1.kt" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "format=json" \
  --data-urlencode "version=1" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "startIndex=$START_INDEX" \
  --data-urlencode "serviceToken=$SERVICE_TOKEN" \
  --data-urlencode "pageSize=20"
```

Also forward any extra keys found in pagination params (`swiftId`, `pageType`, `pageId`, etc.).

### Step 4: Add an item
```bash
curl -sS -G "$ATV/cdp/discovery/AddTitleToList" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "titleId=$ASIN" \
  --data-urlencode "version=2" \
  --data-urlencode "format=json" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID"
```

### Step 5: Remove an item
```bash
curl -sS -G "$ATV/cdp/discovery/RemoveTitleFromList" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-type: ATVWatchlist" \
  --data-urlencode "titleId=$ASIN" \
  --data-urlencode "version=2" \
  --data-urlencode "format=json" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID"
```

## 6) Practical automation notes

- Treat HTTP `2xx` as success for add/remove.
- Implement your own retry/backoff for network errors.
- For watchlist sync:
  1. Fetch initial page.
  2. Keep requesting next pages until no pagination params remain.
  3. Build a set of `ASIN`s from all pages.

## 7) Evidence pointers (decompiled)

- Browse transform names:
  - `.../watchlist/cache/WatchlistPageCache$Companion.smali`
- Add/remove endpoints + params:
  - `.../watchlist/service/ModifyWatchlistServiceClient.smali`
- Global request params + headers:
  - `.../http/ATVRequestBuilder.smali`
- Watchlist static request params:
  - `.../watchlist/WatchlistConfig.smali`
- Pagination parameter forwarding:
  - `.../page/pagination/CollectionPaginationRequest.smali`
