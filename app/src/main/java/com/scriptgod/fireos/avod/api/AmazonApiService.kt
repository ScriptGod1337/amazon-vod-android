package com.scriptgod.fireos.avod.api

import android.util.Log
import com.scriptgod.fireos.avod.auth.AmazonAuthService
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.PlaybackInfo
import com.scriptgod.fireos.avod.model.SubtitleTrack
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Catalog browsing and stream manifest fetching.
 * Mirrors android_api.py and network.py (GetPlaybackResources).
 */
class AmazonApiService(private val authService: AmazonAuthService) {

    data class TerritoryInfo(val atvUrl: String, val marketplaceId: String, val sidomain: String, val lang: String)

    companion object {
        private const val TAG = "AmazonApiService"

        // Territory → TerritoryInfo mapping (api-map.md, Kodi login.py:55-78)
        private val TERRITORY_MAP = mapOf(
            "A1PA6795UKMFR9" to TerritoryInfo("https://atv-ps-eu.amazon.de", "A1PA6795UKMFR9", "amazon.de", "de_DE"),           // DE
            "A1F83G8C2ARO7P" to TerritoryInfo("https://atv-ps-eu.amazon.co.uk", "A1F83G8C2ARO7P", "amazon.co.uk", "en_US"),     // UK
            "ATVPDKIKX0DER"  to TerritoryInfo("https://atv-ps.amazon.com", "ATVPDKIKX0DER", "amazon.com", "en_US"),             // US
            "A1VC38T7YXB528" to TerritoryInfo("https://atv-ps-fe.amazon.co.jp", "A1VC38T7YXB528", "amazon.co.jp", "ja_JP"),     // JP
            "A3K6Y4MI8GDYMT" to TerritoryInfo("https://atv-ps-eu.primevideo.com", "A3K6Y4MI8GDYMT", "amazon.com", "en_US"),     // PV EU
            "A2MFUE2XK8ZSSY" to TerritoryInfo("https://atv-ps-eu.primevideo.com", "A2MFUE2XK8ZSSY", "amazon.com", "en_US"),     // PV EU Alt
            "A15PK738MTQHSO" to TerritoryInfo("https://atv-ps-fe.primevideo.com", "A15PK738MTQHSO", "amazon.com", "en_US"),     // PV FE
            "ART4WZ8MWBX2Y"  to TerritoryInfo("https://atv-ps.primevideo.com", "ART4WZ8MWBX2Y", "amazon.com", "en_US")          // PV US
        )

        // Fallback US defaults
        private const val DEFAULT_ATV_URL = "https://atv-ps.amazon.com"
        private const val DEFAULT_MARKETPLACE_ID = "ATVPDKIKX0DER"

        // Android API catalog parameters (android_api.py:40-53)
        private const val FIRMWARE = "fmw:22-app:3.0.351.3955"
        private const val SOFTWARE_VERSION = "351"
        private const val SCREEN_WIDTH = "sw1600dp"
        private const val FEATURE_SCHEME = "mobile-android-features-v13-hdr"

        /** Content type helpers — usable from Activities without an instance */
        fun isMovieContentType(ct: String) =
            ct.equals("Feature", true) || ct.equals("Movie", true)
        fun isSeriesContentType(ct: String) =
            ct.equals("Series", true) || ct.equals("Season", true) ||
            ct.equals("Show", true) || ct.equals("TVSeason", true) || ct.equals("TVSeries", true)
        fun isEpisodeContentType(ct: String) =
            ct.equals("Episode", true) || ct.equals("TVEpisode", true)
        fun isPlayableType(ct: String) =
            isMovieContentType(ct) || isEpisodeContentType(ct)
    }

    private val client: OkHttpClient = authService.buildAuthenticatedClient()
    private val gson = Gson()
    private val emptyBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())

    // Territory-resolved values, populated by detectTerritory()
    @Volatile private var atvUrl: String = DEFAULT_ATV_URL
    @Volatile private var marketplaceId: String = DEFAULT_MARKETPLACE_ID
    @Volatile private var sidomain: String = "amazon.com"
    @Volatile private var lang: String = "en_US"
    @Volatile private var territoryDetected: Boolean = false

    /**
     * Detects the user's territory by calling GetAppStartupConfig (login.py:55-78).
     * 3-layer detection matching Kodi logic:
     *  1. avMarketplace found in TERRITORY_MAP → use preset
     *  2. avMarketplace + defaultVideoWebsite + homeRegion → construct dynamically
     *  3. No marketplace but have defaultVideoWebsite → construct from URL alone
     *  4. Fallback: keep US defaults
     * Sets atvUrl, marketplaceId, sidomain and lang. Pushes sidomain to authService.
     * Must be called before any catalog/playback calls.
     */
    fun detectTerritory() {
        val did = authService.getDeviceId()
        val locales = "da_DK,de_DE,en_US,en_GB,es_ES,fr_FR,it_IT,ja_JP,ko_KR," +
                      "nl_NL,pl_PL,pt_BR,pt_PT,ru_RU,sv_SE,tr_TR,zh_CN,zh_TW"
        val url = "https://atv-ps.amazon.com/cdp/usage/v3/GetAppStartupConfig" +
                  "?deviceTypeID=${AmazonAuthService.DEVICE_TYPE_ID}" +
                  "&deviceID=$did" +
                  "&firmware=1&version=1&supportedLocales=$locales&format=json"
        try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                val json = gson.fromJson(body, JsonObject::class.java)
                val territoryConfig = json?.getAsJsonObject("territoryConfig")
                val customerConfig = json?.getAsJsonObject("customerConfig")
                val marketplace = territoryConfig
                    ?.getAsJsonPrimitive("avMarketplace")?.asString
                val defaultVideoWebsite = territoryConfig
                    ?.getAsJsonPrimitive("defaultVideoWebsite")?.asString
                // homeRegion is under customerConfig (Kodi login.py:70)
                val homeRegion = customerConfig
                    ?.getAsJsonPrimitive("homeRegion")?.asString
                val uxLocale = customerConfig
                    ?.getAsJsonObject("locale")
                    ?.getAsJsonPrimitive("uxLocale")?.asString

                if (!marketplace.isNullOrEmpty()) {
                    val territory = TERRITORY_MAP[marketplace]
                    if (territory != null) {
                        // Layer 1: known marketplace in map
                        atvUrl = territory.atvUrl
                        marketplaceId = territory.marketplaceId
                        sidomain = territory.sidomain
                        lang = territory.lang
                    } else if (!defaultVideoWebsite.isNullOrEmpty()) {
                        // Layer 2: unknown marketplace but have website + region
                        val dynamic = buildDynamicTerritory(defaultVideoWebsite, homeRegion)
                        atvUrl = dynamic.atvUrl
                        marketplaceId = marketplace
                        sidomain = dynamic.sidomain
                    }
                } else if (!defaultVideoWebsite.isNullOrEmpty()) {
                    // Layer 3: no marketplace but have website
                    val dynamic = buildDynamicTerritory(defaultVideoWebsite, homeRegion)
                    atvUrl = dynamic.atvUrl
                    sidomain = dynamic.sidomain
                }

                // Override lang from uxLocale if it looks like a valid locale (xx_XX)
                if (!uxLocale.isNullOrEmpty() && uxLocale.matches(Regex("[a-z]{2}_[A-Z]{2}"))) {
                    lang = uxLocale
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Territory detection failed: ${e.message}")
        }
        authService.setSiDomain(sidomain)
        Log.w(TAG, "Territory: atvUrl=$atvUrl marketplace=$marketplaceId sidomain=$sidomain lang=$lang")
        territoryDetected = true
    }

    /**
     * Constructs territory info dynamically from defaultVideoWebsite and homeRegion.
     * Mirrors Kodi login.py:69-75.
     */
    private fun buildDynamicTerritory(defaultVideoWebsite: String, homeRegion: String?): TerritoryInfo {
        val regionSuffix = when (homeRegion) {
            "EU" -> "-eu"
            "FE" -> "-fe"
            else -> ""
        }
        val atvUrl = defaultVideoWebsite
            .replace("www.", "")
            .replace("://", "://atv-ps$regionSuffix.")
        val host = android.net.Uri.parse(defaultVideoWebsite).host ?: "amazon.com"
        val sd = if (host.contains("primevideo")) "amazon.com" else host.removePrefix("www.")
        return TerritoryInfo(atvUrl = atvUrl, marketplaceId = "", sidomain = sd, lang = "en_US")
    }

    private fun deviceId() = authService.getDeviceId()

    // --- Catalog params (android_api.py:40-53) ---
    private fun baseParams(): String {
        val did = deviceId()
        return "deviceTypeID=${AmazonAuthService.DEVICE_TYPE_ID}" +
               "&firmware=$FIRMWARE" +
               "&softwareVersion=$SOFTWARE_VERSION" +
               "&priorityLevel=2" +
               "&format=json" +
               "&featureScheme=$FEATURE_SCHEME" +
               "&deviceID=$did" +
               "&version=1" +
               "&screenWidth=$SCREEN_WIDTH" +
               "&osLocale=$lang" +
               "&uxLocale=$lang" +
               "&supportsPKMZ=false" +
               "&isLiveEventsV2OverrideEnabled=true" +
               "&swiftPriorityLevel=critical" +
               "&supportsCategories=true"
    }

    /**
     * GET switchblade (p=2) catalog page (android_api.py:108-120).
     * Returns list of ContentItems parsed from the JSON response.
     */
    fun getHomePage(): List<ContentItem> {
        // Home page uses switchblade with pageType/pageId (android_api.py:108-128)
        return getSwitchbladePage("dv-android/landing/initial/v1.kt", "pageType=home&pageId=home")
    }

    /**
     * Content category filter (Phase 7).
     */
    enum class ContentCategory { ALL, PRIME, MOVIES, SERIES }

    /** Library content type filter (Phase 10). */
    enum class LibraryFilter { ALL, MOVIES, TV_SHOWS }

    /** Library sort order (Phase 10). */
    enum class LibrarySort { DATE_ADDED, TITLE_AZ, TITLE_ZA }

    /**
     * Primary search entry point for Phase 7. Handles all content categories:
     * - ALL/PRIME/FREEVEE/LIVE: search then filter client-side
     * - CHANNELS: browse channels category
     * When query is empty, returns category browse page.
     */
    fun getCategoryContent(category: ContentCategory, query: String = ""): List<ContentItem> {
        val items = if (query.isNotEmpty()) getSearchPage(query) else getHomePage()
        Log.i(TAG, "getCategoryContent($category): ${items.size} items")
        return when (category) {
            ContentCategory.ALL -> items
            ContentCategory.PRIME -> items.filter { it.isPrime }
            ContentCategory.MOVIES -> items.filter { isMovieType(it.contentType) }
            ContentCategory.SERIES -> items.filter { isSeriesType(it.contentType) }
        }
    }

    private fun isMovieType(contentType: String): Boolean =
        contentType.equals("Feature", ignoreCase = true) ||
        contentType.equals("Movie", ignoreCase = true)

    private fun isSeriesType(contentType: String): Boolean =
        contentType.equals("Series", ignoreCase = true) ||
        contentType.equals("Season", ignoreCase = true) ||
        contentType.equals("Show", ignoreCase = true) ||
        contentType.equals("TVSeason", ignoreCase = true) ||
        contentType.equals("TVSeries", ignoreCase = true)

    private fun isEpisodeType(contentType: String): Boolean =
        contentType.equals("Episode", ignoreCase = true) ||
        contentType.equals("TVEpisode", ignoreCase = true)

    /** Kodi catalog device type IDs (network.py:252-255) */
    private val CATALOG_DEVICE_TYPE_IDS = listOf(
        "firmware=fmw:28-app:5.2.3&deviceTypeID=A3SSWQ04XYPXBH",
        "firmware=fmw:26-app:3.0.265.20347&deviceTypeID=A1S15DUFSI8AUG",
        "firmware=default&deviceTypeID=A1FYY15VCM5WG1"
    )

    /**
     * Returns Freevee (free ad-supported) content.
     * Uses catalog Browse with OfferGroups=B0043YVHMY for server-side filtering.
     * Falls back to home page with client-side filter for non-Prime content.
     * Note: Freevee may not be available in all territories (e.g. DE).
     */
    fun getFreeveePage(): List<ContentItem> {
        // Primary: catalog Browse with OfferGroups (works in US/UK territories)
        val catalogItems = getCatalogBrowse("OfferGroups=B0043YVHMY&NumberOfResults=40&StartIndex=0&Detailed=T")
        if (catalogItems.isNotEmpty()) {
            Log.i(TAG, "Freevee via catalog Browse: ${catalogItems.size} items")
            return catalogItems
        }

        // Freevee not available in this territory
        Log.i(TAG, "Freevee catalog not available in this territory")
        return emptyList()
    }

    /**
     * Fetches content via the catalog Browse endpoint (Kodi: network.py:242-286).
     * Uses Kodi-style device TypeIDs and response format (message.body.titles[]).
     * Tries multiple device TypeIDs until results are found.
     */
    private fun getCatalogBrowse(query: String): List<ContentItem> {
        val did = deviceId()
        for (typeId in CATALOG_DEVICE_TYPE_IDS) {
            val parameter = "$typeId&deviceID=$did&format=json&version=2&formatVersion=3&marketplaceId=$marketplaceId"
            val url = "$atvUrl/cdp/catalog/Browse?$parameter&IncludeAll=T&AID=1&$query"

            val request = Request.Builder().url(url).get().build()
            Log.i(TAG, "Catalog Browse GET: $url")
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: continue

                if (!response.isSuccessful) {
                    Log.i(TAG, "Catalog Browse failed: HTTP ${response.code}, trying next TypeID")
                    continue
                }

                val items = parseCatalogBrowseItems(body)
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                Log.i(TAG, "Catalog Browse error: ${e.message}, trying next TypeID")
            }
        }
        return emptyList()
    }

    /**
     * Parses the catalog Browse response format (Kodi: atv_api.py:312-370).
     * Structure: message.body.titles[] with titleId, title, contentType, heroUrl, formats[].images[].uri
     */
    private fun parseCatalogBrowseItems(json: String): List<ContentItem> {
        val items = mutableListOf<ContentItem>()
        try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val message = root?.getAsJsonObject("message") ?: return emptyList()
            val statusCode = message.safeString("statusCode")
            if (statusCode != "SUCCESS") {
                Log.i(TAG, "Catalog Browse status: $statusCode")
                return emptyList()
            }
            val bodyObj = message.getAsJsonObject("body") ?: return emptyList()
            val titles = bodyObj.getAsJsonArray("titles") ?: return emptyList()

            for (element in titles) {
                val item = element.asJsonObject ?: continue
                val asin = item.safeString("titleId") ?: continue
                val title = item.safeString("title") ?: continue
                val contentType = item.safeString("contentType") ?: "Feature"
                val subtitle = item.safeString("regulatoryRating") ?: ""

                // Image: formats[0].images[0].uri → heroUrl
                val imageUrl = try {
                    item.getAsJsonArray("formats")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonArray("images")
                        ?.get(0)?.asJsonObject
                        ?.safeString("uri")
                } catch (_: Exception) { null }
                    ?: item.safeString("heroUrl")
                    ?: ""

                items.add(ContentItem(
                    asin = asin,
                    title = title,
                    subtitle = subtitle,
                    imageUrl = imageUrl,
                    contentType = contentType,
                    isPrime = false,
                    isFreeWithAds = true,
                    isLive = false,
                    channelId = ""
                ))
            }
        } catch (e: Exception) {
            Log.i(TAG, "Error parsing catalog Browse response", e)
        }
        Log.i(TAG, "Catalog Browse parsed ${items.size} content items")
        return items
    }

    /**
     * Returns search suggestions by fetching search results and returning the first N titles.
     * Called on each keystroke (debounced 300ms in the UI).
     */
    fun getSearchSuggestions(query: String): List<String> {
        if (query.length < 2) return emptyList()
        return try {
            getSearchPage(query).take(8).map { it.title }
        } catch (e: Exception) {
            Log.w(TAG, "getSearchSuggestions failed", e)
            emptyList()
        }
    }

    fun getSearchPage(query: String): List<ContentItem> {
        val params = baseParams() + "&phrase=" + android.net.Uri.encode(query)
        return getMobilePage("dv-android/search/searchInitial/v3.js", params)
    }

    fun getWatchlistPage(): List<ContentItem> {
        return getWatchlistPageWithPagination("").first
    }

    /**
     * Paginated watchlist fetch using switchblade JVM transforms (watchlist-api-prime-3.0.438.2347.md).
     * Initial: /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/initial/v1.kt
     * Next:    /cdp/switchblade/android/getDataByJvmTransform/v1/dv-android/watchlist/next/v1.kt
     * Returns Pair(items, nextPageParams) where nextPageParams is empty when no more pages.
     */
    fun getWatchlistPageWithPagination(extraParams: String = ""): Pair<List<ContentItem>, String> {
        val isNextPage = extraParams.isNotEmpty()
        val transform = if (isNextPage) "dv-android/watchlist/next/v1.kt"
                         else "dv-android/watchlist/initial/v1.kt"

        val params = if (isNextPage) {
            // Next page: base params + pagination params + pageSize
            baseParams() + "&pageSize=20" + extraParams
        } else {
            // Initial page: base params + watchlist context
            baseParams() + "&pageType=watchlist&pageId=Watchlist"
        }

        val url = "$atvUrl/cdp/switchblade/android/getDataByJvmTransform/v1/$transform?$params"

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return Pair(emptyList(), "")

        if (!response.isSuccessful) {
            Log.w(TAG, "Watchlist request failed: HTTP ${response.code}, body=${body.take(300)}")
            return Pair(emptyList(), "")
        }

        val items = parseContentItems(body)

        // Extract paginationModel for next page
        val nextParams = extractPaginationParams(body)

        return Pair(items, nextParams)
    }

    /**
     * Extracts pagination parameters from a switchblade response.
     * paginationModel can be at root level, inside titles[0], or inside resource.
     */
    private fun extractPaginationParams(json: String): String {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
                ?.let { it.getAsJsonObject("resource") ?: it }

            // Safe getter for paginationModel — getAsJsonObject throws on JsonNull
            fun JsonObject.safePaginationModel(): JsonObject? {
                val el = get("paginationModel") ?: return null
                return if (el.isJsonObject) el.asJsonObject else null
            }

            // Try paginationModel at root, then inside titles[0]
            val pgModel = root?.safePaginationModel()
                ?: root?.getAsJsonArray("titles")
                    ?.takeIf { it.size() > 0 }
                    ?.get(0)?.asJsonObject
                    ?.safePaginationModel()

            if (pgModel == null) return ""

            val pgParams = pgModel.getAsJsonObject("parameters") ?: return ""

            // Replay all pagination parameters as query string
            val sb = StringBuilder()
            for ((key, value) in pgParams.entrySet()) {
                if (value.isJsonPrimitive) {
                    sb.append("&${android.net.Uri.encode(key)}=${android.net.Uri.encode(value.asString)}")
                }
            }
            val result = sb.toString()

            // Must have serviceToken to be a valid next page
            if (!result.contains("serviceToken")) "" else result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract pagination params", e)
            ""
        }
    }

    fun getLibraryPage(): List<ContentItem> {
        return getMobilePage("dv-android/library/libraryInitial/v2.js", baseParams())
    }

    /**
     * Paginated library fetch (Phase 10).
     * startIndex=0 → libraryInitial, startIndex>0 → libraryNext.
     * Client-side filter by contentType and sort.
     */
    fun getLibraryPage(
        startIndex: Int,
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.DATE_ADDED
    ): List<ContentItem> {
        val transform = if (startIndex > 0)
            "dv-android/library/libraryNext/v2.js"
        else
            "dv-android/library/libraryInitial/v2.js"

        val params = baseParams() +
            (if (startIndex > 0) "&startIndex=$startIndex" else "")

        val items = getMobilePage(transform, params)

        // Client-side filter by content type
        val filtered = when (filter) {
            LibraryFilter.ALL -> items
            LibraryFilter.MOVIES -> items.filter {
                it.contentType.equals("Feature", ignoreCase = true) ||
                it.contentType.equals("Movie", ignoreCase = true)
            }
            LibraryFilter.TV_SHOWS -> items.filter {
                it.contentType.equals("Episode", ignoreCase = true) ||
                it.contentType.equals("Season", ignoreCase = true) ||
                it.contentType.equals("Series", ignoreCase = true) ||
                it.contentType.equals("Show", ignoreCase = true)
            }
        }

        // Client-side sort
        return when (sort) {
            LibrarySort.DATE_ADDED -> filtered // API default order
            LibrarySort.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            LibrarySort.TITLE_ZA -> filtered.sortedByDescending { it.title.lowercase() }
        }
    }

    fun getDetailPage(asin: String): List<ContentItem> {
        val params = baseParams() + "&itemId=$asin&capabilities="
        return getMobilePage("android/atf/v3.jstl", params)
    }

    private fun getSwitchbladePage(transform: String, extraParams: String = ""): List<ContentItem> {
        val params = baseParams() + if (extraParams.isNotEmpty()) "&$extraParams" else ""
        val url = "$atvUrl/cdp/switchblade/android/getDataByJvmTransform/v1/$transform?$params"
        return fetchAndParseContentItems(url)
    }

    private fun getMobilePage(transform: String, params: String): List<ContentItem> {
        val url = "$atvUrl/cdp/mobile/getDataByTransform/v1/$transform?$params"
        return fetchAndParseContentItems(url)
    }

    /**
     * Fetches a catalog page and parses content items from the JSON response.
     * Catalog calls use GET (android_api.py:137 has no postdata arg).
     * Only GetPlaybackResources uses POST with empty body.
     */
    private fun fetchAndParseContentItems(url: String): List<ContentItem> {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        Log.i(TAG, "Catalog GET: $url")
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            Log.i(TAG, "Catalog request failed: HTTP ${response.code} url=$url body=${body.take(300)}")
            return emptyList()
        }

        return parseContentItems(body)
    }

    /**
     * Parses content items from the Android API JSON response.
     * Navigates the collections/collectionItemList structure (android_api.py:108-300).
     */
    /**
     * Safe getter: getAsJsonPrimitive throws ClassCastException on JsonNull.
     * This returns null for both missing fields and null values.
     */
    private fun JsonObject.safeString(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    private fun JsonObject.safeBoolean(key: String): Boolean? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive) el.asBoolean else null
    }

    private fun parseContentItems(json: String): List<ContentItem> {
        val items = mutableListOf<ContentItem>()
        try {
            var root = gson.fromJson(json, JsonObject::class.java)
            // Unwrap 'resource' key — Kodi plugin: resp.get('resource', resp) (android_api.py:145)
            root = root?.getAsJsonObject("resource") ?: root

            // Collect all collectionItemList arrays from either format:
            // Home/Browse: collections[].collectionItemList (android_api.py:225-258)
            // Search/Detail: titles[0].collectionItemList (android_api.py:259-285)
            val allItemLists = mutableListOf<com.google.gson.JsonArray>()

            val collectionsArray = root?.getAsJsonArray("collections")
            if (collectionsArray != null) {
                for (collectionElement in collectionsArray) {
                    val collection = collectionElement?.asJsonObject ?: continue
                    val collectionList = collection.getAsJsonArray("collectionItemList") ?: continue
                    allItemLists.add(collectionList)
                }
            }

            // Search/browse results: titles[0].collectionItemList
            val titlesArray = root?.getAsJsonArray("titles")
            if (titlesArray != null && titlesArray.size() > 0) {
                val firstTitle = titlesArray[0]?.asJsonObject
                val collectionList = firstTitle?.getAsJsonArray("collectionItemList")
                if (collectionList != null) {
                    allItemLists.add(collectionList)
                }
            }

            // Switchblade watchlist/next: root-level collectionItemList
            val rootCollectionList = root?.getAsJsonArray("collectionItemList")
            if (rootCollectionList != null) {
                allItemLists.add(rootCollectionList)
            }

            // Also try dataWidgetModels as a flat item list
            val dataWidgets = root?.getAsJsonArray("dataWidgetModels")
            if (dataWidgets != null) {
                allItemLists.add(dataWidgets)
            }

            // Series detail format: { show: {}, seasons: [], episodes: [], selectedSeason: {} }
            // (android_api.py:186-197 — getPage('details'))
            val seasonsArray = root?.getAsJsonArray("seasons")
            if (seasonsArray != null) {
                allItemLists.add(seasonsArray)
            }
            val episodesArray = root?.getAsJsonArray("episodes")
            if (episodesArray != null) {
                allItemLists.add(episodesArray)
            }


            if (allItemLists.isEmpty()) {
                Log.i(TAG, "No parseable item lists in response keys: ${root?.keySet()?.take(10)}")
                return emptyList()
            }

            for (collectionList in allItemLists) {
                for (element in collectionList) {
                    val item = element.asJsonObject ?: continue
                    val model = item.getAsJsonObject("model") ?: item

                    // ASIN extraction: prefer linkAction.titleId for episode/detail items
                    // (episodes from detail page have titleId only inside linkAction)
                    val linkAction = model.getAsJsonObject("linkAction")
                    val asin = model.safeString("catalogId")
                        ?: model.safeString("compactGti")
                        ?: model.safeString("titleId")
                        ?: linkAction?.safeString("titleId")
                        ?: model.safeString("id")
                        ?: model.safeString("asin")
                        ?: continue
                    var title = model.safeString("title") ?: continue

                    // Build subtitle from available metadata
                    val seasonNum = model.safeString("seasonNumber")
                        ?: model.safeString("number")
                    val episodeNum = model.safeString("episodeNumber")
                    val subtitle = model.safeString("ratingsBadge")
                        ?: if (seasonNum != null && episodeNum != null) "S${seasonNum} E${episodeNum}"
                           else if (seasonNum != null) "Season $seasonNum"
                           else if (episodeNum != null) "Episode $episodeNum"
                           else ""

                    // Infer content type: season items from detail API lack contentType
                    // but have seasonNumber; episode items have episodeNumber
                    val contentType = model.safeString("contentType")
                        ?: if (seasonNum != null && episodeNum == null) "SEASON"
                           else if (episodeNum != null) "EPISODE"
                           else "Feature"

                    // Prefix season/episode info into title
                    if (contentType.equals("SEASON", ignoreCase = true) && seasonNum != null) {
                        title = "Season $seasonNum"
                    } else if (contentType.equals("EPISODE", ignoreCase = true) && episodeNum != null) {
                        title = "E$episodeNum: $title"
                    }

                    // Image extraction: try multiple sources
                    // Home/browse: model.image.url
                    // Search: model.titleImageUrls.{BOX_ART,COVER,POSTER} or model.heroImageUrl
                    val imageUrl = model.getAsJsonObject("image")
                        ?.safeString("url")
                        ?: model.safeString("imageUrl")
                        ?: model.getAsJsonObject("titleImageUrls")
                            ?.let { urls ->
                                urls.safeString("BOX_ART")
                                    ?: urls.safeString("COVER")
                                    ?: urls.safeString("POSTER")
                                    ?: urls.safeString("LEGACY")
                                    ?: urls.safeString("WIDE")
                            }
                        ?: model.safeString("heroImageUrl")
                        ?: model.safeString("titleImageUrl")
                        ?: model.safeString("imagePack")
                        ?: ""

                    val isPrime = model.getAsJsonObject("badges")
                        ?.safeBoolean("prime")
                        ?: model.safeBoolean("showPrimeEmblem")
                        ?: model.safeBoolean("isPrime")
                        ?: model.safeBoolean("primeOnly")
                        ?: run {
                            val badge = model.safeString("badgeInfo")
                                ?: model.safeString("contentBadge") ?: ""
                            !badge.contains("FREE", ignoreCase = true)
                        }

                    val isFreeWithAds = model.safeBoolean("isFreeWithAds")
                        ?: model.safeBoolean("freeWithAds")
                        ?: run {
                            val badge = model.safeString("badgeInfo")
                                ?: model.safeString("contentBadge") ?: ""
                            badge.contains("FREE", ignoreCase = true) || badge.contains("AVOD", ignoreCase = true)
                        }

                    val isLive = contentType.equals("live", ignoreCase = true)
                        || contentType.equals("LiveStreaming", ignoreCase = true)
                        || model.has("liveInfo")
                        || model.has("liveState")
                        || (model.safeString("videoMaterialType")
                            ?.equals("LiveStreaming", ignoreCase = true) ?: false)

                    val channelId = model.getAsJsonObject("playbackAction")
                        ?.safeString("channelId")
                        ?: model.getAsJsonObject("station")
                            ?.safeString("id")
                        ?: model.safeString("channelId")
                        ?: ""

                    items.add(ContentItem(
                        asin = asin,
                        title = title,
                        subtitle = subtitle,
                        imageUrl = imageUrl,
                        contentType = contentType,
                        isPrime = isPrime,
                        isFreeWithAds = isFreeWithAds,
                        isLive = isLive,
                        channelId = channelId
                    ))
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Error parsing catalog response", e)
        }
        val unique = items.distinctBy { it.asin }
        Log.w(TAG, "Parsed ${unique.size} content items (${items.size - unique.size} duplicates removed)")
        return unique
    }

    /**
     * Fetches stream manifest URL via GetPlaybackResources.
     * Mirrors network.py:185-228 and playback.py:374-408.
     *
     * @param asin The content ASIN to play
     * @param videoMaterialType Feature, Trailer, or Episode
     * @return PlaybackInfo with manifest and license URLs
     */
    fun getPlaybackInfo(asin: String, videoMaterialType: String = "Feature"): PlaybackInfo {
        val did = deviceId()
        val params = buildString {
            append("asin=").append(asin)
            append("&deviceTypeID=").append(AmazonAuthService.DEVICE_TYPE_ID)
            append("&firmware=1")
            append("&deviceID=").append(did)
            append("&marketplaceID=").append(marketplaceId)
            append("&format=json")
            append("&version=2")
            append("&gascEnabled=true")
            append("&resourceUsage=ImmediateConsumption")
            append("&consumptionType=Streaming")
            append("&deviceDrmOverride=CENC")
            append("&deviceStreamingTechnologyOverride=DASH")
            append("&deviceProtocolOverride=Https")
            append("&deviceBitrateAdaptationsOverride=CVBR%2CCBR")
            append("&audioTrackId=all")
            append("&languageFeature=MLFv2")
            append("&videoMaterialType=").append(videoMaterialType)
            append("&desiredResources=PlaybackUrls,SubtitleUrls,ForcedNarratives,TransitionTimecodes")
            append("&supportedDRMKeyScheme=DUAL_KEY")
            // Quality params (decisions.md Decision 9)
            append("&deviceVideoCodecOverride=H264")
            append("&deviceHdrFormatsOverride=None")
            append("&deviceVideoQualityOverride=HD")
        }

        val url = "$atvUrl/cdp/catalog/GetPlaybackResources?$params"
        Log.i(TAG, "GetPlaybackResources: asin=$asin materialType=$videoMaterialType")

        val request = Request.Builder()
            .url(url)
            .post(emptyBody)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
            ?: throw RuntimeException("Empty GetPlaybackResources response")

        if (!response.isSuccessful) {
            throw RuntimeException("GetPlaybackResources failed: HTTP ${response.code} — $body")
        }

        val manifestUrl = extractManifestUrl(body)
        if (manifestUrl == null) {
            // Check for rights errors (rent/buy-only titles)
            val noRights = body.contains("PRS.NoRights")
            if (noRights) {
                throw RuntimeException("This title requires purchase or rental — it is not included with Prime.")
            }
            throw RuntimeException("Could not extract manifest URL from response")
        }

        val licenseUrl = buildLicenseUrl(asin, did)
        val subtitles = extractSubtitleTracks(body)

        return PlaybackInfo(manifestUrl = manifestUrl, licenseUrl = licenseUrl, asin = asin, subtitleTracks = subtitles)
    }

    /**
     * Extracts the DASH manifest URL from GetPlaybackResources JSON.
     * Handles both new (playbackUrls) and legacy (audioVideoUrls) formats.
     * Mirrors playback.py:88-158 (_ParseStreams).
     */
    private fun extractManifestUrl(json: String): String? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)

            // New format: playbackUrls.urlSets[defaultUrlSetId].urls.manifest.url
            val playbackUrls = root.getAsJsonObject("playbackUrls")
            if (playbackUrls != null) {
                val defaultId = playbackUrls.getAsJsonPrimitive("defaultUrlSetId")?.asString
                val urlSets = playbackUrls.getAsJsonObject("urlSets")
                val urlSet = if (defaultId != null) urlSets?.getAsJsonObject(defaultId) else null
                val url = urlSet?.getAsJsonObject("urls")
                    ?.getAsJsonObject("manifest")
                    ?.getAsJsonPrimitive("url")?.asString
                if (!url.isNullOrEmpty()) return url
            }

            // Legacy format: audioVideoUrls.avCdnUrlSets[0].avUrlInfoList[0].url
            val audioVideoUrls = root.getAsJsonObject("audioVideoUrls")
            val avCdnUrlSets = audioVideoUrls?.getAsJsonArray("avCdnUrlSets")
            val firstSet = avCdnUrlSets?.firstOrNull()?.asJsonObject
            firstSet?.getAsJsonArray("avUrlInfoList")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonPrimitive("url")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting manifest URL", e)
            null
        }
    }

    private fun extractSubtitleTracks(json: String): List<SubtitleTrack> {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val tracks = mutableListOf<SubtitleTrack>()
            // subtitleUrls array
            root.getAsJsonArray("subtitleUrls")?.forEach { elem ->
                val obj = elem.asJsonObject
                val url = obj.get("url")?.asString ?: return@forEach
                val lang = obj.get("languageCode")?.asString ?: "und"
                val type = obj.get("type")?.asString ?: "regular"
                tracks.add(SubtitleTrack(url, lang, type))
            }
            // forcedNarratives array
            root.getAsJsonArray("forcedNarratives")?.forEach { elem ->
                val obj = elem.asJsonObject
                val url = obj.get("url")?.asString ?: return@forEach
                val lang = obj.get("languageCode")?.asString ?: "und"
                tracks.add(SubtitleTrack(url, lang, "forced"))
            }
            Log.w(TAG, "Extracted ${tracks.size} subtitle tracks")
            tracks
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract subtitles", e)
            emptyList()
        }
    }

    /**
     * Builds the Widevine license server URL (api-map.md, playback.py:452-467).
     */
    fun buildLicenseUrl(asin: String, deviceId: String): String {
        return "$atvUrl/cdp/catalog/GetPlaybackResources" +
               "?asin=$asin" +
               "&deviceTypeID=${AmazonAuthService.DEVICE_TYPE_ID}" +
               "&firmware=1" +
               "&deviceID=$deviceId" +
               "&marketplaceID=$marketplaceId" +
               "&format=json" +
               "&version=2" +
               "&gascEnabled=true" +
               "&resourceUsage=ImmediateConsumption" +
               "&consumptionType=Streaming" +
               "&deviceDrmOverride=CENC" +
               "&deviceStreamingTechnologyOverride=DASH" +
               "&deviceProtocolOverride=Https" +
               "&deviceBitrateAdaptationsOverride=CVBR%2CCBR" +
               "&audioTrackId=all" +
               "&languageFeature=MLFv2" +
               "&videoMaterialType=Feature" +
               "&desiredResources=Widevine2License" +
               "&supportedDRMKeyScheme=DUAL_KEY" +
               "&deviceVideoCodecOverride=H264" +
               "&deviceHdrFormatsOverride=None" +
               "&deviceVideoQualityOverride=HD"
    }

    // --- Watchlist management (api-map.md, android_api.py:348-360) ---

    /**
     * Adds a title to the user's watchlist.
     * @return true on success
     */
    fun addToWatchlist(asin: String): Boolean {
        val url = "$atvUrl/cdp/discovery/AddTitleToList" +
                  "?${baseParams()}&titleId=$asin"
        return executeWatchlistAction(url, "AddTitleToList", asin)
    }

    /**
     * Removes a title from the user's watchlist.
     * @return true on success
     */
    fun removeFromWatchlist(asin: String): Boolean {
        val url = "$atvUrl/cdp/discovery/RemoveTitleFromList" +
                  "?${baseParams()}&titleId=$asin"
        return executeWatchlistAction(url, "RemoveTitleFromList", asin)
    }

    private fun executeWatchlistAction(url: String, action: String, asin: String): Boolean {
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            Log.i(TAG, "$action asin=$asin success=$success")
            success
        } catch (e: Exception) {
            Log.i(TAG, "$action failed for $asin", e)
            false
        }
    }

    /**
     * Fetches all ASINs in the user's watchlist (paginated via paginationModel).
     * Used to mark items with isInWatchlist when displaying other pages.
     */
    fun getWatchlistAsins(): Set<String> {
        return try {
            val allAsins = mutableSetOf<String>()
            var nextParams = ""
            var pageCount = 0
            val maxPages = 25 // safety limit: 25 * 20 = 500 items max
            do {
                val (page, newNextParams) = getWatchlistPageWithPagination(nextParams)
                val prevSize = allAsins.size
                allAsins.addAll(page.map { it.asin })
                pageCount++
                // Stop if no new items were added (server returning same page)
                if (allAsins.size == prevSize && page.isNotEmpty()) {
                    Log.w(TAG, "Watchlist pagination stalled at page $pageCount, stopping")
                    break
                }
                nextParams = newNextParams
            } while (nextParams.isNotEmpty() && pageCount < maxPages)
            Log.w(TAG, "Watchlist ASINs loaded: ${allAsins.size} total in $pageCount pages")
            allAsins
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch watchlist ASINs", e)
            emptySet()
        }
    }

    // --- Watch Progress Tracking (dev/analysis/watch-progress-api.md) ---

    /**
     * UpdateStream (legacy bookmarking) — GET /cdp/usage/UpdateStream
     * Returns server-directed callback interval in seconds, or default 60.
     */
    fun updateStream(
        asin: String,
        event: String,
        timecodeSeconds: Long,
        sessionId: String = ""
    ): Int {
        val did = deviceId()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val url = "$atvUrl/cdp/usage/UpdateStream" +
                  "?titleId=$asin" +
                  "&event=$event" +
                  "&timecode=$timecodeSeconds" +
                  "&timecodeChangeTime=${java.net.URLEncoder.encode(now, "UTF-8")}" +
                  (if (sessionId.isNotEmpty()) "&userWatchSessionId=$sessionId" else "") +
                  "&deviceTypeID=${AmazonAuthService.DEVICE_TYPE_ID}" +
                  "&firmware=1" +
                  "&deviceID=$did" +
                  "&marketplaceID=$marketplaceId" +
                  "&format=json"
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            Log.w(TAG, "UpdateStream $event t=${timecodeSeconds}s for $asin")
            // Parse callbackIntervalInSeconds from response
            if (body != null) {
                try {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    json?.get("callbackIntervalInSeconds")?.asInt ?: 60
                } catch (_: Exception) { 60 }
            } else 60
        } catch (e: Exception) {
            Log.w(TAG, "UpdateStream failed (non-fatal)", e)
            60
        }
    }

    /**
     * PES V2 StartSession — POST /cdp/playback/pes/StartSession
     * Returns (sessionToken, callbackIntervalInSeconds).
     */
    fun pesStartSession(asin: String, timecodeSeconds: Long): Pair<String, Int> {
        val duration = secondsToIsoDuration(timecodeSeconds)
        val payload = JsonObject().apply {
            add("streamInfo", JsonObject().apply {
                addProperty("eventType", "START")
                add("vodProgressInfo", JsonObject().apply {
                    addProperty("currentProgressTime", duration)
                    addProperty("timeFormat", "ISO8601DURATION")
                })
            })
        }
        return pesRequest("/cdp/playback/pes/StartSession", payload, asin)
    }

    /**
     * PES V2 UpdateSession — POST /cdp/playback/pes/UpdateSession
     * Returns (sessionToken, callbackIntervalInSeconds).
     */
    fun pesUpdateSession(sessionToken: String, event: String, timecodeSeconds: Long, asin: String): Pair<String, Int> {
        val duration = secondsToIsoDuration(timecodeSeconds)
        val payload = JsonObject().apply {
            addProperty("sessionToken", sessionToken)
            add("streamInfo", JsonObject().apply {
                addProperty("eventType", event)
                add("vodProgressInfo", JsonObject().apply {
                    addProperty("currentProgressTime", duration)
                    addProperty("timeFormat", "ISO8601DURATION")
                })
            })
        }
        return pesRequest("/cdp/playback/pes/UpdateSession", payload, asin)
    }

    /**
     * PES V2 StopSession — POST /cdp/playback/pes/StopSession
     */
    fun pesStopSession(sessionToken: String, timecodeSeconds: Long, asin: String) {
        val duration = secondsToIsoDuration(timecodeSeconds)
        val payload = JsonObject().apply {
            addProperty("sessionToken", sessionToken)
            add("streamInfo", JsonObject().apply {
                addProperty("eventType", "STOP")
                add("vodProgressInfo", JsonObject().apply {
                    addProperty("currentProgressTime", duration)
                    addProperty("timeFormat", "ISO8601DURATION")
                })
            })
        }
        pesRequest("/cdp/playback/pes/StopSession", payload, asin)
    }

    private fun pesRequest(path: String, payload: JsonObject, asin: String): Pair<String, Int> {
        val url = "$atvUrl$path"
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()
            Log.w(TAG, "PES ${path.substringAfterLast('/')} for $asin: HTTP ${response.code}")
            if (responseBody != null && response.isSuccessful) {
                val json = gson.fromJson(responseBody, JsonObject::class.java)
                val token = json?.get("sessionToken")?.asString ?: ""
                val interval = json?.get("callbackIntervalInSeconds")?.asInt ?: 60
                Pair(token, interval)
            } else {
                Pair("", 60)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PES request failed (non-fatal): $path", e)
            Pair("", 60)
        }
    }

    /** Converts seconds to ISO 8601 duration, e.g. 3661 -> "PT1H1M1S" */
    private fun secondsToIsoDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            append("PT")
            if (h > 0) append("${h}H")
            if (m > 0) append("${m}M")
            append("${s}S")
        }
    }
}
