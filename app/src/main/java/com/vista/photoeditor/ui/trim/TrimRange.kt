package com.vista.photoeditor.ui.trim

/**
 * Règles de la sélection du découpage, à part pour être vérifiées sans téléphone : les deux
 * poignées ne se croisent pas, gardent au moins [MIN_MILLIS] entre elles (ou toute la vidéo si
 * elle est plus courte) et ne sortent pas de la vidéo.
 */
internal object TrimRange {

    /** Durée la plus courte qu'on puisse garder. */
    const val MIN_MILLIS = 1_000L

    fun minGap(durationMillis: Long): Long = minOf(MIN_MILLIS, durationMillis.coerceAtLeast(0))

    /** Nouveau début, une fois la poignée de gauche amenée à [wanted]. */
    fun moveStart(wanted: Long, end: Long, durationMillis: Long): Long =
        wanted.coerceIn(0, (end - minGap(durationMillis)).coerceAtLeast(0))

    /** Nouvelle fin, une fois la poignée de droite amenée à [wanted]. */
    fun moveEnd(wanted: Long, start: Long, durationMillis: Long): Long =
        wanted.coerceIn((start + minGap(durationMillis)).coerceAtMost(durationMillis), durationMillis.coerceAtLeast(0))

    /** Vrai si la sélection garde toute la vidéo : rien à découper. */
    fun isWhole(start: Long, end: Long, durationMillis: Long): Boolean =
        start <= 0 && end >= durationMillis
}
