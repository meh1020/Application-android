package com.vista.photoeditor.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /** Pellicules déjà extraites : revenir sur une vidéo ne relit pas le fichier. */
    private val stripCache = LruCache<String, List<Bitmap>>(6)

    /**
     * Images de la pellicule de lecture, comme dans Photos sur iOS : [count] images réparties sur
     * toute la vidéo, de [height] px de haut. [onFrame] reçoit chaque image dès qu'elle est prête,
     * pour que la pellicule se remplisse progressivement. Une image illisible est sautée.
     */
    suspend fun filmstrip(
        context: Context,
        uri: Uri,
        count: Int,
        height: Int,
        onFrame: (index: Int, frame: Bitmap) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val key = "$uri#$count#$height"
        stripCache.get(key)?.let { cached ->
            cached.forEachIndexed { i, frame -> onFrame(i, frame) }
            return@withContext
        }
        val frames = ArrayList<Bitmap>(count)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@withContext) * 1000L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (vw, vh) = if (rotation % 180 != 0) h to w else w to h
            val ratio = if (vw > 0 && vh > 0) vw.toFloat() / vh else 1f
            val frameH = height.coerceAtLeast(16)
            val frameW = (frameH * ratio).toInt().coerceAtLeast(16)
            for (i in 0 until count) {
                ensureActive()
                val timeUs = durationUs * (2 * i + 1) / (2 * count)
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, frameW, frameH)
                }.getOrNull() ?: continue
                frames += frame
                withContext(Dispatchers.Main) { onFrame(i, frame) }
            }
            if (frames.size == count) stripCache.put(key, frames)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Vidéo illisible : la pellicule garde ses cases vides, la lecture reste possible.
        } finally {
            retriever.release()
        }
    }
}
