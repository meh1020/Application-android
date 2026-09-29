package com.vista.photoeditor.editor

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Retouche automatique : exposition, contraste, ombres, hautes lumières et balance des blancs
 * calculés d'après la photo.
 *
 * Le résultat est exprimé en valeurs des curseurs de l'éditeur (voir [EditState.colorMatrix]) :
 * l'utilisateur voit ce qui a été fait et peut l'ajuster. Les réglages restent prudents : une photo
 * déjà réussie n'est presque pas touchée, une nuit ne devient pas un jour, un coucher de soleil
 * garde sa chaleur.
 */
object AutoEnhance {

    /** Curseurs que la retouche automatique règle ; les autres restent tels quels. */
    val ADJUSTMENTS = listOf(
        Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.SHADOWS, Adjustment.HIGHLIGHTS,
        Adjustment.WARMTH, Adjustment.TINT,
    )

    /** Noirs et blancs visés : 0,5 % des pixels au-dessous, 0,5 % au-dessus. */
    private const val BLACK_TARGET = 8f
    private const val WHITE_TARGET = 247f

    /** Étirement maximal des tons ; moindre pour une scène de nuit, qui doit rester sombre. */
    private const val MAX_STRETCH = 1.5f
    private const val MAX_NIGHT_STRETCH = 1.15f
    private const val NIGHT_MIDDLE = 40f

    /** Étirement en dessous duquel la photo est laissée telle quelle : elle occupe déjà la plage. */
    private const val MIN_STRETCH = 1.06f

    /** Bornes du contraste obtenu (facteur), pour garder des curseurs raisonnables. */
    private const val MIN_CONTRAST = 0.85f
    private const val MAX_CONTRAST = 1.4f

    /** Correction des couleurs : part corrigée, et limites en valeurs de curseur. */
    private const val WB_STRENGTH = 0.7f
    private const val MAX_WARMTH = 35f
    private const val MAX_TINT = 10f

    /**
     * La teinte (vert / magenta) est la mesure la moins sûre : sur des photos sans dominante, elle
     * variait d'une photo à l'autre dans les deux sens. Seul un écart net est corrigé.
     */
    private const val MIN_TINT = 6f

    /** En dessous, une valeur de curseur est ignorée : la photo n'a pas besoin de ce réglage. */
    private const val MIN_STEP = 4f

    private const val PIVOT = 127.5f

    /**
     * Contre-jour : le quart le plus sombre de l'image reste sous [DARK_QUARTER] après exposition et
     * contraste, alors que des zones claires (au-dessus de [BRIGHT_AREA]) empêchent d'éclaircir le
     * tout. Les ombres remontent alors ce quart vers [SHADOW_TARGET], sans dépasser [MAX_SHADOWS]
     * (une silhouette voulue n'est pas effacée), ni [MAX_NIGHT_SHADOWS] la nuit (le bruit
     * ressortirait).
     */
    private const val DARK_QUARTER = 45f
    private const val BRIGHT_AREA = 215f
    private const val SHADOW_TARGET = 62f
    private const val MAX_SHADOWS = 40
    private const val MAX_NIGHT_SHADOWS = 15

    /**
     * Grand ciel clair mais pas brûlé : un quart de l'image au-dessus de [BRIGHT_QUARTER] et les
     * 5 % les plus clairs sous [BURNT]. Les hautes lumières descendent alors ces 5 % de
     * [HIGHLIGHT_PULL], jusqu'à [MAX_HIGHLIGHTS] : les nuages retrouvent du relief. Un blanc
     * brûlé n'a plus de détail à rendre : il n'est pas touché.
     */
    private const val BRIGHT_QUARTER = 200f
    private const val BURNT = 250f
    private const val HIGHLIGHT_PULL = 10f
    private const val MAX_HIGHLIGHTS = 25

    /**
     * Valeurs des curseurs [ADJUSTMENTS] pour la photo [pixels] (ARGB, ligne après ligne, [width]
     * pixels par ligne ; une image réduite suffit), compte tenu du filtre et des autres réglages de
     * [state]. 0 : réglage inutile.
     */
    fun compute(pixels: IntArray, width: Int, state: EditState): Map<Adjustment, Float> {
        val none = ADJUSTMENTS.associateWith { 0f }
        if (pixels.isEmpty() || width <= 1) return none
        val filter = state.filterMatrix()
        val n = pixels.size
        val red = FloatArray(n)
        val green = FloatArray(n)
        val blue = FloatArray(n)
        val histogram = IntArray(256)
        for (i in 0 until n) {
            val p = pixels[i]
            val r0 = ((p shr 16) and 0xFF).toFloat()
            val g0 = ((p shr 8) and 0xFF).toFloat()
            val b0 = (p and 0xFF).toFloat()
            // Couleurs telles que le filtre choisi les rend : la retouche vient après lui.
            red[i] = channel(filter, 0, r0, g0, b0)
            green[i] = channel(filter, 1, r0, g0, b0)
            blue[i] = channel(filter, 2, r0, g0, b0)
            histogram[luma(red[i], green[i], blue[i]).roundToInt().coerceIn(0, 255)]++
        }
        val brightness = state.value(Adjustment.BRIGHTNESS) * 0.6f
        val black = percentile(histogram, n, 0.005f) + brightness
        val middle = percentile(histogram, n, 0.5f) + brightness
        val white = percentile(histogram, n, 0.995f) + brightness
        // Photo presque uniforme : rien à mesurer, rien à corriger.
        if (white - black < 16f) return none

        val (exposure, contrast) = levels(black, middle, white)
        // Luminosité d'un pixel une fois l'exposition et le contraste appliqués.
        fun leveled(fraction: Float): Float =
            (contrast * (exposure * (percentile(histogram, n, fraction) + brightness) - PIVOT) + PIVOT).coerceIn(0f, 255f)
        val shadows = shadowsFor(leveled(0.25f), leveled(0.95f), middle)
        val highlights = highlightsFor(leveled(0.75f), leveled(0.95f), shadows)

        // Balance des blancs : l'écart de couleur restant après exposition, contraste et saturation.
        var warmth = 0f
        var tint = 0f
        edgeCast(red, green, blue, width, middle)?.let { (redMinusBlue, greenExcess) ->
            val gain = exposure * contrast * (1f + state.value(Adjustment.SATURATION) / 100f)
            // Chaleur : rouge +0,3 / vert +0,06 / bleu −0,3 par cran ; teinte : rouge et bleu
            // +0,125, vert −0,25 par cran (voir ColorMatrices.warmth et tint).
            warmth = -redMinusBlue * gain / 0.6f
            tint = (greenExcess * gain + 0.06f * warmth) / 0.375f
            warmth *= WB_STRENGTH
            tint *= WB_STRENGTH
            // Une photo trop chaude est souvent voulue (bougies, soleil couchant) : on la refroidit
            // à moitié seulement.
            if (warmth < 0f) warmth *= 0.5f
        }

        val contrastSlider = if (contrast >= 1f) (contrast - 1f) * 100f else (contrast - 1f) * 100f / 0.7f
        return mapOf(
            Adjustment.EXPOSURE to step(100f * log2(exposure), Adjustment.EXPOSURE),
            Adjustment.CONTRAST to step(contrastSlider, Adjustment.CONTRAST),
            Adjustment.SHADOWS to step(shadows.toFloat(), Adjustment.SHADOWS),
            Adjustment.HIGHLIGHTS to step(highlights.toFloat(), Adjustment.HIGHLIGHTS),
            Adjustment.WARMTH to step(warmth.coerceIn(-MAX_WARMTH, MAX_WARMTH), Adjustment.WARMTH),
            Adjustment.TINT to (step(tint.coerceIn(-MAX_TINT, MAX_TINT), Adjustment.TINT).takeIf { abs(it) >= MIN_TINT } ?: 0f),
        )
    }

    /**
     * Étire les tons pour que les noirs et les blancs atteignent [BLACK_TARGET] et [WHITE_TARGET],
     * comme un réglage des niveaux, traduit en exposition (facteur) et contraste (facteur autour du
     * gris moyen). Une photo qui occupe déjà toute la plage n'est pas touchée ; on ne resserre
     * jamais les tons : une photo claire (neige, ciel) ou sombre à dessein le reste.
     */
    private fun levels(black: Float, middle: Float, white: Float): Pair<Float, Float> {
        val maxStretch = if (middle < NIGHT_MIDDLE) MAX_NIGHT_STRETCH else MAX_STRETCH
        val stretch = ((WHITE_TARGET - BLACK_TARGET) / (white - black)).coerceIn(1f, maxStretch)
        if (stretch < MIN_STRETCH) return 1f to 1f
        // Les noirs restent où ils sont s'ils sont déjà plus sombres que visé : relever les noirs
        // d'une nuit la rendrait laiteuse.
        val offset = min(BLACK_TARGET, black) - stretch * black
        // v' = contraste × (exposition × v − 127,5) + 127,5 = étirement × v + décalage.
        val contrast = (1f - offset / PIVOT).coerceIn(MIN_CONTRAST, MAX_CONTRAST)
        val exposure = (stretch / contrast).coerceIn(0.6f, 1.8f)
        return exposure to contrast
    }

    /** Réglage des ombres (0..[MAX_SHADOWS]) : le plus faible qui éclaircit assez le quart sombre. */
    private fun shadowsFor(darkQuarter: Float, bright: Float, middle: Float): Int {
        if (darkQuarter >= DARK_QUARTER || bright < BRIGHT_AREA) return 0
        val limit = if (middle < NIGHT_MIDDLE) MAX_NIGHT_SHADOWS else MAX_SHADOWS
        for (value in 5..limit step 5) {
            if (ToneCurve.of(value.toFloat(), 0f).map(darkQuarter / 255f) * 255f >= SHADOW_TARGET) return value
        }
        return limit
    }

    /** Réglage des hautes lumières (0..−[MAX_HIGHLIGHTS]), compte tenu des ombres déjà choisies. */
    private fun highlightsFor(brightQuarter: Float, top: Float, shadows: Int): Int {
        if (brightQuarter < BRIGHT_QUARTER || top >= BURNT) return 0
        val target = top - HIGHLIGHT_PULL
        for (value in 5..MAX_HIGHLIGHTS step 5) {
            if (ToneCurve.of(shadows.toFloat(), -value.toFloat()).map(top / 255f) * 255f <= target) return -value
        }
        return -MAX_HIGHLIGHTS
    }

    /**
     * Dominante de couleur, estimée sur les contours (« gray-edge », van de Weijer et al., 2007) :
     * en moyenne, les différences entre pixels voisins sont grises sous un éclairage neutre. Un
     * ciel bleu ou une pelouse, unis, n'y pèsent presque rien, contrairement à une moyenne des
     * pixels, qui prenait leur couleur pour une dominante. Renvoie l'écart rouge − bleu et l'excès
     * de vert d'un gris de niveau [middle], ou null si la photo n'a presque pas de contours.
     */
    private fun edgeCast(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, middle: Float): Pair<Float, Float>? {
        var er = 0.0
        var eg = 0.0
        var eb = 0.0
        var pairs = 0
        fun add(i: Int, j: Int) {
            // Ni pixels brûlés (leur couleur est fausse), ni pixels presque noirs (du bruit).
            if (max(red[i], max(green[i], blue[i])) >= 250f || max(red[j], max(green[j], blue[j])) >= 250f) return
            if (luma(red[i], green[i], blue[i]) < 20f || luma(red[j], green[j], blue[j]) < 20f) return
            val dr = (red[i] - red[j]).toDouble()
            val dg = (green[i] - green[j]).toDouble()
            val db = (blue[i] - blue[j]).toDouble()
            // Norme 2 : les contours francs comptent plus que le grain.
            er += dr * dr; eg += dg * dg; eb += db * db; pairs++
        }
        val height = red.size / width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (x + 1 < width) add(i, i + 1)
                if (y + 1 < height) add(i, i + width)
            }
        }
        if (pairs < red.size / 4) return null
        val r = sqrt(er / pairs).toFloat()
        val g = sqrt(eg / pairs).toFloat()
        val b = sqrt(eb / pairs).toFloat()
        val mean = (r + g + b) / 3f
        if (mean < 1f) return null
        // Même dominante pour un gris du niveau des tons moyens.
        val level = middle.coerceIn(60f, 180f)
        return level * (r - b) / mean to level * (g - (r + b) / 2f) / mean
    }

    private fun step(value: Float, adjustment: Adjustment): Float {
        val rounded = value.roundToInt().toFloat().coerceIn(adjustment.min, adjustment.max)
        return if (abs(rounded) < MIN_STEP) 0f else rounded
    }

    private fun channel(m: FloatArray, row: Int, r: Float, g: Float, b: Float): Float {
        val o = row * 5
        return (m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 4]).coerceIn(0f, 255f)
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Float {
        val target = fraction * total
        var seen = 0
        for (v in histogram.indices) {
            seen += histogram[v]
            if (seen >= target) return v.toFloat()
        }
        return 255f
    }
}
