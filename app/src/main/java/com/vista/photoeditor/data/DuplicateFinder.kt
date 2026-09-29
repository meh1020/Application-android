package com.vista.photoeditor.data

import kotlin.math.max

/**
 * Doublons et rafales : des photos presque identiques ont des vecteurs MobileCLIP presque
 * identiques. Seuils réglés avec `tools/clip/duplicates.py` sur des séries fabriquées (recadrage,
 * décalage, rotation, exposition) et des photos différentes au même sujet :
 * - **doublons** (copie, photo réenregistrée), à n'importe quelle date : similarité ≥ 0,98 ;
 *   au-dessus, toutes les paires vues étaient la même photo ;
 * - **rafales** (prises répétées) : ≥ 0,92 et moins d'une minute entre deux prises. Entre 0,90 et
 *   0,94, la moitié des paires sont des photos différentes au sujet voisin (deux gratte-ciel, deux
 *   feux d'artifice) : c'est la proximité dans le temps qui les écarte. À moins de 10 secondes, 0,85
 *   suffit : une prise très floue (0,87 avec les autres dans une rafale d'essai), justement celle
 *   qu'on veut jeter, rejoint ainsi sa série.
 * Documents et captures d'écran n'entrent que comme doublons : deux pages manuscrites différentes
 * atteignaient 0,92, deux pages d'un tableau 0,96.
 */
object DuplicateFinder {

    const val SAME_PHOTO = 0.98f
    const val BURST = 0.92f
    const val BURST_GAP_MILLIS = 60_000L

    /** Prises à quelques secondes d'intervalle : même scène presque à coup sûr. */
    const val CLOSE_BURST = 0.85f
    const val CLOSE_GAP_MILLIS = 10_000L

    /** Une rafale ne dérive pas : chaque prise reste proche de la première. */
    const val BURST_ANCHOR = 0.85f

    class Candidate(
        val id: Long,
        val dateMillis: Long,
        /** Vecteur MobileCLIP de norme 1, vide si la photo n'est pas analysée. */
        val vector: FloatArray,
        /** Faux pour un document ou une capture d'écran : doublons exacts seulement. */
        val canBurst: Boolean,
    )

    /** Séries d'au moins deux photos, identifiants dans l'ordre des prises ; les plus récentes d'abord. */
    fun groups(candidates: List<Candidate>): List<List<Long>> {
        val items = candidates.filter { it.vector.isNotEmpty() }.sortedBy { it.dateMillis }
        val parent = IntArray(items.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var k = i
            while (parent[k] != r) { val next = parent[k]; parent[k] = r; k = next }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[max(ra, rb)] = minOf(ra, rb)
        }

        // Rafales : de proche en proche dans le temps, sans s'éloigner de la première prise.
        var start = 0
        for (i in 1 until items.size) {
            val previous = items[i - 1]
            val current = items[i]
            val gap = current.dateMillis - previous.dateMillis
            val threshold = if (gap <= CLOSE_GAP_MILLIS) CLOSE_BURST else BURST
            val joins = current.canBurst && previous.canBurst && items[start].canBurst &&
                gap <= BURST_GAP_MILLIS &&
                dot(previous.vector, current.vector) >= threshold &&
                dot(items[start].vector, current.vector) >= BURST_ANCHOR
            if (joins) union(i - 1, i) else start = i
        }

        // Doublons : à n'importe quelle date.
        for (i in items.indices) {
            for (j in i + 1 until items.size) {
                if (find(i) != find(j) && dot(items[i].vector, items[j].vector) >= SAME_PHOTO) union(i, j)
            }
        }

        return items.indices.groupBy(::find).values
            .filter { it.size >= 2 }
            .sortedByDescending { members -> items[members.last()].dateMillis }
            .map { members -> members.map { items[it].id } }
    }

    /**
     * Photo à garder dans une série : la plus nette. À 5 % près, la netteté ne départage plus
     * vraiment : c'est alors la plus grande définition qui l'emporte (une copie réduite perd).
     */
    fun <T> keeperOf(series: List<T>, sharpness: (T) -> Float, pixels: (T) -> Long): T {
        val best = series.maxOf(sharpness)
        return series.filter { sharpness(it) >= best * 0.95f }.maxBy(pixels)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}

/**
 * Netteté d'une photo : énergie des détails fins (laplacien) rapportée à celle de toute l'image,
 * pour que deux prises d'une même rafale se comparent même si l'une est plus claire. Plus c'est
 * grand, plus c'est net ; n'a de sens qu'entre photos d'une même scène.
 */
object Sharpness {

    /** [pixels] ARGB ligne après ligne, [width] pixels par ligne. */
    fun score(pixels: IntArray, width: Int): Float {
        if (width < 3 || pixels.size < width * 3) return 0f
        val height = pixels.size / width
        val luma = FloatArray(pixels.size) { i ->
            val p = pixels[i]
            0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        var mean = 0.0
        for (v in luma) mean += v
        mean /= luma.size
        var variance = 0.0
        for (v in luma) variance += (v - mean) * (v - mean)
        variance /= luma.size
        if (variance < 1.0) return 0f

        var energy = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
                energy += lap * lap
                count++
            }
        }
        return (energy / count / variance).toFloat()
    }
}
