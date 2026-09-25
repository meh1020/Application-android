# Classement des photos par MobileCLIP — outils

Ces scripts préparent, mesurent et règlent le classement des photos de l'écran **Explorer**. Ils
produisent les deux fichiers livrés dans l'app, sous `app/src/main/assets/clip/` :

| Fichier | Rôle |
|---|---|
| `vision.onnx` (11 Mo) | encodeur d'images MobileCLIP-S0 (Apple), poids compressés en int8 |
| `classes.bin` | descriptions des catégories encodées, seuils de chaque catégorie, titres français |

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
| Commons (`dataset.py`) | 2 652 photos d'entraînement, 676 de test triées à la main | réglage global, mesure |
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
