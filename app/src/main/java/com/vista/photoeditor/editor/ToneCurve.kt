package com.vista.photoeditor.editor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ombres et hautes lumières : une courbe de tons sur la luminosité, que la matrice de couleurs (qui
 * ne sait faire que des réglages linéaires) ne permet pas. Éclaircir les ombres sauve un contre-jour
 * sans brûler le ciel ; baisser les hautes lumières rend du détail aux zones claires.
 *
 * Pour une luminosité x (0..1) : f(x) = x + a·x(1−x)³ + b·x³(1−x). Le premier terme agit surtout
 * vers x = 0,25, le second vers x = 0,75 ; les noirs (0) et les blancs (1) ne bougent pas. Chaque
 * couleur reçoit la moyenne de deux corrections ([shift]) : multipliée par f(x)/x, elle garderait sa
 * saturation mais la gonflerait dans les ombres (un bleu marine éclairci virait au violet vif) ;
 * augmentée de f(x) − x, elle tournerait au gris. La moyenne éclaircit sans l'un ni l'autre.
 *
 * Fait partie de l'étalonnage ([Grade]), dont le shader reprend la même formule pour l'aperçu :
 * ce qu'on voit est ce qu'on enregistre.
 */
class ToneCurve private constructor(
    /** Coefficient des ombres (a). */
    val shadowAmp: Float,
    /** Coefficient des hautes lumières (b). */
    val highlightAmp: Float,
) {
    val isIdentity get() = shadowAmp == 0f && highlightAmp == 0f

    /** Luminosité après la courbe. */
    fun map(x: Float): Float {
        val y = 1f - x
        return x + shadowAmp * x * y * y * y + highlightAmp * x * x * x * y
    }

    /** Facteur appliqué à un pixel de luminosité [x] ; au noir, la pente de la courbe. */
    fun gain(x: Float): Float = if (x > 1e-4f) map(x) / x else 1f + shadowAmp

    /** Couleur [c] (0..1) d'un pixel de luminosité [x], après la courbe. */
    fun shift(c: Float, x: Float): Float = shift(c, x, map(x), gain(x))

    private fun shift(c: Float, x: Float, fx: Float, k: Float): Float = 0.5f * (c * k + c + fx - x)

    /** Applique la courbe à des pixels ARGB (couleurs sur 0..255), sur place. */
    fun applyTo(pixels: IntArray, from: Int = 0, to: Int = pixels.size) {
        if (isIdentity) return
        for (i in from until to) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val x = ((LUMA_R * r + LUMA_G * g + LUMA_B * b) / 255f).coerceIn(0f, 1f)
            val fx = map(x)
            val k = gain(x)
            fun out(c: Int) = channel(shift(c / 255f, x, fx, k) * 255f)
            pixels[i] = (p and 0xFF000000.toInt()) or (out(r) shl 16) or (out(g) shl 8) or out(b)
        }
    }

    private fun channel(v: Float) = min(255, max(0, v.roundToInt()))

    companion object {
        val IDENTITY = ToneCurve(0f, 0f)

        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f

        /** Maximum de x(1−x)³ (en x = 0,25) : un réglage à 100 déplace ce point de [STRONG]. */
        private const val PEAK = 0.10546875f

        /**
         * Déplacement maximal à 100 dans le sens utile (éclaircir les ombres, baisser les hautes
         * lumières) : 0,25 sur 1, de quoi sortir un visage d'un contre-jour.
         */
        private const val STRONG = 0.25f

        /**
         * Dans l'autre sens (boucher les ombres, pousser les hautes lumières), au-delà de ce
         * déplacement la courbe redescendrait près du noir ou du blanc : l'image s'inverserait.
         */
        private const val GENTLE = 0.08f

        /** Pente minimale de la courbe : en dessous, les dégradés se tasseraient en aplats. */
        private const val MIN_SLOPE = 0.25f

        /**
         * Courbe pour les curseurs [shadows] et [highlights] (−100..100). Si les deux, poussés
         * ensemble, aplatissaient la courbe, leur effet est réduit d'autant.
         */
        fun of(shadows: Float, highlights: Float): ToneCurve {
            if (shadows == 0f && highlights == 0f) return IDENTITY
            val s = (shadows / 100f).coerceIn(-1f, 1f)
            val h = (highlights / 100f).coerceIn(-1f, 1f)
            var a = s * (if (s > 0f) STRONG else GENTLE) / PEAK
            // Pour les hautes lumières, le sens utile est de les baisser (h < 0).
            var b = h * (if (h < 0f) STRONG else GENTLE) / PEAK
            val lowest = minSlope(a, b)
            if (lowest < MIN_SLOPE) {
                val k = (1f - MIN_SLOPE) / (1f - lowest)
                a *= k
                b *= k
            }
            return ToneCurve(a, b)
        }

        /** Pente la plus faible de la courbe sur 0..1 (dérivée de f). */
        private fun minSlope(a: Float, b: Float): Float {
            var lowest = Float.MAX_VALUE
            for (i in 0..200) {
                val x = i / 200f
                val y = 1f - x
                val slope = 1f + a * (y * y * y - 3f * x * y * y) + b * (3f * x * x * y - x * x * x)
                lowest = min(lowest, slope)
            }
            return lowest
        }
    }
}
