package com.vista.photoeditor.editor

import com.vista.photoeditor.editor.ColorMatrices as Cm

/** Familles du panneau des filtres. */
enum class FilterFamily(val label: String) {
    COLOR("Couleur"),
    FILM("Film"),
    MONO("Noir & blanc"),
    CREATIVE("Créatif"),
}

/**
 * Filtre : une matrice de couleurs, et au besoin un étalonnage (ombres et hautes lumières en
 * valeurs de curseur, virage partiel, couleur sélective) que la matrice ne sait pas faire. Le tout
 * est dosé par l'intensité choisie.
 */
class FilterPreset(
    val id: String,
    val name: String,
    val family: FilterFamily,
    val matrix: FloatArray,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val split: SplitTone? = null,
    val selective: SelectiveColor? = null,
)

object Filters {
    const val ORIGINAL_ID = "original"

    private val C = FilterFamily.COLOR
    private val F = FilterFamily.FILM
    private val M = FilterFamily.MONO
    private val X = FilterFamily.CREATIVE

    /** Demi-largeur de teinte gardée par la couleur sélective : environ ±20°. */
    private const val SELECTIVE_WIDTH = 0.055f

    val all: List<FilterPreset> = listOf(
        FilterPreset(ORIGINAL_ID, "Original", C, Cm.identity()),

        // Couleur
        FilterPreset("vivid", "Vivid", C, Cm.chain(Cm.saturation(1.35f), Cm.contrast(1.12f))),
        FilterPreset("summer", "Été", C, Cm.chain(Cm.scale(1.08f, 1.02f, 0.9f), Cm.saturation(1.25f), Cm.brightness(10f), Cm.contrast(1.05f))),
        FilterPreset("portrait", "Portrait", C, Cm.chain(Cm.scale(1.05f, 1f, 0.95f), Cm.contrast(0.93f), Cm.saturation(0.92f), Cm.brightness(5f)), shadows = 15f),
        FilterPreset("aurora", "Aurora", C, Cm.chain(Cm.scale(1.02f, 0.97f, 1.12f), Cm.saturation(1.2f), Cm.brightness(6f))),
        FilterPreset("golden", "Golden", C, Cm.chain(Cm.scale(1.1f, 1.02f, 0.86f), Cm.brightness(8f), Cm.saturation(1.1f))),
        FilterPreset("arctic", "Arctic", C, Cm.chain(Cm.scale(0.9f, 1f, 1.12f), Cm.brightness(6f), Cm.saturation(0.9f))),
        FilterPreset("midnight", "Minuit", C, Cm.chain(Cm.scale(0.84f, 0.93f, 1.15f), Cm.exposure(0.85f), Cm.saturation(0.8f), Cm.contrast(1.1f))),
        FilterPreset("pastel", "Pastel", C, Cm.chain(Cm.contrast(0.85f), Cm.brightness(14f), Cm.saturation(0.8f))),

        // Film
        FilterPreset(
            "film", "Film", F,
            Cm.chain(Cm.saturation(0.85f), Cm.scale(1.03f, 1f, 0.96f), Cm.fade(0.1f)),
            // Courbe en S douce : noirs un peu creusés, clairs un peu poussés, sur des noirs relevés.
            shadows = -70f, highlights = 60f,
        ),
        FilterPreset(
            "slide", "Pellicule", F,
            Cm.chain(
                // Rouges profonds, verts un peu froids : un film diapo des années 70.
                floatArrayOf(
                    1.12f, -0.06f, -0.06f, 0f, 0f,
                    -0.04f, 1.02f, 0.02f, 0f, 0f,
                    -0.02f, 0.02f, 1f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f,
                ),
                Cm.contrast(1.12f),
                Cm.saturation(1.15f),
            ),
        ),
        FilterPreset(
            "cinema", "Cinema", F,
            Cm.chain(
                floatArrayOf(
                    1.12f, -0.04f, -0.06f, 0f, -4f,
                    -0.02f, 1.02f, 0f, 0f, 0f,
                    -0.08f, 0.04f, 1.06f, 0f, 6f,
                    0f, 0f, 0f, 1f, 0f,
                ),
                Cm.contrast(1.15f),
                Cm.saturation(1.05f),
            ),
        ),
        FilterPreset(
            "tealorange", "Teal & Orange", F,
            Cm.chain(Cm.contrast(1.08f), Cm.saturation(1.1f)),
            split = SplitTone.of(0x0E6B73, 0.45f, 0xFF9A48, 0.35f),
        ),
        FilterPreset("relief", "Relief", F, Cm.chain(Cm.saturation(1.18f), Cm.contrast(1.04f)), shadows = 60f, highlights = -60f),
        FilterPreset(
            "softlight", "Lumière douce", F,
            Cm.chain(Cm.fade(0.08f), Cm.brightness(8f), Cm.saturation(0.95f), Cm.scale(1.03f, 1f, 0.98f)),
            shadows = 20f, highlights = -45f,
        ),
        FilterPreset("backlight", "Contre-jour", F, Cm.saturation(1.05f), shadows = 90f, highlights = -30f),
        FilterPreset("matte", "Matte", F, Cm.chain(Cm.saturation(0.85f), Cm.fade(0.22f))),
        FilterPreset("drama", "Drama", F, Cm.chain(Cm.exposure(0.92f), Cm.contrast(1.35f), Cm.saturation(0.75f))),
        FilterPreset(
            "retro", "Rétro", F,
            Cm.chain(Cm.lerp(Cm.identity(), Cm.sepia(), 0.45f), Cm.scale(1.04f, 1f, 0.92f), Cm.contrast(0.95f), Cm.fade(0.15f)),
        ),

        // Noir & blanc
        FilterPreset("mono", "Mono", M, Cm.saturation(0f)),
        FilterPreset("noir", "Noir", M, Cm.chain(Cm.saturation(0f), Cm.contrast(1.45f), Cm.brightness(-12f))),
        FilterPreset("selenium", "Sélénium", M, Cm.chain(Cm.contrast(1.08f), Cm.duotone(0x12141F, 0xF5F3EE))),
        FilterPreset("sepia", "Sépia", M, Cm.sepia()),
        FilterPreset("cyanotype", "Cyanotype", M, Cm.duotone(0x0A204E, 0xE4F0FA)),
        FilterPreset("duotone", "Bichromie", M, Cm.duotone(0x3A145A, 0xFFECD2)),

        // Créatif
        // Noir et blanc infrarouge : le feuillage (vert) devient clair, le ciel (bleu) sombre.
        FilterPreset("infrared", "Infrarouge", X, Cm.chain(Cm.monoMix(-0.3f, 1.6f, -0.3f), Cm.contrast(1.1f))),
        FilterPreset("keepred", "Rouge seul", X, Cm.identity(), selective = SelectiveColor(0f, SELECTIVE_WIDTH)),
        FilterPreset("keepyellow", "Jaune seul", X, Cm.identity(), selective = SelectiveColor(0.14f, SELECTIVE_WIDTH)),
        FilterPreset("keepgreen", "Vert seul", X, Cm.identity(), selective = SelectiveColor(0.3f, 0.08f)),
        FilterPreset("keepblue", "Bleu seul", X, Cm.identity(), selective = SelectiveColor(0.6f, 0.07f)),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): FilterPreset = byId[id] ?: all.first()
}
