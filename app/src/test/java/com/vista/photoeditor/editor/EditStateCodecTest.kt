package com.vista.photoeditor.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditStateCodecTest {

    @Test
    fun `retouche par défaut`() {
        assertEquals(EditState(), decodeEditState(EditState().encode()))
        assertEquals(EditState(), decodeEditState(""))
    }

    @Test
    fun `retouche complète relue telle quelle`() {
        val state = EditState(
            filterId = "teal_orange",
            filterIntensity = 0.42f,
            adjustments = mapOf(Adjustment.EXPOSURE to -12.5f, Adjustment.VIGNETTE to 30f, Adjustment.SHADOWS to 77f),
            quarterTurns = 3,
            flipH = true,
            flipV = false,
            straighten = -7.25f,
            crop = NormRect(0.1f, 0.05f, 0.9f, 0.8f),
        )
        assertEquals(state, decodeEditState(state.encode()))
    }

    @Test
    fun `valeurs illisibles ou hors limites`() {
        val state = decodeEditState("intensity=abc\nturns=6\nstraighten=90\nadj.CONTRAST=500\nadj.INCONNU=3\ncrop=1,2")
        assertEquals(EditState().filterIntensity, state.filterIntensity)
        assertEquals(2, state.quarterTurns)
        assertEquals(MAX_STRAIGHTEN_DEGREES, state.straighten)
        assertEquals(mapOf(Adjustment.CONTRAST to 100f), state.adjustments)
        assertEquals(NormRect(), state.crop)
    }
}
