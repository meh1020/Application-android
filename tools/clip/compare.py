# -*- coding: utf-8 -*-
"""
Compare les méthodes de classement sur les trois jeux de mesure :
  - Commons test : même style de photos que l'entraînement ;
  - Unsplash    : style d'une galerie de téléphone, étiqueté à la main (attendues | tolérées) ;
  - émulateur   : 32 photos, vecteurs calculés par le téléphone.

Méthodes :
  texte   : descriptions texte seules (catégories en concurrence) ;
  appris  : un classifieur par catégorie appris sur les vecteurs ;
  hybride : le même classifieur, qui reçoit en plus le jugement des descriptions texte ; appris
            avec une forte régularisation, il ne corrige ce jugement que là où les exemples insistent.
"""
import os, sys
import numpy as np
import onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold, cross_val_predict

import dataset, unsplash, phone_check, train
import eval as ev
from prompts import CATEGORIES, PRIMARY, SECONDARY, LOGIT_SCALE

CATS = list(CATEGORIES)
NAMES, T = ev.class_matrix()
TEXT_WEIGHT = 10.0   # le jugement texte, amplifié : sa pondération échappe presque à la régularisation

def text_probs(X):
    L = LOGIT_SCALE * X @ T.T
    P = np.exp(L - L.max(1, keepdims=True)); return P / P.sum(1, keepdims=True)

def text_predict(X):
    return [ev.classify(p, NAMES, PRIMARY, SECONDARY) for p in text_probs(X)]

def text_feature(X, cat):
    """Log-probabilité texte de la catégorie : le point de départ de l'hybride."""
    return TEXT_WEIGHT * np.log(text_probs(X)[:, NAMES.index(cat)] + 1e-6)[:, None] / 10.0

def fit(X, labels, cat, hybrid, C_grid):
    y = np.array([train.target(l, cat) for l in labels], dtype=object)
    m = np.array([v is not None for v in y]); Xc, yc = X[m], y[m].astype(int)
    F = np.hstack([Xc, text_feature(Xc, cat)]) if hybrid else Xc
    best = None
    folds = StratifiedKFold(5, shuffle=True, random_state=7)
    for C in C_grid:
        clf = LogisticRegression(C=C, class_weight="balanced", max_iter=3000)
        p = cross_val_predict(clf, F, yc, cv=folds, method="predict_proba")[:, 1]
        t, f = train.pick_threshold(p, yc)
        if best is None or f > best[0]: best = (f, C, t)
    _, C, t = best
    return LogisticRegression(C=C, class_weight="balanced", max_iter=3000).fit(F, yc), t, C

def predictor(heads, hybrid):
    def predict(X):
        out = [[] for _ in range(len(X))]
        for cat, (clf, t, _) in heads.items():
            F = np.hstack([X, text_feature(X, cat)]) if hybrid else X
            p = clf.predict_proba(F)[:, 1]
            for i in np.where(p >= t)[0]: out[i].append(cat)
        return out
    return predict

def score(preds, truths):
    """truths : [(attendues, tolérées)]. Renvoie précision et rappel moyens (par catégorie) et totaux."""
    tp = dict.fromkeys(CATS, 0); fp = dict.fromkeys(CATS, 0); fn = dict.fromkeys(CATS, 0)
    for pred, (yes, ok) in zip(preds, truths):
        for c in CATS:
            if c in yes:
                tp[c] += c in pred; fn[c] += c not in pred
            elif c in pred and c not in ok:
                fp[c] += 1
    P = [tp[c] / (tp[c] + fp[c]) for c in CATS if tp[c] + fp[c]]
    R = [tp[c] / (tp[c] + fn[c]) for c in CATS if tp[c] + fn[c]]
    return np.mean(P), np.mean(R), sum(fp.values()), sum(fn.values()), (tp, fp, fn)

def commons_truths(labels):
    out = []
    for l in labels:
        ok = set()
        for c in CATS:
            if train.target(l, c) is None: ok.add(c)
        out.append((set(l), ok))
    return out

def embed(paths):
    return np.stack(train.embeddings(paths))

if __name__ == "__main__":
    Xtr, Ltr, _ = train.load("train")
    Xte, Lte, _ = train.load("test")
    us = unsplash.load()
    import json
    lab = json.load(open(os.path.join(unsplash.DIR, "labels.json"), encoding="utf-8"))
    Xus = embed([p for p, _ in us])
    us_truth = [(set(lab[os.path.basename(p)[:-4]]["yes"]), set(lab[os.path.basename(p)[:-4]]["ok"])) for p, _ in us]
    Xph = np.stack(phone_check.device_embeddings())
    ph_truth = [({m} if m else set(), set(filter(None, ok.split(",")))) for m, ok in phone_check.EXPECTED]
    sets = [("Commons test", Xte, commons_truths(Lte)), ("Unsplash", Xus, us_truth), ("emulateur", Xph, ph_truth)]

    methods = {"texte": text_predict}
    for name, hybrid, grid in (("appris", False, (0.5, 1, 2, 4, 8)),
                               ("hybride", True, (0.05, 0.1, 0.2, 0.5))):
        heads = {c: fit(Xtr, Ltr, c, hybrid, grid) for c in CATS}
        methods[name] = predictor(heads, hybrid)
        if name == "hybride": hybrid_heads = heads
    print("%-9s" % "" + "".join("| %-28s" % s for s, _, _ in sets))
    print("%-9s" % "methode" + "| precision rappel intrus oublis " * len(sets))
    for name, pred in methods.items():
        row = "%-9s" % name
        for _, X, truth in sets:
            p, r, nfp, nfn, _ = score(pred(X), truth)
            row += "|   %4.0f%%  %4.0f%%  %5d  %5d   " % (100 * p, 100 * r, nfp, nfn)
        print(row)
    if "--detail" in sys.argv:
        for sname, X, truth in sets[1:]:
            for name in ("texte", "hybride"):
                _, _, _, _, (tp, fp, fn) = score(methods[name](X), truth)
                print("\n%s / %s :" % (sname, name), "  ".join("%s %d/%d/%d" % (c, tp[c], fp[c], fn[c]) for c in CATS if tp[c] + fp[c] + fn[c]))
