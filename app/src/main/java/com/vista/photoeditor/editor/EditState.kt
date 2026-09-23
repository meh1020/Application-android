package com.vista.photoeditor.editor

import kotlin.math.max
import kotlin.math.pow
import com.vista.photoeditor.editor.ColorMatrices as Cm

/** Opacité maximale du bord de la vignette et rayon (relatif) où elle commence. */
const val VIGNETTE_MAX_ALPHA = 0.9f
const val VIGNETTE_INNER_STOP = 0.5f

enum class Adjustment(val label: String, val min: Float, val max: Float) {
    BRIGHTNESS("Luminosité", -100f, 100f),
    EXPOSURE("Exposition", -100f, 100f),
    CONTRAST("Contraste", -100f, 100f),
    SATURATION("Saturation", -100f, 100f),
    WARMTH("Chaleur", -100f, 100f),
    TINT("Teinte", -100f, 100f),
    FADE("Fondu", 0f, 100f),
    VIGNETTE("Vignette", 0f, 100f);

    val isBipolar get() = min < 0f
}

enum class AspectRatio(val label: String) {
    FREE("Libre"),
    ORIGINAL("Original"),
    SQUARE("1:1"),
    RATIO_4_5("4:5"),
    RATIO_3_4("3:4"),
    RATIO_9_16("9:16"),
    RATIO_3_2("3:2"),
    RATIO_16_9("16:9");

    /** Rapport largeur/hauteur en pixels, ou null si libre. */
    fun resolve(imageRatio: Float): Float? = when (this) {
        FREE -> null
        ORIGINAL -> imageRatio
        SQUARE -> 1f
        RATIO_4_5 -> 4f / 5f
        RATIO_3_4 -> 3f / 4f
        RATIO_9_16 -> 9f / 16f
        RATIO_3_2 -> 3f / 2f
        RATIO_16_9 -> 16f / 9f
    }

    /** Vrai si le rapport reste valable après une rotation de 90°. */
    val survivesRotation get() = this == FREE || this == ORIGINAL || this == SQUARE
}

/** Rectangle en coordonnées normalisées (0..1) de l'image pivotée/retournée. */
data class NormRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width get() = right - left
    val height get() = bottom - top
    val isFull get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    fun rotatedCw() = NormRect(1f - bottom, left, 1f - top, right)
    fun flippedHorizontally() = NormRect(1f - right, top, 1f - left, bottom)
    fun flippedVertically() = NormRect(left, 1f - bottom, right, 1f - top)

    fun movedBy(dx: Float, dy: Float): NormRect {
        val l = (left + dx).coerceIn(0f, max(0f, 1f - width))
        val t = (top + dy).coerceIn(0f, max(0f, 1f - height))
        return NormRect(l, t, l + width, t + height)
    }

    companion object {
        /** Plus grand rectangle centré de rapport [ratio] dans une image de rapport [imageRatio]. */
        fun centered(ratio: Float, imageRatio: Float): NormRect {
            var w = 1f
            var h = imageRatio / ratio
            if (h > 1f) {
                h = 1f
                w = ratio / imageRatio
            }
            val l = (1f - w) / 2f
            val t = (1f - h) / 2f
            return NormRect(l, t, l + w, t + h)
        }
    }
}

const val MAX_STRAIGHTEN_DEGREES = 45f

/**
 * Agrandissement nécessaire pour qu'une image [width]x[height] pivotée de [degrees]
 * recouvre entièrement son cadre d'origine (pas de coins vides).
 */
fun straightenScale(width: Float, height: Float, degrees: Float): Float {
    if (degrees == 0f) return 1f
    val rad = Math.toRadians(kotlin.math.abs(degrees).toDouble())
    val c = kotlin.math.cos(rad).toFloat()
    val s = kotlin.math.sin(rad).toFloat()
    return max((width * c + height * s) / width, (width * s + height * c) / height)
}

/**
 * Géométrie : rotation horaire par quarts de tour, miroirs, redressement fin
 * (rotation libre avec zoom automatique), puis recadrage.
 */
data class Geometry(
    val quarterTurns: Int,
    val flipH: Boolean,
    val flipV: Boolean,
    val straighten: Float,
    val crop: NormRect,
) {
    fun sameTransform(other: Geometry) =
        quarterTurns == other.quarterTurns && flipH == other.flipH && flipV == other.flipV
}

data class EditState(
    val filterId: String = Filters.ORIGINAL_ID,
    val filterIntensity: Float = 1f,
    val adjustments: Map<Adjustment, Float> = emptyMap(),
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,
    val crop: NormRect = NormRect(),
) {
    fun value(adjustment: Adjustment): Float = adjustments[adjustment] ?: 0f

    val geometry get() = Geometry(quarterTurns, flipH, flipV, straighten, crop)

    val vignette get() = value(Adjustment.VIGNETTE) / 100f

    /** Filtre (dosé par l'intensité) puis réglages manuels. */
    fun colorMatrix(): FloatArray {
        val filter = Cm.lerp(Cm.identity(), Filters.byId(filterId).matrix, filterIntensity)

        val contrast = value(Adjustment.CONTRAST) / 100f
        val adjustments = Cm.chain(
            Cm.exposure(2f.pow(value(Adjustment.EXPOSURE) / 100f)),
            Cm.brightness(value(Adjustment.BRIGHTNESS) * 0.6f),
            Cm.contrast(if (contrast >= 0f) 1f + contrast else 1f + contrast * 0.7f),
            Cm.saturation(1f + value(Adjustment.SATURATION) / 100f),
            Cm.warmth(value(Adjustment.WARMTH) * 0.3f),
            Cm.tint(value(Adjustment.TINT) * 0.25f),
            Cm.fade(value(Adjustment.FADE) / 100f * 0.35f),
        )
        return Cm.concat(adjustments, filter)
    }
}
