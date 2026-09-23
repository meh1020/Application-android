package com.vista.photoeditor.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.vista.photoeditor.editor.ImageIO
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reconnaissance d'images embarquée : chaque photo reçoit des mots-clés (« Dog », « Beach »…)
 * conservés dans un fichier, pour une recherche par contenu qui fonctionne hors ligne.
 */
class SearchIndex(private val context: Context) {

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val labels = mutableMapOf<Long, List<String>>()
    private val mutex = Mutex()
    private var loaded = false

    var indexed by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    val isIndexing get() = indexed < total
    val progress get() = if (total <= 0) 1f else indexed.toFloat() / total

    /** Analyse les photos pas encore connues ; reprend là où elle s'est arrêtée. */
    suspend fun ensureIndexed(photos: List<MediaPhoto>) = mutex.withLock {
        load()
        val scope = photos.take(MAX_PHOTOS)
        val missing = scope.filter { it.id !in labels }
        total = scope.size
        indexed = scope.size - missing.size
        if (missing.isEmpty()) return@withLock

        var sinceSave = 0
        for (photo in missing) {
            currentCoroutineContext().ensureActive()
            labels[photo.id] = runCatching { labelsOf(photo.uri) }.getOrDefault(emptyList())
            indexed++
            if (++sinceSave >= SAVE_EVERY) {
                save()
                sinceSave = 0
            }
        }
        if (sinceSave > 0) save()
    }

    /** [normalizedQuery] vient de [PhotoLabels.normalize]. */
    fun matches(photoId: Long, normalizedQuery: String): Boolean {
        val found = labels[photoId] ?: return false
        return found.any { label -> PhotoLabels.searchTerms(label).any { it.contains(normalizedQuery) } }
    }

    /** Mots-clés les plus fréquents, proposés quand la recherche est vide. */
    fun suggestions(photos: List<MediaPhoto>, limit: Int = 10): List<String> {
        if (labels.isEmpty()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        photos.forEach { photo ->
            labels[photo.id]?.forEach { label -> counts[label] = (counts[label] ?: 0) + 1 }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { PhotoLabels.display(it.key) }
    }

    private suspend fun labelsOf(uri: Uri): List<String> {
        val bitmap = ImageIO.decode(context, uri, ANALYSIS_SIZE)
        val result = labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.map { it.text }
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return@runCatching
                val items = JSONObject(file.readText()).optJSONObject("items") ?: return@runCatching
                items.keys().forEach { key ->
                    val array = items.getJSONArray(key)
                    labels[key.toLong()] = List(array.length()) { array.getString(it) }
                }
            }
        }
    }

    private suspend fun save() {
        val snapshot = labels.toMap()
        withContext(Dispatchers.IO) {
            runCatching {
                val items = JSONObject()
                snapshot.forEach { (id, values) -> items.put(id.toString(), JSONArray(values)) }
                File(context.filesDir, FILE_NAME).writeText(JSONObject().put("items", items).toString())
            }
        }
    }

    private companion object {
        const val FILE_NAME = "photo-labels.json"
        const val MAX_PHOTOS = 600
        const val SAVE_EVERY = 20
        const val ANALYSIS_SIZE = 320
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation: CancellableContinuation<T> ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
