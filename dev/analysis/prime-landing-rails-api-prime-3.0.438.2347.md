# Prime Landing Rails API (Prime Video Android `3.0.438.2347`)

Scope: Prime tab landing page rails such as `Trending`, `Popular`, `Recommended TV`, and country-scoped `Top 10`.

Source analyzed:
- `/home/scriptgod/Documents/Projects/amazon-vod-noads/analysis/decompiled/prime-3.0.438.2347-smali`

## 1) Confirmed network paths

### Landing initial (Prime tab page load)
- `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/landing/initial/v2.kt`
- Fallback variant: `.../dv-android/landing/initial/v1.kt`

### Landing next (page-level pagination of more rails)
- `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/landing/next/v2.kt`
- Fallback variant: `.../dv-android/landing/next/v1.kt`

### Carousel next (collection-level pagination inside a rail)
- `GET /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/carousel/next/v2.kt`
- Fallback variant: `.../dv-android/carousel/next/v1.kt`
- Legacy fallback path also exists in code: `/cdp/mobile/getDataByTransform/v1//carousel/carouselNext/v1.js`

## 2) Prime tab page context and filter

Prime tab builds page context as:
- `pageType=home`
- `pageId=home`
- `serviceToken=<base64 json>`
- headers: `x-atv-page-id=home`, `x-atv-page-type=<HOME reportable string>`

Service token payload (before base64):
- `{"type":"lib","nav":false,"filter":{"OFFER_FILTER":["PRIME"]}}`

Encoded value used by app:
- `eyJ0eXBlIjoibGliIiwibmF2IjpmYWxzZSwiZmlsdGVyIjp7Ik9GRkVSX0ZJTFRFUiI6WyJQUklNRSJdfX0=`

## 3) How Trending/Popular/Recommended TV/Top 10 are represented

These rails are server-driven collections in landing response.

- Top-level landing response includes `collections`.
- Each rail is a `CollectionModel` with:
  - `headerText` (rail title shown in UI)
  - `subHeaderText`
  - `collectionItemList` (cards)
  - optional `paginationModel` (for that rail)

Important: no hardcoded strings for `Trending TV`, `Popular TV`, `Recommended TV`, or `Top 10` rail headers were found in app code. The labels come from server response data.

## 4) Top 10 country signal (title-level badge)

Top-10 state is also exposed as a title metadata badge in each card's message presentation:

- Card JSON path: `messagePresentation.titleMetadataBadgeSlot[]`
- Badge object fields: `text`, `itemId`, `level`, `type`
- `itemId=charts_top_10_badge` maps to app enum `TOP_10_BADGE`
- Related trending badge: `itemId=trending_titles_badge` maps to `TRENDING_BADGE`

Implication: "Top 10 in your country" is not a separate dedicated endpoint; it is represented in the same landing payload/rails plus title badges.

## 5) Top 10 rail type signal (collection-level)

For V3 collection models, the rail can also be identified by container metadata:

- Collection JSON field: `containerMetadata`
- Container type JSON field: `containerMetadata.type`
- Top-10/chart rails use `type="Charts"` (`ContainerType.CHARTS`)
- UI mapping maps `CHARTS` -> `CHART_CAROUSEL`

## 6) Pagination model semantics

### Page-level pagination (more rails on page)
- Source: top-level `paginationModel` (type `PaginationModelV2`) from landing response.
- Key fields used:
  - `id` -> sent as `swiftId`
  - `startIndex`
  - `pageContext` (its parameters/headers are replayed)
- Request adds `pageSize` (app uses configured scheme; default scheme is `[2,5,10,20,40]`).

### Rail-level pagination (more items inside one rail)
- Source: collection `paginationModel` (type `PaginationModel`).
- Key fields used:
  - `apiPath` (logical API target)
  - `parameters` map (includes rail/page context like `swiftId`, `pageType`, `pageId`, `serviceToken`, `startIndex`, etc.)
- App sends those parameters plus `pageSize` and capability flags via carousel-next endpoint.

## 7) Add/remove behavior for these rails

No dedicated "add/remove rail element" API was found for Prime landing rails themselves.

- Rails and ordering are server-controlled.
- Title-level actions (for example watchlist add/remove) are separate APIs and not specific to these rails.
- Use existing watchlist docs for add/remove (`dev/analysis/watchlist-api-usage.md`).

## 8) Evidence pointers (decompiled)

- Prime page context + service token:
  - `smali_classes5/com/amazon/avod/client/activity/PrimeTabActivity.smali`
- Landing initial/next path selection:
  - `smali_classes5/com/amazon/avod/config/switchblade/SwitchbladeServiceConfig.smali`
  - `smali_classes6/com/amazon/avod/discovery/landing/LandingPageServiceClient.smali`
  - `smali_classes6/com/amazon/avod/discovery/landing/LandingCollectionsServiceClient.smali`
- Switchblade base path:
  - `smali_classes6/com/amazon/avod/core/remotetransform/SwitchbladeTransformRequestFactory.smali`
- Collection model fields (`headerText`, `subHeaderText`, `collectionItemList`, `paginationModel`):
  - `smali_classes6/com/amazon/avod/discovery/collections/CollectionModelBuilder.smali`
- V3 collection + container metadata:
  - `smali_classes6/com/amazon/avod/discovery/collections/CollectionModelV3.smali`
  - `smali_classes6/com/amazon/avod/discovery/collections/container/ContainerMetadata.smali` (`@JsonProperty("type")`)
- Container type enum includes `Charts`:
  - `smali_classes6/com/amazon/avod/discovery/collections/container/ContainerType.smali`
- Container-to-view mapping includes `CHARTS` -> `CHART_CAROUSEL`:
  - `smali_classes6/com/amazon/avod/discovery/viewcontrollers/ViewController$ViewType.smali`
  - `smali_classes6/com/amazon/avod/discovery/landing/CollectionViewModel$WhenMappings.smali`
- Card message-presentation JSON field:
  - `smali_classes6/com/amazon/avod/core/CoverArtTitleModel$Builder.smali` (`@JsonProperty("messagePresentation")`)
- Message-presentation badge slot JSON field:
  - `smali_classes6/com/amazon/avod/discovery/collections/MessagePresentationModel.smali` (`@JsonProperty("titleMetadataBadgeSlot")`)
- Badge slot fields (`text`, `itemId`, `level`, `type`):
  - `smali_classes6/com/amazon/avod/discovery/collections/TitleMetadataBadgeSlotModel.smali`
- Top 10 / Trending badge IDs:
  - `smali_classes6/com/amazon/avod/discovery/collections/TitleMetadataBadgeItemId.smali`
- Page-level pagination model fields (`id`, `startIndex`, `pageContext`):
  - `smali_classes7/com/amazon/avod/page/pagination/PaginationModelV2.smali`
- Rail-level carousel pagination path + request mode:
  - `smali_classes10/com/amazon/avod/widget/carousel/cache/CarouselNetworkRetriever.smali`
  - `smali_classes5/com/amazon/avod/config/CarouselNextConfig.smali`
