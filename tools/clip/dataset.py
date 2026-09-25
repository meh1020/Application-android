# -*- coding: utf-8 -*-
"""
Constitue le jeu de photos étiquetées (Wikimedia Commons) qui sert à entraîner et à mesurer le
classement. Chaque source Commons porte un *ensemble* de catégories de l'app : « People in canoes »
vaut à la fois Personnes et Bateaux. C'est ce qui apprend au classement qu'une personne reste une
personne au milieu d'une scène.

Tout est rangé dans data/ (hors de git) :
  data/images/<empreinte>.jpg   les photos (miniatures de 500 px)
  data/manifest.json            titre -> fichier, catégories, source, jeu (train / test)
  data/excluded.txt             titres écartés à la main (hors sujet, ambigus), un par ligne

Le partage entraînement / test dépend du seul titre (un quart en test) : il est stable d'une
exécution à l'autre, et une photo de test ne sert jamais à l'entraînement.
"""
import hashlib, json, os, sys, threading, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
import truststore
truststore.inject_into_ssl()  # certificats du système (ceux de Python sont périmés sur ce poste)

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "VistaEval/1.0 (photo classification research; contact via project)"}
PER_SOURCE = 32

# Source Commons -> catégories de l'app (ensemble vide : photo « quelconque », à ne ranger nulle part).
SOURCES = {
    # Personnes, seules ou au milieu d'une scène
    "Portrait photographs of women": {"people"}, "Portrait photographs of men": {"people"},
    "Selfies": {"people"}, "Groups of people": {"people"}, "Children playing": {"people"},
    "Guitarists": {"people"}, "Women with guitars": {"people"}, "Tourists": {"people"},
    "Street musicians": {"people", "city"}, "Sunbathing": {"people", "beach"},
    "People in boats": {"people", "boats"}, "Canoeing": {"people", "boats"},
    "Kayaking": {"people", "boats"}, "Sea kayaking": {"people", "boats"}, "Rowing": {"people", "boats"},
    "Hikers": {"people", "landscape"}, "Silhouettes of people": {"people"},
    "People with dogs": {"people", "animals"}, "People with bicycles": {"people"},
    # Animaux
    "Dogs": {"animals"}, "Cats": {"animals"}, "Domestic cats": {"animals"},
    "Golden Retriever": {"animals"}, "Birds in flight": {"animals"}, "Horses": {"animals"},
    "Kittens": {"animals"}, "Puppies": {"animals"}, "Parrots": {"animals"},
    # Mer, plages, couchers de soleil
    "Beaches": {"beach"}, "Sandy beaches": {"beach"}, "Beaches of Spain": {"beach"},
    "Beaches in Portugal": {"beach"}, "Seascapes": {"beach"}, "Coasts": {"beach"},
    "Sunsets": {"sunset"}, "Sunsets over the sea": {"sunset", "beach"}, "Sunrises": {"sunset"},
    "Sunsets in Italy": {"sunset"},
    # Paysages (dont déserts et dunes : du blanc qui n'est pas de la neige)
    "Landscapes": {"landscape"}, "Hills": {"landscape"}, "Forests in Germany": {"landscape"},
    "Mountains of the Alps": {"landscape"}, "Deserts": {"landscape"}, "Sand dunes": {"landscape"},
    "Fog": {"landscape"},
    "Red flowers": {"flowers"}, "Yellow flowers": {"flowers"}, "Pink flowers": {"flowers"},
    "White flowers": {"flowers"}, "Blue flowers": {"flowers"},
    "City skylines": {"city"}, "Skylines": {"city"}, "Street views": {"city"},
    "Streets in Paris": {"city"}, "Skyscrapers": {"city"},
    "Food": {"food"}, "Pizza": {"food"}, "Cakes": {"food"}, "Salads": {"food"},
    "Breakfast": {"food"}, "Hamburgers": {"food"}, "Sushi": {"food"},
    "Dresses": {"fashion"}, "Wedding dresses": {"fashion"}, "Shoes": {"fashion"},
    "Sneakers": {"fashion"}, "Handbags": {"fashion"}, "Jackets": {"fashion"},
    "Evening gowns": {"fashion"}, "Hats": {"fashion"},
    "Smartphones": {"electronics"}, "Mobile phones": {"electronics"}, "Laptops": {"electronics"},
    "iPhone": {"electronics"}, "Samsung Galaxy phones": {"electronics"},
    "Tablet computers": {"electronics"},
    "Automobiles": {"vehicles"}, "Cars": {"vehicles"}, "Motorcycles": {"vehicles"},
    "Buses": {"vehicles"}, "Trains": {"vehicles"}, "Aircraft": {"vehicles"},
    "Bicycles": {"vehicles"},
    "Boats": {"boats"}, "Sailboats": {"boats"}, "Ships": {"boats"}, "Fishing boats": {"boats"},
    "Yachts": {"boats"}, "Ferries": {"boats"}, "Canoes": {"boats"},
    "Handwriting": {"documents"}, "Certificates": {"documents"}, "Notebooks": {"documents"},
    "Diplomas": {"documents"}, "Letters": {"documents"}, "Maps": {"documents"},
    "Football": {"sport"}, "Basketball": {"sport"}, "Tennis": {"sport"}, "Running": {"sport"},
    "Cycling races": {"sport", "people"}, "Skiing": {"sport", "snow"},
    "Association football matches": {"sport", "people"}, "Marathons": {"sport", "people"},
    "Volleyball": {"sport"},
    "Birthday parties": {"party", "people"}, "Birthday cakes": {"party", "food"},
    "Fireworks": {"party"}, "Wedding ceremonies": {"party", "people"},
    "Concerts": {"party", "people"}, "Christmas trees": {"party"}, "Carnivals": {"party", "people"},
    "Interiors of houses": {"home"}, "Hotel rooms": {"home"}, "Dining rooms": {"home"},
    "Beds": {"home"}, "Home offices": {"home"},
    "Snow": {"snow"}, "Snowy landscapes": {"snow", "landscape"},
    "Winter landscapes": {"snow", "landscape"}, "Snow-covered trees": {"snow"},
    "Paintings": {"art"}, "Drawings": {"art"}, "Street art": {"art"}, "Graffiti": {"art"},
    "Oil paintings": {"art"},
    # Photos quelconques
    "Hand tools": set(), "Pens": set(), "Keys": set(), "Bottles": set(), "Textures": set(),
    "Stones": set(), "Cables": set(), "Cardboard boxes": set(), "Screws": set(), "Coins": set(),
    "Light bulbs": set(), "Plastic bags": set(),
}

def api(params):
    url = API + "?" + urllib.parse.urlencode(dict(params, format="json"))
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as r:
                return json.load(r)
        except Exception:
            time.sleep(2 + attempt * 4)
    return {}

def files_in(category, limit):
    data = api({"action": "query", "list": "categorymembers", "cmtitle": "Category:" + category,
                "cmtype": "file", "cmlimit": 200})
    titles = [m["title"] for m in data.get("query", {}).get("categorymembers", [])
              if m["title"].lower().endswith((".jpg", ".jpeg"))]
    return sorted(titles)[:limit]

def thumbs(titles, width=500):
    out = {}
    for i in range(0, len(titles), 40):
        data = api({"action": "query", "titles": "|".join(titles[i:i + 40]), "prop": "imageinfo",
                    "iiprop": "url", "iiurlwidth": width})
        for page in data.get("query", {}).get("pages", {}).values():
            info = (page.get("imageinfo") or [{}])[0]
            if "thumburl" in info:
                out[page["title"]] = info["thumburl"]
    return out

def split_of(title):
    return "test" if int(hashlib.sha1(title.encode("utf-8")).hexdigest(), 16) % 4 == 0 else "train"

def fetch(url, dest):
    if os.path.exists(dest):
        return True
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                data = r.read()
            with open(dest, "wb") as f:
                f.write(data)
            return True
        except Exception:
            time.sleep(3 + attempt * 5)
    return False

def build(sources, manifest_name="manifest.json"):
    """Télécharge les [sources] ; plusieurs téléchargeurs peuvent tourner à la fois, chacun avec
    son propre manifeste (ils sont fusionnés à la lecture)."""
    os.makedirs(os.path.join(DATA, "images"), exist_ok=True)
    path = os.path.join(DATA, manifest_name)
    manifest = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    lock = threading.Lock()
    for source in sources:
        labels = SOURCES[source]
        titles = [t for t in files_in(source, PER_SOURCE) if t not in manifest]
        urls = thumbs(titles)
        jobs = []
        with ThreadPoolExecutor(max_workers=6) as pool:
            for title, url in urls.items():
                name = hashlib.sha1(title.encode("utf-8")).hexdigest()[:16] + ".jpg"
                jobs.append((title, name, pool.submit(fetch, url, os.path.join(DATA, "images", name))))
        ok = 0
        for title, name, job in jobs:
            if job.result():
                with lock:
                    manifest[title] = {"file": name, "labels": sorted(labels), "source": source,
                                       "split": split_of(title)}
                ok += 1
        json.dump(manifest, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
        print("%-32s %3d nouvelles photos" % (source, ok), flush=True)
    print("total: %d photos" % len(manifest))

def load(split=None):
    """Photos retenues : [(chemin, catégories, source)], sans les titres écartés."""
    manifest = {}
    for name in sorted(os.listdir(DATA)):
        if name.startswith("manifest") and name.endswith(".json"):
            manifest.update(json.load(open(os.path.join(DATA, name), encoding="utf-8")))
    excluded_path = os.path.join(DATA, "excluded.txt")
    excluded = set()
    if os.path.exists(excluded_path):
        excluded = {l.strip() for l in open(excluded_path, encoding="utf-8") if l.strip()}
    out = []
    for title, e in sorted(manifest.items()):
        if title in excluded or (split and e["split"] != split):
            continue
        out.append((os.path.join(DATA, "images", e["file"]), set(e["labels"]), e["source"], title))
    return out

if __name__ == "__main__":
    order = list(SOURCES)
    if "--only" in sys.argv:
        build(sys.argv[sys.argv.index("--only") + 1].split(","), "manifest_c.json")
    elif "--reverse" in sys.argv:
        build(order[::-1], "manifest_b.json")
    else:
        build(order)
