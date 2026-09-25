package com.vista.photoeditor.data

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Albums construits tout seuls à partir de ce que l'analyse embarquée a compris des photos.
 *
 * Deux familles, comme dans les galeries qui rangent à votre place :
 * - les **catégories** ([categories]) regroupent les photos par sujet (personnes, plages,
 *   vêtements…), d'après la catégorie que MobileCLIP attribue à chaque photo ;
 * - les **souvenirs** ([memories]) regroupent les photos prises au même moment, et prennent le nom
 *   du sujet qui y domine (« Plage », « Neige »…).
 *
 * Les captures d'écran forment leur propre catégorie et n'entrent dans aucune autre : une capture
 * d'une conversation n'est pas une photo de personne. Tout est calculé sur le téléphone.
 */
object SmartAlbums {

    const val CATEGORY_PREFIX = "cat:"
    const val MEMORY_PREFIX = "mem:"

    private const val SCREENSHOTS = "screenshots"
    private const val SCREENSHOTS_TITLE = "Captures d'écran"

    /** Au moins ce nombre de photos pour qu'une catégorie mérite d'être affichée. */
    private const val MIN_CATEGORY_PHOTOS = 2

    /** Au moins ce nombre de photos pour qu'un moment devienne un souvenir. */
    private const val MIN_MEMORY_PHOTOS = 4

    /** Deux photos séparées de plus de ce délai appartiennent à deux souvenirs différents. */
    private val MEMORY_GAP_MILLIS = TimeUnit.HOURS.toMillis(20)

    private const val MAX_MEMORIES = 8

    /** Écart toléré autour de la date anniversaire, en jours. */
    private const val ANNIVERSARY_WINDOW_DAYS = 3

    /** Rayons non vides, du plus fourni au moins fourni. */
    fun categories(
        photos: List<MediaPhoto>,
        categoriesOf: (Long) -> List<String>,
        titles: Map<String, String>,
        /** Photo retirée à la main de cette catégorie : elle n'y revient pas. */
        isRemoved: (category: String, photoId: Long) -> Boolean = { _, _ -> false },
    ): List<Album> {
        val found = linkedMapOf<String, MutableList<MediaPhoto>>()
        for (photo in photos) {
            val keys = if (isScreenshot(photo)) listOf(SCREENSHOTS) else categoriesOf(photo.id)
            keys.filterNot { isRemoved(it, photo.id) }.forEach { found.getOrPut(it) { mutableListOf() } += photo }
        }
        return found.mapNotNull { (key, list) ->
            val title = if (key == SCREENSHOTS) SCREENSHOTS_TITLE else titles[key]
            if (title == null || list.size < MIN_CATEGORY_PHOTOS) null
            else Album(CATEGORY_PREFIX + key, title, list)
        }.sortedByDescending { it.count }
    }

    /** Capture d'écran : rangée dans l'album système des captures, ou nommée comme telle. */
    private fun isScreenshot(photo: MediaPhoto): Boolean =
        photo.bucketName == SCREENSHOTS_TITLE ||
            photo.bucketName.contains("screenshot", ignoreCase = true) ||
            photo.name.startsWith("screenshot", ignoreCase = true)

    /**
     * Moments passés ensemble : les photos prises à moins de 20 heures d'intervalle forment un
     * souvenir, nommé d'après le sujet qui y revient le plus. Les plus récents d'abord, et ceux
     * qui tombent sur une date anniversaire sont mis en avant.
     */
    fun memories(
        photos: List<MediaPhoto>,
        categoriesOf: (Long) -> List<String>,
        memoryNames: Map<String, String>,
        now: Long,
    ): List<Album> {
        val sorted = photos.filterNot(::isScreenshot).sortedByDescending { it.dateMillis }
        val groups = mutableListOf<MutableList<MediaPhoto>>()
        for (photo in sorted) {
            val current = groups.lastOrNull()
            if (current != null && current.last().dateMillis - photo.dateMillis <= MEMORY_GAP_MILLIS) {
                current += photo
            } else {
                groups += mutableListOf(photo)
            }
        }
        return groups
            .filter { it.size >= MIN_MEMORY_PHOTOS }
            .map { group ->
                val anniversary = yearsAgo(group.first().dateMillis, now)
                val subject = dominantSubject(group, categoriesOf, memoryNames)
                Album(
                    key = MEMORY_PREFIX + group.first().id,
                    name = when {
                        anniversary == 1 -> "Il y a un an"
                        anniversary != null -> "Il y a $anniversary ans"
                        subject != null -> "$subject · ${dateRange(group)}"
                        else -> dateRange(group)
                    },
                    photos = group,
                )
            }
            .sortedWith(
                compareByDescending<Album> { it.name.startsWith("Il y a") }
                    .thenByDescending { it.photos.first().dateMillis }
            )
            .take(MAX_MEMORIES)
    }

    /**
     * Sujet qui revient le plus dans le groupe, s'il concerne au moins un tiers des photos.
     * Seules les catégories qui font un bon titre de souvenir comptent : « Plage » oui,
     * « Appareils & écrans » non.
     */
    private fun dominantSubject(
        group: List<MediaPhoto>,
        categoriesOf: (Long) -> List<String>,
        memoryNames: Map<String, String>,
    ): String? {
        val counts = mutableMapOf<String, Int>()
        group.forEach { photo ->
            categoriesOf(photo.id).filter { it in memoryNames }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return null
        if (best.value * 3 < group.size) return null
        return memoryNames[best.key]
    }

    /** Nombre d'années entières si la date tombe sur l'anniversaire du jour, sinon null. */
    private fun yearsAgo(dateMillis: Long, now: Long): Int? {
        val then = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val years = today.get(Calendar.YEAR) - then.get(Calendar.YEAR)
        if (years < 1) return null
        // Même jour de l'année, à quelques jours près, en tenant compte du passage d'année.
        val dayGap = kotlin.math.abs(today.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR))
        val gap = minOf(dayGap, 365 - dayGap)
        return if (gap <= ANNIVERSARY_WINDOW_DAYS) years else null
    }

    private fun dateRange(group: List<MediaPhoto>): String {
        val newest = group.first().dateMillis
        val oldest = group.last().dateMillis
        val start = day(oldest)
        val end = day(newest)
        return if (start == end) end else "$start – $end"
    }

    private fun day(millis: Long): String = Calendar.getInstance().apply { timeInMillis = millis }.let {
        val month = it.getDisplayName(Calendar.MONTH, Calendar.SHORT_FORMAT, Locale.FRENCH) ?: ""
        "${it.get(Calendar.DAY_OF_MONTH)} $month"
    }
}
