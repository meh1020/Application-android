package com.vista.photoeditor.editor

/**
 * Helpers pour les matrices de couleur 4x5 (format android.graphics.ColorMatrix,
 * décalages exprimés sur l'échelle 0..255).
 */
object ColorMatrices {

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Matrice équivalente à appliquer [first] puis [then]. */
    fun concat(then: FloatArray, first: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += then[r * 5 + k] * first[k * 5 + c]
                out[r * 5 + c] = sum
            }
            var offset = then[r * 5 + 4]
            for (k in 0 until 4) offset += then[r * 5 + k] * first[k * 5 + 4]
            out[r * 5 + 4] = offset
        }
        return out
    }

    /** Applique les matrices dans l'ordre donné. */
    fun chain(vararg matrices: FloatArray): FloatArray =
        matrices.fold(identity()) { acc, m -> concat(m, acc) }

    fun lerp(from: FloatArray, to: FloatArray, t: Float): FloatArray =
        FloatArray(20) { i -> from[i] + (to[i] - from[i]) * t }

    fun scale(r: Float, g: Float, b: Float) = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun offset(r: Float, g: Float, b: Float) = floatArrayOf(
        1f, 0f, 0f, 0f, r,
        0f, 1f, 0f, 0f, g,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f,
    )

    fun brightness(amount: Float) = offset(amount, amount, amount)

    fun exposure(factor: Float) = scale(factor, factor, factor)

    fun contrast(factor: Float): FloatArray {
        val t = (1f - factor) * 127.5f
        return floatArrayOf(
            factor, 0f, 0f, 0f, t,
            0f, factor, 0f, 0f, t,
            0f, 0f, factor, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun saturation(s: Float): FloatArray {
        val r = LUMA_R * (1f - s)
        val g = LUMA_G * (1f - s)
        val b = LUMA_B * (1f - s)
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Positif = plus chaud (orange), négatif = plus froid (bleu). */
    fun warmth(amount: Float) = offset(amount, amount * 0.2f, -amount)

    /** Positif = magenta, négatif = vert. */
    fun tint(amount: Float) = offset(amount * 0.5f, -amount, amount * 0.5f)

    /** Rapproche les noirs et les blancs d'un gris : rendu mat. [k] dans 0..1. */
    fun fade(k: Float): FloatArray {
        val s = 1f - k
        val o = k * 110f
        return floatArrayOf(
            s, 0f, 0f, 0f, o,
            0f, s, 0f, 0f, o,
            0f, 0f, s, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Noir et blanc viré : la luminosité va de la couleur [dark] (noirs) à [light] (blancs),
     * 0xRRGGBB. Linéaire en luminosité, donc faisable par une matrice.
     */
    fun duotone(dark: Int, light: Int): FloatArray {
        fun ch(c: Int, shift: Int) = ((c shr shift) and 0xFF).toFloat()
        val out = FloatArray(20)
        for ((row, shift) in listOf(0 to 16, 1 to 8, 2 to 0)) {
            val a = ch(dark, shift)
            val span = (ch(light, shift) - a) / 255f
            out[row * 5] = LUMA_R * span
            out[row * 5 + 1] = LUMA_G * span
            out[row * 5 + 2] = LUMA_B * span
            out[row * 5 + 4] = a
        }
        out[18] = 1f
        return out
    }

    /** Chaque couleur de sortie est la même combinaison de rouge, vert et bleu : un noir et blanc « filtré ». */
    fun monoMix(r: Float, g: Float, b: Float) = floatArrayOf(
        r, g, b, 0f, 0f,
        r, g, b, 0f, 0f,
        r, g, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun sepia() = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
