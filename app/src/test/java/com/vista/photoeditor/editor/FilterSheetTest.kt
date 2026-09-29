package com.vista.photoeditor.editor

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Tous les filtres sur quelques vraies photos, rendus avec le code de l'app (matrice puis
 * étalonnage), pour les juger à l'œil : `tools/editor/filter_sheet.py` prépare les photos et
 * assemble la planche. Ignoré si rien n'a été préparé.
 */
class FilterSheetTest {

    @Test
    fun renderEveryFilter() {
        val input = File("build/filters/in")
        val photos = input.listFiles { f -> f.extension == "rgb" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("aucune photo préparée", photos.isNotEmpty())
        val output = File("build/filters/out").apply { mkdirs() }
        for (file in photos) {
            val (width, height, pixels) = DataInputStream(file.inputStream().buffered()).use { s ->
                val w = s.readInt()
                val h = s.readInt()
                Triple(w, h, IntArray(w * h) { (0xFF shl 24) or (s.readUnsignedByte() shl 16) or (s.readUnsignedByte() shl 8) or s.readUnsignedByte() })
            }
            for (filter in Filters.all) {
                val state = EditState(filterId = filter.id)
                val m = state.colorMatrix()
                val out = IntArray(pixels.size) { i ->
                    val p = pixels[i]
                    val r = ((p shr 16) and 0xFF).toFloat()
                    val g = ((p shr 8) and 0xFF).toFloat()
                    val b = (p and 0xFF).toFloat()
                    fun row(o: Int) = (m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 4]).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (row(0) shl 16) or (row(5) shl 8) or row(10)
                }
                state.grade().applyTo(out)
                DataOutputStream(File(output, "${file.nameWithoutExtension}__${filter.id}.rgb").outputStream().buffered()).use { o ->
                    o.writeInt(width)
                    o.writeInt(height)
                    for (p in out) { o.writeByte((p shr 16) and 0xFF); o.writeByte((p shr 8) and 0xFF); o.writeByte(p and 0xFF) }
                }
            }
        }
    }
}
