package com.vista.photoeditor.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class MemoryWidgetPickerTest {

    /** Photo de test : sa date, et si le widget peut l'afficher (pas une vidéo). */
    private data class Shot(val name: String, val date: Long, val still: Boolean = true)

    private fun at(date: String, hour: Int = 12) =
        LocalDateTime.parse("${date}T%02d:00".format(hour)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val now = at("2026-11-02")

    private fun choose(memories: List<Pair<String, List<Shot>>>, photos: List<Shot>, now: Long = this.now) =
        MemoryWidgetPicker.choose(memories, photos, Shot::date, Shot::still, now, ZoneOffset.UTC)

    private val lastYear = (0 until 4).map { Shot("an$it", at("2025-11-02", 18 - it)) }
    private val twoYearsAgo = (0 until 4).map { Shot("deux$it", at("2024-11-01", 18 - it)) }
    private val summer = (0 until 5).map { Shot("été$it", at("2026-07-14", 18 - it)) }
    private val lonely = Shot("seule", at("2023-11-03"))

    @Test
    fun `le souvenir anniversaire passe avant tout le reste`() {
        val choice = choose(listOf("mem:été" to summer, "mem:an" to lastYear), summer + lastYear + lonely)!!
        assertEquals("mem:an", choice.albumKey)
        assertEquals(1, choice.years)
        assertEquals(4, choice.count)
        assertEquals(true, choice.photo in lastYear)
    }

    @Test
    fun `la photo change toutes les trois heures et saute les vidéos`() {
        val withVideo = lastYear.mapIndexed { i, shot -> if (i == 1) shot.copy(still = false) else shot }
        val shown = (0 until 6).map { step ->
            choose(listOf("mem:an" to withVideo), withVideo, now + step * MemoryWidgetPicker.ROTATION_MILLIS)!!.photo.name
        }
        assertEquals(setOf("an0", "an2", "an3"), shown.toSet())
        assertEquals(false, "an1" in shown)
        // Deux créneaux qui se suivent ne montrent pas la même photo.
        shown.zipWithNext().forEach { (a, b) -> assertEquals(false, a == b) }
    }

    @Test
    fun `plusieurs anniversaires se relaient`() {
        val memories = listOf("mem:an" to lastYear, "mem:deux" to twoYearsAgo)
        val keys = (0 until 4).map { step ->
            choose(memories, lastYear + twoYearsAgo, now + step * MemoryWidgetPicker.ROTATION_MILLIS)!!.albumKey
        }
        assertEquals(setOf("mem:an", "mem:deux"), keys.toSet())
    }

    @Test
    fun `sans souvenir anniversaire, une photo prise ce jour-là suffit`() {
        val choice = choose(listOf("mem:été" to summer), summer + lonely)!!
        assertEquals("all", choice.albumKey)
        assertEquals(lonely, choice.photo)
        assertEquals(3, choice.years)
        assertEquals(1, choice.count)
    }

    @Test
    fun `un souvenir anniversaire fait seulement de vidéos est ignoré`() {
        val videos = lastYear.map { it.copy(still = false) }
        val choice = choose(listOf("mem:an" to videos), videos + lonely)!!
        assertEquals("all", choice.albumKey)
        assertEquals(lonely, choice.photo)
    }

    @Test
    fun `en dernier recours, un souvenir d'au moins une semaine`() {
        val choice = choose(listOf("mem:été" to summer), summer)!!
        assertEquals("mem:été", choice.albumKey)
        assertNull(choice.years)
        assertEquals(5, choice.count)
    }

    @Test
    fun `rien à montrer`() {
        val yesterday = (0 until 4).map { Shot("hier$it", at("2026-11-01", 18 - it)) }
        assertNull(choose(listOf("mem:hier" to yesterday), yesterday))
        assertNull(choose(emptyList(), emptyList()))
    }
}
