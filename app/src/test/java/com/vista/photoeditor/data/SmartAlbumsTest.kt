package com.vista.photoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SmartAlbumsTest {

    private fun yearsAgo(then: String, today: String): Int? {
        fun millis(date: String) = LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return SmartAlbums.yearsAgo(millis(then), millis(today), ZoneOffset.UTC)
    }

    @Test
    fun `une photo de la veille du nouvel an n'a pas un an`() {
        assertNull(yearsAgo("2025-12-30", "2026-01-01"))
    }

    @Test
    fun `un anniversaire à cheval sur le nouvel an compte les vraies années`() {
        assertEquals(1, yearsAgo("2024-12-30", "2026-01-01"))
        assertEquals(1, yearsAgo("2025-01-03", "2026-01-01"))
    }

    @Test
    fun `anniversaires du jour et à quelques jours près`() {
        assertEquals(1, yearsAgo("2025-10-31", "2026-10-31"))
        assertEquals(3, yearsAgo("2023-10-29", "2026-10-31"))
        assertEquals(10, yearsAgo("2016-11-03", "2026-10-31"))
    }

    @Test
    fun `29 février`() {
        assertEquals(1, yearsAgo("2024-02-29", "2025-02-28"))
        assertEquals(1, yearsAgo("2024-02-29", "2025-03-03"))
    }

    @Test
    fun `hors de la fenêtre ou moins d'un an`() {
        assertNull(yearsAgo("2026-10-31", "2026-10-31"))
        assertNull(yearsAgo("2025-06-01", "2026-10-31"))
        assertNull(yearsAgo("2025-10-20", "2026-10-31"))
    }
}
