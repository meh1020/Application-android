# -*- coding: utf-8 -*-
"""
Planche avant / après de la retouche automatique, calculée par le code de l'app (AutoEnhance.kt).

  python auto_enhance_sheet.py prepare <dossier de photos> [nombre]
  ./gradlew testDebugUnitTest --tests "*AutoEnhanceSheetTest*"     (depuis la racine du projet)
  python auto_enhance_sheet.py sheet

Les photos sont réduites (240 px de côté) et écrites en pixels bruts dans app/build/auto-enhance/in ;
le test Kotlin écrit le rendu et les valeurs des curseurs dans app/build/auto-enhance/out ; la
planche va dans app/build/auto-enhance/sheet_N.jpg.
"""
import os, struct, sys
from PIL import Image, ImageDraw, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", "app", "build", "auto-enhance"))
SIDE = 240

def write_raw(path, im):
    with open(path, "wb") as out:
        out.write(struct.pack(">ii", *im.size)); out.write(im.tobytes())

def read_raw(path):
    data = open(path, "rb").read()
    w, h = struct.unpack(">ii", data[:8])
    return Image.frombytes("RGB", (w, h), data[8:])

def prepare(folder, count):
    dst = os.path.join(ROOT, "in"); os.makedirs(dst, exist_ok=True)
    for f in os.listdir(dst): os.remove(os.path.join(dst, f))
    names = sorted(f for f in os.listdir(folder) if f.lower().endswith((".jpg", ".jpeg", ".png")))[:count]
    for i, name in enumerate(names):
        im = ImageOps.exif_transpose(Image.open(os.path.join(folder, name))).convert("RGB")
        im.thumbnail((SIDE, SIDE))
        write_raw(os.path.join(dst, "%03d_%s.rgb" % (i, os.path.splitext(name)[0])), im)
    print("%d photos preparees dans %s" % (len(names), dst))

def sheet(per_sheet=24, cols=4):
    src, out = os.path.join(ROOT, "in"), os.path.join(ROOT, "out")
    values = {}
    for line in open(os.path.join(out, "values.tsv"), encoding="utf-8"):
        name, *v = line.rstrip("\n").split("\t"); values[name] = v
    names = sorted(values)
    font = ImageFont.truetype("arial.ttf", 14)
    for s in range(0, len(names), per_sheet):
        chunk = names[s:s + per_sheet]; rows = (len(chunk) + cols - 1) // cols
        page = Image.new("RGB", (cols * SIDE * 2 + cols * 8, rows * (SIDE + 20)), "white"); dr = ImageDraw.Draw(page)
        for i, name in enumerate(chunk):
            x, y = (i % cols) * (SIDE * 2 + 8), (i // cols) * (SIDE + 20)
            page.paste(read_raw(os.path.join(src, name + ".rgb")), (x, y))
            page.paste(read_raw(os.path.join(out, name + ".rgb")), (x + SIDE, y))
            e, c, sh, hi, w, t = values[name]
            dr.text((x + 2, y + SIDE + 2), "%s  exp %s  con %s  omb %s  hl %s  cha %s  tei %s" % (name[:3], e, c, sh, hi, w, t), fill="black", font=font)
        path = os.path.join(ROOT, "sheet_%d.jpg" % (s // per_sheet)); page.save(path, quality=85); print(path)

if __name__ == "__main__":
    if sys.argv[1] == "prepare": prepare(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 48)
    else: sheet()
