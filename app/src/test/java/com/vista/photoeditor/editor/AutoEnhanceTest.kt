package com.vista.photoeditor.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Scènes synthétiques au défaut connu, de [WIDTH] × [HEIGHT] pixels : en haut, un damier de gris
 * (ce qui devrait rester neutre), en bas des aplats de couleur franche (ce qui ne doit pas fausser
 * la mesure).
 */
class AutoEnhanceTest {

    private val WIDTH = 100
    private val HEIGHT = 60
    private val GRAY_PIXELS = WIDTH * HEIGHT / 2

    private fun scene(transform: (Float, Float, Float) -> Triple<Float, Float, Float> = { r, g, b -> Triple(r, g, b) }): IntArray {
        val colors = listOf(
            Triple(70f, 140f, 60f), Triple(40f, 90f, 160f), Triple(170f, 60f, 50f),
            Triple(200f, 170f, 90f), Triple(110f, 80f, 150f), Triple(60f, 120f, 120f),
        )
        return IntArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH
            val y = i / WIDTH
            if (y < HEIGHT / 2) {
                // Cases de 5 × 5 pixels, gris de 12 à 240.
                val v = 12f + 228f * (((x / 5) * 7 + (y / 5) * 3) % 20) / 19f
                argb(transform(v, v, v))
            } else {
                val (r, g, b) = colors[x * colors.size / WIDTH]
                val k = 0.6f + 0.8f * (y - HEIGHT / 2) / (HEIGHT / 2 - 1)
                argb(transform(r * k, g * k, b * k))
            }
        }
    }

    private fun argb(c: Triple<Float, Float, Float>): Int {
        fun ch(v: Float) = v.roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(c.first) shl 16) or (ch(c.second) shl 8) or ch(c.third)
    }

    private fun apply(pixels: IntArray, values: Map<Adjustment, Float>): List<Triple<Float, Float, Float>> {
        val m = EditState(adjustments = values.filterValues { it != 0f }).colorMatrix()
        return pixels.map { p ->
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            fun row(o: Int) = (m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 4]).coerceIn(0f, 255f)
            Triple(row(0), row(5), row(10))
        }
    }

    /** Écart rouge − bleu moyen des gris de la scène (moitié haute). */
    private fun grayCast(colors: List<Triple<Float, Float, Float>>) =
        colors.take(GRAY_PIXELS).filter { (r, g, b) -> (r + g + b) / 3 in 40f..220f }.map { it.first - it.third }.average()

    private fun median(colors: List<Triple<Float, Float, Float>>) =
        colors.map { (r, g, b) -> 0.2126f * r + 0.7152f * g + 0.0722f * b }.sorted()[colors.size / 2]

    @Test
    fun wellExposedNeutralSceneIsLeftAlone() {
        val values = AutoEnhance.compute(scene(), WIDTH, EditState())
        AutoEnhance.ADJUSTMENTS.forEach { assertEquals("$it", 0f, values.getValue(it)) }
    }

    @Test
    fun blueCastIsWarmed() {
        val pixels = scene { r, g, b -> Triple(r * 0.85f, g, b * 1.15f) }
        val values = AutoEnhance.compute(pixels, WIDTH, EditState())
        assertTrue("chaleur ${values[Adjustment.WARMTH]}", values.getValue(Adjustment.WARMTH) > 0f)
        val before = abs(grayCast(apply(pixels, emptyMap())))
        val after = abs(grayCast(apply(pixels, values)))
        // Correction prudente, mais nette.
        assertTrue("dominante $before -> $after", after < before * 0.6)
    }

    @Test
    fun greenCastIsCorrectedWithTint() {
        val pixels = scene { r, g, b -> Triple(r, g * 1.12f, b) }
        val values = AutoEnhance.compute(pixels, WIDTH, EditState())
        assertTrue("teinte ${values[Adjustment.TINT]}", values.getValue(Adjustment.TINT) > 0f)
    }

    @Test
    fun warmCastIsOnlyHalfCooled() {
        // Soleil couchant : la chaleur est souvent voulue.
        val pixels = scene { r, g, b -> Triple(r * 1.1f, g, b * 0.88f) }
        val values = AutoEnhance.compute(pixels, WIDTH, EditState())
        val after = grayCast(apply(pixels, values))
        assertTrue("chaleur ${values[Adjustment.WARMTH]}", values.getValue(Adjustment.WARMTH) < 0f)
        assertTrue("reste chaud : $after", after > 5.0)
    }

    @Test
    fun underexposedSceneIsBrightened() {
        val pixels = scene { r, g, b -> Triple(r * 0.45f, g * 0.45f, b * 0.45f) }
        val values = AutoEnhance.compute(pixels, WIDTH, EditState())
        assertTrue("exposition ${values[Adjustment.EXPOSURE]}", values.getValue(Adjustment.EXPOSURE) > 0f)
        val before = median(apply(pixels, emptyMap()))
        val after = median(apply(pixels, values))
        // L'éclaircissement est plafonné (× 1,5) : assez pour sauver la photo, sans faire ressortir
        // le bruit d'une photo très sombre.
        assertTrue("tons moyens $before -> $after", after > before * 1.4f)
    }

    @Test
    fun brightHighlightsAreNotBurnt() {
        // Scène sombre avec des lampes allumées : l'éclaircir brûlerait les lampes.
        val dark = scene { r, g, b -> Triple(r * 0.4f, g * 0.4f, b * 0.4f) }
        val lamps = IntArray(WIDTH * 3) { argb(Triple(255f, 250f, 240f)) }
        val values = AutoEnhance.compute(dark + lamps, WIDTH, EditState())
        assertTrue("exposition ${values[Adjustment.EXPOSURE]}", values.getValue(Adjustment.EXPOSURE) <= 0f)
    }

    @Test
    fun hazySceneGetsContrast() {
        val pixels = scene { r, g, b -> Triple(r * 0.6f + 50f, g * 0.6f + 50f, b * 0.6f + 50f) }
        val values = AutoEnhance.compute(pixels, WIDTH, EditState())
        assertTrue("contraste ${values[Adjustment.CONTRAST]}", values.getValue(Adjustment.CONTRAST) > 0f)
    }

    @Test
    fun emptyOrFlatImagesAreLeftAlone() {
        assertTrue(AutoEnhance.compute(IntArray(0), WIDTH, EditState()).values.all { it == 0f })
        val gray = IntArray(WIDTH * HEIGHT) { argb(Triple(128f, 128f, 128f)) }
        assertTrue(AutoEnhance.compute(gray, WIDTH, EditState()).values.all { it == 0f })
    }
}
