# -*- coding: utf-8 -*-
"""
Jeu de test « style téléphone » : photos Unsplash récentes et soignées (via picsum.photos), le
style d'une galerie de téléphone moderne, très différent des photos de Wikimedia Commons.
Les étiquettes sont posées à la main dans data/unsplash/labels.json ({"id": ["people", …]}).

Usage : python unsplash.py download      -> data/unsplash/<id>.jpg
        python unsplash.py sheet <page>  -> data/unsplash/sheet_<page>.jpg (60 photos numérotées)
"""
import json, os, random, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageDraw, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
DIR = os.path.join(HERE, "data", "unsplash")
COUNT = 240

def listing():
    out = []
    for page in range(1, 20):
        with urllib.request.urlopen("https://picsum.photos/v2/list?page=%d&limit=100" % page, timeout=60) as r:
            items = json.load(r)
        if not items:
            break
        out += items
    return out

def download():
    os.makedirs(DIR, exist_ok=True)
    items = listing()
    random.Random(2026).shuffle(items)
    chosen = items[:COUNT]
    def fetch(it):
        dest = os.path.join(DIR, "%s.jpg" % it["id"])
        if os.path.exists(dest): return
        w, h = int(it["width"]), int(it["height"])
        url = "https://picsum.photos/id/%s/500/%d" % (it["id"], max(1, round(500 * h / w)))
        with urllib.request.urlopen(url, timeout=60) as r, open(dest, "wb") as f:
            f.write(r.read())
    with ThreadPoolExecutor(8) as pool:
        list(pool.map(fetch, chosen))
    json.dump([it["id"] for it in chosen], open(os.path.join(DIR, "order.json"), "w"))
    print("%d photos (sur %d disponibles)" % (len(chosen), len(items)))

def ids():
    return json.load(open(os.path.join(DIR, "order.json")))

def sheet(page, per=60, cols=10, cell=170):
    chunk = ids()[page * per:(page + 1) * per]
    rows = (len(chunk) + cols - 1) // cols
    img = Image.new("RGB", (cols * cell, rows * cell), "white"); d = ImageDraw.Draw(img)
    for i, pid in enumerate(chunk):
        x, y = (i % cols) * cell, (i // cols) * cell
        t = ImageOps.exif_transpose(Image.open(os.path.join(DIR, "%s.jpg" % pid))).convert("RGB")
        t.thumbnail((cell - 4, cell - 4)); img.paste(t, (x + 2, y + 2))
        d.rectangle((x + 2, y + 2, x + 34, y + 16), fill="black"); d.text((x + 4, y + 3), str(page * per + i), fill="yellow")
    out = os.path.join(DIR, "sheet_%d.jpg" % page); img.save(out, quality=85); print(out)

def load():
    """[(chemin, catégories)] des photos étiquetées."""
    labels = json.load(open(os.path.join(DIR, "labels.json"), encoding="utf-8"))
    return [(os.path.join(DIR, "%s.jpg" % pid), set(labels[pid])) for pid in ids() if pid in labels]

if __name__ == "__main__":
    if sys.argv[1] == "download": download()
    elif sys.argv[1] == "sheet": sheet(int(sys.argv[2]))
