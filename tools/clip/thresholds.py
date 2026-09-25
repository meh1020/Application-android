# -*- coding: utf-8 -*-
"""
Seuils propres à chaque catégorie pour le classement par descriptions texte.

Réglage : entraînement Commons + une moitié d'Unsplash. Mesure : test Commons, l'autre moitié
d'Unsplash, émulateur ; puis les moitiés sont inversées. Le réglage final utilise tout Unsplash.
Objectif : F0.5 moyen (la précision compte double : un intrus gêne plus qu'un oubli).

Usage : python thresholds.py            -> validation croisée et réglage final
        python thresholds.py --export   -> écrit aussi le réglage final dans prompts_thresholds.json
"""
import json, os, sys
import numpy as np
import compare as cp
import train, unsplash, phone_check
from prompts import PRIMARY, SECONDARY, SECONDARY_BY_CLASS

HERE = os.path.dirname(os.path.abspath(__file__))
CATS = cp.CATS
PRIMARY_GRID = (0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
SECONDARY_GRID = (0.03, 0.05, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5)

def decide(probs, prim, sec):
    """Catégorie principale au-dessus de son seuil principal, puis chaque autre au-dessus de son seuil."""
    out = []
    order = np.argsort(-probs)
    top = order[0]
    name = cp.NAMES[top]
    if name.startswith("_bg") or probs[top] < prim[name]:
        return out
    out.append(name)
    for i in order[1:]:
        n = cp.NAMES[i]
        if not n.startswith("_bg") and probs[i] >= sec[n]:
            out.append(n)
    return out

def f05(P, truths, prim, sec):
    tp = dict.fromkeys(CATS, 0); fp = dict.fromkeys(CATS, 0); fn = dict.fromkeys(CATS, 0)
    for p, (yes, ok) in zip(P, truths):
        pred = decide(p, prim, sec)
        for c in CATS:
            if c in yes:
                tp[c] += c in pred; fn[c] += c not in pred
            elif c in pred and c not in ok:
                fp[c] += 1
    scores, precs, recs = [], [], []
    for c in CATS:
        if tp[c] + fn[c] == 0 and fp[c] == 0: continue
        p = tp[c] / (tp[c] + fp[c]) if tp[c] + fp[c] else 0.0
        r = tp[c] / (tp[c] + fn[c]) if tp[c] + fn[c] else 1.0
        if tp[c] + fp[c]: precs.append(p)
        if tp[c] + fn[c]: recs.append(r)
        scores.append(1.25 * p * r / (0.25 * p + r) if p + r else 0.0)
    return float(np.mean(scores)), float(np.mean(precs)), float(np.mean(recs)), sum(fp.values()), sum(fn.values())

def tune(sets):
    """Descente coordonnée sur les seuils de chaque catégorie ; [sets] = [(probas, vérités)]."""
    prim = {c: PRIMARY for c in CATS}
    sec = {c: SECONDARY_BY_CLASS.get(c, SECONDARY) for c in CATS}
    objective = lambda: sum(f05(P, t, prim, sec)[0] for P, t in sets)
    best = objective()
    for _ in range(3):
        for c in CATS:
            for table, grid in ((prim, PRIMARY_GRID), (sec, SECONDARY_GRID)):
                keep = table[c]
                for v in grid:
                    table[c] = v
                    s = objective()
                    if s > best + 1e-9: best, keep = s, v
                table[c] = keep
    return prim, sec

def report(label, sets, prim, sec):
    row = "%-22s" % label
    for name, P, t in sets:
        _, p, r, nfp, nfn = f05(P, t, prim, sec)
        row += "| %-9s %4.0f%% %4.0f%% %3d %3d " % (name, 100 * p, 100 * r, nfp, nfn)
    print(row)

if __name__ == "__main__":
    Xtr, Ltr, _ = train.load("train"); Xte, Lte, _ = train.load("test")
    us = unsplash.load()
    lab = json.load(open(os.path.join(unsplash.DIR, "labels.json"), encoding="utf-8"))
    Xus = cp.embed([p for p, _ in us])
    us_t = [(set(lab[os.path.basename(p)[:-4]]["yes"]), set(lab[os.path.basename(p)[:-4]]["ok"])) for p, _ in us]
    Xph = np.stack(phone_check.device_embeddings())
    ph_t = [({m} if m else set(), set(filter(None, ok.split(",")))) for m, ok in phone_check.EXPECTED]
    Ptr, Pte, Pus, Pph = (cp.text_probs(X) for X in (Xtr, Xte, Xus, Xph))
    tr_t, te_t = cp.commons_truths(Ltr), cp.commons_truths(Lte)
    halves = [np.arange(len(us_t)) % 2 == k for k in (0, 1)]

    base_prim = {c: PRIMARY for c in CATS}
    base_sec = {c: SECONDARY_BY_CLASS.get(c, SECONDARY) for c in CATS}
    print("%-22s| %-26s | %-26s | %-26s" % ("", "jeu  prec rapp intrus oublis", "", ""))
    for k, fit in enumerate(halves):
        held = ~fit
        sets_fit = [(Ptr, tr_t), (Pus[fit], [t for t, m in zip(us_t, fit) if m])]
        prim, sec = tune(sets_fit)
        sets_eval = [("Commons", Pte, te_t), ("Unsplash", Pus[held], [t for t, m in zip(us_t, held) if m]), ("emulateur", Pph, ph_t)]
        report("moitie %d : actuel" % (k + 1), sets_eval, base_prim, base_sec)
        report("moitie %d : par categ." % (k + 1), sets_eval, prim, sec)

    prim, sec = tune([(Ptr, tr_t), (Pus, us_t)])
    print("\nReglage final (entrainement Commons + tout Unsplash) :")
    report("actuel", [("Commons", Pte, te_t), ("emulateur", Pph, ph_t)], base_prim, base_sec)
    report("par categorie", [("Commons", Pte, te_t), ("emulateur", Pph, ph_t)], prim, sec)
    changed = {c: (prim[c], sec[c]) for c in CATS if prim[c] != PRIMARY or sec[c] != SECONDARY_BY_CLASS.get(c, SECONDARY)}
    print("seuils modifies (principal, secondaire) :", changed)
    if "--export" in sys.argv:
        json.dump({"primary": prim, "secondary": sec}, open(os.path.join(HERE, "prompts_thresholds.json"), "w"), indent=1)
        print("ecrit : prompts_thresholds.json")
