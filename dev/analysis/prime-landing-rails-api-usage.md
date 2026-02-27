# Prime Landing Rails API: Usage Guide (Raw Requests)

This is a practical guide to browse Prime tab rails (`Trending`, `Popular`, `Recommended TV`, `Top 10 in your country`) from Prime Video Android `3.0.438.2347` behavior.

## 1) Prerequisites

Set your runtime values:

```bash
export ATV="https://atv-ps.primevideo.com"
export ACCESS_TOKEN="..."
export DEVICE_TYPE_ID="..."
export DEVICE_ID="..."
export FIRMWARE="fmw:34-app:3.0.438.2347"
export CLIENT_NAME="android-avod"
export LOCALE="en_US"
export SOFTWARE_VERSION="438"
```

Prime tab service token used by app:

```bash
export PRIME_SERVICE_TOKEN='eyJ0eXBlIjoibGliIiwibmF2IjpmYWxzZSwiZmlsdGVyIjp7Ik9GRkVSX0ZJTFRFUiI6WyJQUklNRSJdfX0='
```

## 2) Initial Prime landing request

Try `v2` first (fallback to `v1` if needed):

```bash
curl -sS -G "$ATV/cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/landing/initial/v2.kt" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  -H "x-atv-page-id: home" \
  -H "x-atv-page-type: <HOME_REPORTABLE_STRING>" \
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
  --data-urlencode "pageType=home" \
  --data-urlencode "pageId=home" \
  --data-urlencode "serviceToken=$PRIME_SERVICE_TOKEN"
```

`<HOME_REPORTABLE_STRING>` is the reportable string for `PageType.HOME` used by the app.

## 3) Find Trending/Popular/Recommended TV/Top 10 rails

Rails are server-provided collections. Filter by `headerText`:

```bash
jq '.collections[] | {headerText, subHeaderText, collectionId, hasPagination:(.paginationModel!=null)}'
```

Example filter:

```bash
jq '.collections[]
  | select(((.headerText // "") | ascii_downcase | test("trending|popular|recommended|top\\s*10")))
  | {headerText, collectionId, itemCount:(.collectionItemList|length)}'
```

Top-10 can also be detected by title badges in cards (more stable than localized header text):

```bash
jq '.collections[]
  | select(any(.collectionItemList[]?;
      ((.messagePresentation.titleMetadataBadgeSlot // [])
       | any(.itemId == "charts_top_10_badge"))))
  | {headerText, collectionId}'
```

Inspect actual top-10-badged titles:

```bash
jq '.. | objects
  | select(.messagePresentation? != null)
  | select((.messagePresentation.titleMetadataBadgeSlot // [])
      | any(.itemId == "charts_top_10_badge"))
  | {title, asin, badges:.messagePresentation.titleMetadataBadgeSlot}'
```

`itemId="trending_titles_badge"` is the similar marker for trending.

Collection-level signal (V3 collections): `containerMetadata.type == "Charts"`.

```bash
jq '.collections[]
  | select(.containerMetadata.type? == "Charts")
  | {headerText, collectionId, containerType:.containerMetadata.type}'
```

## 4) Country behavior for Top 10

No dedicated `top10` endpoint or explicit `country` query parameter was found for Prime landing rails in this app version.

- Country targeting is server-side (account/profile marketplace + region context).
- Keep locale/device context consistent (`osLocale`, `uxLocale`, `deviceTypeID`, `deviceId`, auth profile).
- In pagination requests, replay all parameters returned by `pageContext.parameters`.

## 5) Page-level pagination (load more rails)

From response, read top-level:
- `.paginationModel.id` => `swiftId`
- `.paginationModel.startIndex`
- `.paginationModel.pageContext.parameters` (replay these)

Call next endpoint:

```bash
curl -sS -G "$ATV/cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/landing/next/v2.kt" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  --data-urlencode "format=json" \
  --data-urlencode "version=1" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "swiftId=$SWIFT_ID" \
  --data-urlencode "startIndex=$START_INDEX" \
  --data-urlencode "pageSize=20" \
  --data-urlencode "pageType=home" \
  --data-urlencode "pageId=home" \
  --data-urlencode "serviceToken=$PRIME_SERVICE_TOKEN"
```

Also forward any extra keys present in `pageContext.parameters`.

## 6) Rail-level pagination (more items in one rail)

For a chosen collection with `.paginationModel`:
- Take all keys from `.paginationModel.parameters`
- Send them to carousel-next endpoint
- Include `startIndex`, `pageSize`, `swiftPriorityLevel`

```bash
curl -sS -G "$ATV/cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/carousel/next/v2.kt" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json" \
  --data-urlencode "format=json" \
  --data-urlencode "version=1" \
  --data-urlencode "deviceTypeID=$DEVICE_TYPE_ID" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "swiftPriorityLevel=critical" \
  --data-urlencode "pageSize=20" \
  --data-urlencode "startIndex=$START_INDEX" \
  --data-urlencode "swiftId=$SWIFT_ID" \
  --data-urlencode "pageType=home" \
  --data-urlencode "pageId=home" \
  --data-urlencode "serviceToken=$PRIME_SERVICE_TOKEN"
```

If your model includes additional keys (for example `journeyIngressContext`, `osLocale`, `featureScheme`), forward them as well.

## 7) Add/remove note

There is no dedicated API to add/remove rails on the Prime landing page.

- Rails are server-composed.
- Title actions (like watchlist add/remove) are separate endpoints.
- Use `dev/analysis/watchlist-api-usage.md` for watchlist mutations.
