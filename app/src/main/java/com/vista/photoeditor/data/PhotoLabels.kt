package com.vista.photoeditor.data

import java.text.Normalizer
import java.util.Locale

/**
 * Vocabulaire de la recherche par contenu : le modèle embarqué répond en anglais,
 * ces correspondances permettent de chercher en français.
 */
object PhotoLabels {

    private val french = mapOf(
        "Animal" to listOf("animal"),
        "Aquarium" to listOf("aquarium"),
        "Art" to listOf("art", "oeuvre", "tableau"),
        "Backpacking" to listOf("randonnee", "sac a dos"),
        "Ball" to listOf("ballon", "balle"),
        "Balloon" to listOf("ballon", "montgolfiere"),
        "Beach" to listOf("plage", "mer", "sable"),
        "Bicycle" to listOf("velo", "bicyclette"),
        "Bird" to listOf("oiseau"),
        "Blackboard" to listOf("tableau noir"),
        "Boat" to listOf("bateau", "barque"),
        "Book" to listOf("livre"),
        "Bridge" to listOf("pont"),
        "Building" to listOf("batiment", "immeuble"),
        "Butterfly" to listOf("papillon"),
        "Cat" to listOf("chat"),
        "Candle" to listOf("bougie"),
        "Car" to listOf("voiture", "auto"),
        "Castle" to listOf("chateau"),
        "Cattle" to listOf("vache", "betail"),
        "Christmas" to listOf("noel"),
        "City" to listOf("ville"),
        "Cloud" to listOf("nuage", "ciel"),
        "Coast" to listOf("cote", "littoral"),
        "Coffee" to listOf("cafe"),
        "Concert" to listOf("concert"),
        "Cool" to listOf("frais"),
        "Dance" to listOf("danse"),
        "Desk" to listOf("bureau"),
        "Dessert" to listOf("dessert", "gateau"),
        "Dog" to listOf("chien"),
        "Drink" to listOf("boisson", "verre"),
        "Eating" to listOf("repas", "manger"),
        "Fashion" to listOf("mode", "vetement"),
        "Fireworks" to listOf("feu d'artifice"),
        "Flower" to listOf("fleur"),
        "Food" to listOf("nourriture", "plat", "repas"),
        "Forest" to listOf("foret", "bois"),
        "Fun" to listOf("fete", "amusement"),
        "Furniture" to listOf("meuble"),
        "Glasses" to listOf("lunettes"),
        "Grass" to listOf("herbe", "pelouse"),
        "Hat" to listOf("chapeau", "bonnet"),
        "Hiking" to listOf("randonnee"),
        "Horse" to listOf("cheval"),
        "House" to listOf("maison"),
        "Ice" to listOf("glace"),
        "Insect" to listOf("insecte"),
        "Jungle" to listOf("jungle"),
        "Lake" to listOf("lac"),
        "Laptop" to listOf("ordinateur", "portable"),
        "Leaf" to listOf("feuille"),
        "Lighthouse" to listOf("phare"),
        "Monument" to listOf("monument"),
        "Moon" to listOf("lune"),
        "Motorcycle" to listOf("moto"),
        "Mountain" to listOf("montagne", "sommet"),
        "Museum" to listOf("musee"),
        "Nature" to listOf("nature"),
        "Night" to listOf("nuit"),
        "Party" to listOf("fete", "soiree"),
        "Pasta" to listOf("pates"),
        "People" to listOf("gens", "personnes"),
        "Person" to listOf("personne", "portrait"),
        "Pet" to listOf("animal de compagnie"),
        "Photograph" to listOf("photo"),
        "Picnic" to listOf("pique-nique"),
        "Pizza" to listOf("pizza"),
        "Plant" to listOf("plante"),
        "Pool" to listOf("piscine"),
        "Rain" to listOf("pluie"),
        "Restaurant" to listOf("restaurant"),
        "River" to listOf("riviere", "fleuve"),
        "Road" to listOf("route", "rue"),
        "Rock" to listOf("rocher", "pierre"),
        "Room" to listOf("piece", "salon"),
        "Sand" to listOf("sable"),
        "Screenshot" to listOf("capture d'ecran"),
        "Sea" to listOf("mer", "ocean"),
        "Selfie" to listOf("selfie"),
        "Sky" to listOf("ciel"),
        "Skyscraper" to listOf("gratte-ciel"),
        "Snow" to listOf("neige"),
        "Sport" to listOf("sport"),
        "Stadium" to listOf("stade"),
        "Street" to listOf("rue"),
        "Sunglasses" to listOf("lunettes de soleil"),
        "Sunset" to listOf("coucher de soleil"),
        "Swimming" to listOf("nage", "baignade"),
        "Table" to listOf("table"),
        "Temple" to listOf("temple"),
        "Text" to listOf("texte", "document"),
        "Toy" to listOf("jouet"),
        "Train" to listOf("train"),
        "Tree" to listOf("arbre"),
        "Umbrella" to listOf("parapluie"),
        "Vehicle" to listOf("vehicule"),
        "Wall" to listOf("mur"),
        "Waterfall" to listOf("cascade", "chute d'eau"),
        "Wedding" to listOf("mariage"),
        "Wildlife" to listOf("faune", "animal sauvage"),
        "Window" to listOf("fenetre"),
        "Winter" to listOf("hiver"),
    )

    /** Libellé affiché à l'utilisateur, en français quand la traduction existe. */
    fun display(label: String): String =
        french[label]?.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: label

    /** Termes acceptés pour retrouver ce libellé, sans accents ni majuscules. */
    fun searchTerms(label: String): List<String> =
        (listOf(label) + french[label].orEmpty()).map { normalize(it) }

    fun normalize(text: String): String = Normalizer
        .normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()
}
