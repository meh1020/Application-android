# -*- coding: utf-8 -*-
"""
Planches contact des photos de TEST, pour écarter à la main celles qui ne correspondent pas à
leurs catégories. Usage : python review.py <nom> <source1,source2,…>  -> data/review_<nom>.jpg
Chaque vignette porte un numéro ; la table correspondante est écrite dans data/review_<nom>.txt.
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageOps
import dataset

HERE = os.path.dirname(os.path.abspath(__file__))
PER_PAGE, COLS, CELL = 60, 10, 170

def photos_of(sources):
    items = [(t, p, sorted(l), s) for p, l, s, t in dataset.load("test") if s in sources]
    items.sort(key=lambda it: (it[3], it[0]))
    return items

if __name__ == "__main__":
    k = sys.argv[1]
    page = photos_of(set(sys.argv[2].split(",")))[:PER_PAGE * 2]
    rows = (len(page) + COLS - 1) // COLS
    img = Image.new("RGB", (COLS * CELL, rows * (CELL + 14)), "white"); d = ImageDraw.Draw(img)
    lines = []
    for i, (title, path, labels, source) in enumerate(page):
        x, y = (i % COLS) * CELL, (i // COLS) * (CELL + 14)
        try:
            t = ImageOps.exif_transpose(Image.open(path)).convert("RGB"); t.thumbnail((CELL - 4, CELL - 4))
            img.paste(t, (x + 2, y + 2))
        except Exception:
            pass
        d.rectangle((x + 2, y + 2, x + 30, y + 16), fill="black"); d.text((x + 4, y + 3), "%d" % i, fill="yellow")
        d.text((x + 2, y + CELL - 1), (source[:18] + " " + ",".join(labels))[:30], fill="black")
        lines.append("%d\t%s\t%s\t%s" % (i, source, ",".join(labels), title))
    out = os.path.join(HERE, "data", "review_%s" % k)
    img.save(out + ".jpg", quality=85)
    open(out + ".txt", "w", encoding="utf-8").write("\n".join(lines))
    print(out + ".jpg", len(page), "photos")
