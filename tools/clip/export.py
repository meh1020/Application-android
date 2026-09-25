# -*- coding: utf-8 -*-
"""
Écrit les fichiers de l'app à partir de prompts.py : app/src/main/assets/clip/classes.bin (format 3)
et vision.onnx. Affiche aussi le bilan du réglage face à celui livré précédemment, sur les trois
jeux de mesure (test Commons, Unsplash, émulateur).
"""
import json, os, shutil, struct
import numpy as np
import variants as va, dataset, unsplash, phone_check, compare
import thresholds as th
import prompts
from prompts import (CATEGORIES, BACKGROUND, MEMORY_NAMES, LOGIT_SCALE, PRIMARY, SECONDARY,
                     PRIMARY_BY_CLASS, SECONDARY_BY_CLASS)

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "clip"))
CATS = list(CATEGORIES)

def settings():
    prim = {c: PRIMARY_BY_CLASS.get(c, PRIMARY) for c in CATS}
    sec = {c: SECONDARY_BY_CLASS.get(c, SECONDARY) for c in CATS}
    return prim, sec

def write_utf(out, text):
    data = text.encode("utf-8"); out.write(struct.pack(">H", len(data))); out.write(data)

def export(names, T):
    prim, sec = settings()
    with open(os.path.join(ASSETS, "classes.bin"), "wb") as out:
        out.write(struct.pack(">ifii", 3, LOGIT_SCALE, len(names), T.shape[1]))
        for key, vector in zip(names, T):
            write_utf(out, key)
            write_utf(out, CATEGORIES[key][0] if key in CATEGORIES else "")
            write_utf(out, MEMORY_NAMES.get(key, ""))
            # Les classes de fond ne sont jamais retenues : seuils impossibles à atteindre.
            out.write(struct.pack(">ff", prim.get(key, 2.0), sec.get(key, 2.0)))
            out.write(struct.pack(">%df" % T.shape[1], *vector.astype(np.float32)))
    shutil.copyfile(os.path.join(HERE, "vision_wq8.onnx"), os.path.join(ASSETS, "vision.onnx"))
    print("ecrit : %s (%d classes)" % (os.path.join(ASSETS, "classes.bin"), len(names)))

if __name__ == "__main__":
    te = [(p, l) for p, l, _, _ in dataset.load("test")]
    us = unsplash.load(); lab = json.load(open(os.path.join(unsplash.DIR, "labels.json"), encoding="utf-8"))
    us_t = [(set(lab[os.path.basename(p)[:-4]]["yes"]), set(lab[os.path.basename(p)[:-4]]["ok"])) for p, _ in us]
    ph_ids = sorted(int(f[:-4]) for f in os.listdir(os.path.join(HERE, "data", "phone")) if f.endswith(".jpg") and f[:-4].isdigit())
    ph_paths = [os.path.join(HERE, "data", "phone", "%d.jpg" % i) for i in ph_ids]
    ph_t = [({m} if m else set(), set(filter(None, ok.split(",")))) for m, ok in phone_check.EXPECTED]
    te_t = compare.commons_truths([l for _, l in te])
    vision, text, tok = va.MODELS["S0"]
    X = [va.image_matrix("S0", vision, [p for p, _ in te]), va.image_matrix("S0", vision, [p for p, _ in us]),
         va.image_matrix("S0", vision, ph_paths, exif=True)]

    # Réglage livré précédemment, reconstitué pour comparaison.
    new_people, new_bg = list(CATEGORIES["people"][1]), list(BACKGROUND)
    CATEGORIES["people"] = (CATEGORIES["people"][0], new_people[:7])
    prompts.BACKGROUND[:] = ["a photo of an object", "a close-up photo of a texture", "a photo of a wall",
                             "a close-up photo of a small item", "a photo of tools"]
    va.BACKGROUND = prompts.BACKGROUND
    names, T = va.text_matrix(text, tok); th.cp.NAMES = names
    old_prim = {c: 0.30 for c in CATS}; old_sec = {c: 0.20 for c in CATS}; old_sec["people"] = 0.05
    th.report("livre precedemment", [(n, va.probs(x, T), t) for n, x, t in
              zip(("Commons", "Unsplash", "emulateur"), X, (te_t, us_t, ph_t))], old_prim, old_sec)

    CATEGORIES["people"] = (CATEGORIES["people"][0], new_people)
    prompts.BACKGROUND[:] = new_bg; va.BACKGROUND = new_bg
    names, T = va.text_matrix(text, tok); th.cp.NAMES = names
    prim, sec = settings()
    th.report("nouveau reglage", [(n, va.probs(x, T), t) for n, x, t in
              zip(("Commons", "Unsplash", "emulateur"), X, (te_t, us_t, ph_t))], prim, sec)
    print("\ndetail emulateur (nouveau) :")
    for i, (p, (yes, ok)) in enumerate(zip(va.probs(X[2], T), ph_t)):
        got = th.decide(p, prim, sec)
        bad = [c for c in got if c not in yes | ok]; miss = yes - set(got)
        if bad or miss: print("  photo %2d attendu %-10s obtenu %s" % (i, ",".join(yes) or "(rien)", ",".join(got) or "-"))
    export(names, T)
