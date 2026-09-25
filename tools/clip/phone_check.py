# -*- coding: utf-8 -*-
"""
Second test : les 32 photos de l'émulateur, étiquetées à la main, avec les vecteurs calculés par le
téléphone lui-même. Pour chaque photo : catégories attendues (la première doit apparaître) et
catégories tolérées ; toute autre catégorie attribuée est un intrus.
"""
import os, struct
import numpy as np
import eval as ev

# Ordre : identifiants MediaStore croissants (celui de device_sheet.jpg).
EXPECTED = [
    ("landscape", ""), ("landscape", ""), ("landscape", ""), ("landscape", ""),
    ("landscape", "sunset"), ("landscape", ""), ("snow", "landscape"), ("landscape", ""),
    ("landscape", ""), ("people", "flowers,fashion"), ("people", "fashion"), ("people", "art"),
    ("people", "sunset"), ("people", "beach"), ("people", ""), ("people", "boats,landscape"),
    ("animals", ""), ("animals", ""), ("animals", ""), ("people", "landscape,sport"),
    ("people", "beach"), ("", "electronics"), ("people", "art"), ("people", "city,vehicles"),
    ("animals", ""), ("landscape", ""), ("landscape", ""), ("landscape", ""),
    ("animals", ""), ("landscape", ""), ("landscape", ""), ("landscape", ""),
]

def device_embeddings(path=os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "phone", "device.bin")):
    raw = open(path, "rb").read()
    n = struct.unpack(">i", raw[:4])[0]; off = 4; out = {}
    for _ in range(n):
        pid, dim = struct.unpack(">qi", raw[off:off + 12]); off += 12
        out[pid] = np.array(struct.unpack(">%df" % dim, raw[off:off + 4 * dim]), dtype=np.float32)
        off += 4 * dim
    return [v for _, v in sorted(out.items())]

def check(names, T, primary, secondary, scale=100.0, verbose=False):
    intrus = rates = 0
    for k, (v, (main, ok)) in enumerate(zip(device_embeddings(), EXPECTED)):
        L = scale * v @ T.T; P = np.exp(L - L.max()); P /= P.sum()
        pred = ev.classify(P, names, primary, secondary)
        allowed = {main} | set(filter(None, ok.split(",")))
        bad = [c for c in pred if c not in allowed]
        miss = bool(main) and main not in pred
        intrus += len(bad); rates += miss
        if verbose and (bad or miss):
            print("  photo %2d attendu %-10s obtenu %-24s %s" % (k, main or "(rien)", ",".join(pred) or "-",
                  ("intrus: " + ",".join(bad)) if bad else "manquee"))
    return intrus, rates
