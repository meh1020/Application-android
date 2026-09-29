package com.vista.photoeditor.data

import java.util.Locale
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant
import androidx.exifinterface.media.ExifInterface
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vista.photoeditor.editor.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Photo du dossier masqué : ce qu'il faut pour l'afficher et la rendre à la galerie telle quelle. */
data class HiddenPhoto(
    val id: String,
    val name: String,
    val mimeType: String,
    val dateMillis: Long,
    /** Dossier d'origine dans la galerie (« DCIM/Camera/ »), pour la restaurer au même endroit. */
    val relativePath: String,
    val sizeBytes: Long,
    /**
     * Photo d'origine dans la galerie, tant que sa suppression n'est pas constatée : la copie
     * n'apparaît dans le dossier qu'une fois l'original parti.
     */
    val source: String? = null,
)

/**
 * Dossier masqué : les photos y sont copiées **chiffrées** dans le stockage privé de l'app, puis
 * retirées de la galerie (après confirmation du système). Aucune autre app ne les voit ; les
 * sauvegardes automatiques les excluent (voir `res/xml/`).
 *
 * Chiffrement AES-GCM avec une clé de données tirée au hasard, elle-même chiffrée par une clé du
 * Keystore d'Android, qui ne sort jamais du téléphone. L'ouverture du dossier est gardée par
 * l'empreinte ou le code de l'appareil (voir `ui/hidden`). La clé n'est pas liée à l'empreinte :
 * changer d'empreinte ou de code ne rend pas les photos illisibles.
 */
class HiddenVault(private val context: Context) {

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val mutex = Mutex()
    private var dataKey: SecretKey? = null

    /** Toutes les entrées, y compris celles en attente de la suppression de leur original. */
    private var entries: List<HiddenPhoto> = emptyList()

    /** Photos masquées, les plus récentes d'abord ; chargées à la première ouverture. */
    var photos by mutableStateOf<List<HiddenPhoto>>(emptyList())
        private set
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }

    /**
     * Chiffre [sources] dans le dossier (photo et vignette) et les note « en attente » : elles
     * n'apparaissent qu'une fois leur original supprimé de la galerie (voir [settle]). Renvoie les
     * adresses des originaux à supprimer.
     */
    suspend fun prepare(sources: List<MediaPhoto>): List<Uri> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val prepared = sources.mapNotNull { photo ->
                val entry = HiddenPhoto(
                    id = UUID.randomUUID().toString(),
                    name = photo.name,
                    mimeType = photo.mimeType.ifEmpty { "image/jpeg" },
                    dateMillis = photo.dateMillis,
                    relativePath = relativePathOf(photo.uri),
                    sizeBytes = photo.sizeBytes,
                    source = photo.uri.toString(),
                )
                runCatching {
                    val bytes = context.contentResolver.openInputStream(photo.uri)!!.use { it.readBytes() }
                    write(file(entry.id), bytes)
                    write(thumbFile(entry.id), thumbnailBytes(photo.uri))
                    entry
                }.getOrElse {
                    file(entry.id).delete()
                    thumbFile(entry.id).delete()
                    null
                }
            }
            update(entries + prepared)
            prepared.map { Uri.parse(it.source) }
        }
    }

    /**
     * Règle les photos en attente d'après la galerie elle-même, pas d'après la réponse au dialogue
     * de suppression (perdue si l'app est arrêtée entre-temps) : original disparu, la copie entre
     * dans le dossier ; original encore là, la copie est effacée. Renvoie le nombre de photos
     * entrées dans le dossier.
     */
    suspend fun settle(): Int = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); settlePending() } }

    private fun settlePending(): Int {
        var moved = 0
        val next = entries.mapNotNull { entry ->
            val source = entry.source ?: return@mapNotNull entry
            if (exists(Uri.parse(source))) {
                file(entry.id).delete()
                thumbFile(entry.id).delete()
                null
            } else {
                moved++
                entry.copy(source = null)
            }
        }
        if (next != entries) update(next)
        return moved
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun update(next: List<HiddenPhoto>) {
        entries = next.sortedByDescending { it.dateMillis }
        photos = entries.filter { it.source == null }
        saveIndex()
    }

    /** Rend les photos à la galerie, dans leur dossier et à leur date d'origine. */
    suspend fun restore(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            var restored = 0
            val kept = entries.filter { entry ->
                if (entry.id !in ids || entry.source != null) return@filter true
                val ok = runCatching { insertIntoGallery(entry, read(file(entry.id))) }.isSuccess
                if (ok) {
                    file(entry.id).delete()
                    thumbFile(entry.id).delete()
                    restored++
                }
                !ok
            }
            update(kept)
            restored
        }
    }

    /** Supprime définitivement : ni corbeille, ni retour possible. */
    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            entries.filter { it.id in ids && it.source == null }.forEach { file(it.id).delete(); thumbFile(it.id).delete() }
            update(entries.filter { it.id !in ids || it.source != null })
        }
    }

    suspend fun thumbnail(entry: HiddenPhoto): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(read(thumbFile(entry.id)), THUMB_SIDE) }.getOrNull()
    }

    suspend fun image(entry: HiddenPhoto, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(read(file(entry.id)), maxSide) }.getOrNull()
    }

    /** À l'écran de verrouillage : plus rien de déchiffré ne reste en mémoire. */
    fun lock() {
        dataKey = null
    }

    private fun ensureLoaded() {
        if (loaded) return
        val index = File(dir, INDEX)
        entries = if (index.exists()) runCatching { parse(String(read(index), Charsets.UTF_8)) }.getOrDefault(emptyList()) else emptyList()
        photos = entries.filter { it.source == null }
        loaded = true
        settlePending()
        // Fichiers qu'aucune entrée ne connaît (écriture interrompue) : ils ne servent à rien.
        val known = entries.mapTo(HashSet()) { it.id }
        val stale = System.currentTimeMillis() - ORPHAN_AGE_MILLIS
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(TMP_SUFFIX).removeSuffix(THUMB_SUFFIX).removeSuffix(PHOTO_SUFFIX)
            val ours = f.name != INDEX && f.name != KEY_FILE
            if (ours && id !in known && f.lastModified() < stale) f.delete()
        }
    }

    private fun saveIndex() {
        val array = JSONArray()
        entries.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("mime", p.mimeType)
                    .put("date", p.dateMillis).put("path", p.relativePath).put("size", p.sizeBytes)
                    .put("source", p.source ?: "")
            )
        }
        write(File(dir, INDEX), array.toString().toByteArray(Charsets.UTF_8))
    }

    private fun parse(json: String): List<HiddenPhoto> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            HiddenPhoto(
                o.getString("id"), o.getString("name"), o.getString("mime"), o.getLong("date"), o.getString("path"),
                o.getLong("size"), o.optString("source").ifEmpty { null },
            )
        }
    }

    private fun relativePathOf(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: DEFAULT_PATH

    private suspend fun thumbnailBytes(uri: Uri): ByteArray {
        val bitmap = ImageIO.decode(context, uri, THUMB_SIDE)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private fun insertIntoGallery(entry: HiddenPhoto, bytes: ByteArray) {
        val resolver = context.contentResolver
        // La galerie d'images n'accepte que DCIM/ et Pictures/.
        val path = entry.relativePath.takeIf { it.startsWith("DCIM/") || it.startsWith("Pictures/") } ?: DEFAULT_PATH
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, entry.name)
            put(MediaStore.Images.Media.MIME_TYPE, entry.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.DATE_TAKEN, entry.dateMillis)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaRepository.collection, values) ?: error("insertion refusée")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(withDate(entry, bytes)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Le système date une photo d'après son EXIF, et ignore la date qu'on lui donne : une photo sans
     * date EXIF (téléchargement, capture) reviendrait datée du jour. Sa date d'origine est donc
     * écrite dans le fichier, s'il n'en a pas déjà une (JPEG seulement).
     */
    private fun withDate(entry: HiddenPhoto, bytes: ByteArray): ByteArray {
        if (entry.mimeType != "image/jpeg") return bytes
        val tmp = File(context.cacheDir, "restore-${entry.id}.jpg")
        return try {
            tmp.writeBytes(bytes)
            val exif = ExifInterface(tmp.path)
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null) return bytes
            val time = Instant.ofEpochMilli(entry.dateMillis).atZone(ZoneId.systemDefault())
            val stamp = time.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US))
            val offset = time.format(DateTimeFormatter.ofPattern("xxx", Locale.US))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            exif.saveAttributes()
            tmp.readBytes()
        } catch (e: Exception) {
            bytes
        } finally {
            tmp.delete()
        }
    }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap =
        // ImageDecoder applique l'orientation EXIF des JPEG.
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            if (side > maxSide) {
                val k = maxSide.toFloat() / side
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }

    private fun file(id: String) = File(dir, id + PHOTO_SUFFIX)
    private fun thumbFile(id: String) = File(dir, id + THUMB_SUFFIX)

    // Chiffrement : [IV (12 octets)][données chiffrées + étiquette GCM].

    private fun write(target: File, plain: ByteArray) {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        val tmp = File(target.path + TMP_SUFFIX)
        tmp.outputStream().use { out ->
            out.write(iv)
            out.write(cipher.doFinal(plain))
        }
        if (!tmp.renameTo(target)) error("écriture impossible")
    }

    private fun read(source: File): ByteArray {
        val data = source.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    /** Clé de données, déchiffrée par la clé du Keystore ; créée au premier usage. */
    private fun key(): SecretKey {
        dataKey?.let { return it }
        val wrapped = File(dir, KEY_FILE)
        val master = masterKey()
        val raw = if (wrapped.exists()) {
            val data = wrapped.readBytes()
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
                doFinal(data, IV_BYTES, data.size - IV_BYTES)
            }
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            // Le Keystore choisit lui-même l'IV : on le range devant la clé chiffrée.
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, master) }
            wrapped.writeBytes(cipher.iv + cipher.doFinal(fresh))
            fresh
        }
        return SecretKeySpec(raw, "AES").also { dataKey = it }
    }

    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val DIR = "hidden"
        const val INDEX = "index.bin"
        const val KEY_FILE = "key.bin"
        const val PHOTO_SUFFIX = ".photo"
        const val THUMB_SUFFIX = ".thumb"
        const val TMP_SUFFIX = ".tmp"
        const val DEFAULT_PATH = "Pictures/"
        const val THUMB_SIDE = 400
        const val ORPHAN_AGE_MILLIS = 60 * 60 * 1000L
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vista_hidden_folder"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
