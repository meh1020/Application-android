# -*- coding: utf-8 -*-
"""
Règle le seuil de ressemblance des doublons et rafales (DuplicateFinder.kt), avec les vecteurs du
modèle livré (vision_wq8.onnx).

- Séries fabriquées : chaque photo Unsplash (style téléphone) et des variantes comme en produit une
  rafale ou une prise répétée (recadrage, décalage, rotation, exposition, recompression).
- Photos différentes : paires de photos Commons de la même source (deux chiens, deux plages), le
  piège à éviter, et toutes les paires Unsplash.

Usage : python duplicates.py [--sheet]   (--sheet : planche des paires différentes les plus proches)
"""
import os, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageOps

import dataset, unsplash
import variants as va

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "data", "dup_variants")

# Nom -> transformation d'une photo (PIL, déjà à l'endroit).
def _crop(im, keep, dx=0.0, dy=0.0):
    w, h = im.size; cw, ch = int(w * keep), int(h * keep)
    x = int((w - cw) / 2 + dx * w); y = int((h - ch) / 2 + dy * h)
    x = max(0, min(w - cw, x)); y = max(0, min(h - ch, y))
    return im.crop((x, y, x + cw, y + ch))

VARIANTS = {
    "recompression": lambda im: im.resize((im.width // 2, im.height // 2)),
    "cadrage 95 %": lambda im: _crop(im, 0.95, 0.02, 0.0),
    "decalage 10 %": lambda im: _crop(im, 0.88, 0.06, 0.02),
    "zoom 80 %": lambda im: _crop(im, 0.80, 0.0, 0.03),
    "rotation 4 deg": lambda im: _crop(im.rotate(4, resample=Image.BICUBIC), 0.9),
    "exposition +30 %": lambda im: ImageEnhance.Brightness(_crop(im, 0.93, -0.03, 0)).enhance(1.3),
    "zoom 70 % + decalage": lambda im: _crop(im, 0.70, 0.1, 0.05),
}

def make_variants(paths):
    os.makedirs(OUT, exist_ok=True)
    pairs = []
    for p in paths:
        im = None
        for name, f in VARIANTS.items():
            dst = os.path.join(OUT, "%s__%s.jpg" % (os.path.splitext(os.path.basename(p))[0], name.replace(" ", "_").replace("%", "pc").replace("+", "p")))
            if not os.path.exists(dst):
                im = im or ImageOps.exif_transpose(Image.open(p)).convert("RGB")
                f(im).save(dst, quality=80)
            pairs.append((p, dst, name))
    return pairs

def pair_sims(X, groups):
    """Similarités de toutes les paires de photos différentes à l'intérieur de chaque groupe."""
    out = []
    for idx in groups.values():
        if len(idx) < 2: continue
        S = X[idx] @ X[idx].T
        iu = np.triu_indices(len(idx), 1)
        out += [(S[a, b], idx[a], idx[b]) for a, b in zip(*iu)]
    return out

if __name__ == "__main__":
    vision = va.MODELS["S0"][0]
    us = [p for p, _ in unsplash.load()]
    pairs = make_variants(us)
    E = va.image_matrix("S0", vision, sorted(set(us) | {d for _, d, _ in pairs}), exif=False)
    index = {p: i for i, p in enumerate(sorted(set(us) | {d for _, d, _ in pairs}))}
    print("Series fabriquees (%d photos Unsplash) : similarite photo / variante" % len(us))
    pos = []
    for name in VARIANTS:
        s = np.array([E[index[a]] @ E[index[b]] for a, b, n in pairs if n == name]); pos.append(s)
        print("  %-22s min %.3f  1 %% %.3f  5 %% %.3f  mediane %.3f" % (name, s.min(), np.percentile(s, 1), np.percentile(s, 5), np.median(s)))
    pos = np.concatenate(pos)

    items = dataset.load("train") + dataset.load("test")
    paths = [p for p, _, _, _ in items]; srcs = [s for _, _, s, _ in items]
    X = va.image_matrix("S0", vision, paths)
    groups = {}
    for i, s in enumerate(srcs): groups.setdefault(s, []).append(i)
    neg = pair_sims(X, groups)
    U = np.stack([E[index[p]] for p in us]); SU = U @ U.T; iu = np.triu_indices(len(us), 1)
    neg_u = SU[iu]
    ns = np.array([s for s, _, _ in neg])
    print("\nPhotos differentes : %d paires Commons de meme source, %d paires Unsplash" % (len(ns), len(neg_u)))
    for t in (0.85, 0.88, 0.90, 0.92, 0.94, 0.96):
        print("  seuil %.2f : series trouvees %5.1f %%  |  paires differentes au-dessus : Commons %d, Unsplash %d"
              % (t, 100 * (pos >= t).mean(), (ns >= t).sum(), (neg_u >= t).sum()))

    if "--sheet" in sys.argv:
        top = sorted(neg, reverse=True)[:40]
        T = 200; sheet = Image.new("RGB", (4 * 2 * T + 3 * 10, 10 * (T + 18)), "white"); dr = ImageDraw.Draw(sheet)
        for n, (s, a, b) in enumerate(top):
            x, y = (n % 4) * (2 * T + 10), (n // 4) * (T + 18)
            for k, i in enumerate((a, b)):
                im = ImageOps.exif_transpose(Image.open(paths[i])).convert("RGB"); im.thumbnail((T, T)); sheet.paste(im, (x + k * T, y))
            dr.text((x + 2, y + T + 2), "%d  %.3f  %s" % (n, s, srcs[a][:30]), fill="black")
        path = os.path.join(HERE, "data", "dup_negatives.jpg"); sheet.save(path, quality=80); print(path)
