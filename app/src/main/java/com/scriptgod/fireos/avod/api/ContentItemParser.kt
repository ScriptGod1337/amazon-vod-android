package com.scriptgod.fireos.avod.api

import com.google.gson.JsonObject
import com.scriptgod.fireos.avod.model.ContentItem

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
        val title = resolveTitle(model.safeString("title") ?: return null, contentType, seasonNumber, episodeNumber)
        val subtitle = resolveSubtitle(model, seasonNumber, episodeNumber)
        val imageUrl = resolveImageUrl(model)
        val badgeText = listOfNotNull(
            model.safeString("badgeInfo"),
            model.safeString("contentBadge"),
            model.getAsJsonObject("badges")?.safeString("text")
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

        val isPrime = (model.getAsJsonObject("badges")?.safeBoolean("prime")
            ?: model.safeBoolean("showPrimeEmblem")
            ?: model.safeBoolean("isPrime")
            ?: model.safeBoolean("primeOnly")
            ?: badgeText.contains("PRIME", ignoreCase = true))
            && !isFreeWithAds
            && !isLive

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
            isPrime = isPrime,
            isFreeWithAds = isFreeWithAds,
            isLive = isLive,
            channelId = channelId,
            runtimeMs = runtimeMs,
            watchProgressMs = resolveWatchProgressMs(model, runtimeMs, contentType)
        )
    }

    private fun resolveContentType(model: JsonObject, seasonNumber: String?, episodeNumber: String?): String {
        return model.safeString("contentType")
            ?: if (seasonNumber != null && episodeNumber == null) "SEASON"
            else if (episodeNumber != null) "EPISODE"
            else "Feature"
    }

    private fun resolveTitle(rawTitle: String, contentType: String, seasonNumber: String?, episodeNumber: String?): String {
        return when {
            contentType.equals("SEASON", ignoreCase = true) && seasonNumber != null -> "Season $seasonNumber"
            contentType.equals("EPISODE", ignoreCase = true) && episodeNumber != null -> "E$episodeNumber: $rawTitle"
            else -> rawTitle
        }
    }

    private fun resolveSubtitle(model: JsonObject, seasonNumber: String?, episodeNumber: String?): String {
        return model.safeString("ratingsBadge")
            ?: if (seasonNumber != null && episodeNumber != null) "S${seasonNumber} E${episodeNumber}"
            else if (seasonNumber != null) "Season $seasonNumber"
            else if (episodeNumber != null) "Episode $episodeNumber"
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
}
