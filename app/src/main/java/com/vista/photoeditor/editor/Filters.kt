package com.vista.photoeditor.editor

import com.vista.photoeditor.editor.ColorMatrices as Cm

class FilterPreset(val id: String, val name: String, val matrix: FloatArray)

object Filters {
    const val ORIGINAL_ID = "original"

    val all: List<FilterPreset> = listOf(
        FilterPreset(ORIGINAL_ID, "Original", Cm.identity()),
        FilterPreset("vivid", "Vivid", Cm.chain(Cm.saturation(1.35f), Cm.contrast(1.12f))),
        FilterPreset(
            "aurora", "Aurora",
            Cm.chain(Cm.scale(1.02f, 0.97f, 1.12f), Cm.saturation(1.2f), Cm.brightness(6f)),
        ),
        FilterPreset(
            "golden", "Golden",
            Cm.chain(Cm.scale(1.1f, 1.02f, 0.86f), Cm.brightness(8f), Cm.saturation(1.1f)),
        ),
        FilterPreset(
            "arctic", "Arctic",
            Cm.chain(Cm.scale(0.9f, 1f, 1.12f), Cm.brightness(6f), Cm.saturation(0.9f)),
        ),
        FilterPreset("matte", "Matte", Cm.chain(Cm.saturation(0.85f), Cm.fade(0.22f))),
        FilterPreset(
            "cinema", "Cinema",
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
            "drama", "Drama",
            Cm.chain(Cm.exposure(0.92f), Cm.contrast(1.35f), Cm.saturation(0.75f)),
        ),
        FilterPreset(
            "retro", "Rétro",
            Cm.chain(
                Cm.lerp(Cm.identity(), Cm.sepia(), 0.45f),
                Cm.scale(1.04f, 1f, 0.92f),
                Cm.contrast(0.95f),
                Cm.fade(0.15f),
            ),
        ),
        FilterPreset("sepia", "Sépia", Cm.sepia()),
        FilterPreset("mono", "Mono", Cm.saturation(0f)),
        FilterPreset(
            "noir", "Noir",
            Cm.chain(Cm.saturation(0f), Cm.contrast(1.45f), Cm.brightness(-12f)),
        ),
        FilterPreset(
            "pastel", "Pastel",
            Cm.chain(Cm.contrast(0.85f), Cm.brightness(14f), Cm.saturation(0.8f)),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): FilterPreset = byId[id] ?: all.first()
}
