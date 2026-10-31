package com.vista.photoeditor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {

    private val all = listOf(
        Screen.Onboarding, Screen.Home, Screen.AlbumDetail("bucket:42"), Screen.AlbumDetail("cat:beach"),
        Screen.Viewer("mem:17", 123L), Screen.Viewer("all", 9L), Screen.Editor, Screen.Search,
        Screen.Explore, Screen.Trash, Screen.Duplicates, Screen.Hidden,
    )

    @Test
    fun `chaque écran se relit tel quel`() {
        all.forEach { assertEquals(it, decodeScreen(encodeScreen(it))) }
    }

    @Test
    fun `texte inconnu ou incomplet`() {
        assertNull(decodeScreen("inconnu"))
        assertNull(decodeScreen("viewer:abc:all"))
        assertNull(decodeScreen("viewer:12"))
        assertNull(decodeScreen("album:"))
    }

    @Test
    fun `la pile s'arrête avant le dossier masqué`() {
        val saved = listOf(Screen.Home, Screen.Explore, Screen.Hidden, Screen.Viewer("all", 1L)).map(::encodeScreen)
        assertEquals(listOf(Screen.Home, Screen.Explore), restoreStack(saved))
    }

    @Test
    fun `la pile s'arrête à un écran illisible`() {
        assertEquals(listOf(Screen.Home), restoreStack(listOf("home", "???", "search")))
    }
}
