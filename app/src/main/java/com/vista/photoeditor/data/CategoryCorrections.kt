package com.vista.photoeditor.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Photos retirées à la main d'une catégorie (« ce n'est pas une personne ») : le classement
 * automatique ne les y remet plus. Aucun classement n'est parfait, et c'est sur ses propres photos
 * qu'on voit ses erreurs ; la correction reste donc entre les mains de l'utilisateur.
 */
class CategoryCorrections(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val writer = Executors.newSingleThreadExecutor()
    private val removed: MutableMap<String, MutableSet<Long>> = load()

    /** Change à chaque correction : les écrans qui l'observent se remettent à jour. */
    var version by mutableIntStateOf(0)
        private set

    fun isRemoved(category: String, photoId: Long): Boolean = removed[category]?.contains(photoId) == true

    /** Retire [photoIds] de [category] (clé de catégorie, « people »). */
    fun remove(category: String, photoIds: Collection<Long>) {
        if (photoIds.isEmpty()) return
        removed.getOrPut(category) { mutableSetOf() } += photoIds
        version++
        val snapshot = removed.mapValues { it.value.toList() }
        writer.execute { save(snapshot) }
    }

    private fun load(): MutableMap<String, MutableSet<Long>> {
        val out = mutableMapOf<String, MutableSet<Long>>()
        runCatching {
            if (!file.exists()) return out
            val json = JSONObject(file.readText())
            json.keys().forEach { key ->
                val array = json.getJSONArray(key)
                out[key] = MutableList(array.length()) { array.getLong(it) }.toMutableSet()
            }
        }
        return out
    }

    private fun save(snapshot: Map<String, List<Long>>) {
        runCatching {
            val json = JSONObject()
            snapshot.forEach { (key, ids) -> json.put(key, JSONArray(ids)) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val FILE_NAME = "category-corrections.json"
    }
}
