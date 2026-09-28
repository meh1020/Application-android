package com.vista.photoeditor.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.DataInputStream
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compréhension d'image embarquée (MobileCLIP-S0, Apple) : chaque photo devient un vecteur de
 * 512 nombres qui résume toute la scène, comparé à des descriptions de catégories ([ClipClasses]) :
 * contrairement à des étiquettes isolées, un coucher de soleil sur un bateau reste un coucher de
 * soleil, pas un « oiseau ».
 *
 * Le modèle est livré avec ses poids compressés en int8 (calculs en virgule flottante) : 11 Mo,
 * pour une précision mesurée équivalente à la version complète. Tout tourne sur le téléphone.
 */
class ClipModel(private val context: Context) {

    private val env by lazy { OrtEnvironment.getEnvironment() }

    private val session: OrtSession by lazy {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            // Jusqu'à 4 cœurs : l'analyse va 22 % plus vite qu'avec 2, et l'interface garde de la marge.
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Les poids sont livrés compressés en int8 : sans cette option, ils seraient décompressés
            // à chaque photo. Décompressés une fois au chargement, l'analyse est 1,6 fois plus rapide,
            // pour un résultat identique.
            addConfigEntry("session.disable_quant_qdq", "1")
        }
        env.createSession(bytes, options)
    }

    /** Descriptions des catégories et leurs seuils, livrés avec l'app. */
    val classes: ClipClasses by lazy {
        DataInputStream(context.assets.open(CLASSES_ASSET).buffered()).use { ClipClasses.read(it) }
    }

    /** Vocabulaire de la recherche par contenu (« chien », « robe »…), livré avec l'app. */
    val concepts: ClipConcepts by lazy {
        DataInputStream(context.assets.open(CONCEPTS_ASSET).buffered()).use { ClipConcepts.read(it) }
    }

    /** Vecteur de la photo, de norme 1. [bitmap] doit être à l'endroit (rotation EXIF appliquée). */
    fun embed(bitmap: Bitmap): FloatArray {
        val input = OnnxTensor.createTensor(env, pixels(bitmap), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
        input.use {
            session.run(mapOf(INPUT to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = (result[0].value as Array<FloatArray>)[0]
                return normalized(out)
            }
        }
    }

    /**
     * Préparation identique à celle du modèle d'origine : le bord le plus court ramené à 256 px
     * (interpolation bilinéaire), recadrage central 256 × 256, valeurs RVB entre 0 et 1.
     */
    private fun pixels(source: Bitmap): FloatBuffer {
        val scale = SIZE.toFloat() / minOf(source.width, source.height)
        val w = max(SIZE, (source.width * scale).roundToInt())
        val h = max(SIZE, (source.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val left = (w - SIZE) / 2
        val top = (h - SIZE) / 2
        val argb = IntArray(SIZE * SIZE)
        scaled.getPixels(argb, 0, SIZE, left, top, SIZE, SIZE)
        if (scaled !== source) scaled.recycle()

        val plane = SIZE * SIZE
        val buffer = FloatBuffer.allocate(3 * plane)
        val data = buffer.array()
        for (i in 0 until plane) {
            val c = argb[i]
            data[i] = ((c shr 16) and 0xFF) / 255f
            data[plane + i] = ((c shr 8) and 0xFF) / 255f
            data[2 * plane + i] = (c and 0xFF) / 255f
        }
        return buffer
    }

    companion object {
        const val MODEL_ASSET = "clip/vision.onnx"
        const val CLASSES_ASSET = "clip/classes.bin"
        const val CONCEPTS_ASSET = "clip/concepts.bin"
        private const val INPUT = "pixel_values"
        private const val SIZE = 256

        fun normalized(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            val norm = sqrt(sum).coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / norm }
        }
    }
}

/**
 * Descriptions des catégories, encodées sur ordinateur (voir `tools/clip/`), avec deux seuils par
 * catégorie réglés sur des photos étiquetées : l'un pour être la catégorie principale d'une photo,
 * l'autre pour s'y ajouter. Des classes « de fond » (objet, texture) écartent les photos quelconques.
 */
class ClipClasses(
    /** Clé de chaque classe ; celles qui commencent par « _ » sont des classes de fond. */
    val keys: List<String>,
    /** Nom affiché de chaque catégorie (« Plages & mer »). */
    val titles: Map<String, String>,
    /** Nom donné à un souvenir où la catégorie domine (« Plage »), ou rien si elle ne s'y prête pas. */
    val memoryNames: Map<String, String>,
    /** Vecteurs de norme 1, [keys].size × dim. */
    private val vectors: Array<FloatArray>,
    private val logitScale: Float,
    private val primary: FloatArray,
    private val secondary: FloatArray,
) {
    /**
     * Catégories de la photo : la plus probable si elle dépasse son seuil principal, puis chaque
     * autre au-dessus de son seuil secondaire. Rien si la photo ressemble d'abord à une classe de fond.
     */
    fun classify(embedding: FloatArray): List<String> {
        val probs = probabilities(embedding)
        val order = probs.indices.sortedByDescending { probs[it] }
        val top = order.first()
        if (keys[top].startsWith("_") || probs[top] < primary[top]) return emptyList()
        val found = mutableListOf(keys[top])
        for (i in order.drop(1)) {
            if (!keys[i].startsWith("_") && probs[i] >= secondary[i]) found += keys[i]
        }
        return found
    }

    /** Probabilité de chaque classe (softmax des similarités). */
    private fun probabilities(embedding: FloatArray): FloatArray {
        val logits = FloatArray(vectors.size) { c ->
            var dot = 0f
            val v = vectors[c]
            for (d in v.indices) dot += v[d] * embedding[d]
            logitScale * dot
        }
        val top = logits.max()
        var sum = 0f
        val out = FloatArray(logits.size) { exp(logits[it] - top).also { e -> sum += e } }
        for (i in out.indices) out[i] /= sum
        return out
    }

    companion object {
        private const val FORMAT_VERSION = 3

        /**
         * Format (gros-boutiste) : version, échelle, n, dim, puis n × (clé, titre, nom de souvenir,
         * seuil principal, seuil secondaire, vecteur[dim]).
         */
        fun read(input: DataInputStream): ClipClasses {
            check(input.readInt() == FORMAT_VERSION) { "classes.bin : format inattendu" }
            val scale = input.readFloat()
            val count = input.readInt()
            val dim = input.readInt()
            val keys = ArrayList<String>(count)
            val titles = mutableMapOf<String, String>()
            val memoryNames = mutableMapOf<String, String>()
            val primary = FloatArray(count)
            val secondary = FloatArray(count)
            val vectors = Array(count) { index ->
                val key = input.readUTF()
                keys += key
                input.readUTF().takeIf { it.isNotEmpty() }?.let { titles[key] = it }
                input.readUTF().takeIf { it.isNotEmpty() }?.let { memoryNames[key] = it }
                primary[index] = input.readFloat()
                secondary[index] = input.readFloat()
                FloatArray(dim) { input.readFloat() }
            }
            return ClipClasses(keys, titles, memoryNames, vectors, scale, primary, secondary)
        }
    }
}

/**
 * Vocabulaire de la recherche : environ 380 concepts décrits en anglais pour MobileCLIP (vecteurs
 * encodés sur ordinateur, voir `tools/clip/concepts.py`) et nommés en français pour la recherche.
 */
class ClipConcepts(
    private val displayNames: List<String>,
    /** Termes français de chaque concept, sans accents ni majuscules. */
    private val terms: List<List<String>>,
    /** Concepts qui en chapeautent d'autres (« animal de compagnie », « musicien »). */
    private val generic: BooleanArray,
    private val vectors: Array<FloatArray>,
) {
    /**
     * Concepts retenus pour une photo, du plus ressemblant au moins ressemblant, et leur similarité :
     * les concepts génériques en tête du classement, puis le premier concept précis.
     */
    class Match(private val indices: IntArray, private val scores: FloatArray) {
        /** Le concept précis, qui nomme le sujet de la photo (« chien » plutôt qu'« animal de compagnie »). */
        val subject: Int get() = indices.last()

        /** Similarité du premier des [concepts] retenu pour la photo, ou null si aucun ne l'est. */
        fun scoreOf(concepts: Set<Int>): Float? {
            for (i in indices.indices) if (indices[i] in concepts) return scores[i]
            return null
        }
    }

    /**
     * Un chien est souvent d'abord un « animal de compagnie », une guitare un « musicien » : retenir
     * le seul premier concept perdait ces photos. Les génériques ne font donc pas écran au premier
     * concept précis qui les suit. Mesuré : 23 photos retrouvées de plus sur 469, pour 5 intrus.
     */
    fun describe(embedding: FloatArray): Match {
        val scores = FloatArray(vectors.size) { c ->
            val v = vectors[c]
            var dot = 0f
            for (d in v.indices) dot += v[d] * embedding[d]
            dot
        }
        val kept = ArrayList<Int>(2)
        for (c in scores.indices.sortedByDescending { scores[it] }) {
            kept += c
            if (!generic[c]) break
        }
        return Match(kept.toIntArray(), FloatArray(kept.size) { scores[kept[it]] })
    }

    /**
     * Concepts que désigne la requête : ceux dont un terme commence par elle en mots entiers
     * (« robe » : la robe et la robe de soirée) ; à défaut, par le début d'un mot, pour une saisie en
     * cours (« chie » → chien). Sans cette priorité, « lit » ramenait le « littoral » ; et jamais le
     * milieu d'un mot : « clé » ne doit pas ramener les « bicyclettes ».
     */
    fun matching(normalizedQuery: String): Set<Int> {
        // « livres » doit trouver ce qui est nommé « livre ».
        val forms = setOf(normalizedQuery, PhotoLabels.singular(normalizedQuery))
        val whole = terms.indices.filterTo(mutableSetOf()) { c ->
            terms[c].any { term -> forms.any { term == it || term.startsWith("$it ") } }
        }
        if (whole.isNotEmpty()) return whole
        return terms.indices.filterTo(mutableSetOf()) { c ->
            terms[c].any { term -> forms.any { term.startsWith(it) } }
        }
    }

    fun displayName(index: Int): String = displayNames[index]

    companion object {
        private const val FORMAT_VERSION = 3

        /**
         * Format (gros-boutiste) : version, n, dim, puis n × (clé, nom affiché, termes séparés
         * par « | », générique ou non, vecteur[dim]).
         */
        fun read(input: DataInputStream): ClipConcepts {
            check(input.readInt() == FORMAT_VERSION) { "concepts.bin : format inattendu" }
            val count = input.readInt()
            val dim = input.readInt()
            val names = ArrayList<String>(count)
            val terms = ArrayList<List<String>>(count)
            val generic = BooleanArray(count)
            val vectors = Array(count) { index ->
                input.readUTF() // clé anglaise : utile aux outils seulement
                names += input.readUTF()
                terms += input.readUTF().split('|').filter { it.isNotEmpty() }
                generic[index] = input.readBoolean()
                FloatArray(dim) { input.readFloat() }
            }
            return ClipConcepts(names, terms, generic, vectors)
        }
    }
}
