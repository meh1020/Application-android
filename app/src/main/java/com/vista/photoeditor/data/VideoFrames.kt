package com.vista.photoeditor.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Image représentative d'une vidéo : vignette de la galerie, analyse du contenu. */
object VideoFrames {

    /** Adresse d'une vidéo de MediaStore (« content://media/…/video/media/42 »), sans requête. */
    fun isVideo(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.pathSegments.contains("video")

    /**
     * Image représentative de la vidéo, d'au plus [maxSide] px de grand côté. Android la calcule
     * une fois et la garde en cache : bien plus rapide que de décoder la vidéo soi-même.
     */
    suspend fun poster(context: Context, uri: Uri, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null)
        // Les calculs sur les pixels (analyse, netteté) ont besoin d'une image en mémoire normale.
        if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }
}
