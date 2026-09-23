package com.vista.photoeditor.editor

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.vista.photoeditor.data.MediaPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageIO {

    private const val ALBUM = "Vista"

    private val imagesCollection
        get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Décode l'image (orientation EXIF appliquée) avec un grand côté d'au plus [maxSide] px. */
    suspend fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        scaleDown(bitmap, maxSide)
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val s = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * s).roundToInt().coerceAtLeast(1),
            (bitmap.height * s).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    fun transform(src: Bitmap, geometry: Geometry): Bitmap {
        if (geometry.quarterTurns == 0 && !geometry.flipH && !geometry.flipV) return src
        val matrix = Matrix().apply {
            postRotate(90f * geometry.quarterTurns)
            postScale(if (geometry.flipH) -1f else 1f, if (geometry.flipV) -1f else 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /** Rotation libre autour du centre avec zoom pour couvrir le cadre ; taille inchangée. */
    fun straighten(src: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return src
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val scale = straightenScale(w, h, degrees)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            translate(w / 2f, h / 2f)
            rotate(degrees)
            scale(scale, scale)
            drawBitmap(src, -w / 2f, -h / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    fun crop(src: Bitmap, rect: NormRect): Bitmap {
        if (rect.isFull) return src
        val x = (rect.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (rect.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val w = (rect.width * src.width).roundToInt().coerceIn(1, src.width - x)
        val h = (rect.height * src.height).roundToInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    /** Vignette carrée pour les aperçus de filtres. */
    fun thumbnail(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, size, size, true)
    }

    /** Applique les couleurs et la vignette : doit rester fidèle à l'aperçu Compose. */
    fun render(src: Bitmap, colorMatrix: FloatArray, vignette: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (vignette > 0f) {
            val w = out.width.toFloat()
            val h = out.height.toFloat()
            val edge = Color.argb((VIGNETTE_MAX_ALPHA * vignette * 255).roundToInt(), 0, 0, 0)
            val vignettePaint = Paint().apply {
                shader = RadialGradient(
                    w / 2f, h / 2f, hypot(w, h) / 2f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
                    floatArrayOf(0f, VIGNETTE_INNER_STOP, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w, h, vignettePaint)
        }
        return out
    }

    /**
     * Enregistre le rendu dans la galerie en recopiant les métadonnées de [source]
     * (date de prise de vue, lieu, appareil) pour que la copie se range au bon endroit.
     */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap, source: Uri?): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val originalExif = source?.let { readSourceExif(context, it) }
        val takenAt = originalExif?.let { exifDateMillis(it) } ?: source?.let { mediaDateMillis(context, it) }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "VISTA_${timestamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            // Annoncée dès la création : la copie se range avec la photo d'origine.
            takenAt?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(imagesCollection, values)
            ?: error("Impossible de créer le fichier")
        try {
            resolver.openOutputStream(uri).use { stream ->
                checkNotNull(stream) { "Flux de sortie indisponible" }
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "Compression échouée" }
            }
            if (originalExif != null) copyExif(context, originalExif, uri)

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            takenAt?.let { values.put(MediaStore.Images.Media.DATE_TAKEN, it) }
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Métadonnées recopiées : la date, le lieu et l'appareil ; pas les dimensions ni l'orientation. */
    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
    )

    /** Le lieu n'est lisible que sur l'original, et seulement avec ACCESS_MEDIA_LOCATION. */
    private fun readSourceExif(context: Context, source: Uri): ExifInterface? = runCatching {
        val uri = if (MediaPermissions.canReadLocation(context)) {
            runCatching { MediaStore.setRequireOriginal(source) }.getOrDefault(source)
        } else {
            source
        }
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    }.getOrNull()

    private fun copyExif(context: Context, from: ExifInterface, target: Uri) {
        runCatching {
            context.contentResolver.openFileDescriptor(target, "rw")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                COPIED_TAGS.forEach { tag ->
                    from.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
                }
                // La rotation est déjà appliquée aux pixels du rendu.
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Vista")
                exif.saveAttributes()
            }
        }
    }

    private fun exifDateMillis(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    private fun mediaDateMillis(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED),
            null, null, null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val taken = cursor.getLong(0)
            if (taken > 0) taken else cursor.getLong(1) * 1000
        }
    }.getOrNull()

    /** Photos exportées par l'application (visibles sans permission depuis Android 10). */
    fun queryRecents(context: Context, limit: Int = 20): List<Uri> = runCatching {
        context.contentResolver.query(
            imagesCollection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_PICTURES}/$ALBUM%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            buildList {
                while (size < limit && cursor.moveToNext()) {
                    add(ContentUris.withAppendedId(imagesCollection, cursor.getLong(idColumn)))
                }
            }
        }
    }.getOrNull().orEmpty()

    /** Emplacement où l'appareil photo écrira la prise de vue. */
    fun createCaptureUri(context: Context): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "VISTA_CAPTURE_${timestamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$ALBUM")
        }
        context.contentResolver.insert(imagesCollection, values)
    }.getOrNull()

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
