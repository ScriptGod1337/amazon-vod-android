package com.scriptgod.fireos.avod.api

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentItemParserTest {

    @Test
    fun parseCollectionElement_requiresExplicitPrimeSignal() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "movie-1",
                "title": "Movie",
                "contentType": "Feature",
                "badgeInfo": "Top Pick"
              }
            }
            """.trimIndent()
        )

        assertFalse(item.isPrime)
        assertFalse(item.isFreeWithAds)
        assertFalse(item.isLive)
    }

    @Test
    fun parseCollectionElement_detectsPrimeFromBadgeText() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "movie-2",
                "title": "Prime Movie",
                "contentType": "Feature",
                "badgeInfo": "Included with PRIME"
              }
            }
            """.trimIndent()
        )

        assertTrue(item.isPrime)
        assertFalse(item.isFreeWithAds)
    }

    @Test
    fun parseCollectionElement_detectsPrimeFromBadgeArrayLabels() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "movie-2b",
                "title": "Prime Movie",
                "contentType": "Feature",
                "entitlementBadges": [
                  {"label": "Included with Prime"}
                ]
              }
            }
            """.trimIndent()
        )

        assertTrue(item.isPrime)
        assertFalse(item.isFreeWithAds)
    }

    @Test
    fun parseCollectionElement_handlesBadgeObjectWithoutCrashing() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "season-1",
                "title": "Fallout - Staffel 2",
                "contentType": "SEASON",
                "badges": {
                  "text": "Prime",
                  "primaryText": "Included with Prime"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("Fallout - Staffel 2", item.title)
        assertTrue(item.isPrime)
    }

    @Test
    fun parseCollectionElement_detectsFreeveeFromBadgeText() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "movie-3",
                "title": "Free Movie",
                "contentType": "Feature",
                "badgeInfo": "FREE with ads"
              }
            }
            """.trimIndent()
        )

        assertFalse(item.isPrime)
        assertTrue(item.isFreeWithAds)
    }

    @Test
    fun parseCollectionElement_detectsLiveAndDoesNotMarkPrime() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "live-1",
                "title": "Live Channel",
                "contentType": "LiveStreaming",
                "showPrimeEmblem": true,
                "liveInfo": {}
              }
            }
            """.trimIndent()
        )

        assertTrue(item.isLive)
        assertFalse(item.isPrime)
    }

    @Test
    fun parseCollectionElement_mapsEpisodeAndProgress() {
        val item = parse(
            """
            {
              "model": {
                "catalogId": "ep-1",
                "title": "Baby, Baby, Baby",
                "contentType": "EPISODE",
                "seasonNumber": "7",
                "episodeNumber": "1",
                "runtimeSeconds": "3600",
                "timecodeSeconds": "900"
              }
            }
            """.trimIndent()
        )

        assertEquals("E1: Baby, Baby, Baby", item.title)
        assertEquals("S7 E1", item.subtitle)
        assertEquals(900_000L, item.watchProgressMs)
    }

    private fun parse(json: String) =
        ContentItemParser.parseCollectionElement(JsonParser.parseString(json).asJsonObject)!!
}
