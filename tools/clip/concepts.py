# -*- coding: utf-8 -*-
"""
Vocabulaire de la recherche par contenu : environ 370 concepts (« chien », « robe », « coucher de
soleil »…), chacun décrit en anglais pour MobileCLIP et nommé en français pour la recherche.

Mesure : les sources Commons donnent une vérité terrain par concept (chercher « chien » doit
ramener les photos des sources Dogs, Puppies, Golden Retriever). Plusieurs règles de
correspondance sont réglées sur l'entraînement et mesurées sur le test.

Usage : python concepts.py           -> comparaison des règles
        python concepts.py --export  -> écrit aussi app/src/main/assets/clip/concepts.bin
"""
import io, json, os, re, struct, sys
import numpy as np
import dataset, variants as va
import eval as ev

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "clip"))

# Sans intérêt pour une recherche, ou trop vagues.
DROPPED = {"Cool", "Fun", "Photograph", "Dude", "Pest", "Larva", "Pomacentridae", "Face"}

# Concepts courants dans une galerie de téléphone, absents du dictionnaire hérité de ML Kit.
EXTRA = {
    "Mobile phone": ["telephone", "smartphone", "portable", "mobile"],  # « telephone » d'abord : nom affiché
    "Tablet computer": ["tablette", "ipad"],
    "Computer": ["ordinateur", "pc"],
    "Television": ["television", "tele", "ecran"],
    "Camera": ["appareil photo"],
    "Headphones": ["casque", "ecouteurs"],
    "Guitar": ["guitare"], "Piano": ["piano"],
    "Painting": ["tableau", "peinture"], "Drawing": ["dessin"], "Graffiti": ["graffiti", "tag"],
    "Map": ["carte", "plan"], "Diploma": ["diplome", "certificat", "attestation"],
    "Handwriting": ["ecriture", "manuscrit", "lettre", "note"], "Ticket": ["billet", "ticket"],
    "Bottle": ["bouteille"], "Key": ["cle", "cles"], "Pen": ["stylo"], "Light bulb": ["ampoule"],
    "Box": ["carton", "boite", "colis"], "Tool": ["outil", "outils"], "Coin": ["piece de monnaie"],
    "Ship": ["navire", "paquebot", "ferry"], "Truck": ["camion"], "Tram": ["tramway", "tram"],
    "Airport": ["aeroport"], "Tent": ["tente", "camping"],
    "Birthday": ["anniversaire"], "Sunrise": ["lever de soleil", "aube"],
    "Basketball": ["basket", "basketball"], "Tennis": ["tennis"], "Volleyball": ["volley", "volleyball"],
    "Sheep": ["mouton"], "Rabbit": ["lapin"], "Fish": ["poisson"], "Chicken": ["poule", "poulet"],
    "Elephant": ["elephant"], "Lion": ["lion"], "Giraffe": ["girafe"], "Tiger": ["tigre"],
    "Rice": ["riz"], "Soup": ["soupe"], "Salad": ["salade"], "Sandwich": ["sandwich"],
    "French fries": ["frites"], "Meat": ["viande", "steak"], "Egg": ["oeuf", "oeufs"],
    "Cheese": ["fromage"], "Chocolate": ["chocolat"], "Beer": ["biere"],
    "Bed": ["lit"], "Hotel room": ["hotel", "chambre d'hotel"],
    # Livres : « livre » seul ne ramenait que les livres isolés, pas les étagères ni les bibliothèques.
    "Bookshelf": ["etagere a livres", "bibliotheque", "livre"], "Library": ["bibliotheque", "librairie", "livre"],
    "Reading": ["lecture", "lire"],
}

# Synonymes qui se volent la place : pour une photo de neige, c'est souvent « Winter » qui l'emporte.
SYNONYMS = {"Kayak": ["canoe"], "Canoe": ["kayak"], "Winter": ["neige"], "Bedroom": ["lit"],
            "Hotel room": ["lit"], "Skyline": ["ville"], "City": ["panorama urbain"]}

# Concepts qui chapeautent d'autres concepts : un chien est souvent d'abord un « animal de compagnie »,
# une guitare un « musicien ». Ils ne privent pas de résultat le premier concept précis qui les suit.
# Pas les concepts de personnes, de sport ou de pièce : derrière eux, le suivant n'est qu'une
# supposition (des groupes devenaient « mariage », une salle à manger « lit »).
GENERIC = {"Animal", "Pet", "Wildlife", "Primate", "Waterfowl", "Flora", "Plant", "Nature", "Food", "Cuisine",
           "Meal", "Lunch", "Supper", "Dessert", "Fast food", "Drink", "Alcohol", "Vehicle", "Aircraft",
           "Furniture", "Fashion", "Outerwear", "Jewellery", "Art", "Musician",
           # Activités : le canoë passe souvent derrière « rafting » ou « aviron ».
           "Rafting", "Rowing", "Dance", "Eating", "Hiking", "Backpacking", "Cycling", "Swimming", "Fishing"}

def vocabulary():
    """{concept anglais: [termes français sans accents]}, depuis vocabulary.json."""
    base = json.load(io.open(os.path.join(HERE, "vocabulary.json"), encoding="utf-8"))
    vocab = {k: v for k, v in base.items() if k not in DROPPED}
    for extra in (EXTRA, SYNONYMS):
        for key, terms in extra.items():
            # L'ordre compte : le premier terme donne le nom affiché (« Téléphone », pas « Mobile »).
            current = vocab.get(key, [])
            vocab[key] = current + [t for t in terms if t not in current]
    return vocab

# Orthographe pour l'affichage (les termes de recherche restent sans accents).
ACCENTS = {
    "actualite": "actualité", "aeronef": "aéronef", "aeroport": "aéroport", "aurore boreale": "aurore boréale",
    "bande dessinee": "bande dessinée", "batiment": "bâtiment", "bebe": "bébé", "biere": "bière", "boite": "boîte",
    "boite de nuit": "boîte de nuit", "bouee tractee": "bouée tractée", "cafe": "café", "canape": "canapé",
    "canoe": "canoë", "capture d'ecran": "capture d'écran", "cathedrale": "cathédrale",
    "chambre d'hotel": "chambre d'hôtel", "chateau": "château", "cle": "clé", "comete": "comète", "cote": "côte",
    "dejeuner": "déjeuner", "desert": "désert", "diner": "dîner", "echarpe": "écharpe", "ecran": "écran",
    "ecriture": "écriture", "eglise": "église", "elephant": "éléphant", "epave": "épave", "etagere": "étagère",
    "evier": "évier", "fenetre": "fenêtre", "fete": "fête", "foret": "forêt", "fregate": "frégate", "fusee": "fusée",
    "gateau": "gâteau", "helicoptere": "hélicoptère", "jetee": "jetée", "legume": "légume", "lit superpose": "lit superposé",
    "marie": "marié", "mariee": "mariée", "mosquee": "mosquée", "musee": "musée", "nebuleuse": "nébuleuse",
    "noel": "Noël", "oeuf": "œuf", "pates": "pâtes", "peche": "pêche", "pere noel": "père Noël", "piece": "pièce",
    "piece de monnaie": "pièce de monnaie", "planche a voile": "planche à voile", "plongee": "plongée",
    "poupee": "poupée", "presentation": "présentation", "randonnee": "randonnée", "recif": "récif", "recu": "reçu",
    "remise de diplome": "remise de diplôme", "riviere": "rivière", "robe de soiree": "robe de soirée",
    "course a pied": "course à pied", "sac a main": "sac à main", "speleologie": "spéléologie",
    "super-heros": "super-héros", "tir a l'arc": "tir à l'arc", "vehicule": "véhicule", "velo": "vélo",
    "telephone": "téléphone", "television": "télévision", "diplome": "diplôme", "hotel": "hôtel",
    "etagere a livres": "étagère à livres", "bibliotheque": "bibliothèque",
}

def display_name(concept, vocab):
    first = vocab[concept][0]
    name = ACCENTS.get(first, first)
    return name[0].upper() + name[1:]

TEMPLATES = ["a photo of a {}.", "a photo of {}.", "a close-up photo of a {}."]

def text_vectors(concepts):
    _, text, tok = va.MODELS["S0"]
    from tokenizers import Tokenizer
    import onnxruntime as ort
    t = Tokenizer.from_file(tok); sess = ort.InferenceSession(text, providers=["CPUExecutionProvider"])
    prompts = [tpl.format(c.lower()) for c in concepts for tpl in TEMPLATES]
    ids = np.zeros((len(prompts), 77), dtype=np.int64)
    for i, p in enumerate(prompts):
        e = t.encode(p).ids[:77]; ids[i, :len(e)] = e
    out = []
    for i in range(0, len(prompts), 64):
        out.append(sess.run(None, {"input_ids": ids[i:i + 64]})[0])
    E = ev.normalize(np.concatenate(out)).reshape(len(concepts), len(TEMPLATES), -1)
    return ev.normalize(E.mean(1))

# Vérité terrain : concept -> sources Commons qui le montrent sans ambiguïté.
TRUTH = {
    "Dog": ["Dogs", "Puppies", "Golden Retriever", "People with dogs"], "Cat": ["Cats", "Domestic cats", "Kittens"],
    "Bird": ["Birds in flight", "Parrots"], "Horse": ["Horses"],
    "Dress": ["Dresses", "Wedding dresses", "Evening gowns"], "Shoe": ["Shoes"], "Handbag": ["Handbags"],
    "Hat": ["Hats"], "Jacket": ["Jackets"],
    "Mobile phone": ["Smartphones", "Mobile phones", "iPhone"], "Laptop": ["Laptops"], "Tablet computer": ["Tablet computers"],
    "Car": ["Automobiles", "Cars"], "Motorcycle": ["Motorcycles"], "Bus": ["Buses"], "Airplane": ["Aircraft"],
    "Bicycle": ["Bicycles", "People with bicycles"], "Sailboat": ["Sailboats"], "Ship": ["Ships", "Ferries"],
    "Canoe": ["Canoes", "Canoeing", "Kayaking", "Sea kayaking"],
    "Pizza": ["Pizza"], "Cake": ["Cakes", "Birthday cakes"], "Salad": ["Salads"], "Cheeseburger": ["Hamburgers"], "Sushi": ["Sushi"],
    "Fireworks": ["Fireworks"], "Christmas": ["Christmas trees"], "Concert": ["Concerts"], "Wedding": ["Wedding ceremonies"],
    "Bed": ["Beds", "Hotel rooms"], "Skyscraper": ["Skyscrapers"], "Skyline": ["Skylines", "City skylines"],
    "Sunset": ["Sunsets", "Sunrises", "Sunsets over the sea"], "Beach": ["Beaches", "Beaches of Spain", "Sandy beaches"],
    "Desert": ["Deserts", "Sand dunes"], "Forest": ["Forests in Germany"], "Mountain": ["Mountains of the Alps"],
    "Flower": ["Red flowers", "Yellow flowers", "Pink flowers", "White flowers", "Blue flowers"],
    "Snow": ["Snowy landscapes", "Winter landscapes", "Skiing"], "Soccer": ["Association football matches"],
    "Basketball": ["Basketball"], "Tennis": ["Tennis"], "Volleyball": ["Volleyball"], "Running": ["Marathons", "Running"],
    "Handwriting": ["Handwriting", "Letters"], "Diploma": ["Certificates", "Diplomas"], "Map": ["Maps"],
    "Painting": ["Paintings", "Oil paintings"], "Drawing": ["Drawings"], "Graffiti": ["Graffiti", "Street art"],
    "Selfie": ["Selfies"], "Child": ["Children playing"], "Guitar": ["Guitarists", "Women with guitars"],
    "Bottle": ["Bottles"], "Key": ["Keys"], "Pen": ["Pens"], "Light bulb": ["Light bulbs"], "Box": ["Cardboard boxes"],
    "Tool": ["Hand tools"], "Coin": ["Coins"],
}
# Sources où le concept peut apparaître sans être le sujet : ni exemple ni contre-exemple.
MAYBE = {
    "Dog": ["Pugs", "Hikers"], "Beach": ["Seascapes", "Coasts", "Sunbathing", "Sea kayaking", "Sunsets over the sea"],
    "Sunset": ["Silhouettes of people"], "Snow": ["Mountains of the Alps"], "Mountain": ["Hills", "Landscapes", "Hikers", "Snowy landscapes", "Winter landscapes", "Skiing"],
    "Forest": ["Landscapes", "Hikers", "Fog", "Snowy landscapes", "Winter landscapes"], "Car": ["Street views", "Streets in Paris"],
    "Bus": ["Street views"], "Bicycle": ["Cycling races"], "Christmas": ["Winter landscapes"],
    "Cake": ["Birthday parties"], "Guitar": ["Concerts", "Street musicians"], "Child": ["Birthday parties", "Groups of people"],
    "Laptop": ["Home offices"], "Mobile phone": ["Selfies"], "Handwriting": ["Notebooks", "Diplomas", "Maps"],
    "Painting": ["Drawings"], "Drawing": ["Paintings", "Oil paintings", "Maps"], "Skyline": ["Skyscrapers", "Street views"],
    "Skyscraper": ["Skylines", "City skylines"], "Shoe": ["Sneakers"], "Canoe": ["Rowing", "People in boats", "Boats"],
    "Sailboat": ["Boats", "Yachts"], "Ship": ["Boats", "Fishing boats"], "Bed": ["Interiors of houses"],
    "Concert": ["Street musicians", "Guitarists"], "Selfie": ["Portrait photographs of women", "Portrait photographs of men"],
    "Running": ["Association football matches"], "Soccer": ["Football"], "Box": ["Plastic bags"],
}

def scores(X, C):
    return X @ C.T

def export(vocab, concepts, C):
    """concepts.bin (gros-boutiste) : version, n, dim, puis n × (clé, nom affiché, termes séparés par |,
    générique ou non, vecteur)."""
    def utf(out, text):
        data = text.encode("utf-8"); out.write(struct.pack(">H", len(data))); out.write(data)
    assert GENERIC <= set(concepts), GENERIC - set(concepts)
    path = os.path.join(ASSETS, "concepts.bin")
    with open(path, "wb") as out:
        out.write(struct.pack(">iii", 3, len(concepts), C.shape[1]))
        for c, v in zip(concepts, C):
            utf(out, c)
            utf(out, display_name(c, vocab))
            utf(out, "|".join(normalize(t) for t in vocab[c]))
            out.write(struct.pack(">?", c in GENERIC))
            out.write(struct.pack(">%df" % C.shape[1], *v.astype(np.float32)))
    print("ecrit : %s (%d concepts, %.0f Ko)" % (path, len(concepts), os.path.getsize(path) / 1024))

def normalize(text):
    import unicodedata
    t = unicodedata.normalize("NFD", text.lower())
    return "".join(ch for ch in t if unicodedata.category(ch) != "Mn").strip()

def singular(q):
    """Comme PhotoLabels.singular."""
    return " ".join(w[:-1] if len(w) > 3 and w[-1] in "sx" else w for w in q.split(" "))

def matching(query, vocab, concepts):
    """Comme ClipConcepts.matching : les concepts dont un terme commence par la requête en mots entiers
    (« lit », « lit superposé ») ; à défaut, par le début d'un mot (« chie » -> chien). Sans cela, « lit »
    ramenait le « littoral »."""
    q = normalize(query); qs = {q, singular(q)}
    terms = [[normalize(t) for t in vocab[c]] for c in concepts]
    whole = [k for k, ts in enumerate(terms) if any(t == x or t.startswith(x + " ") for t in ts for x in qs)]
    return whole or [k for k, ts in enumerate(terms) if any(t.startswith(x) for t in ts for x in qs)]

def first_specific(S, concepts):
    """Pour chaque photo, les concepts retenus comme dans l'app : les génériques en tête du classement,
    puis le premier concept précis. Tableau booléen photos × concepts."""
    generic = np.array([c in GENERIC for c in concepts])
    out = np.zeros(S.shape, bool)
    for i, order in enumerate(np.argsort(-S, 1)):
        for k in order:
            out[i, k] = True
            if not generic[k]: break
    return out

def evaluate(S, sources, concepts, rule, vocab, detail=False):
    """Au niveau de la requête française : précision, rappel, précision des premiers résultats."""
    P, R, PK, rows = [], [], [], []
    for c, pos_src in TRUTH.items():
        query = vocab[c][0]
        ks = matching(query, vocab, concepts)
        pos = np.array([s in pos_src for s in sources]); maybe = np.array([s in MAYBE.get(c, []) for s in sources])
        if pos.sum() == 0 or not ks: continue
        hit = np.zeros(len(sources), bool)
        for k in ks: hit |= rule(S, k)
        judged = ~maybe
        tp = int((hit & pos).sum()); fp = int((hit & ~pos & judged).sum())
        p = tp / (tp + fp) if tp + fp else 1.0; r = tp / pos.sum()
        best = S[:, ks].max(1); order = [i for i in np.argsort(-best) if judged[i] or pos[i]]
        n = min(10, int(pos.sum())); pk = float(np.mean([pos[i] for i in order[:n]]))
        P.append(p); R.append(r); PK.append(pk)
        rows.append((query, len(ks), tp, fp, int(pos.sum()) - tp))
    if detail:
        for q, nk, tp, fp, fn in sorted(rows, key=lambda r: -(r[3] + r[4])):
            print("    %-16s %2d concepts  trouvees %3d  intrus %3d  oubliees %3d" % (q, nk, tp, fp, fn))
    return float(np.mean(P)), float(np.mean(R)), float(np.mean(PK))

if __name__ == "__main__":
    vocab = vocabulary(); concepts = sorted(vocab)
    print("%d concepts" % len(concepts))
    C = text_vectors(concepts)
    if "--export" in sys.argv:
        export(vocab, concepts, C); sys.exit()
    vision = va.MODELS["S0"][0]
    tr = dataset.load("train"); te = dataset.load("test")
    Xtr = va.image_matrix("S0", vision, [p for p, _, _, _ in tr]); Xte = va.image_matrix("S0", vision, [p for p, _, _, _ in te])
    Str, Ste = scores(Xtr, C), scores(Xte, C)
    src_tr, src_te = [s for _, _, s, _ in tr], [s for _, _, s, _ in te]

    # Calibration par concept sur l'entraînement (photos non étiquetées pour ce concept : sa similarité « ordinaire »).
    mu, sd = Str.mean(0), Str.std(0) + 1e-6
    rules = {}
    for tau in np.arange(0.20, 0.32, 0.01):
        rules["seuil absolu %.2f" % tau] = (lambda t: (lambda S, k: S[:, k] >= t))(tau)
    for K in (1, 2, 3, 5, 8):
        rules["parmi les %d meilleurs" % K] = (lambda K: (lambda S, k: (S >= np.sort(S, 1)[:, [-K]]).T[k]))(K)
    for z in np.arange(1.5, 4.6, 0.25):
        rules["calibre z>=%.2f" % z] = (lambda z: (lambda S, k: (S[:, k] - mu[k]) / sd[k] >= z))(z)
    for z in (1.5, 2.0, 2.5, 3.0):
        for K in (3, 5, 8):
            rules["calibre z>=%.1f et top %d" % (z, K)] = (lambda z, K: (lambda S, k: ((S[:, k] - mu[k]) / sd[k] >= z) & (S >= np.sort(S, 1)[:, [-K]]).T[k]))(z, K)

    results = []
    for name, rule in rules.items():
        p, r, p10 = evaluate(Str, src_tr, concepts, rule, vocab)
        f = 1.25 * p * r / (0.25 * p + r) if p + r else 0
        results.append((f, name, p, r, p10))
    results.sort(reverse=True)
    print("\nMeilleures regles sur l'ENTRAINEMENT (F0.5) :")
    for f, name, p, r, p10 in results[:3]:
        print("  %-26s precision %3.0f%%  rappel %3.0f%%  premiers %3.0f%%" % (name, 100 * p, 100 * r, 100 * p10))
    print("\nMesure sur le TEST, meilleure regle de chaque famille :")
    seen = set()
    for f, name, p, r, p10 in results:
        family = name.split(" ")[0] + (" top" if "top" in name else "")
        if family in seen: continue
        seen.add(family)
        pt, rt, p10t = evaluate(Ste, src_te, concepts, rules[name], vocab)
        print("  %-26s precision %3.0f%%  rappel %3.0f%%  premiers %3.0f%%" % (name, 100 * pt, 100 * rt, 100 * p10t))
