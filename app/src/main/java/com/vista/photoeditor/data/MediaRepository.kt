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
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
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
        granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

object MediaRepository {
    const val ALL_KEY = "all"
    const val FAVORITES_KEY = "favorites"

    /** Album des photos exportées par l'éditeur. */
    const val CREATIONS_NAME = "Créations Vista"

    val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val friendlyNames = mapOf(
        "Camera" to "Camera",
        "Screenshots" to "Captures d'écran",
        "Download" to "Téléchargements",
        "Pictures" to "Images",
        "Vista" to CREATIONS_NAME,
    )

    fun loadPhotos(context: Context, trashed: Boolean = false): List<MediaPhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.IS_FAVORITE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.ORIENTATION,
        )
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val photos = mutableListOf<MediaPhoto>()
        runCatching {
            context.contentResolver.query(collection, projection, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketId = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val width = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val favorite = c.getColumnIndexOrThrow(MediaStore.Images.Media.IS_FAVORITE)
                val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val orientation = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
                while (c.moveToNext()) {
                    val photoId = c.getLong(id)
                    val rawBucket = c.getString(bucketName).orEmpty()
                    photos += MediaPhoto(
                        id = photoId,
                        uri = ContentUris.withAppendedId(collection, photoId),
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
                    )
                }
            }
        }
        return photos.sortedByDescending { it.dateMillis }
    }

    /** Albums triés par taille, avec « Favoris » en deuxième position s'il existe. */
    fun buildAlbums(photos: List<MediaPhoto>): List<Album> {
        val buckets = photos.groupBy { it.bucketId }
            .map { (id, list) -> Album("bucket:$id", list.first().bucketName, list) }
            .sortedByDescending { it.count }
            .toMutableList()
        val favorites = photos.filter { it.isFavorite }
        if (favorites.isNotEmpty()) {
            buckets.add(minOf(1, buckets.size), Album(FAVORITES_KEY, "Favoris", favorites))
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
