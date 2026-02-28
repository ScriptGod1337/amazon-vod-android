package com.scriptgod.fireos.avod.model

data class DetailInfo(
    val asin: String,
    val title: String,
    val contentType: String,
    val synopsis: String,
    val heroImageUrl: String,
    val posterImageUrl: String,
    val year: Int,
    val runtimeSeconds: Int,
    val imdbRating: Float,          // 0f if not available
    val genres: List<String>,
    val directors: List<String>,
    val ageRating: String,
    val isInWatchlist: Boolean,
    val isTrailerAvailable: Boolean,
    val isUhd: Boolean,
    val isHdr: Boolean,
    val isDolby51: Boolean,
    // For series/season detail
    val showTitle: String = "",
    val showAsin: String = ""
)
