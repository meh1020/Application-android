package com.vista.photoeditor.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Virage partiel : les tons sombres et les tons clairs reçoivent chacun leur couleur (le
 * « teal & orange » du cinéma : ombres bleu-vert, peaux orangées). Chaque teinte est ramenée à une
 * luminosité nulle : elle colore sans éclaircir ni assombrir.
 */
class SplitTone private constructor(
    /** Décalage (0..1 par canal) ajouté aux tons sombres, pondéré par (1 − L)². */
    val shadow: FloatArray,
    /** Décalage ajouté aux tons clairs, pondéré par L². */
    val highlight: FloatArray,
) {
    fun scaled(k: Float) = SplitTone(FloatArray(3) { shadow[it] * k }, FloatArray(3) { highlight[it] * k })

    companion object {
        /** [shadowColor] et [highlightColor] en 0xRRGGBB, [amount] entre 0 et 1. */
        fun of(shadowColor: Int, shadowAmount: Float, highlightColor: Int, highlightAmount: Float) =
            SplitTone(tint(shadowColor, shadowAmount), tint(highlightColor, highlightAmount))

        private fun tint(color: Int, amount: Float): FloatArray {
            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            val l = Grade.luma(r, g, b)
            return floatArrayOf((r - l) * amount, (g - l) * amount, (b - l) * amount)
        }
    }
}

/**
 * Couleur sélective : une seule teinte garde sa couleur, le reste passe en noir et blanc. Les
 * couleurs ternes (peu saturées) suivent le noir et blanc : un gris légèrement rosé n'est pas « rouge ».
 */
class SelectiveColor(
    /** Teinte gardée, sur 0..1 (0 rouge, 1/6 jaune, 1/3 vert, 2/3 bleu). */
    val hue: Float,
    /** Demi-largeur de la plage gardée, sur 0..1 ; au-delà, fondu sur [FEATHER]. */
    val width: Float,
    /** 0 : sans effet, 1 : effet complet. */
    val strength: Float = 1f,
) {
    fun scaled(k: Float) = SelectiveColor(hue, width, strength * k)

    companion object {
        const val FEATHER = 0.06f
        const val MIN_SATURATION = 0.08f
        const val FULL_SATURATION = 0.25f
    }
}

/**
 * Étalonnage appliqué après la matrice de couleurs : courbe de tons, virage partiel, couleur
 * sélective, dans cet ordre. Ce sont des traitements non linéaires, qu'une matrice ne sait pas
 * faire. La même formule sert à l'aperçu ([SHADER], Android 13 et plus) et à l'export ([applyTo]).
 */
class Grade(
    val tone: ToneCurve = ToneCurve.IDENTITY,
    val split: SplitTone? = null,
    val selective: SelectiveColor? = null,
) {
    val isIdentity get() = tone.isIdentity && split == null && (selective == null || selective.strength <= 0f)

    /** Applique l'étalonnage à des pixels ARGB (couleurs sur 0..255), sur place. */
    fun applyTo(pixels: IntArray, from: Int = 0, to: Int = pixels.size) {
        if (isIdentity) return
        val out = FloatArray(3)
        for (i in from until to) {
            val p = pixels[i]
            apply(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, out)
            pixels[i] = (p and 0xFF000000.toInt()) or (channel(out[0]) shl 16) or (channel(out[1]) shl 8) or channel(out[2])
        }
    }

    /** Un pixel (couleurs sur 0..1) ; résultat dans [out]. */
    fun apply(r0: Float, g0: Float, b0: Float, out: FloatArray) {
        var r = r0
        var g = g0
        var b = b0
        if (!tone.isIdentity) {
            val x = luma(r, g, b).coerceIn(0f, 1f)
            r = clamp(tone.shift(r, x)); g = clamp(tone.shift(g, x)); b = clamp(tone.shift(b, x))
        }
        split?.let { s ->
            val l = luma(r, g, b)
            val ws = (1f - l) * (1f - l)
            val wh = l * l
            r = clamp(r + ws * s.shadow[0] + wh * s.highlight[0])
            g = clamp(g + ws * s.shadow[1] + wh * s.highlight[1])
            b = clamp(b + ws * s.shadow[2] + wh * s.highlight[2])
        }
        selective?.takeIf { it.strength > 0f }?.let { sel ->
            val top = max(r, max(g, b))
            val low = min(r, min(g, b))
            val saturation = if (top > 0f) (top - low) / top else 0f
            val d = abs(hue(r, g, b, top, low) - sel.hue).let { min(it, 1f - it) }
            val keep = (1f - smoothstep(sel.width, sel.width + SelectiveColor.FEATHER, d)) *
                smoothstep(SelectiveColor.MIN_SATURATION, SelectiveColor.FULL_SATURATION, saturation)
            val l = luma(r, g, b)
            // Gris là où la teinte n'est pas gardée ; dosé par l'intensité.
            val mix = sel.strength * (1f - keep)
            r += (l - r) * mix; g += (l - g) * mix; b += (l - b) * mix
        }
        out[0] = r; out[1] = g; out[2] = b
    }

    companion object {
        val IDENTITY = Grade()

        fun luma(r: Float, g: Float, b: Float) = ToneCurve.LUMA_R * r + ToneCurve.LUMA_G * g + ToneCurve.LUMA_B * b

        private fun clamp(v: Float) = min(1f, max(0f, v))

        private fun channel(v: Float) = min(255, max(0, (v * 255f).roundToInt()))

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Teinte sur 0..1 (TSV), connaissant le maximum et le minimum des trois couleurs. */
        fun hue(r: Float, g: Float, b: Float, top: Float, low: Float): Float {
            val c = top - low
            if (c <= 1e-6f) return 0f
            val h = when (top) {
                r -> ((g - b) / c).let { if (it < 0f) it + 6f else it }
                g -> (b - r) / c + 2f
                else -> (r - g) / c + 4f
            }
            return h / 6f
        }

        /**
         * Shader AGSL de l'aperçu : mêmes formules que [apply], sur l'image déjà passée par la
         * matrice de couleurs.
         */
        const val SHADER = """
            uniform shader image;
            uniform float shadowAmp;
            uniform float highlightAmp;
            uniform float3 splitShadow;
            uniform float3 splitHighlight;
            uniform float selHue;
            uniform float selWidth;
            uniform float selStrength;

            const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

            float hueOf(float3 c, float top, float low) {
                float d = top - low;
                if (d <= 0.000001) return 0.0;
                float h;
                if (top == c.r) { h = (c.g - c.b) / d; if (h < 0.0) h += 6.0; }
                else if (top == c.g) { h = (c.b - c.r) / d + 2.0; }
                else { h = (c.r - c.g) / d + 4.0; }
                return h / 6.0;
            }

            half4 main(float2 coord) {
                half4 src = image.eval(coord);
                if (src.a <= 0.0) return src;
                float3 c = src.rgb / src.a;

                // Courbe de tons (ombres, hautes lumières).
                float x = clamp(dot(c, LUMA), 0.0, 1.0);
                float y = 1.0 - x;
                float f = x + shadowAmp * x * y * y * y + highlightAmp * x * x * x * y;
                float k = x > 0.0001 ? f / x : 1.0 + shadowAmp;
                c = clamp(0.5 * (c * k + c + (f - x)), 0.0, 1.0);

                // Virage partiel.
                float l = dot(c, LUMA);
                c = clamp(c + (1.0 - l) * (1.0 - l) * splitShadow + l * l * splitHighlight, 0.0, 1.0);

                // Couleur sélective.
                if (selStrength > 0.0) {
                    float top = max(c.r, max(c.g, c.b));
                    float low = min(c.r, min(c.g, c.b));
                    float sat = top > 0.0 ? (top - low) / top : 0.0;
                    float d = abs(hueOf(c, top, low) - selHue);
                    d = min(d, 1.0 - d);
                    float keep = (1.0 - smoothstep(selWidth, selWidth + 0.06, d)) * smoothstep(0.08, 0.25, sat);
                    float g = dot(c, LUMA);
                    c = mix(c, float3(g), selStrength * (1.0 - keep));
                }
                return half4(c * src.a, src.a);
            }
        """
    }
}
