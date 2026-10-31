package com.vista.photoeditor.ui.components

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.request.allowHardware
import coil3.size.pxOrElse
import coil3.toAndroidUri
import android.graphics.Bitmap
import com.vista.photoeditor.data.MediaThumbnails
import com.vista.photoeditor.data.VideoFrames
import kotlin.math.max

/**
 * Vignettes des vidéos pour Coil : la grille affiche une vidéo comme une photo, à partir de
 * l'image qu'Android garde déjà en cache pour elle, sans décoder la vidéo à chaque défilement.
 */
private class VideoThumbnailFetcher(
    private val uri: android.net.Uri,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val requested = max(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 })
        // Taille d'origine demandée (visionneuse) : une affiche nette, sans aller jusqu'à la 4K.
        val side = if (requested > 0) requested.coerceAtMost(MAX_SIDE) else MAX_SIDE
        val bitmap = VideoFrames.poster(options.context, uri, side)
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toAndroidUri()
            return if (VideoFrames.isVideo(uri)) VideoThumbnailFetcher(uri, options) else null
        }
    }

    private companion object {
        const val MAX_SIDE = 1440
    }
}

/**
 * Vignettes des photos pour Coil : une case de grille, une couverture d'album ou une miniature lit
 * la vignette qu'Android garde en cache pour la photo, au lieu de décoder tout le fichier à chaque
 * défilement. Pour une image plus grande que ces vignettes (visionneuse, grandes cartes), ou si
 * Android n'en a pas, Coil décode le fichier comme avant.
 */
private class PhotoThumbnailFetcher(
    private val uri: android.net.Uri,
    private val options: Options,
    private val requested: Int,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bitmap = MediaThumbnails.load(options.context, uri, requested) ?: return null
        // Vignette nettement plus petite que la case (Android en garde de plus petites sur cet
        // appareil) : le fichier complet, pour rester net.
        if (maxOf(bitmap.width, bitmap.height) < requested * MIN_COVERAGE) {
            bitmap.recycle()
            return null
        }
        val image = if (!options.allowHardware && bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() } ?: return null
        } else {
            bitmap
        }
        return ImageFetchResult(image.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val uri = data.toAndroidUri()
            if (!MediaThumbnails.isGalleryImage(uri)) return null
            val requested = max(options.size.width.pxOrElse { 0 }, options.size.height.pxOrElse { 0 })
            // Taille d'origine (visionneuse) ou plus grande que les vignettes : le fichier complet.
            if (requested <= 0 || requested > MediaThumbnails.cachedSide(options.context)) return null
            return PhotoThumbnailFetcher(uri, options, requested)
        }
    }

    private companion object {
        /** Part de la taille demandée qu'une vignette doit couvrir pour rester nette à l'écran. */
        const val MIN_COVERAGE = 0.75f
    }
}

/** À appeler avant le premier affichage : photos et vidéos passent par le même chargeur. */
fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(VideoThumbnailFetcher.Factory())
                add(PhotoThumbnailFetcher.Factory())
            }
            .build()
    }
}
