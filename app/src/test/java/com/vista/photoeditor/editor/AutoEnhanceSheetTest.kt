package com.vista.photoeditor.editor

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Retouche automatique appliquée à de vraies photos, pour la juger à l'œil (planche avant / après).
 * `tools/editor/auto_enhance_sheet.py` prépare les photos en pixels bruts dans
 * app/build/auto-enhance/in, ce test calcule les réglages et le rendu avec le code de l'app, puis le
 * script assemble la planche. Ignoré si rien n'a été préparé.
 *
 * Format brut (gros-boutiste) : largeur, hauteur, puis les pixels en RVB, un octet par canal.
 */
class AutoEnhanceSheetTest {

    @Test
    fun renderPreparedPhotos() {
        val input = File("build/auto-enhance/in")
        val photos = input.listFiles { f -> f.extension == "rgb" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("aucune photo préparée", photos.isNotEmpty())
        val output = File("build/auto-enhance/out").apply { mkdirs() }
        val log = StringBuilder()
        for (file in photos) {
            val (width, height, pixels) = read(file)
            val values = AutoEnhance.compute(pixels, width, EditState())
            val state = EditState(adjustments = values.filterValues { it != 0f })
            val matrix = state.colorMatrix()
            val rendered = IntArray(pixels.size) { render(pixels[it], matrix) }
            state.grade().applyTo(rendered)
            write(File(output, file.name), width, height, rendered)
            log.append(file.nameWithoutExtension)
            AutoEnhance.ADJUSTMENTS.forEach { log.append('\t').append(values.getValue(it).toInt()) }
            log.append('\n')
        }
        File(output, "values.tsv").writeText(log.toString())
    }

    private fun read(file: File): Triple<Int, Int, IntArray> =
        DataInputStream(file.inputStream().buffered()).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val pixels = IntArray(width * height) {
                val r = input.readUnsignedByte()
                val g = input.readUnsignedByte()
                val b = input.readUnsignedByte()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            Triple(width, height, pixels)
        }

    private fun write(file: File, width: Int, height: Int, pixels: IntArray) =
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(width)
            out.writeInt(height)
            for (p in pixels) {
                out.writeByte((p shr 16) and 0xFF)
                out.writeByte((p shr 8) and 0xFF)
                out.writeByte(p and 0xFF)
            }
        }

    /** Comme ColorMatrixColorFilter : décalages sur l'échelle 0..255. */
    private fun render(p: Int, m: FloatArray): Int {
        val r = ((p shr 16) and 0xFF).toFloat()
        val g = ((p shr 8) and 0xFF).toFloat()
        val b = (p and 0xFF).toFloat()
        fun row(o: Int) = (m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 4]).toInt().coerceIn(0, 255)
        return (row(0) shl 16) or (row(5) shl 8) or row(10)
    }
}
