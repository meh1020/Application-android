package com.vista.photoeditor.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Rendu d'une retouche sans sa géométrie : filtre, intensité et réglages.
 * C'est ce qui se copie d'une photo à l'autre et ce qu'on enregistre en préréglage.
 */
data class EditLook(
    val filterId: String = Filters.ORIGINAL_ID,
    val filterIntensity: Float = 1f,
    val adjustments: Map<Adjustment, Float> = emptyMap(),
) {
    val isNeutral get() = filterId == Filters.ORIGINAL_ID && adjustments.isEmpty()

    /** Résumé court affiché sous le nom du préréglage. */
    fun summary(): String {
        val parts = buildList {
            if (filterId != Filters.ORIGINAL_ID) add(Filters.byId(filterId).name)
            if (adjustments.isNotEmpty()) add("${adjustments.size} réglage${if (adjustments.size > 1) "s" else ""}")
        }
        return parts.joinToString(" · ").ifEmpty { "Aucune retouche" }
    }
}

data class EditPreset(val id: String, val name: String, val look: EditLook)

val EditState.look get() = EditLook(filterId, filterIntensity, adjustments)

fun EditState.withLook(look: EditLook) = copy(
    filterId = look.filterId,
    filterIntensity = look.filterIntensity,
    adjustments = look.adjustments,
)

/** Préréglages et presse-papiers de retouche, conservés d'une session à l'autre. */
class EditPresetStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun presets(): List<EditPreset> = runCatching {
        val array = JSONArray(prefs.getString(KEY_PRESETS, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    EditPreset(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        name = item.optString("name", "Préréglage"),
                        look = item.getJSONObject("look").toLook(),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(name: String, look: EditLook): List<EditPreset> {
        val updated = presets() + EditPreset(UUID.randomUUID().toString(), name, look)
        write(updated)
        return updated
    }

    fun delete(id: String): List<EditPreset> {
        val updated = presets().filterNot { it.id == id }
        write(updated)
        return updated
    }

    var clipboard: EditLook?
        get() = runCatching { prefs.getString(KEY_CLIPBOARD, null)?.let { JSONObject(it).toLook() } }.getOrNull()
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_CLIPBOARD) else putString(KEY_CLIPBOARD, value.toJson().toString())
            }.apply()
        }

    private fun write(presets: List<EditPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("name", preset.name)
                    put("look", preset.look.toJson())
                }
            )
        }
        prefs.edit().putString(KEY_PRESETS, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "vista_presets"
        const val KEY_PRESETS = "presets"
        const val KEY_CLIPBOARD = "clipboard"
    }
}

private fun EditLook.toJson() = JSONObject().apply {
    put("filter", filterId)
    put("intensity", filterIntensity.toDouble())
    put(
        "adjustments",
        JSONObject().apply { adjustments.forEach { (key, value) -> put(key.name, value.toDouble()) } },
    )
}

private fun JSONObject.toLook(): EditLook {
    val adjustments = optJSONObject("adjustments")
    val values = buildMap {
        if (adjustments != null) {
            adjustments.keys().forEach { key ->
                val adjustment = Adjustment.entries.firstOrNull { it.name == key }
                if (adjustment != null) put(adjustment, adjustments.getDouble(key).toFloat())
            }
        }
    }
    return EditLook(
        filterId = optString("filter", Filters.ORIGINAL_ID),
        filterIntensity = optDouble("intensity", 1.0).toFloat(),
        adjustments = values,
    )
}
