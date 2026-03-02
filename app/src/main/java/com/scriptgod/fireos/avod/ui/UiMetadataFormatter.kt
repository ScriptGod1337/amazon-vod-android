package com.scriptgod.fireos.avod.ui

import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.DetailInfo
import com.scriptgod.fireos.avod.model.isFullyWatched
import com.scriptgod.fireos.avod.model.isEpisode
import com.scriptgod.fireos.avod.model.isLiveChannel
import com.scriptgod.fireos.avod.model.isMovie
import com.scriptgod.fireos.avod.model.isSeriesContainer
import com.scriptgod.fireos.avod.model.primaryAvailabilityBadge

internal object UiMetadataFormatter {

    data class CardMetadata(
        val overline: String,
        val title: String,
        val subtitle: String
    )

    fun badgeLabels(item: ContentItem): List<String> = buildList {
        item.primaryAvailabilityBadge()?.let(::add)
        if (item.isFullyWatched()) add("Watched")
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
                subtitle = ""
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

    fun progressSubtitle(item: ContentItem): String? =
        progressText(item.watchProgressMs, item.runtimeMs)
            ?: if (item.isFullyWatched()) "Finished recently" else null

    /**
     * Canonical progress text formatter. Used by both card subtitles (via [progressSubtitle])
     * and the detail page, so both surfaces always show the same string for the same position.
     *
     * Returns null when there is no meaningful progress to display.
     * Returns "Finished recently" for [posMs] == -1L (fully-watched sentinel) or ≥ 95% of runtime.
     */
    fun progressText(posMs: Long, runtimeMs: Long): String? {
        if (posMs == 0L || runtimeMs <= 0L) return null
        if (posMs == -1L) return "Finished recently"
        val progressPercent = ((posMs * 100) / runtimeMs).toInt().coerceIn(1, 99)
        if (progressPercent >= 95) return "Finished recently"
        val remainingMinutes = ((runtimeMs - posMs).coerceAtLeast(0L) / 60_000L).toInt()
        return if (remainingMinutes > 0) "$progressPercent% watched · ${remainingMinutes} min left"
               else "$progressPercent% watched"
    }

    private fun defaultOverline(item: ContentItem, hasProgress: Boolean): String {
        if (hasProgress) return "Continue Watching"
        return when {
            item.isLiveChannel() -> "Live"
            item.isEpisode() -> "Episode"
            item.isSeriesContainer() -> "Series"
            else -> "Movie"   // movies and unknown content types
        }
    }

    private fun contentLabel(item: ContentItem): String {
        return when {
            item.isLiveChannel() -> "Live"
            item.isEpisode() -> "Episode"
            item.isSeriesContainer() -> "Series"
            else -> "Movie"   // movies and unknown content types
        }
    }

    private fun secondaryLine(item: ContentItem): String {
        val cleanedSubtitle = sanitizedSubtitle(item)
        if (cleanedSubtitle.isNotBlank()) return cleanedSubtitle

        val parts = mutableListOf<String>()
        when {
            item.isEpisode() -> parts += "Playable episode"
            item.isSeriesContainer() -> parts += "Series overview"
        }
        when (item.primaryAvailabilityBadge()) {
            "Freevee" -> parts += "Ad-supported"
            "Live" -> parts += "Live channel"
        }
        return parts.joinToString("  ·  ")
    }

    private fun landscapeSubtitle(item: ContentItem): String {
        return if (item.isSeriesContainer()) "Open season overview"
        else secondaryLine(item)
    }

    private fun sanitizedSubtitle(item: ContentItem): String {
        val cleaned = item.subtitle
            .replace("Included with Prime", "", ignoreCase = true)
            .replace("Prime Video", "", ignoreCase = true)
            .replace("Watch with Prime", "", ignoreCase = true)
            .replace("Included with", "", ignoreCase = true)
            .replace("  ·   ·  ", "  ·  ")
            .replace(" • ", "  ·  ")
            .replace(Regex("\\s+·\\s*$"), "")
            .replace(Regex("^\\s*·\\s+"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return if (cleaned.equals(item.title, ignoreCase = true)) "" else cleaned
    }

    private fun episodeLabelParts(rawTitle: String): Pair<String, String> {
        val match = Regex("^E(\\d+):\\s*(.+)$", RegexOption.IGNORE_CASE).matchEntire(rawTitle.trim())
        if (match != null) {
            return "Episode ${match.groupValues[1]}" to match.groupValues[2]
        }
        return "" to rawTitle
    }
}
