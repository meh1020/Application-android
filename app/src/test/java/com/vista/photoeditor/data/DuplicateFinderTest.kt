package com.vista.photoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DuplicateFinderTest {

    /** Vecteur de norme 1 dont la similarité avec [base] vaut [similarity] (dans le plan base/ortho). */
    private fun near(base: FloatArray, ortho: FloatArray, similarity: Float): FloatArray {
        val angle = kotlin.math.acos(similarity.toDouble())
        return FloatArray(base.size) { (base[it] * cos(angle) + ortho[it] * sin(angle)).toFloat() }
    }

    private fun axis(dim: Int, k: Int) = FloatArray(dim).also { it[k] = 1f }

    private fun candidate(id: Long, seconds: Long, vector: FloatArray, canBurst: Boolean = true) =
        DuplicateFinder.Candidate(id, seconds * 1000, vector, canBurst)

    private val a = axis(8, 0)
    private val b = axis(8, 1)

    @Test
    fun burstTakenSecondsApartIsGrouped() {
        val groups = DuplicateFinder.groups(
            listOf(candidate(1, 0, a), candidate(2, 3, near(a, b, 0.95f)), candidate(3, 7, near(a, b, 0.97f)))
        )
        assertEquals(listOf(listOf(1L, 2L, 3L)), groups)
    }

    @Test
    fun blurredShotSecondsApartJoinsItsBurst() {
        // Prise ratée (floue) : moins ressemblante, mais à 2 secondes de la bonne.
        val close = listOf(candidate(1, 0, a), candidate(2, 2, near(a, b, 0.87f)))
        assertEquals(1, DuplicateFinder.groups(close).size)
        // Même ressemblance à 30 secondes d'écart : pas assez pour une rafale.
        val apart = listOf(candidate(1, 0, a), candidate(2, 30, near(a, b, 0.87f)))
        assertTrue(DuplicateFinder.groups(apart).isEmpty())
    }

    @Test
    fun similarPhotosTakenHoursApartAreNotABurst() {
        // Deux gratte-ciel différents, photographiés le même jour : ressemblants, mais pas une rafale.
        val groups = DuplicateFinder.groups(listOf(candidate(1, 0, a), candidate(2, 3600, near(a, b, 0.95f))))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun copyIsFoundWhateverTheDate() {
        val groups = DuplicateFinder.groups(listOf(candidate(1, 0, a), candidate(2, 86_400 * 30, near(a, b, 0.99f))))
        assertEquals(listOf(listOf(1L, 2L)), groups)
    }

    @Test
    fun documentPagesAreOnlyGroupedWhenIdentical() {
        // Deux pages différentes d'un même document, photographiées à la suite.
        val pages = listOf(candidate(1, 0, a, canBurst = false), candidate(2, 5, near(a, b, 0.95f), canBurst = false))
        assertTrue(DuplicateFinder.groups(pages).isEmpty())
        val copies = listOf(candidate(1, 0, a, canBurst = false), candidate(2, 5, near(a, b, 0.99f), canBurst = false))
        assertEquals(1, DuplicateFinder.groups(copies).size)
    }

    @Test
    fun slowlyChangingSceneIsSplitInsteadOfDrifting() {
        // Chaque prise ressemble à la précédente, mais la dernière ne ressemble plus à la première.
        val steps = (0..6).map { k ->
            // 0,25 rad : 0,97 entre deux prises, sous le seuil des doublons (0,98).
            val angle = k * 0.25
            FloatArray(8).also { it[0] = cos(angle).toFloat(); it[1] = sin(angle).toFloat() }
        }
        val groups = DuplicateFinder.groups(steps.mapIndexed { i, v -> candidate(i.toLong(), i * 5L, v) })
        assertTrue("des séries plus courtes que la scène entière : $groups", groups.all { it.size < steps.size })
    }

    @Test
    fun unanalysedPhotosAreIgnored() {
        assertTrue(DuplicateFinder.groups(listOf(candidate(1, 0, FloatArray(0)), candidate(2, 1, FloatArray(0)))).isEmpty())
    }

    private data class Shot(val name: String, val sharpness: Float, val pixels: Long)

    @Test
    fun keeperIsTheSharpestShot() {
        val series = listOf(Shot("bougée", 2f, 12_000_000), Shot("nette", 5f, 12_000_000), Shot("floue", 1f, 12_000_000))
        assertEquals("nette", DuplicateFinder.keeperOf(series, Shot::sharpness, Shot::pixels).name)
    }

    @Test
    fun equallySharpCopiesKeepTheLargest() {
        val series = listOf(Shot("copie réduite", 5f, 1_000_000), Shot("originale", 4.9f, 12_000_000))
        assertEquals("originale", DuplicateFinder.keeperOf(series, Shot::sharpness, Shot::pixels).name)
    }

    // Netteté : une scène à détails fins, nette puis floutée.

    private val size = 64

    private fun scene(brightness: Float = 1f): IntArray = IntArray(size * size) { i ->
        val x = i % size
        val y = i / size
        val v = 128 + 60 * sin(x * 0.9) * cos(y * 0.7) + 40 * (if ((x / 4 + y / 4) % 2 == 0) 1 else -1)
        gray((v * brightness).roundToInt())
    }

    private fun gray(v: Int): Int {
        val c = v.coerceIn(0, 255)
        return (0xFF shl 24) or (c shl 16) or (c shl 8) or c
    }

    private fun blur(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val x = i % size
        val y = i / size
        var sum = 0
        var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until size && yy in 0 until size) { sum += pixels[yy * size + xx] and 0xFF; n++ }
        }
        gray(sum / n)
    }

    @Test
    fun sharpPhotoScoresHigherThanBlurredOne() {
        val sharp = scene()
        assertTrue(Sharpness.score(sharp, size) > 2 * Sharpness.score(blur(sharp), size))
    }

    @Test
    fun sharpnessIgnoresBrightness() {
        // Une prise floue mais plus claire ne doit pas passer pour la plus nette.
        val brighterBlur = blur(scene(1.3f))
        assertTrue(Sharpness.score(scene(0.8f), size) > Sharpness.score(brighterBlur, size))
        val ratio = Sharpness.score(scene(0.8f), size) / Sharpness.score(scene(1f), size)
        assertEquals(1f, ratio, 0.15f)
    }

    @Test
    fun flatImageHasNoSharpness() {
        assertEquals(0f, Sharpness.score(IntArray(size * size) { gray(120) }, size))
        assertEquals(0f, sqrt(0f))
    }
}
