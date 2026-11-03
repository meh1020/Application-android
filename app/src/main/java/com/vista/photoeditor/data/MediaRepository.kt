package com.vista.photoeditor.data

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat

data class MediaPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucketId: Long,
    val bucketName: String,
    val dateMillis: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val isFavorite: Boolean,
    val mimeType: String,
    /** Rotation EXIF en degrés ; MediaStore donne les dimensions avant rotation. */
    val orientation: Int = 0,
    /** Vidéo plutôt que photo : la visionneuse la lit, la découpe et le dossier masqué la prennent ; l'éditeur l'écarte. */
    val isVideo: Boolean = false,
    /** Durée d'une vidéo, en millisecondes (0 pour une photo). */
    val durationMillis: Long = 0,
    /** Dans la corbeille : date à laquelle Android la supprimera définitivement (0 sinon). */
    val expiresMillis: Long = 0,
) {
    private val quarterTurned get() = orientation == 90 || orientation == 270

    /** Dimensions telles que la photo s'affiche, rotation appliquée. */
    val displayWidth get() = if (quarterTurned) height else width
    val displayHeight get() = if (quarterTurned) width else height
}

data class Album(val key: String, val name: String, val photos: List<MediaPhoto>) {
    val cover get() = photos.firstOrNull()
    val count get() = photos.size
}

object MediaPermissions {
    val required: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Ce qui est demandé à l'utilisateur : l'accès aux photos, plus le lieu qu'elles contiennent. */
    val request: Array<String>
        get() = required + Manifest.permission.ACCESS_MEDIA_LOCATION

    fun hasAccess(context: Context) = required.any { granted(context, it) }

    /** Le lieu d'origine n'est lisible qu'avec cette permission. */
    fun canReadLocation(context: Context) = granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

    /** Android 14+ : l'utilisateur n'a partagé qu'une sélection de photos. */
    fun isPartial(context: Context) = Build.VERSION.SDK_INT >= 34 &&
        !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
        !granted(context, Manifest.permission.READ_MEDIA_VIDEO) &&
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    /**
     * Accès aux photos accordé avant que l'app ne montre les vidéos : il reste à demander les vidéos
     * (même groupe d'autorisations : Android les accorde en général sans poser de nouvelle question).
     */
    fun lacksVideos(context: Context) = Build.VERSION.SDK_INT >= 33 &&
        granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
        !granted(context, Manifest.permission.READ_MEDIA_VIDEO)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

object MediaRepository {
    const val ALL_KEY = "all"
    const val FAVORITES_KEY = "favorites"
    const val VIDEOS_KEY = "videos"

    /** Album des photos exportées par l'éditeur. */
    const val CREATIONS_NAME = "Créations Vista"

    val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val videoCollection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val friendlyNames = mapOf(
        "Camera" to "Camera",
        "Screenshots" to "Captures d'écran",
        "Download" to "Téléchargements",
        "Pictures" to "Images",
        "Movies" to "Films",
        "Vista" to CREATIONS_NAME,
    )

    /**
     * Photos et vidéos de la galerie, les plus récentes d'abord. Chacune garde l'adresse de sa
     * propre collection (images ou vidéos) : c'est celle qu'attendent corbeille, favoris et partage.
     * Les identifiants MediaStore sont communs aux deux collections : ils ne se chevauchent pas.
     */
    fun loadPhotos(context: Context, trashed: Boolean = false): List<MediaPhoto> =
        (query(context, collection, trashed, video = false) + query(context, videoCollection, trashed, video = true))
            .sortedByDescending { it.dateMillis }

    private fun query(context: Context, from: Uri, trashed: Boolean, video: Boolean): List<MediaPhoto> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val photos = mutableListOf<MediaPhoto>()
        runCatching {
            context.contentResolver.query(from, projection, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val bucketId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val bucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val favorite = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val orientation = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
                val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val expires = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
                while (c.moveToNext()) {
                    val photoId = c.getLong(id)
                    val rawBucket = c.getString(bucketName).orEmpty()
                    photos += MediaPhoto(
                        id = photoId,
                        uri = ContentUris.withAppendedId(from, photoId),
                        name = c.getString(name).orEmpty(),
                        bucketId = c.getLong(bucketId),
                        bucketName = friendlyNames[rawBucket] ?: rawBucket.ifEmpty { "Autres" },
                        dateMillis = c.getLong(taken).takeIf { it > 0 } ?: (c.getLong(added) * 1000),
                        width = c.getInt(width),
                        height = c.getInt(height),
                        sizeBytes = c.getLong(size),
                        isFavorite = c.getInt(favorite) == 1,
                        mimeType = c.getString(mime).orEmpty(),
                        orientation = c.getInt(orientation),
                        isVideo = video,
                        durationMillis = if (video) c.getLong(duration) else 0,
                        expiresMillis = if (trashed) c.getLong(expires) * 1000 else 0,
                    )
                }
            }
        }
        return photos
    }

    /** Albums triés par taille, avec « Favoris » puis « Vidéos » juste après le premier, s'ils existent. */
    fun buildAlbums(photos: List<MediaPhoto>): List<Album> {
        val buckets = photos.groupBy { it.bucketId }
            .map { (id, list) -> Album("bucket:$id", list.first().bucketName, list) }
            .sortedByDescending { it.count }
            .toMutableList()
        val favorites = photos.filter { it.isFavorite }
        if (favorites.isNotEmpty()) {
            buckets.add(minOf(1, buckets.size), Album(FAVORITES_KEY, "Favoris", favorites))
        }
        // Comme le type de média « Vidéos » de Photos sur iOS : toutes les vidéos, quel que soit leur dossier.
        val videos = photos.filter { it.isVideo }
        if (videos.isNotEmpty()) {
            val position = if (favorites.isNotEmpty()) 2 else 1
            buckets.add(minOf(position, buckets.size), Album(VIDEOS_KEY, "Vidéos", videos))
        }
        return buckets
    }

    fun trashRequest(context: Context, uris: List<Uri>, trash: Boolean): PendingIntent =
        MediaStore.createTrashRequest(context.contentResolver, uris, trash)

    fun deleteRequest(context: Context, uris: List<Uri>): PendingIntent =
        MediaStore.createDeleteRequest(context.contentResolver, uris)

    fun favoriteRequest(context: Context, uris: List<Uri>, favorite: Boolean): PendingIntent =
        MediaStore.createFavoriteRequest(context.contentResolver, uris, favorite)
}
