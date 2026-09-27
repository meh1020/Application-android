# -*- coding: utf-8 -*-
"""
Compare la recherche actuelle (mots-clés ML Kit, calculés sur l'émulateur) et la recherche par
MobileCLIP, sur les mêmes photos de test et les mêmes requêtes françaises. Les deux utilisent ce
que le téléphone a réellement calculé (data/phone_eval/, voir README).

Usage : python search_eval.py [--detail]
"""
import io, json, os, re, struct, sys
import numpy as np
import concepts as cc, dataset

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, "data", "phone_eval")

def load_device():
    raw = open(os.path.join(D, "device.bin"), "rb").read()
    n = struct.unpack(">i", raw[:4])[0]; off = 4; out = {}
    for _ in range(n):
        pid, dim = struct.unpack(">qi", raw[off:off + 12]); off += 12
        out[pid] = np.array(struct.unpack(">%df" % dim, raw[off:off + 4 * dim]), dtype=np.float32); off += 4 * dim
    return out

def mlkit_dictionary():
    """Le dictionnaire tel que l'app l'utilise aujourd'hui : mot-clé anglais -> termes français."""
    return json.load(io.open(os.path.join(HERE, "vocabulary.json"), encoding="utf-8"))

def mlkit_matches(labels, query, dico):
    """Reproduit SearchIndex.matches : un terme (mot-clé ou traduction) contient la requête."""
    q = cc.normalize(query)
    return any(q in cc.normalize(t) for l in labels for t in [l["l"]] + dico.get(l["l"], []))

def rule_top1(S, k):
    return S[:, k] >= S.max(1)

def singular(q):
    """Comme PhotoLabels.singular."""
    return " ".join(w[:-1] if len(w) > 3 and w[-1] in "sx" else w for w in q.split(" "))

def main_subject(title, q):
    """Comme PhotoLabels.isMainSubject : la recherche désigne le premier sujet de la catégorie."""
    return len(q) >= 3 and singular(cc.normalize(title.split("&")[0])).startswith(singular(q))

if __name__ == "__main__":
    names = {}
    for line in open(os.path.join(D, "names.txt"), encoding="utf-8"):
        m = re.search(r"_id=(\d+), _display_name=(.+)$", line.strip())
        if m: names[m.group(2)] = int(m.group(1))
    labels = json.load(open(os.path.join(D, "labels.json"), encoding="utf-8"))["items"]
    emb = load_device()
    test = [(os.path.basename(p), s) for p, _, s, _ in dataset.load("test")]
    test = [(f, s, names[f]) for f, s in test if f in names]
    print("%d photos de test presentes sur l'emulateur" % len(test))

    vocab = cc.vocabulary(); concepts = sorted(vocab); C = cc.text_vectors(concepts)
    X = np.stack([emb[i] for _, _, i in test]); S = X @ C.T
    src = [s for _, s, _ in test]
    dico = mlkit_dictionary()
    import variants as va, export, thresholds as th
    from prompts import CATEGORIES
    names_c, Tc = va.text_matrix(*va.MODELS["S0"][1:]); th.cp.NAMES = names_c
    prim, sec = export.settings()
    cats = [th.decide(p, prim, sec) for p in va.probs(X, Tc)]

    rows = []; totals = {"mlkit": [0, 0, 0], "clip": [0, 0, 0]}
    per_query = {"mlkit": ([], []), "clip": ([], [])}
    for c, pos_src in cc.TRUTH.items():
        query = vocab[c][0]
        pos = np.array([s in pos_src for s in src]); judged = ~np.array([s in cc.MAYBE.get(c, []) for s in src])
        if pos.sum() == 0: continue
        hits = {
            "mlkit": np.array([mlkit_matches(labels.get(str(i), []), query, dico) for _, _, i in test]),
            "clip": np.zeros(len(test), bool),
        }
        q = cc.normalize(query)
        ks = [k for k, c in enumerate(concepts) if any(cc.normalize(t).startswith(q) or cc.normalize(t).startswith(singular(q)) for t in vocab[c])]
        for k in ks: hits["clip"] |= rule_top1(S, k)
        # Catégorie d'Explorer désignée par la recherche (comme dans l'app).
        linked = {key for key, (title, _) in CATEGORIES.items() if main_subject(title, q)}
        if linked: hits["clip"] |= np.array([bool(set(c) & linked) for c in cats])
        row = [query, int(pos.sum())]
        for m in ("mlkit", "clip"):
            h = hits[m]; tp = int((h & pos).sum()); fp = int((h & ~pos & judged).sum()); fn = int(pos.sum()) - tp
            totals[m][0] += tp; totals[m][1] += fp; totals[m][2] += fn
            per_query[m][0].append(tp / (tp + fp) if tp + fp else 1.0); per_query[m][1].append(tp / pos.sum())
            row += [tp, fp, fn]
        rows.append(row)
    for m, label in (("mlkit", "ML Kit (actuel)"), ("clip", "MobileCLIP")):
        tp, fp, fn = totals[m]
        print("%-16s precision moyenne %3.0f%%  rappel moyen %3.0f%%  | au total %4d trouvees, %4d intrus, %4d oubliees"
              % (label, 100 * np.mean(per_query[m][0]), 100 * np.mean(per_query[m][1]), tp, fp, fn))
    if "--detail" in sys.argv:
        print("\n%-18s %4s | %-18s | %-18s" % ("requete", "n", "ML Kit trouv/intr/oubl", "CLIP trouv/intr/oubl"))
        for q, n, a, b, c_, d, e, f in rows:
            print("%-18s %4d |   %3d %3d %3d        |   %3d %3d %3d" % (q, n, a, b, c_, d, e, f))
