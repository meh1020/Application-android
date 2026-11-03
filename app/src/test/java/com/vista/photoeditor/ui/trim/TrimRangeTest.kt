package com.vista.photoeditor.ui.trim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimRangeTest {

    @Test
    fun lesPoigneesNeSeCroisentPas() {
        // Vidéo de 10 s, fin à 6 s : le début ne dépasse pas 5 s.
        assertEquals(5_000L, TrimRange.moveStart(8_000, end = 6_000, durationMillis = 10_000))
        // Début à 4 s : la fin ne descend pas sous 5 s.
        assertEquals(5_000L, TrimRange.moveEnd(1_000, start = 4_000, durationMillis = 10_000))
    }

    @Test
    fun lesPoigneesRestentDansLaVideo() {
        assertEquals(0L, TrimRange.moveStart(-500, end = 6_000, durationMillis = 10_000))
        assertEquals(10_000L, TrimRange.moveEnd(12_000, start = 4_000, durationMillis = 10_000))
    }

    @Test
    fun videoPlusCourteQueLEcartMinimum() {
        // 600 ms : l'écart minimum devient toute la vidéo, rien ne déborde.
        assertEquals(0L, TrimRange.moveStart(300, end = 600, durationMillis = 600))
        assertEquals(600L, TrimRange.moveEnd(100, start = 0, durationMillis = 600))
    }

    @Test
    fun selectionEntiere() {
        assertTrue(TrimRange.isWhole(0, 10_000, 10_000))
        assertTrue(!TrimRange.isWhole(1, 10_000, 10_000))
    }
}
