package com.vista.photoeditor.widget

import com.vista.photoeditor.data.MediaRepository
import com.vista.photoeditor.data.SmartAlbums
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Choix de la photo du widget « Il y a un an », par ordre de préférence :
 * 1. un **souvenir anniversaire** (« Il y a un an », « Il y a 3 ans »), le même que celui
 *    d'Explorer : toucher le widget ouvre ce souvenir ;
 * 2. à défaut, une photo prise **ce jour-là** une année passée (à quelques jours près, comme les
 *    souvenirs), même seule : pas besoin d'un moment de 4 photos pour se rappeler une date ;
 * 3. à défaut, un **souvenir** plus ancien d'une semaine, pour que le widget ne reste pas vide.
 *
 * La photo change toutes les [ROTATION_MILLIS] parmi celles du souvenir (et d'un souvenir à
 * l'autre s'il y en a plusieurs) ; le souvenir de secours change chaque jour.
 *
 * Générique sur le type de photo pour se tester sans Android ; voir [MemoryWidget] pour l'usage.
 */
internal object MemoryWidgetPicker {

    /** Photo affichée, l'album qu'ouvre le widget, et ce qu'il en dit. */
    class Choice<P>(
        /** Souvenir (« mem:… ») ou toute la galerie ([MediaRepository.ALL_KEY]). */
        val albumKey: String,
        val photo: P,
        /** Années écoulées, ou null pour un souvenir de secours. */
        val years: Int?,
        /** Photos du souvenir (ou prises ce jour-là, la même année). */
        val count: Int,
    )

    /** Une nouvelle photo toutes les 3 heures : le widget se met à jour à ce rythme. */
    val ROTATION_MILLIS = TimeUnit.HOURS.toMillis(3)

    /** Âge minimum d'un souvenir de secours : celui d'hier n'a rien d'un souvenir. */
    private val MIN_MEMORY_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)

    /**
     * [memories] : souvenirs tels que [SmartAlbums.memories] les donne (clé, photos les plus récentes
     * d'abord). [photos] : toute la galerie. [eligible] : photo affichable dans le widget (pas une
     * vidéo ni une capture d'écran).
     */
    fun <P> choose(
        memories: List<Pair<String, List<P>>>,
        photos: List<P>,
        dateOf: (P) -> Long,
        eligible: (P) -> Boolean,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Choice<P>? {
        val slot = Math.floorDiv(now, ROTATION_MILLIS)

        // 1. Souvenirs anniversaires.
        val anniversaries = memories.mapNotNull { (key, list) ->
            val newest = list.firstOrNull() ?: return@mapNotNull null
            val years = SmartAlbums.yearsAgo(dateOf(newest), now, zone) ?: return@mapNotNull null
            val stills = list.filter(eligible)
            if (stills.isEmpty()) null else Anniversary(key, stills, years, list.size)
        }
        if (anniversaries.isNotEmpty()) {
            val memory = anniversaries[pickIndex(slot, anniversaries.size)]
            val photo = memory.stills[pickIndex(slot / anniversaries.size, memory.stills.size)]
            return Choice(memory.key, photo, memory.years, memory.count)
        }

        // 2. Photos prises ce jour-là, une année passée.
        val onThisDay = photos.mapNotNull { photo ->
            if (!eligible(photo)) null else SmartAlbums.yearsAgo(dateOf(photo), now, zone)?.let { photo to it }
        }
        if (onThisDay.isNotEmpty()) {
            val (photo, years) = onThisDay[pickIndex(slot, onThisDay.size)]
            return Choice(MediaRepository.ALL_KEY, photo, years, onThisDay.count { it.second == years })
        }

        // 3. Un souvenir plus ancien, différent chaque jour.
        val older = memories.filter { (_, list) ->
            val newest = list.firstOrNull()
            newest != null && now - dateOf(newest) >= MIN_MEMORY_AGE_MILLIS && list.any(eligible)
        }
        if (older.isEmpty()) return null
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        val (key, list) = older[pickIndex(day, older.size)]
        val stills = list.filter(eligible)
        return Choice(key, stills[pickIndex(slot, stills.size)], null, list.size)
    }

    private class Anniversary<P>(val key: String, val stills: List<P>, val years: Int, val count: Int)

    private fun pickIndex(counter: Long, size: Int): Int = Math.floorMod(counter, size.toLong()).toInt()
}
