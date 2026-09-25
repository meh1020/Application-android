# -*- coding: utf-8 -*-
"""
Règle « chaque catégorie contre le fond » : une catégorie n'est comparée qu'aux classes de fond
(objet, texture, photo quelconque), jamais aux autres catégories. Les catégories ne se volent plus
la probabilité : une montagne enneigée peut être à la fois « Neige » et « Paysages ».

Seuils réglés sur l'entraînement Commons seulement ; mesure sur test Commons, Unsplash, émulateur.
Usage : python ovb.py [S0 S1 S2]
"""
import json, os, sys
import numpy as np
import variants as va
import dataset, unsplash, phone_check, compare
from prompts import CATEGORIES

CATS = list(CATEGORIES)
# Fonds génériques : ce à quoi ressemble « n'importe quelle photo ».
EXTRA_BACKGROUND = ["a photo", "a picture of something", "an image"]

def ovb_scores(X, names, T, scale):
    """Probabilité de chaque catégorie face aux seules classes de fond."""
    bg = [i for i, n in enumerate(names) if n.startswith("_bg")]
    S = scale * X @ T.T
    out = np.zeros((len(X), len(CATS)))
    for k, c in enumerate(CATS):
        i = names.index(c)
        sub = S[:, [i] + bg]
        e = np.exp(sub - sub.max(1, keepdims=True))
        out[:, k] = e[:, 0] / e.sum(1)
    return out

def f05(Q, truths, tau):
    tp = dict.fromkeys(CATS, 0); fp = dict.fromkeys(CATS, 0); fn = dict.fromkeys(CATS, 0)
    for q, (yes, ok) in zip(Q, truths):
        pred = {c for k, c in enumerate(CATS) if q[k] >= tau[c]}
        for c in CATS:
            if c in yes: tp[c] += c in pred; fn[c] += c not in pred
            elif c in pred and c not in ok: fp[c] += 1
    sc, pr, rc = [], [], []
    for c in CATS:
        if tp[c] + fn[c] == 0 and fp[c] == 0: continue
        p = tp[c] / (tp[c] + fp[c]) if tp[c] + fp[c] else 0.0
        r = tp[c] / (tp[c] + fn[c]) if tp[c] + fn[c] else 1.0
        if tp[c] + fp[c]: pr.append(p)
        if tp[c] + fn[c]: rc.append(r)
        sc.append(1.25 * p * r / (0.25 * p + r) if p + r else 0.0)
    return float(np.mean(sc)), float(np.mean(pr)), float(np.mean(rc)), sum(fp.values()), sum(fn.values())

def tune_tau(Q, truths, grid=np.arange(0.3, 0.99, 0.02)):
    """Un seuil par catégorie, réglé indépendamment (descente coordonnée sur le F0.5 moyen)."""
    tau = {c: 0.8 for c in CATS}
    best = f05(Q, truths, tau)[0]
    for _ in range(2):
        for c in CATS:
            keep = tau[c]
            for v in grid:
                tau[c] = float(v); s = f05(Q, truths, tau)[0]
                if s > best + 1e-9: best, keep = s, float(v)
            tau[c] = keep
    return tau

if __name__ == "__main__":
    tr = [(p, l) for p, l, _, _ in dataset.load("train")]
    te = [(p, l) for p, l, _, _ in dataset.load("test")]
    us = unsplash.load()
    lab = json.load(open(os.path.join(unsplash.DIR, "labels.json"), encoding="utf-8"))
    us_t = [(set(lab[os.path.basename(p)[:-4]]["yes"]), set(lab[os.path.basename(p)[:-4]]["ok"])) for p, _ in us]
    ph_ids = sorted(int(f[:-4]) for f in os.listdir(os.path.join(va.HERE, "data", "phone")) if f.endswith(".jpg") and f[:-4].isdigit())
    ph_paths = [os.path.join(va.HERE, "data", "phone", "%d.jpg" % i) for i in ph_ids]
    ph_t = [({m} if m else set(), set(filter(None, ok.split(",")))) for m, ok in phone_check.EXPECTED]
    tr_t, te_t = compare.commons_truths([l for _, l in tr]), compare.commons_truths([l for _, l in te])

    import prompts
    prompts.BACKGROUND[:] = prompts.BACKGROUND + EXTRA_BACKGROUND
    va.BACKGROUND = prompts.BACKGROUND
    for tag in sys.argv[1:] or ["S0"]:
        vision, text, tok = va.MODELS[tag]
        names, T = va.text_matrix(text, tok)
        for scale in (50.0, 100.0):
            Q = {k: ovb_scores(va.image_matrix(tag, vision, paths, exif=(k == "ph")), names, T, scale)
                 for k, paths in (("tr", [p for p, _ in tr]), ("te", [p for p, _ in te]),
                                  ("us", [p for p, _ in us]), ("ph", ph_paths))}
            tau = tune_tau(Q["tr"], tr_t)
            row = "%s echelle %3.0f " % (tag, scale)
            for name, key, t in (("Commons", "te", te_t), ("Unsplash", "us", us_t), ("emulateur", "ph", ph_t)):
                _, p, r, nfp, nfn = f05(Q[key], t, tau)
                row += "| %-9s %4.0f%% %4.0f%% %3d %3d " % (name, 100 * p, 100 * r, nfp, nfn)
            print(row, flush=True)
            if "--tau" in sys.argv: print("   seuils:", {c: round(v, 2) for c, v in tau.items()})
