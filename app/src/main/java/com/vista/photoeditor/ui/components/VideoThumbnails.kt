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
import coil3.size.pxOrElse
import coil3.toAndroidUri
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

/** À appeler avant le premier affichage : photos et vidéos passent par le même chargeur. */
fun installImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(VideoThumbnailFetcher.Factory()) }
            .build()
    }
}
