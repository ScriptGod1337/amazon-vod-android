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
    val isInWatchlist: Boolean = false
)

data class PlaybackInfo(
    val manifestUrl: String,
    val licenseUrl: String,
    val asin: String
)
