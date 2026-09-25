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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mot-clé reconnu sur une photo, avec la confiance que le modèle lui accorde (0 à 1). */
data class ScoredLabel(val text: String, val score: Float)

/**
 * Analyse embarquée des photos, conservée dans des fichiers pour ne la faire qu'une fois :
 * - des **mots-clés** (« Dog », « Beach »…) pour la recherche par contenu ;
 * - un **vecteur MobileCLIP** qui résume toute la photo, pour la ranger par catégorie
 *   ([categoriesOf]) — bien plus fiable que les mots-clés, qui se trompent seuls.
 * Tout reste sur le téléphone.
 */
class SearchIndex(private val context: Context) {

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val clip = ClipModel(context)

    // Écrits par l'analyse en tâche de fond, lus par les écrans : des tables concurrentes évitent
    // toute lecture pendant une écriture.
    private val labels = ConcurrentHashMap<Long, List<ScoredLabel>>()
    private val embeddings = ConcurrentHashMap<Long, FloatArray>()
    private val categoryCache = ConcurrentHashMap<Long, List<String>>()
    private val mutex = Mutex()
    private var loaded = false

    var indexed by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set

    /** Change à chaque lot analysé : les écrans qui l'observent se remettent à jour. */
    var version by mutableIntStateOf(0)
        private set

    val isIndexing get() = indexed < total
    val progress get() = if (total <= 0) 1f else indexed.toFloat() / total

    /** Nom affiché de chaque catégorie. */
    val categoryTitles: Map<String, String> get() = clip.classes.titles

    /** Nom de souvenir de chaque catégorie qui s'y prête (« Plage », « Neige »…). */
    val memoryNames: Map<String, String> get() = clip.classes.memoryNames

    /** Analyse les photos pas encore connues ; reprend là où elle s'est arrêtée. */
    suspend fun ensureIndexed(photos: List<MediaPhoto>) = mutex.withLock {
        val scope = photos.take(MAX_PHOTOS)
        total = scope.size
        load()
        val missing = scope.filter { !labels.containsKey(it.id) || !embeddings.containsKey(it.id) }
        indexed = scope.size - missing.size
        // Les analyses relues des fichiers comptent comme du neuf pour les écrans qui s'en servent.
        version++
        if (missing.isEmpty()) return@withLock

        var sinceSave = 0
        try {
            for (photo in missing) {
                currentCoroutineContext().ensureActive()
                val result = runCatching { analyse(photo.uri) }.getOrNull()
                labels[photo.id] = result?.first.orEmpty()
                // Une photo illisible reçoit un vecteur vide : elle n'est pas réanalysée à chaque fois.
                embeddings[photo.id] = result?.second ?: FloatArray(0)
                categoryCache.remove(photo.id)
                indexed++
                if (++sinceSave >= SAVE_EVERY) {
                    save()
                    sinceSave = 0
                }
            }
        } finally {
            // Interrompue (écran quitté, galerie rechargée) : le travail fait n'est pas perdu.
            if (sinceSave > 0) withContext(NonCancellable) { save() }
        }
    }

    /**
     * Catégories de la photo (« beach », « people »…), d'après son vecteur MobileCLIP. Vide tant
     * qu'elle n'est pas analysée, ou si elle ne ressemble à aucune catégorie.
     */
    fun categoriesOf(photoId: Long): List<String> {
        categoryCache[photoId]?.let { return it }
        val embedding = embeddings[photoId]?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return clip.classes.classify(embedding).also { categoryCache[photoId] = it }
    }

    /** [normalizedQuery] vient de [PhotoLabels.normalize]. */
    fun matches(photoId: Long, normalizedQuery: String): Boolean {
        val found = labels[photoId] ?: return false
        return found.any { label -> PhotoLabels.searchTerms(label.text).any { it.contains(normalizedQuery) } }
    }

    /** Mots-clés les plus fréquents, proposés quand la recherche est vide. */
    fun suggestions(photos: List<MediaPhoto>, limit: Int = 10): List<String> {
        if (labels.isEmpty()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        photos.forEach { photo ->
            labels[photo.id]?.forEach { label -> counts[label.text] = (counts[label.text] ?: 0) + 1 }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { PhotoLabels.display(it.key) }
    }

    /** Mots-clés et vecteur d'une photo, calculés sur la même image décodée. */
    private suspend fun analyse(uri: Uri): Pair<List<ScoredLabel>, FloatArray> {
        val bitmap = ImageIO.decode(context, uri, ANALYSIS_SIZE)
        val found = labeler.process(InputImage.fromBitmap(bitmap, 0)).await()
            .map { ScoredLabel(it.text, it.confidence) }
        // Calcul lourd : hors du fil de l'interface.
        val embedding = withContext(Dispatchers.Default) { clip.embed(bitmap) }
        return found to embedding
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(context.filesDir, LABELS_FILE)
                if (!file.exists()) return@runCatching
                val items = JSONObject(file.readText()).optJSONObject("items") ?: return@runCatching
                items.keys().forEach { key ->
                    val array = items.getJSONArray(key)
                    labels[key.toLong()] = List(array.length()) {
                        val entry = array.getJSONObject(it)
                        ScoredLabel(entry.getString("l"), entry.getDouble("c").toFloat())
                    }
                }
            }
            runCatching {
                val file = File(context.filesDir, EMBEDDINGS_FILE)
                if (!file.exists()) return@runCatching
                DataInputStream(file.inputStream().buffered()).use { input ->
                    repeat(input.readInt()) {
                        val id = input.readLong()
                        embeddings[id] = FloatArray(input.readInt()) { input.readFloat() }
                    }
                }
            }
        }
    }

    private suspend fun save() {
        val labelSnapshot = labels.toMap()
        val embeddingSnapshot = embeddings.toMap()
        version++
        withContext(Dispatchers.IO) {
            runCatching {
                val items = JSONObject()
                labelSnapshot.forEach { (id, values) ->
                    val array = JSONArray()
                    values.forEach { array.put(JSONObject().put("l", it.text).put("c", it.score)) }
                    items.put(id.toString(), array)
                }
                File(context.filesDir, LABELS_FILE).writeText(JSONObject().put("items", items).toString())
            }
            runCatching {
                // Écrit à côté puis renommé : une coupure en pleine écriture ne corrompt rien.
                val tmp = File(context.filesDir, "$EMBEDDINGS_FILE.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(embeddingSnapshot.size)
                    embeddingSnapshot.forEach { (id, vector) ->
                        out.writeLong(id)
                        out.writeInt(vector.size)
                        vector.forEach { out.writeFloat(it) }
                    }
                }
                tmp.renameTo(File(context.filesDir, EMBEDDINGS_FILE))
            }
        }
    }

    private companion object {
        const val LABELS_FILE = "photo-labels-v3.json"
        const val EMBEDDINGS_FILE = "photo-clip-v1.bin"
        /** Les photos les plus récentes : quelques minutes d'analyse sur un téléphone récent. */
        const val MAX_PHOTOS = 2000

        /** Sauvegarde par lots : le fichier des vecteurs pèse 2 Ko par photo. */
        const val SAVE_EVERY = 50

        /** Assez grand pour que le bord court dépasse 256 px, la taille d'entrée de MobileCLIP. */
        const val ANALYSIS_SIZE = 512
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation: CancellableContinuation<T> ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
