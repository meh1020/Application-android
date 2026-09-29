package com.vista.photoeditor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GradeTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
    private fun luma(p: Int) = 0.2126f * r(p) + 0.7152f * g(p) + 0.0722f * b(p)

    /** Rendu complet d'un filtre à pleine intensité, comme l'export : matrice puis étalonnage. */
    private fun render(filterId: String, vararg pixels: Int): IntArray {
        val state = EditState(filterId = filterId)
        val m = state.colorMatrix()
        val out = IntArray(pixels.size) { i ->
            val p = pixels[i]
            fun row(o: Int) = (m[o] * r(p) + m[o + 1] * g(p) + m[o + 2] * b(p) + m[o + 4]).toInt().coerceIn(0, 255)
            rgb(row(0), row(5), row(10))
        }
        state.grade().applyTo(out)
        return out
    }

    @Test
    fun tealAndOrangeTintsDarksTealAndBrightsOrangeWithoutChangingBrightness() {
        val (dark, bright) = Grade(split = SplitTone.of(0x0E6B73, 0.45f, 0xFF9A48, 0.35f)).let { grade ->
            intArrayOf(rgb(50, 50, 50), rgb(210, 210, 210)).also { grade.applyTo(it) }
        }.let { it[0] to it[1] }
        assertTrue("ombres bleu-vert : ${r(dark)} ${g(dark)} ${b(dark)}", b(dark) > r(dark) + 5 && g(dark) > r(dark) + 5)
        assertTrue("clairs orangés : ${r(bright)} ${g(bright)} ${b(bright)}", r(bright) > b(bright) + 10)
        assertEquals(50f, luma(dark), 2f)
        assertEquals(210f, luma(bright), 2f)
    }

    @Test
    fun keepRedLeavesRedsAndTurnsTheRestGray() {
        val (red, blue, grayish) = render("keepred", rgb(200, 30, 40), rgb(40, 60, 200), rgb(140, 128, 128)).toList()
        assertTrue("le rouge reste rouge", r(red) > g(red) + 100)
        assertTrue("le bleu devient gris : ${r(blue)} ${g(blue)} ${b(blue)}", abs(r(blue) - b(blue)) <= 2 && abs(g(blue) - b(blue)) <= 2)
        assertTrue("un gris rosé n'est pas « rouge »", abs(r(grayish) - g(grayish)) <= 3)
    }

    @Test
    fun selectiveColorStrengthFollowsIntensity() {
        val half = EditState(filterId = "keepblue", filterIntensity = 0.5f).grade()
        val px = intArrayOf(rgb(220, 60, 40)).also { half.applyTo(it) }[0]
        // À moitié : le rouge perd de sa saturation sans devenir gris.
        assertTrue(r(px) in 120..200 && r(px) - g(px) > 40)
    }

    @Test
    fun duotoneRunsFromTheDarkColorToTheLightColor() {
        val (black, white) = render("cyanotype", rgb(0, 0, 0), rgb(255, 255, 255)).toList()
        assertEquals(listOf(0x0A, 0x20, 0x4E), listOf(r(black), g(black), b(black)))
        assertEquals(listOf(0xE4, 0xF0, 0xFA), listOf(r(white), g(white), b(white)).map { it })
    }

    @Test
    fun infraredMakesFoliageBrightAndSkyDark() {
        val (leaf, sky) = render("infrared", rgb(70, 140, 60), rgb(90, 150, 230)).toList()
        assertTrue("feuillage ${luma(leaf)} > ciel ${luma(sky)}", luma(leaf) > luma(sky) + 20)
    }

    @Test
    fun everyFilterRendersAndIdsAreUnique() {
        assertEquals(Filters.all.size, Filters.all.map { it.id }.toSet().size)
        val sample = intArrayOf(rgb(0, 0, 0), rgb(128, 90, 60), rgb(255, 255, 255), rgb(30, 160, 220))
        Filters.all.forEach { filter ->
            assertEquals(filter.id, 20, filter.matrix.size)
            render(filter.id, *sample)
        }
        assertTrue(render(Filters.ORIGINAL_ID, *sample).contentEquals(sample))
    }

    @Test
    fun zeroIntensityIsNeutral() {
        Filters.all.forEach { filter ->
            assertTrue(filter.id, EditState(filterId = filter.id, filterIntensity = 0f).grade().isIdentity)
        }
    }
}
