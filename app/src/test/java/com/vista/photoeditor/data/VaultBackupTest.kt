package com.vista.photoeditor.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Format de la sauvegarde chiffrée du dossier masqué (voir [VaultBackup]). */
class VaultBackupTest {

    /** Peu d'itérations et de petits morceaux : seule la vitesse des tests change, pas le format. */
    private val fast = VaultBackup.MIN_ITERATIONS
    private val chunk = 1000
    private val password = "correct horse".toCharArray()

    private val items = listOf(
        HiddenPhoto("a", "IMG_0001.jpg", "image/jpeg", 1_700_000_000_000, "DCIM/Camera/", 3000),
        HiddenPhoto("b", "Capture d'écran été.png", "image/png", 1_600_000_000_000, "Pictures/Screenshots/", 5000),
        HiddenPhoto("c", "vide.heic", "image/heic", 0, "Pictures/", 0),
        // Plusieurs morceaux, le dernier incomplet.
        HiddenPhoto("d", "VID_0001.mp4", "video/mp4", 1_650_000_000_000, "DCIM/Camera/", 3500, durationMillis = 12_345),
        // Un multiple exact de la taille des morceaux.
        HiddenPhoto("e", "VID_0002.mp4", "video/mp4", 1_650_000_000_001, "Movies/", 2000, durationMillis = 1),
    )
    private val contents = mapOf(
        "a" to Random(1).nextBytes(3000),
        "b" to Random(2).nextBytes(5000),
        "c" to ByteArray(0),
        "d" to Random(4).nextBytes(3500),
        "e" to Random(5).nextBytes(2000),
    )

    private fun backup(pass: CharArray = password.copyOf()): ByteArray = ByteArrayOutputStream().also { out ->
        VaultBackup.write(
            out, pass, items, { ByteArrayInputStream(contents.getValue(it.id)) },
            createdMillis = 42L, iterations = fast, chunkBytes = chunk,
        )
    }.toByteArray()

    private fun restore(file: ByteArray, pass: CharArray = password.copyOf()): Pair<VaultBackup.Summary, List<Pair<HiddenPhoto, ByteArray>>> {
        val got = mutableListOf<Pair<HiddenPhoto, ByteArray>>()
        val summary = VaultBackup.read(ByteArrayInputStream(file), pass) { _, item, content -> got += item to content.readBytes() }
        return summary to got
    }

    private fun problemOf(block: () -> Unit): VaultBackup.Problem? = try {
        block()
        null
    } catch (e: VaultBackup.BackupException) {
        e.problem
    }

    @Test
    fun allerRetour() {
        val (summary, got) = restore(backup())
        assertEquals(VaultBackup.Summary(42L, items.size), summary)
        assertEquals(items.size, got.size)
        items.zip(got).forEach { (expected, actual) ->
            assertEquals(expected.copy(id = ""), actual.first)
            assertArrayEquals(contents.getValue(expected.id), actual.second)
        }
    }

    @Test
    fun destinataireQuiNeLitRienNiToutRetombeSurSesPieds() {
        // Le premier élément n'est pas lu, le quatrième seulement à moitié : les suivants restent justes.
        val got = mutableMapOf<String, ByteArray>()
        VaultBackup.read(ByteArrayInputStream(backup()), password.copyOf()) { i, item, content ->
            when (i) {
                0 -> Unit
                3 -> content.read(ByteArray(1500))
                else -> got[item.name] = content.readBytes()
            }
        }
        assertArrayEquals(contents.getValue("b"), got.getValue("Capture d'écran été.png"))
        assertArrayEquals(contents.getValue("e"), got.getValue("VID_0002.mp4"))
    }

    @Test
    fun dossierVide() {
        val file = ByteArrayOutputStream().also {
            VaultBackup.write(it, password.copyOf(), emptyList(), { ByteArrayInputStream(ByteArray(0)) }, iterations = fast)
        }.toByteArray()
        val (summary, got) = restore(file)
        assertEquals(0, summary.count)
        assertTrue(got.isEmpty())
    }

    @Test
    fun sauvegardesDifferentesPourLeMemeContenu() {
        // Sel et IV tirés au hasard : deux sauvegardes identiques ne se ressemblent pas.
        assertTrue(!backup().contentEquals(backup()))
    }

    @Test
    fun mauvaisMotDePasse() {
        assertEquals(VaultBackup.Problem.WRONG_PASSWORD, problemOf { restore(backup(), "Correct horse".toCharArray()) })
    }

    @Test
    fun pasUneSauvegarde() {
        assertEquals(VaultBackup.Problem.NOT_A_BACKUP, problemOf { restore("bonjour, ceci est un texte".toByteArray()) })
        assertEquals(VaultBackup.Problem.NOT_A_BACKUP, problemOf { restore(ByteArray(0)) })
    }

    @Test
    fun versionPlusRecente() {
        val file = backup()
        file[8] = 2
        assertEquals(VaultBackup.Problem.NEWER_VERSION, problemOf { restore(file) })
    }

    @Test
    fun fichierCoupeGardeLesElementsDejaLus() {
        val file = backup()
        val got = mutableListOf<String>()
        val problem = problemOf {
            VaultBackup.read(ByteArrayInputStream(file.copyOf(file.size - 10)), password.copyOf()) { _, item, content ->
                content.readBytes()
                got += item.name
            }
        }
        assertEquals(VaultBackup.Problem.DAMAGED, problem)
        // Coupure dans le dernier élément : les quatre premiers ont été rendus avant.
        assertEquals(items.take(4).map { it.name }, got)
    }

    @Test
    fun morceauFinalRetireRefuse() {
        // Retirer le dernier morceau de la vidéo « d » (3500 octets en 4 morceaux) en gardant la suite.
        val file = backup()
        val blocks = splitBlocks(file)
        // Blocs : en-tête, a×3, b×5, c×1, d×4, e×2.
        val withoutLastOfD = blocks.take(1 + 3 + 5 + 1 + 3) + blocks.drop(1 + 3 + 5 + 1 + 4)
        val tampered = file.copyOfRange(0, PREAMBLE) + withoutLastOfD.reduce(ByteArray::plus)
        assertEquals(VaultBackup.Problem.DAMAGED, problemOf { restore(tampered) })
    }

    @Test
    fun octetModifieDansUnElement() {
        val file = backup()
        file[file.size - 1500] = (file[file.size - 1500] + 1).toByte()
        assertEquals(VaultBackup.Problem.DAMAGED, problemOf { restore(file) })
    }

    @Test
    fun drapeauDernierInverseRefuse() {
        val file = backup()
        val blocks = splitBlocks(file)
        // Le premier morceau de « a » se déclare dernier : il ne s'authentifie plus.
        val first = blocks[1].copyOf().also { it[4] = 1 }
        val tampered = file.copyOfRange(0, PREAMBLE) + blocks[0] + first + blocks.drop(2).reduce(ByteArray::plus)
        assertEquals(VaultBackup.Problem.DAMAGED, problemOf { restore(tampered) })
    }

    @Test
    fun sauvegardePlusLongueQueLAnnonceNeTrompePas() {
        val (summary, got) = restore(backup() + backup())
        assertEquals(items.size, summary.count)
        assertEquals(items.size, got.size)
    }

    @Test
    fun blocsInversesRefuses() {
        val file = backup()
        val blocks = splitBlocks(file)
        val swapped = file.copyOfRange(0, PREAMBLE) + blocks[0] + blocks[2] + blocks[1] + blocks.drop(3).reduce(ByteArray::plus)
        assertEquals(VaultBackup.Problem.DAMAGED, problemOf { restore(swapped) })
    }

    @Test
    fun blocVenuDUneAutreSauvegardeRefuse() {
        val file = backup()
        val one = splitBlocks(file)
        val other = splitBlocks(backup())
        val mixed = file.copyOfRange(0, PREAMBLE) + one[0] + other[1] + one.drop(2).reduce(ByteArray::plus)
        assertEquals(VaultBackup.Problem.DAMAGED, problemOf { restore(mixed) })
    }

    @Test
    fun nombreDIterationsAbsurdeRefuse() {
        val file = backup()
        // Itérations aux octets 25 à 28 : 0x7FFFFFFF figerait le téléphone.
        file[25] = 0x7F; file[26] = -1; file[27] = -1; file[28] = -1
        assertEquals(VaultBackup.Problem.NOT_A_BACKUP, problemOf { restore(file) })
    }

    @Test
    fun progression() {
        val seen = mutableListOf<Int>()
        VaultBackup.write(
            ByteArrayOutputStream(), password.copyOf(), items, { ByteArrayInputStream(contents.getValue(it.id)) },
            iterations = fast, chunkBytes = chunk, onProgress = { seen += it },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), seen)
    }

    @Test
    fun erreurDuDestinataireRemonteTelleQuelle() {
        try {
            VaultBackup.read(ByteArrayInputStream(backup()), password.copyOf()) { _, _, _ -> error("disque plein") }
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("disque plein", e.message)
        }
    }

    /** Découpe les blocs [taille][dernier][contenu] qui suivent le préambule. */
    private fun splitBlocks(file: ByteArray): List<ByteArray> {
        val blocks = mutableListOf<ByteArray>()
        var at = PREAMBLE
        while (at < file.size) {
            val size = java.nio.ByteBuffer.wrap(file, at, 4).int
            blocks += file.copyOfRange(at, at + 5 + size)
            at += 5 + size
        }
        return blocks
    }

    private companion object {
        /** "VISTAVLT" · version · sel · itérations. */
        const val PREAMBLE = 8 + 1 + 16 + 4
    }
}
