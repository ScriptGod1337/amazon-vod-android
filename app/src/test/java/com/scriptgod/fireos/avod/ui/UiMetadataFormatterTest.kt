package com.scriptgod.fireos.avod.ui

import com.scriptgod.fireos.avod.model.ContentItem
import com.scriptgod.fireos.avod.model.DetailInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class UiMetadataFormatterTest {

    @Test
    fun badgeLabels_separateAvailabilityFromEditorialProgress() {
        val item = ContentItem(
            asin = "a",
            title = "Title",
            isPrime = true,
            watchProgressMs = -1L
        )

        assertEquals(listOf("Prime", "Watched"), UiMetadataFormatter.badgeLabels(item))
    }

    @Test
    fun featuredMeta_omitsUnknownAvailability() {
        val item = ContentItem(
            asin = "a",
            title = "Title",
            contentType = "Series",
            isPrime = false
        )

        assertEquals("Series  ·  Featured", UiMetadataFormatter.featuredMeta("Featured", item))
    }

    @Test
    fun cardMetadata_usesEpisodeSpecificPresentation() {
        val item = ContentItem(
            asin = "ep",
            title = "E5: Hello",
            subtitle = "S2 E5",
            contentType = "Episode"
        )

        val metadata = UiMetadataFormatter.cardMetadata(item, CardPresentation.EPISODE)
        assertEquals("Episode 5", metadata.overline)
        assertEquals("Hello", metadata.title)
        assertEquals("S2 E5", metadata.subtitle)
    }

    @Test
    fun detailSupportLine_collapsesEmptyState() {
        val info = DetailInfo(
            asin = "a",
            title = "Movie",
            contentType = "Feature",
            synopsis = "",
            heroImageUrl = "",
            posterImageUrl = "",
            year = 0,
            runtimeSeconds = 0,
            imdbRating = 0f,
            genres = emptyList(),
            directors = emptyList(),
            ageRating = "",
            isInWatchlist = false,
            isTrailerAvailable = false,
            isUhd = false,
            isHdr = false,
            isDolby51 = false
        )

        assertEquals(
            "Start playback, browse related titles, or manage watchlist status.",
            UiMetadataFormatter.detailSupportLine(info)
        )
    }

    @Test
    fun detailSupportLine_keepsSeasonContextSpecific() {
        val info = DetailInfo(
            asin = "season",
            title = "Season 7",
            contentType = "SEASON",
            synopsis = "",
            heroImageUrl = "",
            posterImageUrl = "",
            year = 2024,
            runtimeSeconds = 0,
            imdbRating = 0f,
            genres = listOf("Drama"),
            directors = emptyList(),
            ageRating = "",
            isInWatchlist = false,
            isTrailerAvailable = false,
            isUhd = false,
            isHdr = false,
            isDolby51 = false,
            showTitle = "The Good Doctor"
        )

        assertEquals("From The Good Doctor  ·  Drama", UiMetadataFormatter.detailSupportLine(info))
    }
}
