package com.vista.photoeditor.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Vignettes des photos de la galerie qu'Android calcule une fois et garde en cache : les lire
 * évite de décoder tout le fichier (plusieurs mégaoctets) pour une case de grille ou pour l'analyse.
 *
 * Elles gardent le rapport de la photo, rotation EXIF appliquée, et tiennent dans un carré de la
 * moitié du petit côté de l'écran (540 px sur un écran de 1080 px de large, d'après MediaProvider).
 * Au-delà, ou pour une photo hors de la galerie, on revient au fichier complet.
 */
object MediaThumbnails {

    /** Photo de la galerie (« content://media/…/images/media/42 ») : Android lui garde une vignette. */
    fun isGalleryImage(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.pathSegments.contains("images")

    /** Côté du carré où tiennent les vignettes gardées par Android : la moitié du petit côté de l'écran. */
    fun cachedSide(context: Context): Int {
        val metrics = context.resources.displayMetrics
        return min(metrics.widthPixels, metrics.heightPixels) / 2
    }

    /**
     * Vrai si la vignette ([thumbWidth] × [thumbHeight]) est tournée comme la photo telle qu'elle
     * s'affiche ([width] × [height], rotation EXIF appliquée). Une image carrée, ou une photo dont
     * MediaStore ignore les dimensions, ne permet pas d'en juger : elle est acceptée.
     */
    fun sameOrientation(width: Int, height: Int, thumbWidth: Int, thumbHeight: Int): Boolean {
        if (width <= 0 || height <= 0 || width == height || thumbWidth == thumbHeight) return true
        return (width > height) == (thumbWidth > thumbHeight)
    }

    /**
     * Vignette de [uri] d'au plus environ [maxSide] px de grand côté, ou null si Android n'en a pas
     * ou si son petit côté fait moins de [minShortSide] px. [software] : image en mémoire normale,
     * nécessaire pour lire ses pixels (analyse) ; sinon elle peut rester en mémoire graphique.
     */
    suspend fun load(
        context: Context,
        uri: Uri,
        maxSide: Int,
        minShortSide: Int = 0,
        software: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!isGalleryImage(uri)) return@withContext null
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, Size(maxSide, maxSide), null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext null
        }
        when {
            min(bitmap.width, bitmap.height) < minShortSide -> {
                bitmap.recycle()
                null
            }
            software && bitmap.config == Bitmap.Config.HARDWARE ->
                bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
            else -> bitmap
        }
    }
}
