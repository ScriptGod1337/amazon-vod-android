package com.scriptgod.fireos.avod.ui

import com.scriptgod.fireos.avod.api.AmazonApiService
import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.DetailInfo
import com.scriptgod.fireos.avod.model.primaryAvailabilityBadge

internal object UiMetadataFormatter {

    data class CardMetadata(
        val overline: String,
        val title: String,
        val subtitle: String
    )

    fun badgeLabels(item: ContentItem): List<String> = buildList {
        item.primaryAvailabilityBadge()?.let(::add)
        if (item.watchProgressMs == -1L) add("Watched")
    }.take(3)

    fun featuredMeta(railHeader: String, item: ContentItem): String {
        val parts = mutableListOf(contentLabel(item))
        item.primaryAvailabilityBadge()?.let(parts::add)
        if (railHeader.isNotBlank()) parts += railHeader
        return parts.joinToString("  ·  ")
    }

    fun cardMetadata(item: ContentItem, presentation: CardPresentation): CardMetadata {
        val progressSubtitle = progressSubtitle(item)
        return when (presentation) {
            CardPresentation.SEASON -> CardMetadata(
                overline = "Season",
                title = item.title,
                subtitle = sanitizedSubtitle(item).ifBlank { "Open episode list" }
            )
            CardPresentation.PROGRESS -> CardMetadata(
                overline = "Continue Watching",
                title = item.title,
                subtitle = progressSubtitle ?: "Resume playback"
            )
            CardPresentation.EPISODE -> {
                val (episodeOverline, episodeTitle) = episodeLabelParts(item.title)
                CardMetadata(
                    overline = episodeOverline.ifBlank { defaultOverline(item, progressSubtitle != null) },
                    title = episodeTitle,
                    subtitle = sanitizedSubtitle(item).ifBlank { "Start playback" }
                )
            }
            CardPresentation.LANDSCAPE -> CardMetadata(
                overline = defaultOverline(item, progressSubtitle != null),
                title = item.title,
                subtitle = progressSubtitle ?: landscapeSubtitle(item)
            )
            CardPresentation.POSTER -> CardMetadata(
                overline = defaultOverline(item, progressSubtitle != null),
                title = item.title,
                subtitle = progressSubtitle ?: secondaryLine(item)
            )
        }
    }

    fun detailSupportLine(info: DetailInfo): String {
        val parts = mutableListOf<String>()
        if (info.contentType.uppercase().contains("SEASON") && info.showTitle.isNotEmpty()) {
            parts += "From ${info.showTitle}"
        }
        if (info.genres.isNotEmpty()) {
            parts += info.genres.joinToString("  ·  ")
        }
        if (info.contentType.uppercase().contains("EPISODE") && info.showTitle.isNotEmpty()) {
            parts += "From ${info.showTitle}"
        }
        if (info.isTrailerAvailable) {
            parts += "Trailer available"
        }
        return parts.joinToString("  ·  ")
            .ifBlank { "Start playback, browse related titles, or manage watchlist status." }
    }

    private fun progressSubtitle(item: ContentItem): String? {
        if (item.watchProgressMs == 0L || item.runtimeMs <= 0L) return null
        return when (item.watchProgressMs) {
            -1L -> "Finished recently"
            else -> {
                val progressPercent = ((item.watchProgressMs * 100) / item.runtimeMs).toInt().coerceIn(1, 99)
                val remainingMinutes = ((item.runtimeMs - item.watchProgressMs).coerceAtLeast(0L) / 60000L).toInt()
                if (remainingMinutes > 0) "$progressPercent% watched · ${remainingMinutes} min left"
                else "$progressPercent% watched"
            }
        }
    }

    private fun defaultOverline(item: ContentItem, hasProgress: Boolean): String {
        if (hasProgress) return "Continue Watching"
        return when {
            item.isLive -> "Live"
            AmazonApiService.isEpisodeContentType(item.contentType) -> "Episode"
            AmazonApiService.isSeriesContentType(item.contentType) -> "Series"
            AmazonApiService.isMovieContentType(item.contentType) -> "Movie"
            else -> "Featured"
        }
    }

    private fun contentLabel(item: ContentItem): String {
        return when {
            item.isLive -> "Live"
            AmazonApiService.isEpisodeContentType(item.contentType) -> "Episode"
            AmazonApiService.isSeriesContentType(item.contentType) -> "Series"
            AmazonApiService.isMovieContentType(item.contentType) -> "Movie"
            else -> "Featured"
        }
    }

    private fun secondaryLine(item: ContentItem): String {
        val cleanedSubtitle = sanitizedSubtitle(item)
        if (cleanedSubtitle.isNotBlank()) return cleanedSubtitle

        val parts = mutableListOf<String>()
        when {
            AmazonApiService.isEpisodeContentType(item.contentType) -> parts += "Playable episode"
            AmazonApiService.isSeriesContentType(item.contentType) -> parts += "Series overview"
            AmazonApiService.isMovieContentType(item.contentType) -> parts += "Feature film"
        }
        when (item.primaryAvailabilityBadge()) {
            "Freevee" -> parts += "Ad-supported"
            "Live" -> parts += "Live channel"
        }
        return parts.joinToString("  ·  ")
    }

    private fun landscapeSubtitle(item: ContentItem): String {
        return if (AmazonApiService.isSeriesContentType(item.contentType)) "Open season overview"
        else secondaryLine(item)
    }

    private fun sanitizedSubtitle(item: ContentItem): String {
        return item.subtitle
            .replace("Included with Prime", "", ignoreCase = true)
            .replace("Prime Video", "", ignoreCase = true)
            .replace("  ·   ·  ", "  ·  ")
            .replace(" • ", "  ·  ")
            .replace(Regex("\\s+·\\s*$"), "")
            .replace(Regex("^\\s*·\\s+"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun episodeLabelParts(rawTitle: String): Pair<String, String> {
        val match = Regex("^E(\\d+):\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(rawTitle.trim())
        if (match != null) {
            return "Episode ${match.groupValues[1]}" to match.groupValues[2]
        }
        return "" to rawTitle
    }
}
