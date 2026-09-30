package com.vista.photoeditor.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vista.photoeditor.editor.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyse embarquée des photos : chacune est résumée par un **vecteur MobileCLIP**, calculé une
 * fois et conservé dans un fichier. Ce vecteur sert à tout :
 * - ranger la photo par catégorie ([categoriesOf]) ;
 * - la retrouver par son contenu ([relevance]) : « robe », « chien », « coucher de soleil »…
 * Tout reste sur le téléphone.
 */
class SearchIndex private constructor(private val context: Context) {

    private val clip = ClipModel(context)

    // Écrits par l'analyse en tâche de fond, lus par les écrans : des tables concurrentes évitent
    // toute lecture pendant une écriture.
    private val embeddings = ConcurrentHashMap<Long, FloatArray>()
    private val categoryCache = ConcurrentHashMap<Long, List<String>>()
    private val conceptCache = ConcurrentHashMap<Long, ClipConcepts.Match>()
    private val mutex = Mutex()
    private var loaded = false

    /** Photos déjà écrites dans le fichier : seules les nouvelles y sont ajoutées. */
    private val saved = HashSet<Long>()

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

    /**
     * Analyse les photos pas encore connues, parmi les [limit] plus récentes ; reprend là où elle
     * s'est arrêtée. L'app en analyse [MAX_PHOTOS] à l'ouverture ; la tâche de fond ([IndexWorker]),
     * pendant la recharge, va jusqu'à [MAX_BACKGROUND_PHOTOS].
     */
    suspend fun ensureIndexed(photos: List<MediaPhoto>, limit: Int = MAX_PHOTOS) = mutex.withLock {
        val scope = photos.take(limit)
        total = scope.size
        load()
        prune(photos)
        val missing = scope.filter { !embeddings.containsKey(it.id) }
        indexed = scope.size - missing.size
        // Les analyses relues du fichier comptent comme du neuf pour les écrans qui s'en servent.
        version++
        if (missing.isEmpty()) {
            warmUp(scope)
            return@withLock
        }

        var sinceSave = 0
        try {
            for (photo in missing) {
                currentCoroutineContext().ensureActive()
                // Une photo illisible reçoit un vecteur vide : elle n'est pas réanalysée à chaque fois.
                embeddings[photo.id] = runCatching { analyse(photo.uri) }.getOrNull() ?: FloatArray(0)
                categoryCache.remove(photo.id)
                conceptCache.remove(photo.id)
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
        warmUp(scope)
    }

    /**
     * Calcule d'avance catégories et concepts de chaque photo, hors du fil de l'interface :
     * une recherche ou l'ouverture d'Explorer n'ont plus qu'à lire le résultat.
     */
    private suspend fun warmUp(photos: List<MediaPhoto>) = withContext(Dispatchers.Default) {
        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            categoriesOf(photo.id)
            conceptsOf(photo.id)
        }
        version++
    }

    /** Vecteur MobileCLIP de la photo (norme 1), ou null tant qu'elle n'est pas analysée. */
    fun vectorOf(photoId: Long): FloatArray? = embeddings[photoId]?.takeIf { it.isNotEmpty() }

    /**
     * Catégories de la photo (« beach », « people »…), d'après son vecteur MobileCLIP. Vide tant
     * qu'elle n'est pas analysée, ou si elle ne ressemble à aucune catégorie.
     */
    fun categoriesOf(photoId: Long): List<String> {
        categoryCache[photoId]?.let { return it }
        val embedding = embeddings[photoId]?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return clip.classes.classify(embedding).also { categoryCache[photoId] = it }
    }

    /**
     * Concepts du vocabulaire que désigne la recherche [normalizedQuery] (issue de
     * [PhotoLabels.normalize]) : « chien » désigne le chien et les races de chiens. À calculer une
     * fois par requête, puis à passer à [relevance] pour chaque photo.
     */
    fun conceptsFor(normalizedQuery: String): Set<Int> =
        if (normalizedQuery.length < MIN_QUERY_LENGTH) emptySet() else clip.concepts.matching(normalizedQuery)

    /**
     * Pertinence de la photo pour les [concepts] recherchés, ou null si elle ne correspond pas.
     * Une photo correspond quand l'un d'eux est, de tout le vocabulaire, le concept qui la décrit le
     * mieux, ou le premier concept précis derrière des concepts génériques (voir
     * [ClipConcepts.describe]) : exigeant, mais c'est ce qui évite les intrus.
     */
    fun relevance(photoId: Long, concepts: Set<Int>): Float? {
        if (concepts.isEmpty()) return null
        return conceptsOf(photoId)?.scoreOf(concepts)
    }

    /** Sujets les plus fréquents dans les photos, proposés quand la recherche est vide. */
    fun suggestions(photos: List<MediaPhoto>, limit: Int = 10): List<String> {
        val counts = mutableMapOf<Int, Int>()
        photos.forEach { photo -> conceptsOf(photo.id)?.let { counts[it.subject] = (counts[it.subject] ?: 0) + 1 } }
        return counts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { clip.concepts.displayName(it.key) }
            .distinct()
    }

    private fun conceptsOf(photoId: Long): ClipConcepts.Match? {
        conceptCache[photoId]?.let { return it }
        val embedding = embeddings[photoId]?.takeIf { it.isNotEmpty() } ?: return null
        return clip.concepts.describe(embedding).also { conceptCache[photoId] = it }
    }

    /** Vecteur d'une photo : décodée à l'endroit (rotation EXIF appliquée), puis résumée. */
    private suspend fun analyse(uri: Uri): FloatArray {
        val bitmap = ImageIO.decode(context, uri, ANALYSIS_SIZE)
        // Calcul lourd : hors du fil de l'interface.
        return withContext(Dispatchers.Default) { clip.embed(bitmap) }
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            // Traces de l'ancienne analyse (ML Kit) : remplacées par les vecteurs, place libérée.
            File(context.filesDir, OLD_LABELS_FILE).delete()
            File(context.filesDir, OLD_MLKIT_DIR).deleteRecursively()
            // Ancien format (nombre d'entrées en tête, fichier réécrit à chaque lot) : relu une fois.
            val old = File(context.filesDir, OLD_EMBEDDINGS_FILE)
            if (old.exists()) {
                runCatching {
                    DataInputStream(old.inputStream().buffered()).use { input ->
                        repeat(input.readInt()) {
                            val id = input.readLong()
                            embeddings[id] = FloatArray(input.readInt()) { input.readFloat() }
                        }
                    }
                }
            }
            // Suite d'enregistrements ; une fin tronquée (coupure pendant un ajout) est ignorée,
            // et le fichier réécrit proprement pour que les ajouts suivants restent lisibles.
            var intact = true
            val file = File(context.filesDir, EMBEDDINGS_FILE)
            if (file.exists()) {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    try {
                        while (true) {
                            val id = try { input.readLong() } catch (e: EOFException) { break }
                            val size = input.readInt()
                            if (size !in 0..MAX_DIM) { intact = false; break }
                            embeddings[id] = FloatArray(size) { input.readFloat() }
                        }
                    } catch (e: IOException) {
                        intact = false
                    }
                }
            }
            if (old.exists() || !intact) {
                rewrite()
                old.delete()
            } else {
                saved += embeddings.keys
            }
        }
    }

    /**
     * Ajoute au fichier les vecteurs pas encore écrits : 2 Ko par photo, au lieu de réécrire tout le
     * fichier (20 Mo pour 10 000 photos) à chaque lot.
     */
    private suspend fun save() {
        val fresh = embeddings.filterKeys { it !in saved }
        version++
        if (fresh.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                DataOutputStream(FileOutputStream(File(context.filesDir, EMBEDDINGS_FILE), true).buffered()).use { out ->
                    fresh.forEach { (id, vector) -> writeRecord(out, id, vector) }
                }
                saved += fresh.keys
            }
        }
    }

    /**
     * Oublie les photos qui ne sont plus dans la galerie (supprimées, masquées) quand elles pèsent
     * plus de 10 % du fichier : celui-ci est alors réécrit sans elles.
     */
    private suspend fun prune(photos: List<MediaPhoto>) {
        if (photos.isEmpty()) return
        val present = photos.mapTo(HashSet(photos.size)) { it.id }
        val gone = embeddings.keys.filter { it !in present }
        if (gone.size < PRUNE_MIN || gone.size * 10 < embeddings.size) return
        gone.forEach { id -> embeddings.remove(id); categoryCache.remove(id); conceptCache.remove(id) }
        rewrite()
    }

    /** Réécrit tout le fichier, à côté puis renommé : une coupure en pleine écriture ne corrompt rien. */
    private suspend fun rewrite() = withContext(Dispatchers.IO + NonCancellable) {
        val snapshot = embeddings.toMap()
        runCatching {
            val tmp = File(context.filesDir, "$EMBEDDINGS_FILE.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                snapshot.forEach { (id, vector) -> writeRecord(out, id, vector) }
            }
            if (tmp.renameTo(File(context.filesDir, EMBEDDINGS_FILE))) {
                saved.clear()
                saved += snapshot.keys
            }
        }
    }

    private fun writeRecord(out: DataOutputStream, id: Long, vector: FloatArray) {
        out.writeLong(id)
        out.writeInt(vector.size)
        vector.forEach { out.writeFloat(it) }
    }

    companion object {
        /** Les photos les plus récentes : quelques minutes d'analyse sur un téléphone récent. */
        const val MAX_PHOTOS = 2000

        /**
         * Pendant la recharge : toute la galerie, jusqu'à 10 000 photos (20 Mo de vecteurs en
         * mémoire et sur le disque).
         */
        const val MAX_BACKGROUND_PHOTOS = 10_000

        @Volatile
        private var instance: SearchIndex? = null

        /** Un seul index pour l'app et la tâche de fond : ils ne s'écrivent jamais dessus. */
        fun get(context: Context): SearchIndex =
            instance ?: synchronized(this) { instance ?: SearchIndex(context.applicationContext).also { instance = it } }

        private const val OLD_LABELS_FILE = "photo-labels-v3.json"
        private const val OLD_MLKIT_DIR = "com.google.mlkit.acceleration"
        private const val OLD_EMBEDDINGS_FILE = "photo-clip-v1.bin"
        private const val EMBEDDINGS_FILE = "photo-clip-v2.bin"
        private const val MAX_DIM = 4096
        private const val PRUNE_MIN = 50

        /** Sauvegarde par lots : le fichier des vecteurs pèse 2 Ko par photo. */
        private const val SAVE_EVERY = 50

        /** Assez grand pour que le bord court dépasse 256 px, la taille d'entrée de MobileCLIP. */
        private const val ANALYSIS_SIZE = 512

        /** Une seule lettre désignerait la moitié du vocabulaire. */
        private const val MIN_QUERY_LENGTH = 2
    }
}
