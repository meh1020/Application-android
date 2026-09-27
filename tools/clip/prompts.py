# -*- coding: utf-8 -*-
"""Descriptions des catégories (en anglais : la langue d'entraînement du modèle)."""

CATEGORIES = {
    # Les formulations « de dos / silhouette / dans une scène » rattrapent les personnes sans
    # visage visible (randonneur, canoë, coucher de soleil), que les portraits seuls ratent.
    "people": (u"Personnes", [
        "a photo of a person", "a portrait photo of a man", "a portrait photo of a woman",
        "a selfie", "a group of people", "a photo of a child", "people smiling at the camera",
        "a person seen from behind", "a silhouette of a person", "a person watching the sunset",
        "a person in a boat", "a person standing by the sea", "a person walking outdoors",
    ]),
    "animals": (u"Animaux", [
        "a photo of a dog", "a photo of a cat", "a photo of a bird", "a photo of a horse",
        "a photo of a pet", "a photo of a wild animal",
    ]),
    "beach": (u"Plages & mer", [
        "a photo of a beach", "sand and sea on a beach", "a tropical beach with the ocean",
        "a view of the sea from the shore", "the ocean and the coast",
    ]),
    "sunset": (u"Couchers de soleil", [
        "a photo of a sunset", "a sunrise sky", "a golden sunset over the horizon",
    ]),
    "landscape": (u"Paysages", [
        "a landscape photo of mountains", "a photo of a forest", "a scenic lake landscape",
        "a nature landscape",
    ]),
    "flowers": (u"Fleurs & plantes", [
        "a photo of flowers", "a close-up of a flower", "a bouquet of flowers",
    ]),
    "city": (u"Villes & architecture", [
        "a city skyline", "a photo of a city street", "buildings in a city",
        "the architecture of a building",
    ]),
    "food": (u"Nourriture", [
        "a photo of food", "a plate of food", "a photo of a meal", "a photo of a dessert",
        "a drink in a glass",
    ]),
    "fashion": (u"Vêtements & mode", [
        "a photo of a dress", "a photo of clothes", "a pair of shoes", "a handbag",
        "fashion clothing on a hanger",
    ]),
    "electronics": (u"Appareils & écrans", [
        "a photo of a smartphone", "a mobile phone", "a laptop computer",
        "an electronic device",
    ]),
    "vehicles": (u"Véhicules", [
        "a photo of a car", "a motorcycle", "a bus", "a train", "an airplane",
    ]),
    # Centré sur le bateau lui-même : « un voilier sur l'eau » attirait les simples vues de mer.
    "boats": (u"Bateaux", [
        "a photo of a boat", "a close-up photo of a boat", "a sailboat", "a ship",
        "boats in a harbor", "a canoe",
    ]),
    "documents": (u"Documents", [
        "a photo of a document", "a receipt", "a page of printed text", "a handwritten note",
    ]),
    "sport": (u"Sport", [
        "people playing sports", "a football match", "an athlete running", "a sports game",
    ]),
    "party": (u"Fêtes & célébrations", [
        "a birthday party", "fireworks in the sky", "a wedding celebration", "a concert",
    ]),
    "books": (u"Livres & lecture", [
        "a photo of a book", "a stack of books", "a bookshelf full of books", "an open book",
        "a person reading a book", "books in a library", "a book cover",
    ]),
    "home": (u"Intérieur & maison", [
        "a photo of a living room", "a bedroom interior", "a kitchen", "a home interior",
    ]),
    "snow": (u"Neige & hiver", [
        "a snowy landscape", "snow in winter",
    ]),
    "art": (u"Art & dessins", [
        "a painting", "a drawing", "street art graffiti", "an artwork",
    ]),
}

# Classes de fond : une photo qui leur ressemble plus qu'à tout le reste n'est rangée nulle part.
BACKGROUND = [
    # Pas de « photo d'un mur » : trop proche de l'architecture, elle avalait les photos de ville.
    "a photo of an object", "a close-up photo of a texture", "a close-up photo of a small item",
    "a photo of tools",
]

# Doubles appartenances légitimes : pas une erreur si la photo apparaît aussi dans ces rayons.
COMPATIBLE = {
    "people": {"fashion", "sport", "party", "beach", "city", "snow", "art", "books"},
    "animals": {"landscape", "snow", "home", "beach"},
    "beach": {"sunset", "boats", "landscape", "people"},
    "sunset": {"beach", "boats", "landscape", "city", "snow"},
    "landscape": {"snow", "sunset", "flowers", "beach", "boats"},
    "flowers": {"landscape", "home", "party"},
    "city": {"vehicles", "sunset", "boats", "people", "snow", "art"},
    "food": {"party", "home"},
    "fashion": {"people", "party"},
    "electronics": {"documents"},
    "vehicles": {"city", "boats", "landscape", "sport", "snow"},
    "boats": {"beach", "sunset", "landscape", "city", "vehicles"},
    "documents": {"electronics", "art", "books"},
    "sport": {"people", "snow", "landscape", "city"},
    "party": {"people", "food", "city", "sunset"},
    "home": {"art", "food", "flowers", "books"},
    "snow": {"landscape", "sport", "city"},
    "art": {"city", "people", "documents", "home", "animals", "flowers", "books"},
    "books": {"documents", "home", "people", "art", "city"},
}

# Nom d'un souvenir où la catégorie domine ; les catégories absentes ne nomment pas de souvenir.
MEMORY_NAMES = {
    "beach": u"Plage", "sunset": u"Coucher de soleil", "landscape": u"Nature",
    "flowers": u"Fleurs", "city": u"En ville", "food": u"Repas", "party": u"Fête",
    "snow": u"Neige", "boats": u"En bateau", "sport": u"Sport", "animals": u"Animaux",
    "home": u"À la maison", "books": u"Lecture",
}

# Réglages de décision. Seuils globaux réglés sur l'entraînement Commons ; seuils propres à
# certaines catégories validés sur des moitiés d'Unsplash (style téléphone) jamais vues au réglage.
LOGIT_SCALE = 100.0
PRIMARY = 0.40
SECONDARY = 0.30

# Seuil pour être la catégorie principale d'une photo.
PRIMARY_BY_CLASS = {
    "boats": 0.70,   # une vue de mer ou une jetée n'est pas un bateau
}

# Seuil pour s'ajouter en catégorie secondaire.
SECONDARY_BY_CLASS = {
    "people": 0.08,  # les personnes figurent dans quantité de scènes où la scène l'emporte
    "animals": 0.40,
    "boats": 0.50,
    "sunset": 0.40,  # la lumière dorée ne fait pas un coucher de soleil
}
