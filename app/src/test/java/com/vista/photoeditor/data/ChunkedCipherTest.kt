package com.vista.photoeditor.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Chiffrement par blocs des vidéos du dossier masqué (voir [ChunkedCipher]). */
class ChunkedCipherTest {

    private val key = SecretKeySpec(Random(7).nextBytes(32), "AES")
    private val chunk = 1000

    private fun encrypted(content: ByteArray, id: String = "video-1"): File =
        File.createTempFile("chunked", ".video").apply {
            deleteOnExit()
            outputStream().use { ChunkedCipher(key, id, chunk).encrypt(ByteArrayInputStream(content), it) }
        }

    private fun corrupt(block: () -> Unit) {
        try {
            block()
            fail("fichier altéré accepté")
        } catch (_: ChunkedCipher.CorruptChunkException) {
        }
    }

    @Test
    fun allerRetourDeToutesTailles() {
        for (size in listOf(0, 1, 999, 1000, 1001, 3500, 4000)) {
            val content = Random(size).nextBytes(size)
            val file = encrypted(content)
            val cipher = ChunkedCipher(key, "video-1", chunk)
            assertEquals(size.toLong(), cipher.open(file).use { it.length })
            assertArrayEquals("taille $size", content, cipher.inputStream(file).use { it.readBytes() })
        }
    }

    @Test
    fun lectureAPositionQuelconque() {
        val content = Random(3).nextBytes(3500)
        val reader = ChunkedCipher(key, "video-1", chunk).open(encrypted(content))
        reader.use {
            for (position in listOf(0L, 1L, 999L, 1000L, 2500L, 3499L)) {
                val buffer = ByteArray(700)
                val n = it.read(position, buffer, 0, buffer.size)
                // Une lecture ne franchit pas la fin d'un bloc.
                val expected = minOf(700, (chunk - position % chunk).toInt(), (3500 - position).toInt())
                assertEquals(expected, n)
                assertArrayEquals(content.copyOfRange(position.toInt(), position.toInt() + n), buffer.copyOf(n))
            }
            assertEquals(-1, it.read(3500, ByteArray(10), 0, 10))
        }
    }

    @Test
    fun octetModifieRefuse() {
        val file = encrypted(Random(4).nextBytes(3500))
        val bytes = file.readBytes()
        bytes[1500] = (bytes[1500] + 1).toByte()
        file.writeBytes(bytes)
        corrupt { ChunkedCipher(key, "video-1", chunk).inputStream(file).use { it.readBytes() } }
    }

    @Test
    fun coupeEntreDeuxBlocsRefuse() {
        // 3000 octets = 3 blocs pleins ; sans le dernier, l'avant-dernier ne s'annonçait pas « dernier ».
        val file = encrypted(Random(5).nextBytes(3000))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - (chunk + 28)))
        corrupt { ChunkedCipher(key, "video-1", chunk).inputStream(file).use { it.readBytes() } }
    }

    @Test
    fun blocsEchangesRefuses() {
        val file = encrypted(Random(6).nextBytes(3500))
        val bytes = file.readBytes()
        val stride = chunk + 28
        val swapped = bytes.copyOfRange(0, 8) +
            bytes.copyOfRange(8 + stride, 8 + 2 * stride) + bytes.copyOfRange(8, 8 + stride) +
            bytes.copyOfRange(8 + 2 * stride, bytes.size)
        file.writeBytes(swapped)
        corrupt { ChunkedCipher(key, "video-1", chunk).inputStream(file).use { it.readBytes() } }
    }

    @Test
    fun fichierDUneAutreVideoRefuse() {
        val file = encrypted(Random(8).nextBytes(500), id = "video-1")
        corrupt { ChunkedCipher(key, "video-2", chunk).inputStream(file).use { it.readBytes() } }
    }

    @Test
    fun mauvaiseCleRefusee() {
        val file = encrypted(Random(9).nextBytes(500))
        val other = SecretKeySpec(Random(10).nextBytes(32), "AES")
        corrupt { ChunkedCipher(other, "video-1", chunk).inputStream(file).use { it.readBytes() } }
    }

    @Test
    fun pasCeFormatRefuse() {
        val file = File.createTempFile("plain", ".video").apply { deleteOnExit(); writeBytes("pas une vidéo".toByteArray()) }
        corrupt { ChunkedCipher(key, "video-1", chunk).open(file) }
    }
}
