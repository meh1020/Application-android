# Vista — galerie & éditeur photo Android

Application Android (Kotlin + Jetpack Compose) reproduisant le concept Dribbble « VISTA Photo Editor App » :
thème clair, accent violet profond, cartes arrondies et étiquettes en verre dépoli, police Kanit.

## Écrans

| Écran | Contenu |
|---|---|
| **Bienvenue** | Photo plein écran, « Chaque moment compte », bouton « Capturer la vie » (demande l'accès aux photos). |
| **Accueil** | Pile d'albums animée (touchez une carte arrière ou glissez pour la faire passer devant), bouton « + » d'import, barre de navigation (accueil, importer, corbeille, mes créations) et bouton appareil photo. |
| **Album** | Carte « Le meilleur de <mois> », grille en quinconce, sélection multiple (partager / mettre à la corbeille), tri. |
| **Visionneuse** | Carrousel 3D avec photos voisines floutées, infos, favoris, actions Modifier / Partager / Supprimer, bande de miniatures rondes. |
| **Modifier la photo** | Annuler/rétablir, outils Filtres / Ajuster / Recadrer, règle graduée, options rondes (Miroir, Pivoter, Libre, Carré, Portrait, Story, Large…), cadenas de format, enregistrement. |
| **Recherche** | Albums et photos par nom d'album, mois, date ou nom de fichier. |
| **Corbeille** | Restaurer ou supprimer définitivement. |

## Thème clair / sombre

L'application suit le réglage du système : palette claire (violet profond sur fond blanc cassé) ou
sombre (violet clair sur fond anthracite), y compris les ombres, le voile de recadrage et les icônes
des barres système. Le basculement est instantané, sans redémarrage.

## Fluidité et 120 Hz

À chaque retour au premier plan, l'application demande à l'écran son mode le plus rapide à
résolution identique : elle s'anime donc à 120 Hz sur un téléphone qui le propose, et retombe
sur son mode d'origine sinon. Côté coût d'affichage, la courbure de lentille du verre est
réservée aux grandes surfaces, les tuiles de la grille tirent leur format des dimensions déjà
connues (la grille n'est plus réagencée quand les photos arrivent), le fond flouté de l'accueil
part d'une version réduite, et les gestes (goutte des barres, glissé de fermeture, zoom) ne
lisent leurs valeurs animées qu'au dessin, sans recalcul d'interface.

## Recherche par contenu

L'écran Recherche analyse les photos sur l'appareil (modèle embarqué, sans connexion) et leur
associe des mots-clés. On peut alors chercher « chien », « plage », « montagne »… en plus du nom
d'album, du mois ou du nom de fichier. Les mots-clés les plus fréquents sont proposés en pastilles,
et l'analyse reprend là où elle s'est arrêtée (600 photos les plus récentes au maximum).

## Édition

- 13 filtres avec intensité ; réglages : luminosité, exposition, contraste, saturation, chaleur, teinte, fondu, vignette.
- Recadrage libre ou à format fixe, verrouillage du format, rotation 90°, miroir, **redressement ±45°** avec zoom automatique.
- Maintenir l'image pour comparer avec l'original.
- Export JPEG jusqu'à 4096 px dans `Pictures/Vista` (album « Créations Vista »), partage.
- Ouverture depuis d'autres apps via « Modifier avec… » / « Partager ».

## Compiler

Prérequis : Android SDK 35, JDK 17 ou plus récent.

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # APK minifié, signé avec la clé debug
./gradlew installDebug       # installe sur l'appareil/émulateur connecté
```

Minimum : Android 11 (API 30). Permissions : `READ_MEDIA_IMAGES` (Android 13+, accès partiel géré sur 14+) ou `READ_EXTERNAL_STORAGE` (Android 11–12).

## Structure

```
app/src/main/java/com/vista/photoeditor/
├── MainActivity.kt           navigation, lanceurs (sélecteur, caméra, permissions, MediaStore)
├── data/
│   ├── MediaRepository.kt    lecture MediaStore, albums, corbeille, favoris
│   └── GalleryViewModel.kt   état de la galerie, rafraîchissement automatique
├── editor/                   moteur de retouche (matrices, filtres, géométrie, export)
└── ui/
    ├── Navigation.kt         pile d'écrans
    ├── theme/                couleurs, police Kanit
    ├── components/           boutons ronds, verre dépoli, règle graduée, images Coil
    ├── onboarding/ home/ album/ viewer/ search/ trash/
    └── editor/               écran d'édition, panneaux, recadrage
```

Photo d'accueil : Unsplash (via picsum.photos), licence Unsplash. Police Kanit : SIL Open Font License.
