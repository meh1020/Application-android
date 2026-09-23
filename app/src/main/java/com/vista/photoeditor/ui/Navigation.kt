package com.vista.photoeditor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class AlbumDetail(val albumKey: String) : Screen
    data class Viewer(val albumKey: String, val photoId: Long) : Screen
    data object Editor : Screen
    data object Search : Screen
    data object Trash : Screen
}

/** Pile de navigation simple, conservée lors des changements de configuration. */
class Navigator : ViewModel() {
    private val stack = mutableStateListOf<Screen>()

    val current: Screen? get() = stack.lastOrNull()
    val isInitialized get() = stack.isNotEmpty()

    /** Sens du dernier déplacement : sert à orienter l'animation de transition. */
    var isForward by mutableStateOf(true)
        private set

    fun reset(screen: Screen) {
        isForward = true
        stack.clear()
        stack.add(screen)
    }

    fun push(screen: Screen) {
        if (stack.lastOrNull() == screen) return
        isForward = true
        stack.add(screen)
    }

    /** Retourne false s'il n'y a plus d'écran à dépiler. */
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        isForward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Remplace l'écran courant (ex. visionneuse dont la photo a été supprimée). */
    fun replace(screen: Screen) {
        isForward = true
        if (stack.isNotEmpty()) stack[stack.lastIndex] = screen else stack.add(screen)
    }
}
