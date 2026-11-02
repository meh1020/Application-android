package com.vista.photoeditor.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vista.photoeditor.MainActivity
import com.vista.photoeditor.data.MediaRepository
import com.vista.photoeditor.ui.Screen

/**
 * Ce qu'ouvre un toucher sur le widget : la photo affichée, dans son souvenir. Le retour ramène
 * à la grille du souvenir, puis à l'accueil, comme si on l'avait ouvert depuis Explorer.
 */
object MemoryWidgetLink {

    const val ACTION_OPEN = "com.vista.photoeditor.action.OPEN_MEMORY"
    private const val EXTRA_ALBUM = "album"
    private const val EXTRA_PHOTO = "photo"

    /** Ouvre [photoId] dans l'album [albumKey]. */
    fun intent(context: Context, albumKey: String, photoId: Long): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            // Une adresse propre à la photo : Android ne confond pas deux widgets ni deux mises à jour.
            .setData(Uri.parse("vista://memory/$photoId"))
            .putExtra(EXTRA_ALBUM, albumKey)
            .putExtra(EXTRA_PHOTO, photoId)
            // App déjà ouverte : l'écran existant reçoit la demande (onNewIntent) au lieu d'en créer un.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Ouvre simplement l'app (widget vide ou sans accès aux photos). */
    fun openApp(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Écrans à empiler au-dessus de l'accueil, ou null si [intent] ne vient pas du widget. */
    fun screens(intent: Intent?): List<Screen>? {
        if (intent?.action != ACTION_OPEN) return null
        val albumKey = intent.getStringExtra(EXTRA_ALBUM) ?: return null
        val photoId = intent.getLongExtra(EXTRA_PHOTO, -1L).takeIf { it >= 0 } ?: return null
        return screens(albumKey, photoId)
    }

    /** Souvenir : sa grille, puis la photo ; photo isolée (« ce jour-là ») : la photo, parmi toutes. */
    internal fun screens(albumKey: String, photoId: Long): List<Screen> =
        if (albumKey == MediaRepository.ALL_KEY) listOf(Screen.Viewer(albumKey, photoId))
        else listOf(Screen.AlbumDetail(albumKey), Screen.Viewer(albumKey, photoId))
}
