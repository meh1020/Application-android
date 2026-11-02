package com.vista.photoeditor.widget

import com.vista.photoeditor.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryWidgetLinkTest {

    @Test
    fun `un souvenir s'ouvre sur sa grille puis sur la photo`() {
        assertEquals(
            listOf(Screen.AlbumDetail("mem:42"), Screen.Viewer("mem:42", 7L)),
            MemoryWidgetLink.screens("mem:42", 7L),
        )
    }

    @Test
    fun `une photo de ce jour-là s'ouvre parmi toutes les photos`() {
        assertEquals(listOf(Screen.Viewer("all", 7L)), MemoryWidgetLink.screens("all", 7L))
    }
}
