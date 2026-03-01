package com.scriptgod.fireos.avod.api

import com.google.gson.JsonObject
import com.scriptgod.fireos.avod.model.Availability
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.ContentKind

internal object ContentItemParser {

    fun parseCollectionElement(element: JsonObject): ContentItem? {
        val model = element.getAsJsonObject("model") ?: element

        val linkAction = model.getAsJsonObject("linkAction")
        val asin = model.safeString("catalogId")
            ?: model.safeString("compactGti")
            ?: model.safeString("titleId")
            ?: linkAction?.safeString("titleId")
            ?: model.safeString("id")
            ?: model.safeString("asin")
            ?: return null

        val seasonNumber = model.safeString("seasonNumber")
            ?: model.safeString("number")
        val episodeNumber = model.safeString("episodeNumber")
        val contentType = resolveContentType(model, seasonNumber, episodeNumber)
        val kind = resolveKind(contentType)
        val title = resolveTitle(model.safeString("title") ?: return null, kind, seasonNumber, episodeNumber)
        val subtitle = resolveSubtitle(model, kind, seasonNumber, episodeNumber)
        val imageUrl = resolveImageUrl(model)
        val badgeText = listOfNotNull(
            model.safeString("badgeInfo"),
            model.safeString("contentBadge"),
            model.safeString("primaryBadge"),
            model.safeString("secondaryBadge"),
            model.getAsJsonObject("badges")?.safeString("text"),
            model.getAsJsonObject("badges")?.safeString("primaryText"),
            model.getAsJsonObject("badges")?.safeString("secondaryText"),
            model.safeArray("entitlementBadges")?.joinPrimitiveStrings(),
            model.safeArray("badges")?.joinPrimitiveStrings()
        ).joinToString(" ")

        val isFreeWithAds = model.safeBoolean("isFreeWithAds")
            ?: model.safeBoolean("freeWithAds")
            ?: badgeText.contains("FREE", ignoreCase = true)
            || badgeText.contains("AVOD", ignoreCase = true)

        val isLive = contentType.equals("live", ignoreCase = true)
            || contentType.equals("LiveStreaming", ignoreCase = true)
            || model.has("liveInfo")
            || model.has("liveState")
            || (model.safeString("videoMaterialType")
                ?.equals("LiveStreaming", ignoreCase = true) ?: false)

        // Hero carousel items (Featured rail) carry Prime entitlement in
        // messagePresentationModel.entitlementMessageSlotCompact[].imageId == "ENTITLED_ICON"
        // The text must also mention "prime" — ENTITLED_ICON alone is used for channel subs/rentals too.
        val hasEntitledIcon = model.getAsJsonObject("messagePresentationModel")
            ?.safeArray("entitlementMessageSlotCompact")
            ?.any { el ->
                el.isJsonObject &&
                el.asJsonObject.safeString("imageId") == "ENTITLED_ICON" &&
                el.asJsonObject.safeString("text")?.contains("prime", ignoreCase = true) == true
            }
            ?: false

        val isPrime = (model.getAsJsonObject("badges")?.safeBoolean("prime")
            ?: model.safeBoolean("showPrimeEmblem")
            ?: model.safeBoolean("isPrime")
            ?: model.safeBoolean("primeOnly")
            ?: (badgeText.contains("PRIME", ignoreCase = true) || hasEntitledIcon))
            && !isFreeWithAds
            && !isLive
        val availability = when {
            isLive -> Availability.LIVE
            isFreeWithAds -> Availability.FREEVEE
            isPrime -> Availability.PRIME
            else -> Availability.UNKNOWN
        }

        val showId = model.safeString("showId")
            ?: model.safeString("seriesId")
            ?: model.safeString("showTitleId")
            ?: model.getAsJsonObject("show")?.safeString("titleId")
            ?: model.getAsJsonObject("linkAction")?.safeString("seriesTitleId")
            ?: ""
        val seasonId = model.safeString("seasonId")
            ?: model.safeString("seasonTitleId")
            ?: if (kind == ContentKind.SEASON) asin else ""

        val channelId = model.getAsJsonObject("playbackAction")
            ?.safeString("channelId")
            ?: model.getAsJsonObject("station")?.safeString("id")
            ?: model.safeString("channelId")
            ?: ""

        val runtimeMs = model.safeString("runtimeMillis")?.toLongOrNull()
            ?: model.safeString("runtimeSeconds")?.toLongOrNull()?.times(1000)
            ?: model.getAsJsonObject("runtime")?.get("valueMillis")?.asLong
            ?: 0L

        return ContentItem(
            asin = asin,
            title = title,
            subtitle = subtitle,
            imageUrl = imageUrl,
            contentType = contentType,
            contentId = asin,
            showId = showId,
            seasonId = seasonId,
            seasonNumber = seasonNumber?.toIntOrNull(),
            episodeNumber = episodeNumber?.toIntOrNull(),
            kind = kind,
            availability = availability,
            seriesAsin = showId,
            isPrime = isPrime,
            isFreeWithAds = isFreeWithAds,
            isLive = isLive,
            channelId = channelId,
            runtimeMs = runtimeMs,
            watchProgressMs = resolveWatchProgressMs(model, runtimeMs, contentType)
        )
    }

    private fun resolveKind(contentType: String): ContentKind {
        return when {
            contentType.equals("live", ignoreCase = true) ||
                contentType.equals("LiveStreaming", ignoreCase = true) -> ContentKind.LIVE
            AmazonApiService.isEpisodeContentType(contentType) -> ContentKind.EPISODE
            contentType.equals("Season", ignoreCase = true) ||
                contentType.equals("TVSeason", ignoreCase = true) -> ContentKind.SEASON
            contentType.equals("Series", ignoreCase = true) ||
                contentType.equals("Show", ignoreCase = true) ||
                contentType.equals("TVSeries", ignoreCase = true) -> ContentKind.SERIES
            AmazonApiService.isMovieContentType(contentType) -> ContentKind.MOVIE
            else -> ContentKind.OTHER
        }
    }

    private fun resolveContentType(model: JsonObject, seasonNumber: String?, episodeNumber: String?): String {
        return model.safeString("contentType")
            ?: if (seasonNumber != null && episodeNumber == null) "SEASON"
            else if (episodeNumber != null) "EPISODE"
            else "Feature"
    }

    private fun resolveTitle(rawTitle: String, kind: ContentKind, seasonNumber: String?, episodeNumber: String?): String {
        return when {
            kind == ContentKind.SEASON && seasonNumber != null -> "Season $seasonNumber"
            kind == ContentKind.EPISODE && episodeNumber != null -> "E$episodeNumber: $rawTitle"
            else -> rawTitle
        }
    }

    private fun resolveSubtitle(model: JsonObject, kind: ContentKind, seasonNumber: String?, episodeNumber: String?): String {
        return model.safeString("ratingsBadge")
            ?: if (kind == ContentKind.EPISODE && seasonNumber != null && episodeNumber != null) "S${seasonNumber} E${episodeNumber}"
            else if (kind == ContentKind.SEASON && seasonNumber != null) "Season $seasonNumber"
            else if (kind == ContentKind.EPISODE && episodeNumber != null) "Episode $episodeNumber"
            else ""
    }

    private fun resolveImageUrl(model: JsonObject): String {
        return model.getAsJsonObject("image")
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
    }

    private fun resolveWatchProgressMs(model: JsonObject, runtimeMs: Long, contentType: String): Long {
        val remainingSec = model.safeString("remainingTimeInSeconds")?.toLongOrNull()
        val timecodeSec = model.safeString("timecodeSeconds")?.toLongOrNull()
        val completedAfterSec = model.safeString("completedAfterSeconds")?.toLongOrNull()
        val isSeries = AmazonApiService.isSeriesContentType(contentType)
        val runtimeSec = runtimeMs / 1000

        return when {
            isSeries -> 0L
            remainingSec != null && remainingSec > 0 && runtimeSec > 0 && remainingSec < runtimeSec ->
                (runtimeMs - remainingSec * 1000).coerceAtLeast(1L)
            remainingSec != null && remainingSec > 0 && runtimeSec > 0 && remainingSec >= runtimeSec ->
                0L
            timecodeSec != null && timecodeSec > 0 -> {
                if (completedAfterSec != null && timecodeSec >= completedAfterSec) -1L
                else if (runtimeSec > 0 && timecodeSec >= runtimeSec * 9 / 10) -1L
                else timecodeSec * 1000
            }
            else -> 0L
        }
    }

    private fun JsonObject.safeString(key: String): String? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    private fun JsonObject.safeBoolean(key: String): Boolean? {
        val el = get(key) ?: return null
        return if (el.isJsonPrimitive) el.asBoolean else null
    }

    private fun JsonObject.safeArray(key: String): com.google.gson.JsonArray? {
        val el = get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    private fun com.google.gson.JsonArray.joinPrimitiveStrings(): String {
        return mapNotNull { element ->
            when {
                element.isJsonPrimitive -> element.asString
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    obj.safeString("text")
                        ?: obj.safeString("label")
                        ?: obj.safeString("title")
                }
                else -> null
            }
        }.joinToString(" ")
    }
}
