package com.vista.photoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SearchQueryTest {

    private val zone = ZoneId.of("Europe/Paris")
    private fun millis(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun contentAndYear() = assertEquals(SearchQuery("plage", year = 2023), SearchQuery.parse("plage 2023"))

    @Test
    fun contentAndMonth() = assertEquals(SearchQuery("chien", month = 7), SearchQuery.parse("chien juillet"))

    @Test
    fun multiWordContentMonthAndYear() =
        assertEquals(SearchQuery("coucher de soleil", 2024, 8), SearchQuery.parse("coucher de soleil aout 2024"))

    @Test
    fun abbreviatedMonth() = assertEquals(SearchQuery("neige", month = 9), SearchQuery.parse("neige sept."))

    @Test
    fun plainQueryIsUnchanged() {
        assertEquals(SearchQuery("chien"), SearchQuery.parse("chien"))
        assertFalse(SearchQuery.parse("chien").hasDate)
    }

    @Test
    fun monthAloneStaysTextButYearAloneIsADate() {
        // « septembre » seul se cherchait déjà dans les dates ; « mai » peut être un nom d'album.
        assertEquals(SearchQuery("mai"), SearchQuery.parse("mai"))
        assertEquals(SearchQuery("", year = 2023), SearchQuery.parse("2023"))
        assertEquals(SearchQuery("", 2023, 5), SearchQuery.parse("mai 2023"))
    }

    @Test
    fun dateFilter() {
        val q = SearchQuery("plage", 2023, 7)
        assertTrue(q.matchesDate(millis(2023, 7, 14), zone))
        assertFalse(q.matchesDate(millis(2023, 8, 1), zone))
        assertFalse(q.matchesDate(millis(2022, 7, 14), zone))
        assertTrue(SearchQuery("plage").matchesDate(millis(1999, 1, 1), zone))
    }
}
