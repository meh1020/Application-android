# -*- coding: utf-8 -*-
"""
Saisie des étiquettes du jeu Unsplash. Chaque ligne : « numéro: attendues | tolérées », par
exemple « 7: people | landscape ». Les catégories attendues doivent être trouvées ; les tolérées
ne comptent ni comme erreur ni comme oubli. Usage : python label.py < lignes
"""
import json, os, sys
import unsplash
from prompts import CATEGORIES

path = os.path.join(unsplash.DIR, "labels.json")
labels = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
order = unsplash.ids()
for line in sys.stdin:
    line = line.strip()
    if not line or ":" not in line:
        continue
    idx, rest = line.split(":", 1)
    yes, _, ok = rest.partition("|")
    parse = lambda s: [c.strip() for c in s.split(",") if c.strip()]
    y, o = parse(yes), parse(ok)
    unknown = [c for c in y + o if c not in CATEGORIES]
    if unknown:
        sys.exit("categorie inconnue %s (ligne %s)" % (unknown, line))
    labels[order[int(idx)]] = {"yes": y, "ok": o}
json.dump(labels, open(path, "w", encoding="utf-8"), indent=0)
print("%d photos etiquetees" % len(labels))
