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
| 🔍 **Recherche intelligente** | Recherche textuelle *et* reconnaissance visuelle hors-ligne (ML Kit) sur 600 photos |
| ✏️ **Éditeur non destructif** | 13 filtres, 8 réglages fins, recadrage libre ou verrouillé, redressement ±45°, annuler/rétablir |
| 🚀 **Fluidité 120 Hz** | Adaptation dynamique du mode d'affichage, animations optimisées sans recalcul d'interface |
| 🌗 **Thème adaptatif** | Clair & sombre (suivi du système), basculement instantané sans redémarrage |
| 🔒 **Vie privée** | Aucune donnée envoyée : analyse IA, édition et stockage 100 % en local |
| 📤 **Intégration système** | Ouverture depuis *« Modifier avec… »* ou *« Partager »*, export JPEG haute résolution |

---

## 📱 Écrans

### 🟣 Onboarding
Photo plein écran immersive, accroche *« Chaque moment compte »*, bouton **Capturer la vie** qui déclenche la demande de permission d'accès aux médias.

### 🏠 Accueil
Pile d'albums animée avec effet de profondeur — touchez une carte arrière ou glissez pour la faire passer au premier plan. Barre de navigation (Accueil / Importer / Corbeille / Créations), bouton **+** d'import et raccourci appareil photo. Fond flouté par Haze pour le *liquid glass*.

### 🗂️ Album
Carte d'en-tête *« Le meilleur de \<mois\> »*, grille en quinconce avec tuiles pré-dimensionnées (zéro reflow), sélection multiple pour partager ou mettre à la corbeille, options de tri.

### 🔭 Visionneuse
Carrousel 3D avec photos voisines floutées et décalées en perspective, panneau d'informations (date, lieu, appareil via ExifInterface), favoris, actions **Modifier / Partager / Supprimer**, bande de miniatures rondes en bas.

### ✏️ Éditeur
- **Filtres** — 13 filtres (Vivid, Fade, Noir, Warm, Cool, Drama…) avec curseur d'intensité.
- **Ajuster** — Luminosité, Exposition, Contraste, Saturation, Chaleur, Teinte, Fondu, Vignette.
- **Recadrer** — Règle graduée de redressement ±45° avec zoom automatique, formats prédéfinis (Libre, Carré, Portrait, Story, Large…), verrouillage du rapport, rotation 90°, miroir.
- **Comparer** — Maintenir l'image pour afficher l'original côte à côte.

### 🔍 Recherche
Barre de recherche avec suggestions en pastilles des mots-clés les plus fréquents. Recherche par nom d'album, mois, date, nom de fichier **ou** contenu visuel (« chien », « plage », « montagne »…). L'analyse ML reprend là où elle s'est arrêtée.

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
│   ├── PhotoLabels.kt           Cache des mots-clés ML Kit par URI
│   └── SearchIndex.kt           Index de recherche textuelle + contenu visuel
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
| `com.google.mlkit:image-labeling:17.0.9` | Reconnaissance visuelle embarquée, hors-ligne |

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
# → app/build/outputs/apk/release/app-release.apk

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
| **ML Kit Image Labeling** | [Google ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |
| **Haze** par Chris Banes | [Apache 2.0](https://github.com/chrisbanes/haze/blob/main/LICENSE) |

---

<p align="center">Fait avec ❤️ · Kotlin · Jetpack Compose · Material 3</p>
