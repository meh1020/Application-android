# -*- coding: utf-8 -*-
"""
Apprend un classifieur par catégorie sur les vecteurs MobileCLIP, puis le mesure sur les photos
de test (jamais vues à l'entraînement), à côté du classement par descriptions texte utilisé jusque-là.

Pourquoi : avec des descriptions texte, les catégories se font concurrence (« une femme en canoë » :
la scène « canoë » l'emporte et « Personnes » disparaît). Un classifieur par catégorie répond seul à
« y en a-t-il ? », et apprend de contre-exemples (une vue de mer sans bateau, un désert blanc).

Usage : python train.py            -> mesure et comparaison
        python train.py --export   -> écrit aussi les fichiers de l'app
"""
import os, struct, sys
import numpy as np
import onnxruntime as ort
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold, cross_val_predict

import dataset
import eval as ev
from prompts import CATEGORIES, COMPATIBLE, MEMORY_NAMES

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "vision_wq8.onnx")          # celui livré dans l'app
CACHE = os.path.join(HERE, "data", "embeddings.npz")
ASSETS = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "clip")
CATS = list(CATEGORIES)

# Précision visée par catégorie : une photo absente d'un rayon gêne moins qu'un intrus.
TARGET_PRECISION = 0.92
C_GRID = (0.5, 1.0, 2.0, 4.0, 8.0)
MIN_POSITIVES = 8

def embeddings(paths):
    cache = dict(np.load(CACHE, allow_pickle=True)["items"].item()) if os.path.exists(CACHE) else {}
    todo = [p for p in paths if os.path.basename(p) not in cache]
    if todo:
        s = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
        for k, p in enumerate(todo):
            try:
                e = s.run(None, {"pixel_values": ev.preprocess(p)[None]})[0][0]
                cache[os.path.basename(p)] = (e / np.linalg.norm(e)).astype(np.float32)
            except Exception:
                cache[os.path.basename(p)] = None
            if k % 200 == 0:
                print("  vecteurs : %d / %d" % (k, len(todo)), flush=True)
        np.savez(CACHE, items=np.array(cache, dtype=object))
    return [cache[os.path.basename(p)] for p in paths]

def load(split):
    items = [(p, labels, src) for p, labels, src, _ in dataset.load(split)]
    embs = embeddings([p for p, _, _ in items])
    keep = [i for i, e in enumerate(embs) if e is not None]
    return (np.stack([embs[i] for i in keep]), [items[i][1] for i in keep], [items[i][2] for i in keep])

def target(labels, cat):
    """1 : la catégorie est présente ; 0 : absente ; None : indécidable (ignorée pour cette catégorie)."""
    if cat in labels:
        return 1
    if any(cat in COMPATIBLE.get(l, set()) for l in labels):
        return None     # une plage peut montrer quelqu'un : ni exemple ni contre-exemple de « Personnes »
    return 0

def fit_head(X, labels, cat):
    y = np.array([target(l, cat) for l in labels], dtype=object)
    mask = np.array([v is not None for v in y])
    Xc, yc = X[mask], y[mask].astype(int)
    best = None
    folds = StratifiedKFold(n_splits=5, shuffle=True, random_state=7)
    for C in C_GRID:
        clf = LogisticRegression(C=C, class_weight="balanced", max_iter=2000)
        proba = cross_val_predict(clf, Xc, yc, cv=folds, method="predict_proba")[:, 1]
        threshold, f = pick_threshold(proba, yc)
        if best is None or f > best[0]:
            best = (f, C, threshold)
    _, C, threshold = best
    clf = LogisticRegression(C=C, class_weight="balanced", max_iter=2000).fit(Xc, yc)
    return clf, threshold, C, int(yc.sum()), int((1 - yc).sum())

def pick_threshold(proba, y):
    """Seuil le plus bas qui tient la précision visée (sinon celui du meilleur F0.5)."""
    best = (0.5, -1.0)
    for t in np.arange(0.30, 0.96, 0.01):
        pred = proba >= t
        tp = int((pred & (y == 1)).sum()); fp = int((pred & (y == 0)).sum()); fn = int((~pred & (y == 1)).sum())
        if tp == 0: continue
        p, r = tp / (tp + fp), tp / (tp + fn)
        f = 1.25 * p * r / (0.25 * p + r)
        score = f + (1.0 if p >= TARGET_PRECISION else 0.0)   # tenir la précision d'abord
        if score > best[1]: best = (float(t), score)
    return best

def report(name, predict, X, labels):
    tp = dict.fromkeys(CATS, 0); fp = dict.fromkeys(CATS, 0); fn = dict.fromkeys(CATS, 0)
    for x, l in zip(X, labels):
        pred = set(predict(x))
        for c in CATS:
            t = target(l, c)
            if t == 1:
                tp[c] += c in pred; fn[c] += c not in pred
            elif t == 0 and c in pred:
                fp[c] += 1
    prec = {c: tp[c] / (tp[c] + fp[c]) if tp[c] + fp[c] else float("nan") for c in CATS}
    rec = {c: tp[c] / (tp[c] + fn[c]) if tp[c] + fn[c] else float("nan") for c in CATS}
    return prec, rec, tp, fn

def zero_shot_predictor():
    names, T = ev.class_matrix()
    from prompts import PRIMARY, SECONDARY, LOGIT_SCALE
    def predict(x):
        L = LOGIT_SCALE * x @ T.T; P = np.exp(L - L.max()); P /= P.sum()
        return ev.classify(P, names, PRIMARY, SECONDARY)
    return predict

def phone_report(heads):
    """Les 32 photos de l'émulateur, avec les vecteurs calculés par le téléphone lui-même."""
    import phone_check as pc
    names, T = ev.class_matrix()
    from prompts import PRIMARY, SECONDARY, LOGIT_SCALE
    def text_pred(v):
        L = LOGIT_SCALE * v @ T.T; P = np.exp(L - L.max()); P /= P.sum()
        return ev.classify(P, names, PRIMARY, SECONDARY)
    def learned_pred(v):
        return [c for c, (clf, t, *_r) in heads.items() if clf.predict_proba(v[None])[0, 1] >= t]
    print()
    print("Photos de l'emulateur (vecteurs du telephone) :")
    for name, pred in (("texte", text_pred), ("appris", learned_pred)):
        intrus = rates = 0; detail = []
        for k, (v, (main, ok)) in enumerate(zip(pc.device_embeddings(), pc.EXPECTED)):
            got = pred(v)
            allowed = {main} | set(filter(None, ok.split(",")))
            bad = [c for c in got if c not in allowed]
            miss = bool(main) and main not in got
            intrus += len(bad); rates += miss
            if bad or miss:
                detail.append("photo %d: %s -> %s" % (k, main or "(rien)", ",".join(got) or "-"))
        print("  %-7s %d intrus, %d manquees  %s" % (name, intrus, rates, " | ".join(detail)))

def export(heads):
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "classes.bin"), "wb") as out:
        out.write(struct.pack(">iii", 2, len(heads), 512))          # version 2 : un classifieur par catégorie
        for cat, (clf, threshold, *_rest) in heads.items():
            for text in (cat, CATEGORIES[cat][0], MEMORY_NAMES.get(cat, "")):
                data = text.encode("utf-8"); out.write(struct.pack(">H", len(data))); out.write(data)
            out.write(struct.pack(">ff", float(clf.intercept_[0]), threshold))
            out.write(struct.pack(">512f", *clf.coef_[0].astype(np.float32)))
    print("ecrit :", os.path.normpath(os.path.join(ASSETS, "classes.bin")))

if __name__ == "__main__":
    Xtr, Ltr, _ = load("train")
    Xte, Lte, _ = load("test")
    print("photos : %d d'entrainement, %d de test" % (len(Xtr), len(Xte)))
    heads = {}
    for c in CATS:
        y = [target(l, c) for l in Ltr]
        if y.count(1) < MIN_POSITIVES or y.count(0) < MIN_POSITIVES:
            print("  (%s : trop peu d'exemples ou de contre-exemples, categorie ignoree)" % c)
            continue
        heads[c] = fit_head(Xtr, Ltr, c)
    def lr_predict(x):
        return [c for c, (clf, t, *_r) in heads.items() if clf.predict_proba(x[None])[0, 1] >= t]
    zs = report("descriptions", zero_shot_predictor(), Xte, Lte)
    lr = report("classifieurs", lr_predict, Xte, Lte)
    print("\n%-12s %5s %5s | %-17s | %-17s" % ("categorie", "ex+", "ex-", "texte  prec  rapp", "appris prec  rapp"))
    for c in heads:
        _, t, C, npos, nneg = heads[c]
        print("%-12s %5d %5d | %9.0f%% %5.0f%% | %9.0f%% %5.0f%%   (C=%g, seuil %.2f)" % (
            c, npos, nneg, 100 * zs[0][c], 100 * zs[1][c], 100 * lr[0][c], 100 * lr[1][c], C, t))
    for name, (p, r, _, _) in (("texte", zs), ("appris", lr)):
        print("MOYENNE %-7s precision %.0f%%  rappel %.0f%%" % (name, 100 * np.nanmean(list(p.values())), 100 * np.nanmean(list(r.values()))))
    phone_report(heads)
    if "--export" in sys.argv:
        export(heads)
