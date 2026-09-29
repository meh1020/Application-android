package com.vista.photoeditor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCurveTest {

    private val values = listOf(-100f, -60f, -30f, 0f, 30f, 60f, 100f)

    @Test
    fun blacksAndWhitesNeverMove() {
        for (s in values) for (h in values) {
            val curve = ToneCurve.of(s, h)
            assertEquals("noir, ombres $s, hautes lumières $h", 0f, curve.map(0f), 1e-6f)
            assertEquals("blanc, ombres $s, hautes lumières $h", 1f, curve.map(1f), 1e-6f)
        }
    }

    @Test
    fun curveNeverFoldsBack() {
        // Toujours croissante, avec une pente minimale : ni inversion, ni aplats.
        for (s in values) for (h in values) {
            val curve = ToneCurve.of(s, h)
            for (i in 0 until 1000) {
                val slope = (curve.map((i + 1) / 1000f) - curve.map(i / 1000f)) * 1000f
                assertTrue("pente $slope en ${i / 1000f}, ombres $s, hautes lumières $h", slope >= 0.24f)
            }
        }
    }

    @Test
    fun shadowsLiftTheDarksMoreThanTheBrights() {
        val curve = ToneCurve.of(100f, 0f)
        val dark = curve.map(0.2f) - 0.2f
        val bright = curve.map(0.85f) - 0.85f
        assertTrue("ombres +$dark, clairs +$bright", dark > 0.2f && bright < 0.02f)
    }

    @Test
    fun negativeHighlightsRecoverTheBrightsAndKeepTheDarks() {
        val curve = ToneCurve.of(0f, -100f)
        assertTrue(curve.map(0.8f) < 0.8f - 0.2f)
        assertEquals(0.15f, curve.map(0.15f), 0.01f)
    }

    private fun saturation(r: Int, g: Int, b: Int) = (maxOf(r, g, b) - minOf(r, g, b)) / maxOf(r, g, b).toFloat()

    @Test
    fun darkColorsKeepTheirHueWithoutTurningGaudy() {
        // Un orange sombre éclairci reste orange, un bleu marine reste un bleu marine : ni gris,
        // ni couleur criarde.
        for ((r0, g0, b0) in listOf(Triple(90, 45, 15), Triple(30, 35, 80))) {
            val pixels = intArrayOf((0xFF shl 24) or (r0 shl 16) or (g0 shl 8) or b0)
            ToneCurve.of(100f, 0f).applyTo(pixels)
            val r = (pixels[0] shr 16) and 0xFF
            val g = (pixels[0] shr 8) and 0xFF
            val b = pixels[0] and 0xFF
            assertTrue("éclairci : $r $g $b", r + g + b > r0 + g0 + b0 + 30)
            // Même ordre des couleurs : même teinte.
            assertEquals(listOf(r0, g0, b0).sortedDescending().map { listOf(r0, g0, b0).indexOf(it) }, listOf(r, g, b).sortedDescending().map { listOf(r, g, b).indexOf(it) })
            val before = saturation(r0, g0, b0)
            val after = saturation(r, g, b)
            assertTrue("saturation $before -> $after", after in before * 0.6f..before * 1.05f)
        }
    }

    @Test
    fun neutralCurveLeavesPixelsUntouched() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val copy = pixels.copyOf()
        ToneCurve.of(0f, 0f).applyTo(pixels)
        assertTrue(pixels.contentEquals(copy))
    }
}
