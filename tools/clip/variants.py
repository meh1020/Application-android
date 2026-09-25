# -*- coding: utf-8 -*-
"""
Compare MobileCLIP S0 (livré), S1 et S2 en classement par descriptions texte, sur les trois jeux :
test Commons, Unsplash (style téléphone) et photos de l'émulateur. Mêmes descriptions, mêmes
classes de fond, même règle ; les seuils globaux de chaque modèle sont réglés sur l'entraînement
Commons seulement.
"""
import json, os, sys
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageOps
from tokenizers import Tokenizer

import dataset, unsplash, phone_check, train, compare
import eval as ev
from prompts import CATEGORIES, BACKGROUND, SECONDARY_BY_CLASS
import thresholds as th

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = {
    "S0": (os.path.join(HERE, "vision_wq8.onnx"), os.path.join(HERE, "text_model.onnx"), os.path.join(HERE, "tokenizer.json")),
    "S1": tuple(os.path.join(HERE, "variants", "s1", f) for f in ("vision_model.onnx", "text_model.onnx", "tokenizer.json")),
    "S2": tuple(os.path.join(HERE, "variants", "s2", f) for f in ("vision_model.onnx", "text_model.onnx", "tokenizer.json")),
}
CATS = list(CATEGORIES)

def text_matrix(text_model, tokenizer):
    tok = Tokenizer.from_file(tokenizer)
    sess = ort.InferenceSession(text_model, providers=["CPUExecutionProvider"])
    def enc(prompts):
        ids = np.zeros((len(prompts), 77), dtype=np.int64)
        for i, p in enumerate(prompts):
            e = tok.encode(p.lower()).ids[:77]; ids[i, :len(e)] = e
        return ev.normalize(sess.run(None, {"input_ids": ids})[0])
    names, vecs = [], []
    for key, (_, prompts) in CATEGORIES.items():
        names.append(key); vecs.append(ev.normalize(enc(prompts).mean(0)))
    for j, v in enumerate(enc(BACKGROUND)):
        names.append("_bg%d" % j); vecs.append(v)
    return names, np.stack(vecs)

def image_matrix(tag, vision, paths, exif=False):
    cache_path = os.path.join(HERE, "data", "emb_%s.npz" % tag)
    cache = dict(np.load(cache_path, allow_pickle=True)["items"].item()) if os.path.exists(cache_path) else {}
    todo = [p for p in paths if p not in cache]
    if todo:
        sess = ort.InferenceSession(vision, providers=["CPUExecutionProvider"])
        for k, p in enumerate(todo):
            src = p
            if exif:  # le téléphone décode avec la rotation EXIF appliquée
                img = ImageOps.exif_transpose(Image.open(p)).convert("RGB"); src = p + ".upright.jpg"; img.save(src, quality=95)
            e = sess.run(None, {"pixel_values": ev.preprocess(src)[None]})[0][0]
            cache[p] = (e / np.linalg.norm(e)).astype(np.float32)
            if k % 500 == 0: print("   %s : %d / %d" % (tag, k, len(todo)), flush=True)
        np.savez(cache_path, items=np.array(cache, dtype=object))
    return np.stack([cache[p] for p in paths])

def probs(X, T, scale=100.0):
    L = scale * X @ T.T; P = np.exp(L - L.max(1, keepdims=True)); return P / P.sum(1, keepdims=True)

if __name__ == "__main__":
    tr = [(p, l) for p, l, _, _ in dataset.load("train")]
    te = [(p, l) for p, l, _, _ in dataset.load("test")]
    us = unsplash.load()
    lab = json.load(open(os.path.join(unsplash.DIR, "labels.json"), encoding="utf-8"))
    us_t = [(set(lab[os.path.basename(p)[:-4]]["yes"]), set(lab[os.path.basename(p)[:-4]]["ok"])) for p, _ in us]
    ph_ids = sorted(int(f[:-4]) for f in os.listdir(os.path.join(HERE, "data", "phone")) if f.endswith(".jpg") and f[:-4].isdigit())
    ph_paths = [os.path.join(HERE, "data", "phone", "%d.jpg" % i) for i in ph_ids]
    ph_t = [({m} if m else set(), set(filter(None, ok.split(",")))) for m, ok in phone_check.EXPECTED]
    tr_t, te_t = compare.commons_truths([l for _, l in tr]), compare.commons_truths([l for _, l in te])

    for tag in sys.argv[1:] or MODELS:
        vision, text, tok = MODELS[tag]
        names, T = text_matrix(text, tok)
        th.cp.NAMES = names  # la règle de décision lit les noms de classes ici
        Ptr = probs(image_matrix(tag, vision, [p for p, _ in tr]), T)
        Pte = probs(image_matrix(tag, vision, [p for p, _ in te]), T)
        Pus = probs(image_matrix(tag, vision, [p for p, _ in us]), T)
        Pph = probs(image_matrix(tag, vision, ph_paths, exif=True), T)
        # Seuils globaux réglés sur l'entraînement Commons (personnes : seuil secondaire à part).
        best = None
        for pr in (0.2, 0.3, 0.4, 0.5):
            for se in (0.1, 0.15, 0.2, 0.25, 0.3):
                for sp in (0.03, 0.05, 0.1):
                    prim = {c: pr for c in CATS}; sec = {c: se for c in CATS}; sec["people"] = sp
                    f = th.f05(Ptr, tr_t, prim, sec)[0]
                    if best is None or f > best[0]: best = (f, pr, se, sp)
        _, pr, se, sp = best
        prim = {c: pr for c in CATS}; sec = {c: se for c in CATS}; sec["people"] = sp
        th.report("%s (%.2f/%.2f/%.2f)" % (tag, pr, se, sp),
                  [("Commons", Pte, te_t), ("Unsplash", Pus, us_t), ("emulateur", Pph, ph_t)], prim, sec)
