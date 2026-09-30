package com.vista.photoeditor.data

/**
 * Photos qui ressemblent à une autre, d'après leurs vecteurs MobileCLIP (même lieu, même sujet).
 * Seuil réglé sur les 730 photos de test Commons : à 0,55, 5 voisines par photo en moyenne, dont
 * 84 % du même sujet ; à 0,45, 9 voisines mais 76 % seulement.
 */
object SimilarPhotos {

    const val MIN_SIMILARITY = 0.55f
    const val LIMIT = 12

    /** Identifiants des [candidates] les plus proches de [target], du plus au moins ressemblant. */
    fun rank(targetId: Long, target: FloatArray, candidates: List<Pair<Long, FloatArray>>): List<Long> =
        candidates.asSequence()
            .filter { (id, v) -> id != targetId && v.size == target.size }
            .map { (id, v) -> id to dot(target, v) }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
            .take(LIMIT)
            .map { it.first }
            .toList()

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
