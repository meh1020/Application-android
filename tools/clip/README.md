# Classement des photos par MobileCLIP — outils

Ces scripts préparent, mesurent et règlent le classement des photos de l'écran **Explorer**. Ils
produisent les deux fichiers livrés dans l'app, sous `app/src/main/assets/clip/` :

| Fichier | Rôle |
|---|---|
| `vision.onnx` (11 Mo) | encodeur d'images MobileCLIP-S0 (Apple), poids compressés en int8 |
| `classes.bin` | descriptions des catégories encodées, seuils de chaque catégorie, titres français |
| `concepts.bin` (0,8 Mo) | vocabulaire de la recherche : concepts encodés et leurs termes français |

## Principe retenu

1. MobileCLIP résume chaque photo en un vecteur de 512 nombres.
2. Chaque catégorie est décrite par plusieurs phrases en anglais (`prompts.py`), moyennées ; des
   classes « de fond » (objet, texture, outils…) écartent les photos quelconques.
3. Une photo prend sa catégorie la plus probable si elle dépasse son **seuil principal**, puis
   chaque autre catégorie au-dessus de son **seuil secondaire**. Seuils globaux réglés sur
   l'entraînement Commons ; quelques seuils propres (personnes, animaux, bateaux, couchers de
   soleil) validés sur des moitiés d'Unsplash jamais vues au réglage.

## Trois jeux de mesure

| Jeu | Contenu | Usage |
|---|---|---|
| Commons (`dataset.py`) | 2 953 photos d'entraînement, 730 de test triées à la main | réglage global, mesure |
| Unsplash (`unsplash.py`) | 240 photos au style d'une galerie de téléphone, étiquetées à la main (catégories attendues / tolérées) | choix entre méthodes, par moitiés |
| Émulateur (`data/phone/`) | 32 photos avec les vecteurs calculés **par le téléphone** | contrôle final, parité téléphone / ordinateur |

Les photos Commons ont un autre style que celles d'un téléphone (plus anciennes, documentaires) :
une méthode qui brille sur Commons peut échouer sur une vraie galerie. D'où le jeu Unsplash.

## Résultats (réglage livré, face au précédent)

| Jeu | Précision | Rappel | Intrus | Oublis |
|---|---|---|---|---|
| Commons test | 92 → 93 % | 80 → 76 % | 40 → 32 | 201 → 232 |
| Unsplash | 71 → 76 % | 69 → 68 % | 27 → 13 | 65 → 64 |
| Émulateur (vecteurs du téléphone) | — | — | 0 | 0 |

## Enchaînement

```sh
python dataset.py                 # photos Commons -> data/ (reprend là où il s'est arrêté)
python review.py <nom> <sources>  # planche des photos de test ; puis :
python exclude.py <nom> 3,7,12    # écarte les numéros hors sujet (data/excluded.txt)
python unsplash.py download       # photos Unsplash ; unsplash.py sheet <n> puis label.py
python thresholds.py              # réglage des seuils validé par moitiés d'Unsplash
python export.py                  # bilan face au réglage précédent, écrit classes.bin et vision.onnx
```

Prérequis : Python 3.12, `onnxruntime onnx tokenizers numpy pillow scikit-learn truststore`, et
les fichiers de [Xenova/mobileclip_s0](https://huggingface.co/Xenova/mobileclip_s0)
(`onnx/vision_model.onnx`, `onnx/text_model.onnx`, `tokenizer.json`). L'encodeur compressé :

```sh
python -c "import onnx; from onnx import version_converter as v; onnx.save(v.convert_version(onnx.load('vision_model.onnx'), 17), 'vision_model_op17.onnx')"
python weight_quant.py vision_model_op17.onnx vision_wq8.onnx
```

Données, modèles et variantes restent hors de git (`.gitignore`).

## Catégorie « Livres & lecture » (ajoutée ensuite)

Avant : sur 54 photos de livres (test Commons trié à la main), 27 n'étaient rangées nulle part et
13 partaient en « Documents ». Après ajout de la catégorie (sources Books, Open books, Stacks of
books, Bookshelves, Libraries, People reading…), mesure sur les mêmes photos :

| | Livres trouvés | Intrus « Livres » | Documents (trouvés / intrus / oubliés) |
|---|---|---|---|
| Commons test | 43 / 54 | 0 | 18 / 0 / 1 (avant : 19 / 1 / 0) |
| Unsplash | 2 / 2 | 1 (une nef d'église) | — |

Effet de bord : « Personnes » perd 4 photos sur Commons (des gens qui lisent, rangés en « Livres »
seulement). Seuils par défaut conservés : un réglage propre, appris sur l'entraînement, ajoutait un
intrus sans rien trouver de plus. Deux photos Unsplash montrant des livres ont été étiquetées
« Livres » et quatre ambiguës l'ont en toléré.

## Recherche par contenu

Même vecteur MobileCLIP que pour les catégories, comparé à environ 380 concepts (`concepts.py`) :
le dictionnaire hérité de ML Kit (`vocabulary.json`, termes français sans accents), des concepts
courants dans une galerie de téléphone (téléphone, tablette, guitare, diplôme…) et quelques
synonymes qui se volaient la place (« neige » aussi pour « hiver », « canoë » et « kayak »). Une
photo correspond à une recherche quand l'un des concepts désignés est, de tout le vocabulaire,
celui qui la décrit le mieux. `python concepts.py --export` écrit `assets/clip/concepts.bin`.

Mesure (`search_eval.py`) : les 676 photos de test copiées dans la galerie de l'émulateur, analysées
par l'app (mots-clés ML Kit et vecteurs MobileCLIP réellement calculés par le téléphone), 57
requêtes françaises dont les sources Commons donnent la vérité.

| Recherche | Précision | Rappel | Intrus | Oublis |
|---|---|---|---|---|
| Mots-clés ML Kit (précédente) | 77 % | 33 % | 285 | 326 |
| MobileCLIP | 84 % | 63 % | 53 | 209 |
| MobileCLIP + pluriel + catégories (actuelle) | 84 % | 65 % | 63 | 180 |

Ajouts ensuite : les recherches au pluriel (« livres ») trouvent ce qui est nommé au singulier, et
une recherche qui désigne le **sujet principal** d'une catégorie d'Explorer (« livre », « plage »,
« fleurs », « neige ») ramène aussi les photos de cette catégorie, retraits manuels compris. Pas les
sujets secondaires : « dessin » ne ramène pas « Art & dessins », qui ajoutait 7 intrus (tableaux,
graffitis). Les 10 intrus de plus viennent surtout de « neige », dont la moitié montre réellement
de la neige mais hors des sources prévues. Pour les livres (54 photos de test) : « livre » trouvait
22 photos et « livres » aucune ; tous deux en trouvent maintenant 50, pour 1 intrus.

La recherche précédente cherchait la requête *à l'intérieur* des mots (« clé » ramenait 94 intrus :
bicy*cle*tte…) ; la nouvelle compare le *début* des mots. Règle plus souple essayée (concept
premier, ou deuxième nettement au-dessus de sa moyenne) : +5 points de rappel mais 27 intrus de
plus, écartée. Les synonymes ont été choisis en voyant les échecs du test.

Pour refaire la mesure : copier `data/push_test/` (photos de test) dans `Pictures/VistaEval` de
l'émulateur, lancer l'app de développement, puis récupérer dans `data/phone_eval/` le fichier
`photo-clip-v1.bin` et la liste `_id` / `_display_name` de MediaStore.

## Essayé et écarté (mesures à l'appui)

| Piste | Script | Pourquoi écartée |
|---|---|---|
| Classifieur appris par catégorie | `train.py`, `compare.py` | meilleur sur Commons (98 % de précision), bien pire sur Unsplash et l'émulateur : il apprend le style des photos Commons (une personne sur 21 trouvée sur Unsplash) |
| MobileCLIP S1 / S2 | `variants.py` | un peu moins d'intrus, aucun gain de rappel, pour +10 à +23 Mo et une analyse 2 à 3,5 fois plus lente |
| Chaque catégorie contre le fond seul | `ovb.py` | plus précis, nettement moins de rappel |
| Classe de fond « photo d'un mur » | — | avalait 15 photos de ville sur Unsplash ; la retirer fait passer le rappel de 57 à 66 % |
| Versions int8 publiées du modèle | — | fidélité 0,08 à 0,14 au modèle complet : du bruit |
| Quantification statique calibrée | — | fidélité 0,54 : ce modèle (MobileOne) ne supporte pas des calculs en int8 |
| XNNPACK | — | aucun gain de vitesse mesuré |

Retenu pour la vitesse : poids en int8 décompressés **une fois au chargement**
(`session.disable_quant_qdq`), 1,6 fois plus rapide qu'en les décompressant à chaque photo, et
jusqu'à 4 cœurs.

## Licence

MobileCLIP est distribué par Apple sous la licence reproduite dans
`app/src/main/assets/clip/LICENSE-MobileCLIP.txt`, qui accompagne le modèle dans l'app. Les photos
de test restent hors du dépôt (Wikimedia Commons, Unsplash).
