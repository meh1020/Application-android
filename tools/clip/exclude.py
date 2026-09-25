# -*- coding: utf-8 -*-
"""
Écarte des photos de test repérées sur une planche de review.py.
Usage : python exclude.py <nom de planche> <numéros séparés par des virgules>
Les titres correspondants sont ajoutés à data/excluded.txt.
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))

if __name__ == "__main__":
    name, numbers = sys.argv[1], {int(n) for n in sys.argv[2].split(",") if n.strip()}
    table = open(os.path.join(HERE, "data", "review_%s.txt" % name), encoding="utf-8").read().splitlines()
    titles = [line.split("\t")[3] for line in table if int(line.split("\t")[0]) in numbers]
    path = os.path.join(HERE, "data", "excluded.txt")
    known = set(open(path, encoding="utf-8").read().splitlines()) if os.path.exists(path) else set()
    with open(path, "a", encoding="utf-8") as out:
        for t in titles:
            if t not in known:
                out.write(t + "\n")
    print("%d photos ecartees (%s)" % (len(titles), name))
