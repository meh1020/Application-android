package com.vista.photoeditor.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sauvegarde du dossier masqué dans **un seul fichier chiffré par un mot de passe**, que
 * l'utilisateur garde où il veut. Contrairement au dossier lui-même, dont la clé vit dans le
 * Keystore de ce téléphone, ce fichier se rouvre après une désinstallation ou sur un autre
 * appareil : il suffit du mot de passe.
 *
 * Clé tirée du mot de passe par PBKDF2-HMAC-SHA256 (sel aléatoire, nombre d'itérations écrit dans
 * le fichier pour pouvoir l'augmenter plus tard), chiffrement AES-256-GCM. Les photos et vidéos
 * passent **en flux**, par morceaux de 1 Mo : une vidéo de plusieurs centaines de mégaoctets n'est
 * jamais entière en mémoire, ni à l'écriture ni à la relecture.
 *
 * Format, version 1 (entiers en gros-boutiste) :
 * ```
 * préambule : "VISTAVLT" (8 octets) · version (1) · sel (16) · itérations (4)
 * blocs     : [taille (4)] [dernier (1)] [IV (12)] [données chiffrées + étiquette GCM (16)]
 *   bloc 0        en-tête : date de la sauvegarde, nombre d'éléments, puis pour chacun
 *                 nom, type, date, dossier d'origine, taille, durée
 *   blocs 1…      le contenu de chaque élément, dans l'ordre de l'en-tête, en un ou plusieurs
 *                 morceaux ; « dernier » vaut 1 sur le dernier morceau de chaque élément
 * ```
 * Chaque bloc est authentifié avec, en données associées, le préambule, son numéro dans le
 * fichier et son drapeau « dernier » : un bloc déplacé, remplacé, venu d'un autre fichier, ou un
 * élément raccourci sont refusés. Un fichier coupé se voit au nombre d'éléments annoncé.
 *
 * Aucune dépendance à Android : le format est vérifié par des tests sur l'ordinateur.
 */
object VaultBackup {

    /** Extension proposée pour le fichier. */
    const val EXTENSION = "vista"

    /** Mot de passe le plus court accepté pour une nouvelle sauvegarde. */
    const val MIN_PASSWORD_LENGTH = 8

    /** Itérations PBKDF2 des nouvelles sauvegardes (recommandation OWASP pour HMAC-SHA256). */
    const val ITERATIONS = 600_000

    /** Taille en clair des morceaux de contenu. */
    internal const val CHUNK_BYTES = 1024 * 1024

    /** Ce qui empêche de relire une sauvegarde. */
    enum class Problem {
        /** Le fichier ne commence pas comme une sauvegarde Vista. */
        NOT_A_BACKUP,

        /** Sauvegarde écrite par une version plus récente de Vista. */
        NEWER_VERSION,

        /** L'en-tête ne se déchiffre pas : mauvais mot de passe (ou début du fichier abîmé). */
        WRONG_PASSWORD,

        /** En-tête lisible, mais un élément manque ou est abîmé : fichier coupé ou modifié. */
        DAMAGED,
    }

    /** Levée par [read] ; c'est une [IOException], pour traverser les flux qui la relaient. */
    class BackupException(val problem: Problem, cause: Throwable? = null) : IOException(problem.name, cause)

    /** Ce que dit l'en-tête d'une sauvegarde. */
    data class Summary(val createdMillis: Long, val count: Int)

    /**
     * Écrit [items] dans [out], le contenu de chacun étant lu dans le flux que renvoie [open]
     * (déjà déchiffré ; il est fermé ici). [onProgress] reçoit le nombre d'éléments écrits.
     * Ne ferme pas [out].
     */
    fun write(
        out: OutputStream,
        password: CharArray,
        items: List<HiddenPhoto>,
        open: (HiddenPhoto) -> InputStream,
        createdMillis: Long = System.currentTimeMillis(),
        iterations: Int = ITERATIONS,
        chunkBytes: Int = CHUNK_BYTES,
        onProgress: (Int) -> Unit = {},
    ) {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val preamble = preamble(salt, iterations)
        val writer = BlockWriter(DataOutputStream(out), deriveKey(password, salt, iterations), preamble)
        out.write(preamble)

        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).run {
                writeLong(createdMillis)
                writeInt(items.size)
                items.forEach { p ->
                    writeUTF(p.name)
                    writeUTF(p.mimeType)
                    writeLong(p.dateMillis)
                    writeUTF(p.relativePath)
                    writeLong(p.sizeBytes)
                    writeLong(p.durationMillis)
                }
            }
        }.toByteArray()
        writer.write(header, header.size, last = true)

        var current = ByteArray(chunkBytes)
        var next = ByteArray(chunkBytes)
        items.forEachIndexed { i, item ->
            open(item).use { input ->
                var size = ChunkedCipher.fill(input, current)
                while (true) {
                    // Lire le morceau suivant d'abord : c'est lui qui dit si celui-ci est le dernier.
                    val nextSize = if (size == chunkBytes) ChunkedCipher.fill(input, next) else 0
                    writer.write(current, size, last = nextSize == 0)
                    if (nextSize == 0) break
                    current = next.also { next = current }
                    size = nextSize
                }
            }
            onProgress(i + 1)
        }
        out.flush()
    }

    /**
     * Relit une sauvegarde : [onHeader] une fois l'en-tête déchiffré, puis [onItem] pour chaque
     * élément, dans l'ordre, avec un flux de son contenu déchiffré à la volée (l'identifiant des
     * éléments reçus est vide : à chacun d'en donner un). Ce que [onItem] ne lit pas du flux est
     * vérifié puis sauté. Une exception levée par [onItem] interrompt la lecture et remonte telle
     * quelle ; un flux qui découvre un morceau abîmé lève [BackupException] ([Problem.DAMAGED]).
     *
     * @throws BackupException si le fichier n'est pas une sauvegarde lisible avec ce mot de passe.
     */
    fun read(
        input: InputStream,
        password: CharArray,
        onHeader: (Summary) -> Unit = {},
        onItem: (index: Int, item: HiddenPhoto, content: InputStream) -> Unit,
    ): Summary {
        val data = DataInputStream(input)

        val magic = ByteArray(MAGIC.size)
        if (ChunkedCipher.fill(data, magic) < magic.size || !magic.contentEquals(MAGIC)) throw BackupException(Problem.NOT_A_BACKUP)
        val version = data.read()
        if (version < 0) throw BackupException(Problem.NOT_A_BACKUP)
        if (version > VERSION) throw BackupException(Problem.NEWER_VERSION)
        if (version != VERSION) throw BackupException(Problem.NOT_A_BACKUP)
        val salt = ByteArray(SALT_BYTES)
        if (ChunkedCipher.fill(data, salt) < salt.size) throw BackupException(Problem.NOT_A_BACKUP)
        val iterations = try {
            data.readInt()
        } catch (e: EOFException) {
            throw BackupException(Problem.NOT_A_BACKUP, e)
        }
        // Un nombre absurde trahit un fichier quelconque (et figerait le téléphone).
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException(Problem.NOT_A_BACKUP)

        val preamble = preamble(salt, iterations)
        val reader = BlockReader(data, deriveKey(password, salt, iterations), preamble)

        val header = try {
            reader.next()
        } catch (e: BackupException) {
            throw BackupException(Problem.WRONG_PASSWORD, e)
        }
        if (!header.last) throw BackupException(Problem.DAMAGED)
        val (summary, items) = try {
            DataInputStream(ByteArrayInputStream(header.bytes)).run {
                val created = readLong()
                val count = readInt()
                if (count !in 0..MAX_ITEMS) throw BackupException(Problem.DAMAGED)
                val list = List(count) {
                    HiddenPhoto(
                        id = "",
                        name = readUTF(),
                        mimeType = readUTF(),
                        dateMillis = readLong(),
                        relativePath = readUTF(),
                        sizeBytes = readLong(),
                        durationMillis = readLong(),
                    )
                }
                Summary(created, count) to list
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(Problem.DAMAGED, e)
        }
        onHeader(summary)

        items.forEachIndexed { i, item ->
            val content = ItemStream(reader)
            onItem(i, item, content)
            // Morceaux que le destinataire n'a pas lus : vérifiés quand même, pour retomber
            // juste sur l'élément suivant.
            content.skipToEnd()
        }
        return summary
    }

    // --- Blocs ---------------------------------------------------------------------------------

    private class BlockWriter(private val out: DataOutputStream, private val key: SecretKey, private val preamble: ByteArray) {
        private var index = 0L

        fun write(plain: ByteArray, size: Int, last: Boolean) {
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                updateAAD(associatedData(preamble, index++, last))
            }
            val sealed = cipher.doFinal(plain, 0, size)
            out.writeInt(IV_BYTES + sealed.size)
            out.writeByte(if (last) 1 else 0)
            out.write(iv)
            out.write(sealed)
        }
    }

    private class Block(val bytes: ByteArray, val last: Boolean)

    private class BlockReader(private val input: DataInputStream, private val key: SecretKey, private val preamble: ByteArray) {
        private var index = 0L

        /** @throws BackupException ([Problem.DAMAGED]) si le bloc manque, est tronqué ou ne s'authentifie pas. */
        fun next(): Block {
            val size: Int
            val flag: Int
            try {
                size = input.readInt()
                flag = input.readUnsignedByte()
            } catch (e: EOFException) {
                throw BackupException(Problem.DAMAGED, e)
            }
            if (size < IV_BYTES + TAG_BITS / 8 || size > MAX_BLOCK_BYTES || flag > 1) throw BackupException(Problem.DAMAGED)
            val block = ByteArray(size)
            if (ChunkedCipher.fill(input, block) < size) throw BackupException(Problem.DAMAGED)
            val last = flag == 1
            val plain = try {
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, block, 0, IV_BYTES))
                    updateAAD(associatedData(preamble, index, last))
                    doFinal(block, IV_BYTES, size - IV_BYTES)
                }
            } catch (e: AEADBadTagException) {
                throw BackupException(Problem.DAMAGED, e)
            }
            index++
            return Block(plain, last)
        }
    }

    /** Contenu d'un élément, morceau par morceau, jusqu'au morceau marqué « dernier ». */
    private class ItemStream(private val reader: BlockReader) : InputStream() {
        private var chunk = ByteArray(0)
        private var at = 0
        private var finished = false
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at == chunk.size) {
                if (finished) return -1
                val block = reader.next()
                chunk = block.bytes
                at = 0
                finished = block.last
            }
            val n = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, n)
            at += n
            return n
        }

        fun skipToEnd() {
            while (!finished) {
                val block = reader.next()
                finished = block.last
            }
            chunk = ByteArray(0)
            at = 0
        }

        /** Fermer ne saute rien : [read] s'en charge pour retrouver l'élément suivant. */
        override fun close() = Unit
    }

    private fun associatedData(preamble: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(preamble.size + 9).put(preamble).putLong(index).put(if (last) 1 else 0).array()

    private fun preamble(salt: ByteArray, iterations: Int): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 1 + SALT_BYTES + 4)
            .put(MAGIC).put(VERSION.toByte()).put(salt).putInt(iterations)
            .array()

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            val raw = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private val random = SecureRandom()

    private val MAGIC = "VISTAVLT".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Bornes de lecture : au-delà, le fichier n'est pas une sauvegarde. */
    internal const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val MAX_ITEMS = 1_000_000
    private const val MAX_BLOCK_BYTES = 64 * 1024 * 1024
}
