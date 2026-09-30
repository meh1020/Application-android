package com.vista.photoeditor.data

import java.time.Instant
import java.time.ZoneId

/**
 * Requête de recherche découpée en contenu et date : « plage 2023 », « chien juillet »,
 * « neige janvier 2024 ». Les mots de date (année, mois) filtrent les photos ; le reste se cherche
 * comme avant (contenu, catégorie, nom, album).
 */
data class SearchQuery(
    /** Reste de la requête, sans les mots de date ; vide si la requête n'était qu'une date. */
    val text: String,
    val year: Int? = null,
    /** Mois, de 1 à 12. */
    val month: Int? = null,
) {
    val hasDate get() = year != null || month != null

    fun matchesDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!hasDate) return true
        val date = Instant.ofEpochMilli(millis).atZone(zone)
        return (year == null || date.year == year) && (month == null || date.monthValue == month)
    }

    companion object {
        /** Mois français sans accents, et leurs abréviations courantes. */
        private val MONTHS = mapOf(
            "janvier" to 1, "janv" to 1, "fevrier" to 2, "fevr" to 2, "fev" to 2, "mars" to 3,
            "avril" to 4, "avr" to 4, "mai" to 5, "juin" to 6, "juillet" to 7, "juil" to 7,
            "aout" to 8, "septembre" to 9, "sept" to 9, "octobre" to 10, "oct" to 10,
            "novembre" to 11, "nov" to 11, "decembre" to 12, "dec" to 12,
        )
        private val YEAR = Regex("^(19|20)\\d\\d$")

        /** [normalizedQuery] : requête passée par [PhotoLabels.normalize] (minuscules, sans accents). */
        fun parse(normalizedQuery: String): SearchQuery {
            var year: Int? = null
            var month: Int? = null
            val rest = mutableListOf<String>()
            for (word in normalizedQuery.split(' ').filter { it.isNotEmpty() }) {
                val bare = word.trimEnd('.')
                when {
                    year == null && YEAR.matches(bare) -> year = bare.toInt()
                    month == null && bare in MONTHS -> month = MONTHS.getValue(bare)
                    else -> rest += word
                }
            }
            // Un mot seul reste une recherche de texte : « mai » peut être un nom d'album ou de fichier.
            if (rest.isEmpty() && year == null) return SearchQuery(normalizedQuery)
            return SearchQuery(rest.joinToString(" "), year, month)
        }
    }
}
