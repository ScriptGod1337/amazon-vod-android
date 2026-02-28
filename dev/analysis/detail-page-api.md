# Detail Page API Analysis

## Endpoint

`GET {ATV_URL}/cdp/mobile/getDataByTransform/v1/android/atf/v3.jstl?{BASE_PARAMS}&itemId={ASIN}&capabilities=`

This is the same endpoint used by `getDetailPage()` in `AmazonApiService`, already working.

---

## MOVIE Response (`resource.*`)

All rich metadata is directly in `resource`:

| Field | Type | Example |
|-------|------|---------|
| `titleId` | String | `amzn1.dv.gti.e7cf295a-...` |
| `title` | String | `Monster Summer` |
| `contentType` | String | `MOVIE` |
| `synopsis` | String | Full description text |
| `detailPageHeroImageUrl` | String | Full-width backdrop image (16:9) |
| `titleImageUrl` | String | Poster image |
| `titleImageUrl169` | String | 16:9 poster variant |
| `releaseDate` | Long | Unix timestamp in ms |
| `runtimeSeconds` | Int | 5918 |
| `genres` | Array | `["Adventure", "Fantasy", "Horror", "Thriller"]` (skip entries with ">") |
| `directors` | Array | `["David Henrie"]` |
| `amazonMaturityRating` | String | `13+` |
| `regulatoryRating` | String | `12` (German FSK) |
| `imdbRating` | Float | `5.7` |
| `imdbRatingCount` | Int | `7138` |
| `isInWatchlist` | Boolean | `true` |
| `isTrailerAvailable` | Boolean | `true` |
| `badges.uhd` | Boolean | `true` |
| `badges.hdr` | Boolean | `true` |
| `badges.dolby51` | Boolean | `true` |
| `badges.prime` | Boolean | `true` |
| `timecodeSeconds` | Int | `0` (resume position) |
| `playbackActions[0].playbackId` | String | `B0FF5GCGJ3` (offer ASIN) |
| `playbackActions[0].videoMaterialType` | String | `Feature` |

**Not available**: Cast list — not in this endpoint.

---

## SEASON Response (`resource.selectedSeason.*`)

When called with a SEASON ASIN, `resource` itself only contains:
- `resource.show` — `{titleId, title}` of the parent show
- `resource.seasons[]` — season list: `{titleId, title, displayText, seasonNumber, badges}`
- `resource.selectedSeason` — full metadata (same fields as MOVIE above)
- `resource.episodes[]` — episode array with `playbackActions`

Rich metadata (synopsis, imdbRating, genres, heroImageUrl, etc.) is in **`resource.selectedSeason`**.

---

## Trailer Playback

`POST {ATV_URL}/cdp/catalog/GetPlaybackResources?asin={GTI_ASIN}&...&videoMaterialType=Trailer`

- Works with the same content GTI ASIN used for Feature playback
- `isTrailerAvailable: true` flag tells whether a trailer exists
- Returns DASH manifest URL at `playbackUrls.urlSets[defaultUrlSetId].urls.manifest.url`
- Trailers may not require DRM (tested: returns manifest without widevine challenge)

---

## `dv-android/detail/v2/user/v2.5.js` — BROKEN

This endpoint returns HTTP 500 for our account/territory. Do not use.

---

## Implementation Notes

- `genres` array contains sub-genre entries like `"Thriller > Mystery"` — filter them out (skip entries containing `>`).
- `releaseDate` is Unix ms timestamp → `Calendar.getInstance().apply { timeInMillis = date }.get(YEAR)`.
- `imdbRating = 0.0` or `null` for content with no IMDb data — always null-check before display.
- GTI-format ASIN works for both `videoMaterialType=Feature` and `videoMaterialType=Trailer`.
