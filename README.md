# Vista — Galerie & Éditeur Photo Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2011%2B-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Min%20SDK-API%2030-orange" />
  <img src="https://img.shields.io/badge/Target%20SDK-API%2035-blue" />
  <img src="https://img.shields.io/badge/Version-1.0-violet" />
  <img src="https://img.shields.io/badge/License-MIT-lightgrey" />
</p>

> **Vista** est une application Android de galerie et de retouche photo entièrement construite avec **Kotlin** et **Jetpack Compose**, fidèlement inspirée du concept Dribbble *« VISTA Photo Editor App »*. Elle allie une interface soignée — thème clair avec accent violet profond, cartes arrondies, surfaces en verre dépoli (*glassmorphism*), typographie Kanit — à un moteur d'édition non destructif et à une recherche par contenu basée sur l'intelligence artificielle embarquée, le tout sans aucune dépendance réseau.

---

## ✨ Fonctionnalités clés

| Domaine | Ce que Vista propose |
|---|---|
| 🖼️ **Galerie** | Parcours par albums animés, grille en quinconce, sélection multiple, tri par date |
| 🔍 **Recherche intelligente** | Par nom, album, date *ou* contenu (« robe », « chien », « coucher de soleil »), hors ligne, sur 2 000 photos |
| 🪄 **Explorer (IA)** | 19 catégories remplies toutes seules par MobileCLIP (Personnes, Plages, Vêtements…), souvenirs automatiques, doublons et rafales à trier |
| ✏️ **Éditeur non destructif** | 29 filtres en 4 familles, 10 réglages fins dont ombres et hautes lumières, retouche automatique, recadrage libre ou verrouillé, redressement ±45°, annuler/rétablir |
| 🚀 **Fluidité 120 Hz** | Adaptation dynamique du mode d'affichage, animations optimisées sans recalcul d'interface |
| 🌗 **Thème adaptatif** | Clair & sombre (suivi du système), basculement instantané sans redémarrage |
| 🔒 **Vie privée** | Aucune donnée envoyée : analyse IA, édition et stockage 100 % en local ; dossier masqué chiffré, ouvert par empreinte ou code |
| 📤 **Intégration système** | Ouverture depuis *« Modifier avec… »* ou *« Partager »*, export JPEG haute résolution |

---

## 📱 Écrans

### 🟣 Onboarding
Photo plein écran immersive, accroche *« Chaque moment compte »*, bouton **Capturer la vie** qui déclenche la demande de permission d'accès aux médias.

### 🏠 Accueil
Pile d'albums animée avec effet de profondeur — touchez une carte arrière ou glissez pour la faire passer au premier plan. Barre de navigation (Accueil / Explorer / Corbeille / Créations), bouton **+** d'import et raccourci appareil photo. Fond flouté par Haze pour le *liquid glass*.

### 🪄 Explorer
La galerie se range toute seule, sans réseau, grâce à **MobileCLIP** (Apple), un modèle qui compare
chaque photo à des descriptions (« une photo de plage », « une personne vue de dos »…) plutôt que de
lui coller des étiquettes isolées :
- **Catégories** — Personnes, Animaux, Plages & mer, Couchers de soleil, Paysages, Fleurs & plantes,
  Villes & architecture, Nourriture, Vêtements & mode, Appareils & écrans, Véhicules, Bateaux,
  Documents, Livres & lecture, Sport, Fêtes & célébrations, Intérieur & maison, Neige & hiver,
  Art & dessins, plus
  **Captures d'écran** (d'après l'album système, jamais mélangées aux autres rayons).
- **Souvenirs** — les photos prises à moins de 20 h d'intervalle forment un moment, nommé d'après
  la catégorie dominante (« Plage · 15–16 sept. ») ; les dates anniversaires deviennent « Il y a un an ».
- **Doublons et rafales** — les séries de photos presque identiques (même photo enregistrée
  plusieurs fois, prises répétées à quelques secondes d'intervalle) sont regroupées ; la plus nette
  de chaque série est gardée et les autres proposées à la corbeille, après confirmation du système.
  Un toucher change le choix ; favoris et créations de l'éditeur ne sont jamais proposés d'office.
  Documents et captures d'écran ne sont regroupés que s'ils sont identiques (deux pages différentes
  se ressemblent trop). Seuils réglés dans `tools/clip/duplicates.py`.
- **Correction à la main** — dans une catégorie, sélectionner des photos puis « Retirer de la
  catégorie » : elles n'y reviennent plus (la photo elle-même n'est pas supprimée).
- **Analyse en arrière-plan** dès l'ouverture de l'app (2 000 photos les plus récentes), reprise là
  où elle s'est arrêtée.
- Mesuré sur trois jeux (Wikimedia Commons, photos Unsplash au style d'un téléphone, photos de
  l'émulateur) ; méthode, chiffres et pistes écartées dans `tools/clip/`.

### 🔒 Dossier masqué
Depuis la sélection d'un album ou le menu de la visionneuse, **Masquer** chiffre la photo dans le
stockage privé de l'app puis la retire de la galerie (confirmation du système) : aucune autre app
ne la voit plus. Le dossier s'ouvre depuis le bas d'Explorer, après l'empreinte ou le code de
l'appareil ; il se referme dès que l'app passe en arrière-plan, et ni capture d'écran ni aperçu des
apps récentes ne montrent son contenu.
- **Chiffrement** AES-GCM, clé de données chiffrée par une clé du Keystore d'Android (elle ne quitte
  jamais le téléphone) ; vignettes chiffrées à part pour une grille rapide. Exclu des sauvegardes.
- **Sans perte** : une copie n'entre dans le dossier que si l'original a bien quitté la galerie,
  constaté dans la galerie elle-même (pas d'après la réponse au dialogue, perdue si l'app est
  arrêtée entre-temps) ; refusé, la copie est effacée.
- **Restaurer** remet la photo dans son dossier d'origine, à sa date d'origine (écrite dans l'EXIF
  si la photo n'en avait pas). **Supprimer** efface définitivement, après confirmation.
- Demande un verrouillage d'écran : sans empreinte ni code, le dossier ne s'ouvre pas.

### 🗂️ Album
Carte d'en-tête *« Le meilleur de \<mois\> »*, grille en quinconce avec tuiles pré-dimensionnées (zéro reflow), sélection multiple pour partager ou mettre à la corbeille, options de tri.

### 🔭 Visionneuse
Carrousel 3D avec photos voisines floutées et décalées en perspective, panneau d'informations (date, lieu, appareil via ExifInterface), favoris, actions **Modifier / Partager / Supprimer**, bande de miniatures rondes en bas.

### ✏️ Éditeur
- **Filtres** — 29 filtres rangés en familles, avec curseur d'intensité :
  - **Couleur** : Vivid, Été, Portrait, Aurora, Golden, Arctic, Minuit, Pastel ;
  - **Film** : Film, Pellicule, Cinema, Teal & Orange, Relief, Lumière douce, Contre-jour, Matte, Drama, Rétro ;
  - **Noir & blanc** : Mono, Noir, Sélénium, Sépia, Cyanotype, Bichromie ;
  - **Créatif** : Infrarouge, et Rouge / Jaune / Vert / Bleu seul (une couleur gardée, le reste en noir et blanc).

  Un filtre est une matrice de couleurs, plus au besoin un étalonnage qu'une matrice ne sait pas
  faire : courbe de tons (Film, Relief, Contre-jour…), virage partiel (Teal & Orange : ombres
  bleu-vert, tons clairs orangés) ou couleur sélective. L'étalonnage suit la même formule dans le
  shader de l'aperçu et à l'export (`Grade.kt`) : écart mesuré entre les deux sur l'émulateur, 0,7
  sur 255 pour Rouge seul et 1,5 pour Teal & Orange. Les vignettes du panneau sont rendues comme
  l'export, justes sur tous les Android. Planche de tous les filtres : `tools/editor/filter_sheet.py`.
- **Ajuster** — Luminosité, Exposition, Contraste, Hautes lumières, Ombres, Saturation, Chaleur, Teinte, Fondu, Vignette.
- **Ombres et hautes lumières** — une courbe de tons sur la luminosité (les couleurs gardent leur teinte) : éclaircir les ombres sort un sujet d'un contre-jour sans brûler le ciel, baisser les hautes lumières rend du relief aux nuages. Noirs et blancs ne bougent pas, et la courbe ne s'inverse jamais, même les deux curseurs poussés à fond. Aperçu en temps réel par un shader AGSL (Android 13 et plus ; avant, rendu calculé en arrière-plan), export par le processeur avec la même formule (`ToneCurve.kt`) : écart mesuré entre l'export et la formule, 1,7 sur 255 (compression JPEG).
- **Auto** (panneau Ajuster) — exposition, contraste, ombres, hautes lumières et balance des blancs calculés d'après la photo, posés sur les curseurs pour être ajustés ; un second appui les retire. Les tons sont étirés comme un réglage des niveaux, sans jamais assombrir une photo claire ni éclaircir fortement une nuit ; la dominante de couleur est estimée sur les contours (« gray-edge »), qu'un ciel bleu ou une pelouse ne faussent pas, et une photo trop chaude n'est refroidie qu'à moitié. Les ombres ne remontent que si le quart le plus sombre reste très sombre à côté de vraies zones claires (contre-jour), les hautes lumières ne baissent que sur un grand ciel clair mais pas brûlé ; sur 48 photos d'essai, 19 en reçoivent. Planche avant / après : `tools/editor/auto_enhance_sheet.py`.
- **Recadrer** — Règle graduée de redressement ±45° avec zoom automatique, formats prédéfinis (Libre, Carré, Portrait, Story, Large…), verrouillage du rapport, rotation 90°, miroir.
- **Comparer** — Maintenir l'image pour afficher l'original côte à côte.

### 🔍 Recherche
Barre de recherche avec, en pastilles, les sujets les plus fréquents de vos photos. Recherche par nom d'album, mois, date, nom de fichier **ou** contenu (« chien », « robe », « coucher de soleil »…) : environ 380 concepts nommés en français, reconnus par MobileCLIP à partir des vecteurs déjà calculés pour les catégories. Les photos trouvées par leur contenu viennent en tête, de la plus ressemblante à la moins ressemblante.

Les recherches au pluriel fonctionnent (« livres »), et une recherche qui désigne une catégorie d'Explorer (« livre », « plage », « fleurs ») ramène aussi ses photos.

Mesurée sur les mêmes 676 photos et 57 requêtes que l'ancienne recherche par mots-clés ML Kit : précision 83 % contre 77 %, deux fois plus de photos retrouvées (rappel 68 % contre 33 %), 60 intrus contre 285. Une fois la vérité du test corrigée à l'œil (des intrus montraient bien le sujet), 90 % de précision et 76 % de rappel.

### 🗑️ Corbeille
Galerie des éléments supprimés avec options **Restaurer** ou **Supprimer définitivement**.

---

## 🎨 Design System

```
Typographie   : Kanit (Regular / Medium / SemiBold / SemiBold Italic / Bold)
Couleur clé   : Violet profond  #5B21B6  /  clair : #7C3AED
Fond clair    : Blanc cassé #FAFAF9   |   Fond sombre : Anthracite #1C1B1F
Surfaces      : Verre dépoli via dev.chrisbanes.haze (BlurMaskFilter)
Rayons        : 16 dp (tuiles)  ·  24 dp (cartes albums)  ·  999 dp (pastilles/chips)
Transitions   : SharedElement zoom grille ↔ visionneuse (ExperimentalSharedTransitionApi)
```

---

## 🏗️ Architecture & Stack technique

```
app/src/main/java/com/vista/photoeditor/
├── MainActivity.kt              Navigation hôte, permissions, lanceurs MediaStore / caméra
├── data/
│   ├── MediaRepository.kt       Lecture MediaStore, albums, corbeille, favoris, EXIF
│   ├── GalleryViewModel.kt      État galerie, rafraîchissement automatique
│   ├── PhotoLabels.kt           Normalisation des textes de recherche (minuscules, sans accents)
│   ├── ClipModel.kt             MobileCLIP embarqué (ONNX Runtime) et classement par catégorie
│   ├── SmartAlbums.kt           Catégories et souvenirs
│   └── SearchIndex.kt           Analyse (mots-clés + vecteurs MobileCLIP), persistance
├── editor/
│   ├── EditState.kt             État immuable de l'édition (filtres + réglages + géométrie)
│   ├── EditPresets.kt           Définitions des 13 filtres et des 8 curseurs de réglage
│   ├── ColorMatrices.kt         Matrices de couleurs pour les filtres (Canvas / ColorFilter)
│   ├── Filters.kt               Application des matrices sur Bitmap via Canvas
│   ├── EditorViewModel.kt       Logique d'édition, historique annuler/rétablir, prévisualisation
│   └── ImageIO.kt               Lecture URI → Bitmap, export JPEG 4096 px, partage, EXIF
└── ui/
    ├── Navigation.kt            Pile d'écrans Compose, transitions partagées
    ├── theme/                   MaterialTheme, couleurs, police Kanit
    ├── components/              Boutons ronds, surfaces verre (Glass.kt), règle graduée,
    │                            GlassSlidingBar, images Coil, SharedPhoto
    ├── onboarding/              Écran de bienvenue
    ├── home/                    Pile d'albums animée
    ├── album/                   Grille en quinconce, sélection multiple
    ├── viewer/                  Carrousel 3D, miniatures rondes
    ├── explore/                 Souvenirs et catégories automatiques
    ├── search/                  Recherche textuelle + IA
    ├── trash/                   Corbeille
    └── editor/                  EditorScreen, panneaux Filtres/Ajuster, CropEditor
```

### Bibliothèques principales

| Bibliothèque | Rôle |
|---|---|
| `androidx.compose:compose-bom:2024.12.01` | BOM Jetpack Compose (UI, graphics, Material 3) |
| Shared Element Transitions | Transitions zoom grille ↔ visionneuse |
| `io.coil-kt.coil3:coil-compose:3.0.4` | Chargement & mise en cache asynchrone des images |
| `dev.chrisbanes.haze:haze:1.2.2` | Flou d'arrière-plan *liquid glass* (Android 12+) |
| `androidx.exifinterface:exifinterface:1.3.7` | Lecture/écriture métadonnées JPEG (date, GPS, appareil) |
| `com.microsoft.onnxruntime:onnxruntime-android:1.30.0` | Exécution de MobileCLIP (catégories) |

---

## ⚡ Performance & 120 Hz

- **Refresh rate adaptatif** : à chaque premier plan, Vista demande le mode d'affichage le plus rapide disponible (120 Hz si l'écran le supporte, retour automatique sinon).
- **Zéro reflow de grille** : les tuiles sont pré-dimensionnées d'après les tailles déjà connues — la grille ne se réagence pas quand les photos arrivent.
- **Flou optimisé** : le fond flouté de l'accueil part d'une version réduite de l'image (downscale avant BlurMaskFilter).
- **Lecture d'état lazy** : gestes (drag, pinch, swipe) lisent les valeurs animées uniquement au dessin (`graphicsLayer`), sans déclencher de recomposition.
- **Lentille Haze conditionnelle** : l'effet de courbure optique est réservé aux grandes surfaces pour limiter le coût GPU.

---

## 🔐 Permissions

| Permission | API cible | Usage |
|---|---|---|
| `READ_MEDIA_IMAGES` | Android 13+ (API 33+) | Accès aux photos (accès partiel géré sur Android 14+) |
| `READ_EXTERNAL_STORAGE` | Android 11–12 (API 30–32) | Accès aux photos sur anciens builds |
| `WRITE_EXTERNAL_STORAGE` | Android 11 (API 30) | Export dans `Pictures/Vista` |
| `CAMERA` | Tous | Capture via raccourci appareil photo |

---

## 🛠️ Compilation

**Prérequis** : Android SDK 35, JDK 17 ou plus récent.

```sh
# Debug (rapide, non optimisé)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release (minifié + ressources réduites, signé avec la clé debug)
./gradlew assembleRelease
# → un APK par processeur, les modèles embarqués livrant une bibliothèque native par architecture :
#   app-arm64-v8a-release.apk    ~20 Mo  (la grande majorité des téléphones)
#   app-armeabi-v7a-release.apk  ~20 Mo  (appareils 32 bits)
#   app-x86_64-release.apk       ~21 Mo  (émulateurs)
#   app-universal-release.apk    ~41 Mo  (toutes architectures réunies)

# Installer directement sur l'appareil/émulateur connecté
./gradlew installDebug
```

> **Android minimum** : Android 11 (API 30).
> Le flou *liquid glass* (Haze) nécessite Android 12 (API 31) ; sur Android 11, un voile teinté semi-transparent est affiché à la place.

---

## 🗺️ Feuille de route

- [ ] Synchronisation iCloud / Google Photos
- [ ] Partage de collections sous forme d'album partagé
- [ ] Widgets écran d'accueil (photo du jour, album aléatoire)
- [ ] Moteur de filtres GPU via OpenGL ES / Vulkan
- [ ] Localisation (EN / FR / AR)

---

## 📄 Licences & crédits

| Ressource | Licence |
|---|---|
| Photo d'onboarding | [Unsplash](https://unsplash.com) via picsum.photos — Licence Unsplash |
| Police **Kanit** | [SIL Open Font License 1.1](https://scripts.sil.org/OFL) |
| **MobileCLIP-S0** (Apple) | Licence Apple, reproduite dans `app/src/main/assets/clip/LICENSE-MobileCLIP.txt` |
| **ONNX Runtime** | [MIT](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| **Haze** par Chris Banes | [Apache 2.0](https://github.com/chrisbanes/haze/blob/main/LICENSE) |

---

<p align="center">Fait avec ❤️ · Kotlin · Jetpack Compose · Material 3</p>
