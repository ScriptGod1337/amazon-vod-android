package com.scriptgod.fireos.avod.model

data class ContentItem(
    val asin: String,
    val title: String,
    val subtitle: String = "",
    val imageUrl: String = "",
    val contentType: String = "Feature",   // Feature, Episode, Trailer, live, etc.
    val seriesAsin: String = "",
    val isPrime: Boolean = true,
    val isFreeWithAds: Boolean = false,
    val isLive: Boolean = false,
    val channelId: String = "",
    val isInWatchlist: Boolean = false,
    val runtimeMs: Long = 0,
    val watchProgressMs: Long = 0  // 0 = not started, -1 = fully watched, >0 = position ms
)

fun ContentItem.isIncludedWithPrime(): Boolean = isPrime && !isFreeWithAds && !isLive

fun ContentItem.isFullyWatched(): Boolean = when {
    contentType.equals("Season", true) -> false
    contentType.equals("Series", true) -> false
    contentType.equals("Show", true) -> false
    contentType.equals("TVSeason", true) -> false
    contentType.equals("TVSeries", true) -> false
    watchProgressMs == -1L -> true
    watchProgressMs <= 0L -> false
    runtimeMs <= 0L -> false
    watchProgressMs >= (runtimeMs * 95 / 100) -> true
    else -> false
}

fun ContentItem.primaryAvailabilityBadge(): String? = when {
    isFreeWithAds -> "Freevee"
    isLive -> "Live"
    isIncludedWithPrime() -> "Prime"
    else -> null
}

data class SubtitleTrack(
    val url: String,
    val languageCode: String,
    val type: String // "sdh", "regular", "forced"
)

data class PlaybackInfo(
    val manifestUrl: String,
    val licenseUrl: String,
    val asin: String,
    val subtitleTracks: List<SubtitleTrack> = emptyList()
)
