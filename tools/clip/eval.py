# -*- coding: utf-8 -*-
"""Mesure la précision du classement MobileCLIP sur le jeu de test étiqueté."""
import glob, json, os, sys
import numpy as np
import onnxruntime as ort
from PIL import Image
from tokenizers import Tokenizer

from prompts import CATEGORIES, BACKGROUND, COMPATIBLE

HERE = os.path.dirname(os.path.abspath(__file__))
VISION = os.path.join(HERE, sys.argv[1] if len(sys.argv) > 1 else "vision_model_int8.onnx")

def preprocess(path):
    """Identique à preprocessor_config.json : bord court à 256, recadrage central, /255."""
    img = Image.open(path).convert("RGB")
    w, h = img.size
    scale = 256 / min(w, h)
    img = img.resize((max(256, round(w * scale)), max(256, round(h * scale))), Image.BILINEAR)
    w, h = img.size
    left, top = (w - 256) // 2, (h - 256) // 2
    img = img.crop((left, top, left + 256, top + 256))
    return (np.asarray(img, dtype=np.float32) / 255.0).transpose(2, 0, 1)

def normalize(x):
    return x / np.linalg.norm(x, axis=-1, keepdims=True)

def text_embeddings(prompts):
    tok = Tokenizer.from_file(os.path.join(HERE, "tokenizer.json"))
    ids = np.zeros((len(prompts), 77), dtype=np.int64)
    for i, p in enumerate(prompts):
        e = tok.encode(p.lower()).ids[:77]
        ids[i, :len(e)] = e
    s = ort.InferenceSession(os.path.join(HERE, "text_model.onnx"), providers=["CPUExecutionProvider"])
    return normalize(s.run(None, {"input_ids": ids})[0])

def class_matrix():
    """Un vecteur par catégorie : moyenne des descriptions (plus robuste qu'une seule phrase)."""
    names, vectors = [], []
    for key, (title, prompts) in list(CATEGORIES.items()) + [("_bg", ("", BACKGROUND))]:
        emb = text_embeddings(prompts)
        if key == "_bg":
            for j, p in enumerate(prompts):   # le fond garde une classe par phrase
                names.append("_bg%d" % j); vectors.append(emb[j])
        else:
            names.append(key); vectors.append(normalize(emb.mean(axis=0)))
    return names, np.stack(vectors)

def image_embeddings():
    cache = os.path.join(HERE, "cache_" + os.path.basename(VISION) + ".npz")
    files = sorted(glob.glob(os.path.join(HERE, "dataset", "*", "*.jpg")))
    if os.path.exists(cache):
        c = np.load(cache, allow_pickle=True)
        if list(c["files"]) == files:
            return files, c["emb"]
    s = ort.InferenceSession(VISION, providers=["CPUExecutionProvider"])
    embs = []
    for f in files:
        try:
            embs.append(s.run(None, {"pixel_values": preprocess(f)[None]})[0][0])
        except Exception:
            embs.append(np.zeros(512, dtype=np.float32))
    embs = normalize(np.stack(embs) + 1e-9)
    np.savez(cache, files=np.array(files), emb=embs)
    return files, embs

def classify(probs, names, primary, secondary, by_class=None):
    """
    Catégorie principale si assez probable, puis chaque autre catégorie au-dessus de son propre
    seuil secondaire. Rien si l'image ressemble d'abord à une classe de fond.
    """
    if by_class is None:
        from prompts import SECONDARY_BY_CLASS as by_class
    order = np.argsort(-probs)
    top = order[0]
    if names[top].startswith("_bg") or probs[top] < primary:
        return []
    out = [names[top]]
    for i in order[1:]:
        if names[i].startswith("_bg"): continue
        if probs[i] >= by_class.get(names[i], secondary): out.append(names[i])
    return out

def evaluate(primary=0.35, secondary=0.2, scale=100.0, verbose=False):
    names, T = class_matrix.cache if hasattr(class_matrix, "cache") else class_matrix()
    class_matrix.cache = (names, T)
    files, E = image_embeddings()
    logits = scale * E @ T.T
    probs = np.exp(logits - logits.max(axis=1, keepdims=True))
    probs /= probs.sum(axis=1, keepdims=True)
    cats = list(CATEGORIES)
    tp = {c: 0 for c in cats}; fp = {c: 0 for c in cats}; fn = {c: 0 for c in cats}
    errors = []
    for f, p in zip(files, probs):
        truth = os.path.basename(os.path.dirname(f))
        pred = classify(p, names, primary, secondary)
        ok_extra = COMPATIBLE.get(truth, set())
        if truth in pred: tp[truth] += 1
        else: fn[truth] += 1
        for c in pred:
            if c != truth and c not in ok_extra:
                fp[c] += 1
                errors.append((truth, c, os.path.relpath(f, HERE)))
    return cats, tp, fp, fn, errors

if __name__ == "__main__":
    primary = float(sys.argv[2]) if len(sys.argv) > 2 else 0.35
    secondary = float(sys.argv[3]) if len(sys.argv) > 3 else 0.2
    cats, tp, fp, fn, errors = evaluate(primary, secondary)
    print("%-12s %9s %8s %6s" % ("categorie", "precision", "rappel", "n"))
    P = R = 0
    for c in cats:
        prec = tp[c] / (tp[c] + fp[c]) if tp[c] + fp[c] else 1.0
        rec = tp[c] / (tp[c] + fn[c]) if tp[c] + fn[c] else 0.0
        P += prec; R += rec
        print("%-12s %8.0f%% %7.0f%% %6d" % (c, 100 * prec, 100 * rec, tp[c] + fn[c]))
    print("%-12s %8.0f%% %7.0f%%" % ("MOYENNE", 100 * P / len(cats), 100 * R / len(cats)))
    print("\nErreurs (verite -> rangee a tort dans):")
    for t, c, f in errors:
        print("  %-11s -> %-11s %s" % (t, c, f))
