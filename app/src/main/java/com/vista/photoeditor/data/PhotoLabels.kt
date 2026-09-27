package com.vista.photoeditor.data

import java.text.Normalizer
import java.util.Locale

/**
 * Mise en forme des textes comparés par la recherche. Le vocabulaire des contenus (« chien »,
 * « robe »…) est livré avec l'app dans `assets/clip/concepts.bin` ; sa source est
 * `tools/clip/vocabulary.json`.
 */
object PhotoLabels {

    /** Minuscules, sans accents ni espaces autour : « Forêt » et « foret » se retrouvent. */
    fun normalize(text: String): String = Normalizer
        .normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()

    /**
     * Texte au singulier, mot par mot (« livres » → « livre », « bateaux » → « bateau ») : une
     * recherche au pluriel trouve ce qui est nommé au singulier. [normalized] vient de [normalize].
     */
    fun singular(normalized: String): String = normalized.split(' ').joinToString(" ") { w ->
        if (w.length > 3 && (w.endsWith('s') || w.endsWith('x'))) w.dropLast(1) else w
    }

    /**
     * La recherche désigne-t-elle le sujet principal d'une catégorie ? « livre » désigne « Livres
     * & lecture », « coucher de soleil » « Couchers de soleil » ; mais « dessin » ne désigne pas
     * « Art & dessins » : les tableaux et graffitis de cette catégorie ne sont pas des dessins.
     */
    fun isMainSubject(title: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.length < 3) return false
        val subject = singular(normalize(title.substringBefore('&')))
        return subject.startsWith(singular(normalizedQuery))
    }
}
