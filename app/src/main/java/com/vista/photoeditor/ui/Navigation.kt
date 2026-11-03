package com.vista.photoeditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class AlbumDetail(val albumKey: String) : Screen
    data class Viewer(val albumKey: String, val photoId: Long) : Screen
    /** Découpage (début et fin) d'une vidéo de la galerie. */
    data class Trim(val photoId: Long) : Screen
    data object Editor : Screen
    data object Search : Screen
    data object Explore : Screen
    data object Trash : Screen
    data object Duplicates : Screen
    data object Hidden : Screen
}

/**
 * Pile de navigation simple, conservée lors des changements de configuration **et** quand Android
 * arrête l'app en arrière-plan (passage par l'appareil photo, une autre app…) : au retour, on
 * retrouve l'écran qu'on avait quitté. Le dossier masqué fait exception (voir [restoreStack]).
 */
class Navigator(private val saved: SavedStateHandle) : ViewModel() {
    private val stack = mutableStateListOf<Screen>().apply {
        saved.get<ArrayList<String>>(KEY_STACK)?.let { addAll(restoreStack(it)) }
    }

    val current: Screen? get() = stack.lastOrNull()
    val isInitialized get() = stack.isNotEmpty()

    /** Sens du dernier déplacement : sert à orienter l'animation de transition. */
    var isForward by mutableStateOf(true)
        private set

    fun reset(screen: Screen) {
        isForward = true
        stack.clear()
        stack.add(screen)
        persist()
    }

    fun push(screen: Screen) {
        if (stack.lastOrNull() == screen) return
        isForward = true
        stack.add(screen)
        persist()
    }

    /** Retourne false s'il n'y a plus d'écran à dépiler. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        isForward = false
        stack.removeAt(stack.lastIndex)
        persist()
        return true
    }

    /** Remplace l'écran courant (ex. visionneuse dont la photo a été supprimée). */
    fun replace(screen: Screen) {
        isForward = true
        if (stack.isNotEmpty()) stack[stack.lastIndex] = screen else stack.add(screen)
        persist()
    }

    private fun persist() {
        saved[KEY_STACK] = ArrayList(stack.map(::encodeScreen))
    }

    private companion object {
        const val KEY_STACK = "navigation_stack"
    }
}

/** Écran sous forme de texte, pour la sauvegarde de la pile. */
internal fun encodeScreen(screen: Screen): String = when (screen) {
    Screen.Onboarding -> "onboarding"
    Screen.Home -> "home"
    is Screen.AlbumDetail -> "album:${screen.albumKey}"
    // L'identifiant d'abord : la clé d'album contient elle-même des « : » (« bucket:42 », « cat:beach »).
    is Screen.Viewer -> "viewer:${screen.photoId}:${screen.albumKey}"
    is Screen.Trim -> "trim:${screen.photoId}"
    Screen.Editor -> "editor"
    Screen.Search -> "search"
    Screen.Explore -> "explore"
    Screen.Trash -> "trash"
    Screen.Duplicates -> "duplicates"
    Screen.Hidden -> "hidden"
}

/** Écran relu, ou null si le texte n'en désigne aucun. */
internal fun decodeScreen(code: String): Screen? = when {
    code.startsWith("album:") -> code.removePrefix("album:").takeIf { it.isNotEmpty() }?.let { Screen.AlbumDetail(it) }
    code.startsWith("trim:") -> code.removePrefix("trim:").toLongOrNull()?.let { Screen.Trim(it) }
    code.startsWith("viewer:") -> {
        val rest = code.removePrefix("viewer:")
        val photoId = rest.substringBefore(':').toLongOrNull()
        val albumKey = rest.substringAfter(':', "")
        if (photoId == null || albumKey.isEmpty()) null else Screen.Viewer(albumKey, photoId)
    }
    else -> when (code) {
        "onboarding" -> Screen.Onboarding
        "home" -> Screen.Home
        "editor" -> Screen.Editor
        "search" -> Screen.Search
        "explore" -> Screen.Explore
        "trash" -> Screen.Trash
        "duplicates" -> Screen.Duplicates
        "hidden" -> Screen.Hidden
        else -> null
    }
}

/**
 * Pile relue après un arrêt de l'app. Elle s'arrête avant le dossier masqué : il ne se rouvre
 * jamais sans que l'on se reconnaisse à nouveau, ni lui ni ce qui s'empilait au-dessus. Un écran
 * illisible coupe aussi la pile à cet endroit.
 */
internal fun restoreStack(codes: List<String>): List<Screen> {
    val screens = mutableListOf<Screen>()
    for (code in codes) {
        val screen = decodeScreen(code)
        if (screen == null || screen == Screen.Hidden) break
        screens += screen
    }
    return screens
}
