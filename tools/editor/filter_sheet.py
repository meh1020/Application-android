# -*- coding: utf-8 -*-
"""
Planche de tous les filtres, calculés par le code de l'app (FilterSheetTest.kt).

  python filter_sheet.py prepare <photo> [<photo>…]
  ./gradlew testDebugUnitTest --tests "*FilterSheetTest*"     (depuis la racine du projet)
  python filter_sheet.py sheet

Une ligne par photo, une colonne par filtre, dans l'ordre du panneau ; planche dans
app/build/filters/sheet.jpg.
"""
import os, re, struct, sys
from PIL import Image, ImageDraw, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "app", "build", "filters"))
FILTERS = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "java", "com", "vista", "photoeditor", "editor", "Filters.kt"))
SIDE = 170

def read_raw(path):
    data = open(path, "rb").read()
    w, h = struct.unpack(">ii", data[:8])
    return Image.frombytes("RGB", (w, h), data[8:])

def prepare(paths):
    dst = os.path.join(ROOT, "in"); os.makedirs(dst, exist_ok=True)
    for f in os.listdir(dst): os.remove(os.path.join(dst, f))
    for i, p in enumerate(paths):
        im = ImageOps.fit(ImageOps.exif_transpose(Image.open(p)).convert("RGB"), (SIDE, SIDE))
        with open(os.path.join(dst, "%02d.rgb" % i), "wb") as out:
            out.write(struct.pack(">ii", *im.size)); out.write(im.tobytes())

def sheet():
    # Identifiants et noms, dans l'ordre du fichier des filtres.
    order = re.findall(r'FilterPreset\(\s*(?:ORIGINAL_ID|"([a-z]+)"),\s*"([^"]+)"', open(FILTERS, encoding="utf-8").read())
    order = [("original" if not i else i, n) for i, n in order]
    photos = sorted(f[:-4] for f in os.listdir(os.path.join(ROOT, "in")))
    font = ImageFont.truetype("arial.ttf", 13)
    cols = len(order)
    page = Image.new("RGB", (cols * (SIDE + 4), len(photos) * (SIDE + 4) + 20), "white"); dr = ImageDraw.Draw(page)
    for c, (fid, name) in enumerate(order):
        dr.text((c * (SIDE + 4) + 2, 2), name, fill="black", font=font)
        for r, ph in enumerate(photos):
            page.paste(read_raw(os.path.join(ROOT, "out", "%s__%s.rgb" % (ph, fid))), (c * (SIDE + 4), 20 + r * (SIDE + 4)))
    path = os.path.join(ROOT, "sheet.jpg"); page.save(path, quality=85); print(path)

if __name__ == "__main__":
    prepare(sys.argv[2:]) if sys.argv[1] == "prepare" else sheet()
