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
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vista.photoeditor.editor.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Photo ou vidéo du dossier masqué : ce qu'il faut pour l'afficher et la rendre à la galerie telle quelle. */
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
    /** Durée d'une vidéo, en millisecondes (0 pour une photo). */
    val durationMillis: Long = 0,
) {
    /** Vidéo : chiffrée par blocs ([ChunkedCipher]) et lue sans être déchiffrée d'un coup. */
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

/**
 * Dossier masqué : les photos et vidéos y sont copiées **chiffrées** dans le stockage privé de
 * l'app, puis retirées de la galerie (après confirmation du système). Aucune autre app ne les
 * voit ; les sauvegardes automatiques les excluent (voir `res/xml/`).
 *
 * Chiffrement AES-GCM avec une clé de données tirée au hasard, elle-même chiffrée par une clé du
 * Keystore d'Android, qui ne sort jamais du téléphone. L'ouverture du dossier est gardée par
 * l'empreinte ou le code de l'appareil (voir `ui/hidden`). La clé n'est pas liée à l'empreinte :
 * changer d'empreinte ou de code ne rend pas les photos illisibles.
 *
 * Une photo est chiffrée d'un bloc (`.photo`). Une vidéo, trop lourde pour la mémoire, l'est par
 * blocs de 1 Mo (`.video`, voir [ChunkedCipher]) : elle entre, sort et se lit **en flux**, sans
 * jamais être déchiffrée entière, ni en mémoire ni sur le disque.
 *
 * Revers de cette clé : désinstaller l'app ou effacer ses données détruit les photos. D'où la
 * sauvegarde dans un fichier chiffré par mot de passe ([exportBackup], [importBackup], format
 * dans [VaultBackup]), et le compte des éléments qui n'en ont pas encore ([unsavedCount]).
 */
class HiddenVault(private val context: Context) {

    private val dir = File(context.filesDir, DIR).apply { mkdirs() }
    private val mutex = Mutex()
    @Volatile private var dataKey: SecretKey? = null

    /** Toutes les entrées, y compris celles en attente de la suppression de leur original. */
    private var entries: List<HiddenPhoto> = emptyList()

    /** Photos et vidéos masquées, les plus récentes d'abord ; chargées à la première ouverture. */
    var photos by mutableStateOf<List<HiddenPhoto>>(emptyList())
        private set
    private var loaded = false

    /** Suivi des sauvegardes : quels éléments sont dans un fichier, et depuis quand. */
    private val backupPrefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

    /** Éléments du dossier qui ne sont dans aucune sauvegarde : perdus si l'app est désinstallée. */
    var unsavedCount by mutableIntStateOf(0)
        private set

    /** Date de la dernière sauvegarde faite (ou relue) sur ce téléphone, s'il y en a une. */
    var lastBackupMillis by mutableStateOf<Long?>(null)
        private set

    /** Résultat d'une relecture de sauvegarde ; [problem] dit pourquoi elle s'est arrêtée, s'il y a lieu. */
    data class ImportResult(val added: Int, val skipped: Int, val problem: VaultBackup.Problem? = null, val failed: Boolean = false)

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded() } }

    /**
     * Chiffre [sources] dans le dossier (contenu et vignette) et les note « en attente » : elles
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
                    mimeType = photo.mimeType.ifEmpty { if (photo.isVideo) "video/mp4" else "image/jpeg" },
                    dateMillis = photo.dateMillis,
                    relativePath = relativePathOf(photo.uri),
                    sizeBytes = photo.sizeBytes,
                    source = photo.uri.toString(),
                    durationMillis = photo.durationMillis,
                )
                runCatching {
                    if (entry.isVideo) {
                        context.contentResolver.openInputStream(photo.uri)!!.use { writeVideo(entry, it) }
                        // Sans vignette, la case reste vide : ce n'est pas une raison pour refuser la vidéo.
                        runCatching { write(thumbFile(entry.id), jpeg(VideoFrames.poster(context, photo.uri, THUMB_SIDE))) }
                    } else {
                        val bytes = context.contentResolver.openInputStream(photo.uri)!!.use { it.readBytes() }
                        write(contentFile(entry), bytes)
                        write(thumbFile(entry.id), thumbnailBytes(photo.uri))
                    }
                    entry
                }.getOrElse {
                    deleteFiles(entry)
                    null
                }
            }
            update(entries + prepared)
            prepared.map { Uri.parse(it.source) }
        }
    }

    /**
     * Règle les éléments en attente d'après la galerie elle-même, pas d'après la réponse au
     * dialogue de suppression (perdue si l'app est arrêtée entre-temps) : original disparu, la
     * copie entre dans le dossier ; original encore là, la copie est effacée. Renvoie le nombre
     * d'éléments entrés dans le dossier.
     */
    suspend fun settle(): Int = withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(); settlePending() } }

    private fun settlePending(): Int {
        var moved = 0
        val next = entries.mapNotNull { entry ->
            val source = entry.source ?: return@mapNotNull entry
            if (exists(Uri.parse(source))) {
                deleteFiles(entry)
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
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun update(next: List<HiddenPhoto>) {
        entries = next.sortedByDescending { it.dateMillis }
        photos = entries.filter { it.source == null }
        saveIndex()
        refreshBackupState()
    }

    /**
     * Écrit tout le dossier dans [out], chiffré par [password] (voir [VaultBackup]). [onProgress]
     * reçoit (éléments écrits, total). Renvoie le nombre d'éléments sauvegardés ; une fois le
     * fichier complet, ils ne comptent plus comme « sans sauvegarde ». Ne ferme pas [out].
     */
    suspend fun exportBackup(out: OutputStream, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val saved = photos
                VaultBackup.write(out, password, saved, open = ::openContent, onProgress = { onProgress(it, saved.size) })
                out.flush()
                markSaved(saved.map { it.id }, at = System.currentTimeMillis(), replace = true)
                saved.size
            }
        }

    /**
     * Relit une sauvegarde faite par [exportBackup] et remet son contenu dans le dossier (pas dans
     * la galerie : « Restaurer » s'en charge ensuite). Un élément déjà présent (même nom, date et
     * taille) n'est pas ajouté deux fois. Si le fichier est abîmé en cours de route, les éléments
     * déjà relus sont gardés. Ne ferme pas [input].
     */
    suspend fun importBackup(input: InputStream, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): ImportResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLoaded()
                val known = entries.mapTo(HashSet()) { sameness(it) }
                val added = mutableListOf<HiddenPhoto>()
                var skipped = 0
                var total = 0
                var createdMillis: Long? = null
                val outcome = runCatching {
                    VaultBackup.read(
                        input,
                        password,
                        onHeader = { summary ->
                            total = summary.count
                            createdMillis = summary.createdMillis
                            onProgress(0, total)
                        },
                        onItem = { index, item, content ->
                            if (known.add(sameness(item))) {
                                val entry = item.copy(id = UUID.randomUUID().toString(), source = null)
                                try {
                                    if (entry.isVideo) writeVideo(entry, content) else write(contentFile(entry), content.readBytes())
                                } catch (e: Exception) {
                                    deleteFiles(entry)
                                    throw e
                                }
                                // Vignette refaite d'après le contenu ; sans elle, la case reste vide.
                                runCatching { write(thumbFile(entry.id), thumbnailOf(entry)) }
                                added += entry
                            } else {
                                skipped++
                            }
                            onProgress(index + 1, total)
                        },
                    )
                }
                if (added.isNotEmpty()) {
                    update(entries + added)
                    // Ces éléments sont dans le fichier relu : ils ont déjà une sauvegarde.
                    markSaved(added.map { it.id }, at = lastBackupMillis ?: createdMillis, replace = false)
                }
                val error = outcome.exceptionOrNull()
                ImportResult(
                    added = added.size,
                    skipped = skipped,
                    problem = (error as? VaultBackup.BackupException)?.problem,
                    failed = error != null && error !is VaultBackup.BackupException,
                )
            }
        }

    /** Deux éléments identiques pour l'import : même nom, même date, même taille. */
    private fun sameness(photo: HiddenPhoto) = Triple(photo.name, photo.dateMillis, photo.sizeBytes)

    private fun markSaved(ids: Collection<String>, at: Long?, replace: Boolean) {
        val previous = if (replace) emptySet() else backupPrefs.getStringSet(KEY_SAVED_IDS, emptySet()).orEmpty()
        // Seuls les éléments encore dans le dossier comptent : la liste ne grossit pas sans fin.
        val present = photos.mapTo(HashSet()) { it.id }
        val editor = backupPrefs.edit().putStringSet(KEY_SAVED_IDS, (previous + ids).filterTo(HashSet()) { it in present })
        if (at != null) editor.putLong(KEY_LAST_BACKUP, at)
        editor.apply()
        refreshBackupState()
    }

    private fun refreshBackupState() {
        val saved = backupPrefs.getStringSet(KEY_SAVED_IDS, emptySet()).orEmpty()
        unsavedCount = photos.count { it.id !in saved }
        lastBackupMillis = backupPrefs.getLong(KEY_LAST_BACKUP, 0L).takeIf { it > 0L }
    }

    /** Rend les éléments à la galerie, dans leur dossier et à leur date d'origine. */
    suspend fun restore(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            var restored = 0
            val kept = entries.filter { entry ->
                if (entry.id !in ids || entry.source != null) return@filter true
                val ok = runCatching { insertIntoGallery(entry) }.isSuccess
                if (ok) {
                    deleteFiles(entry)
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
            entries.filter { it.id in ids && it.source == null }.forEach(::deleteFiles)
            update(entries.filter { it.id !in ids || it.source != null })
        }
    }

    suspend fun thumbnail(entry: HiddenPhoto): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(read(thumbFile(entry.id)), THUMB_SIDE) }.getOrNull()
    }

    /** Photo en grand ; pour une vidéo, sa vignette (la vidéo se lit avec [openVideo]). */
    suspend fun image(entry: HiddenPhoto, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(read(if (entry.isVideo) thumbFile(entry.id) else contentFile(entry)), maxSide) }.getOrNull()
    }

    /**
     * Lecteur à accès direct d'une vidéo du dossier, déchiffrée bloc par bloc à la demande : le
     * lecteur vidéo saute où il veut sans que la vidéo soit jamais déchiffrée en entier. Bloquant
     * (à appeler hors du fil de l'interface) ; à fermer après usage.
     */
    fun openVideo(entry: HiddenPhoto): ChunkedCipher.Reader = ChunkedCipher(key(), entry.id).open(contentFile(entry))

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
        refreshBackupState()
        // Fichiers qu'aucune entrée ne connaît (écriture interrompue) : ils ne servent à rien.
        val known = entries.mapTo(HashSet()) { it.id }
        val stale = System.currentTimeMillis() - ORPHAN_AGE_MILLIS
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(TMP_SUFFIX).removeSuffix(THUMB_SUFFIX).removeSuffix(PHOTO_SUFFIX).removeSuffix(VIDEO_SUFFIX)
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
                    .put("source", p.source ?: "").put("duration", p.durationMillis)
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
                o.getLong("size"), o.optString("source").ifEmpty { null }, o.optLong("duration", 0L),
            )
        }
    }

    private fun relativePathOf(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: DEFAULT_PATH

    private suspend fun thumbnailBytes(uri: Uri): ByteArray = jpeg(ImageIO.decode(context, uri, THUMB_SIDE))

    /** Vignette d'un élément déjà dans le dossier (relu d'une sauvegarde). */
    private fun thumbnailOf(entry: HiddenPhoto): ByteArray =
        if (entry.isVideo) jpeg(videoFrame(entry)) else jpeg(decode(read(contentFile(entry)), THUMB_SIDE))

    /** Image de la vidéo chiffrée, lue par Android à travers le déchiffrement, sans copie en clair. */
    private fun videoFrame(entry: HiddenPhoto): Bitmap {
        val retriever = MediaMetadataRetriever()
        val reader = openVideo(entry)
        try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    reader.read(position, buffer, offset, size)

                override fun getSize(): Long = reader.length

                override fun close() = Unit
            })
            val frame = retriever.getScaledFrameAtTime(
                entry.durationMillis * 1000 / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_SIDE, THUMB_SIDE,
            ) ?: retriever.getFrameAtTime(0) ?: error("aucune image")
            return frame
        } finally {
            retriever.release()
            reader.close()
        }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }

    /**
     * Contenu déchiffré d'un élément, en flux : d'un bloc pour une photo, bloc par bloc pour une
     * vidéo (jamais entière en mémoire).
     */
    private fun openContent(entry: HiddenPhoto): InputStream =
        if (entry.isVideo) ChunkedCipher(key(), entry.id).inputStream(contentFile(entry))
        else ByteArrayInputStream(read(contentFile(entry)))

    private fun insertIntoGallery(entry: HiddenPhoto) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, galleryPath(entry))
            put(MediaStore.MediaColumns.DATE_TAKEN, entry.dateMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (entry.isVideo) MediaRepository.videoCollection else MediaRepository.collection
        val uri = resolver.insert(collection, values) ?: error("insertion refusée")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                if (entry.isVideo) {
                    openContent(entry).use { it.copyTo(out, COPY_BUFFER) }
                } else {
                    out.write(withDate(entry, read(contentFile(entry))))
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Dossier où remettre l'élément : celui d'origine, s'il est permis pour ce type de média. */
    private fun galleryPath(entry: HiddenPhoto): String {
        // Images : DCIM/ et Pictures/ ; vidéos : DCIM/, Movies/ et Pictures/.
        val allowed = if (entry.isVideo) listOf("DCIM/", "Movies/", "Pictures/") else listOf("DCIM/", "Pictures/")
        return entry.relativePath.takeIf { path -> allowed.any { path.startsWith(it) } }
            ?: if (entry.isVideo) DEFAULT_VIDEO_PATH else DEFAULT_PATH
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

    private fun contentFile(entry: HiddenPhoto) = File(dir, entry.id + if (entry.isVideo) VIDEO_SUFFIX else PHOTO_SUFFIX)
    private fun thumbFile(id: String) = File(dir, id + THUMB_SUFFIX)

    private fun deleteFiles(entry: HiddenPhoto) {
        contentFile(entry).delete()
        thumbFile(entry.id).delete()
        File(contentFile(entry).path + TMP_SUFFIX).delete()
    }

    // Chiffrement des photos et vignettes : [IV (12 octets)][données chiffrées + étiquette GCM].

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

    /** Vidéo : chiffrée par blocs, en flux, d'abord dans un fichier temporaire. */
    private fun writeVideo(entry: HiddenPhoto, input: InputStream) {
        val target = contentFile(entry)
        val tmp = File(target.path + TMP_SUFFIX)
        try {
            tmp.outputStream().buffered(COPY_BUFFER).use { ChunkedCipher(key(), entry.id).encrypt(input, it) }
            if (!tmp.renameTo(target)) error("écriture impossible")
        } finally {
            tmp.delete()
        }
    }

    private fun read(source: File): ByteArray {
        val data = source.readBytes()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        }
        return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    /** Clé de données, déchiffrée par la clé du Keystore ; créée au premier usage. */
    @Synchronized
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
        const val VIDEO_SUFFIX = ".video"
        const val THUMB_SUFFIX = ".thumb"
        const val TMP_SUFFIX = ".tmp"
        const val DEFAULT_PATH = "Pictures/"
        const val DEFAULT_VIDEO_PATH = "Movies/"
        const val THUMB_SIDE = 400
        const val COPY_BUFFER = 256 * 1024
        const val ORPHAN_AGE_MILLIS = 60 * 60 * 1000L
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vista_hidden_folder"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val BACKUP_PREFS = "vista_hidden_backup"
        const val KEY_SAVED_IDS = "saved_ids"
        const val KEY_LAST_BACKUP = "last_backup"
    }
}
