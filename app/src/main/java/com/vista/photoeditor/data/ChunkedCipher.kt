package com.vista.photoeditor.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement d'un fichier par blocs indépendants, pour les vidéos du dossier masqué : une vidéo
 * de plusieurs centaines de mégaoctets ne tient pas en mémoire d'un seul tenant, et sa lecture
 * doit pouvoir sauter n'importe où (avance rapide, pellicule) sans tout déchiffrer.
 *
 * Format :
 * ```
 * "VCH1" (4 octets) · taille des blocs en clair (4)
 * puis chaque bloc : [IV (12)] [données chiffrées (taille des blocs, sauf le dernier) + étiquette GCM (16)]
 * ```
 * Tous les blocs ont la même taille sauf le dernier : le bloc qui contient un octet donné se
 * calcule, sans index. Chaque bloc est authentifié avec, en données associées, l'identifiant du
 * fichier, son numéro et un drapeau « dernier bloc » : un bloc déplacé, venu d'une autre vidéo, ou
 * un fichier coupé net entre deux blocs sont refusés.
 *
 * Aucune dépendance à Android : vérifié par des tests sur l'ordinateur.
 */
class ChunkedCipher(private val key: SecretKey, fileId: String, private val chunkSize: Int = DEFAULT_CHUNK) {

    private val id = fileId.toByteArray(Charsets.UTF_8)

    init {
        require(chunkSize in 1..MAX_CHUNK) { "taille de bloc invalide" }
    }

    /** Chiffre tout [input] dans [output] ; renvoie la taille en clair. Ne ferme aucun des deux flux. */
    fun encrypt(input: InputStream, output: OutputStream): Long {
        output.write(ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(chunkSize).array())
        var current = ByteArray(chunkSize)
        var next = ByteArray(chunkSize)
        var currentSize = fill(input, current)
        var index = 0L
        var total = 0L
        while (true) {
            // Lire le bloc suivant d'abord : c'est lui qui dit si celui-ci est le dernier.
            val nextSize = if (currentSize == chunkSize) fill(input, next) else 0
            val last = nextSize == 0
            output.write(seal(current, currentSize, index, last))
            total += currentSize
            if (last) return total
            index++
            current = next.also { next = current }
            currentSize = nextSize
        }
    }

    /** Lecteur à accès direct de [file] ; à fermer après usage. */
    fun open(file: File): Reader = Reader(RandomAccessFile(file, "r"))

    /** Flux déchiffré de [file], du début à la fin. */
    fun inputStream(file: File): InputStream = ReaderInputStream(open(file))

    /**
     * Lecture à n'importe quelle position, un bloc déchiffré gardé en mémoire : des lectures
     * successives dans le même bloc ne le déchiffrent qu'une fois.
     */
    inner class Reader internal constructor(private val file: RandomAccessFile) : Closeable {

        private val chunkCount: Long

        /** Taille de la vidéo en clair. */
        val length: Long

        private var cachedIndex = -1L
        private var cached = ByteArray(0)

        init {
            try {
                val header = ByteArray(HEADER_BYTES)
                file.seek(0)
                file.readFully(header)
                val buffer = ByteBuffer.wrap(header)
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC) || buffer.int != chunkSize) throw CorruptChunkException("en-tête inattendu")
                val body = file.length() - HEADER_BYTES
                val stride = chunkSize.toLong() + OVERHEAD
                if (body < OVERHEAD) throw CorruptChunkException("fichier tronqué")
                chunkCount = (body + stride - 1) / stride
                val lastSize = body - (chunkCount - 1) * stride - OVERHEAD
                if (lastSize < 0 || lastSize > chunkSize) throw CorruptChunkException("fichier tronqué")
                length = (chunkCount - 1) * chunkSize + lastSize
            } catch (e: IOException) {
                file.close()
                throw if (e is CorruptChunkException) e else CorruptChunkException("lecture impossible", e)
            }
        }

        /**
         * Copie jusqu'à [size] octets à partir de [position] dans [target] ; renvoie le nombre
         * d'octets copiés, ou -1 à la fin du fichier.
         * @throws CorruptChunkException si un bloc a été modifié.
         */
        fun read(position: Long, target: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            if (size == 0) return 0
            val index = position / chunkSize
            val chunk = chunk(index)
            val within = (position - index * chunkSize).toInt()
            val n = minOf(size, chunk.size - within)
            System.arraycopy(chunk, within, target, offset, n)
            return n
        }

        private fun chunk(index: Long): ByteArray {
            if (index == cachedIndex) return cached
            val last = index == chunkCount - 1
            val sealedSize = if (last) (length - index * chunkSize).toInt() + OVERHEAD else chunkSize + OVERHEAD
            val sealed = ByteArray(sealedSize)
            file.seek(HEADER_BYTES + index * (chunkSize.toLong() + OVERHEAD))
            file.readFully(sealed)
            cached = unseal(sealed, index, last)
            cachedIndex = index
            return cached
        }

        override fun close() = file.close()
    }

    private class ReaderInputStream(private val reader: Reader) : InputStream() {
        private var position = 0L
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = reader.read(position, b, off, len)
            if (n > 0) position += n
            return n
        }

        override fun available(): Int = (reader.length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        override fun close() = reader.close()
    }

    private fun seal(plain: ByteArray, size: Int, index: Long, last: Boolean): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(associatedData(index, last))
        }
        return iv + cipher.doFinal(plain, 0, size)
    }

    private fun unseal(sealed: ByteArray, index: Long, last: Boolean): ByteArray = try {
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            updateAAD(associatedData(index, last))
            doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    } catch (e: AEADBadTagException) {
        throw CorruptChunkException("bloc $index altéré", e)
    }

    private fun associatedData(index: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(id.size + 9).put(id).putLong(index).put(if (last) 1 else 0).array()

    /** Fichier chiffré modifié, coupé, ou qui n'est pas de ce format. */
    class CorruptChunkException(message: String, cause: Throwable? = null) : IOException(message, cause)

    companion object {
        /** 1 Mo en clair par bloc : peu de surcoût, et un saut dans la vidéo ne déchiffre que ça. */
        const val DEFAULT_CHUNK = 1024 * 1024
        private const val MAX_CHUNK = 16 * 1024 * 1024
        private val MAGIC = "VCH1".toByteArray(Charsets.US_ASCII)
        private const val HEADER_BYTES = 8
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val OVERHEAD = IV_BYTES + TAG_BITS / 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val random = SecureRandom()

        /** Remplit [target] autant que [input] le permet ; renvoie le nombre d'octets lus. */
        internal fun fill(input: InputStream, target: ByteArray): Int {
            var read = 0
            while (read < target.size) {
                val n = input.read(target, read, target.size - read)
                if (n < 0) break
                read += n
            }
            return read
        }
    }
}
