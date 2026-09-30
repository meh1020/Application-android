package com.vista.photoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SimilarPhotosTest {

    /** Vecteur de norme 1 à similarité [s] de l'axe x. */
    private fun at(s: Float) = FloatArray(4).also { val a = kotlin.math.acos(s.toDouble()); it[0] = cos(a).toFloat(); it[1] = sin(a).toFloat() }

    @Test
    fun closestFirstWithoutThePhotoItselfNorDissimilarOnes() {
        val target = at(1f)
        val candidates = listOf(1L to target, 2L to at(0.6f), 3L to at(0.9f), 4L to at(0.4f), 5L to at(0.75f))
        assertEquals(listOf(3L, 5L, 2L), SimilarPhotos.rank(1L, target, candidates))
    }

    @Test
    fun atMostTwelve() {
        val candidates = (2L..40L).map { it to at(0.9f) }
        assertEquals(SimilarPhotos.LIMIT, SimilarPhotos.rank(1L, at(1f), candidates).size)
    }

    @Test
    fun unanalysedVectorsAreSkipped() {
        assertEquals(emptyList<Long>(), SimilarPhotos.rank(1L, at(1f), listOf(2L to FloatArray(0))))
    }
}
