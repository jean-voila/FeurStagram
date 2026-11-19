# Identité visuelle FeurStagram

Le nom de marque s’écrit **FeurStagram**. Le symbole associe un carré arrondi plein et une lentille ouverte en réserve. Les bannières de marque contiennent le logo seul. Les textes utiles aux autres supports sont descriptifs.

## Palette

| Variable Sketch et CSS | HEX sRGB | Usage |
| --- | --- | --- |
| `ink` | `#050505` | Logo sur fond clair, fond sombre principal |
| `paper` | `#F7F7F7` | Logo sur fond sombre, fond clair principal |
| `surface` | `#121212` | Surface secondaire sombre |
| `hover` | `#1C1C1C` | Survol et sélections discrètes |
| `line` | `#303030` | Filets et séparateurs sur fond sombre |
| `secondary` | `#C7C7C7` | Texte courant secondaire sur fond sombre |
| `muted` | `#A3A3A3` | Légendes sur fond sombre |

Les logos d’encre et de papier sur leurs fonds opposés offrent un contraste d’environ 19:1. Sur une image, réserver un aplat suffisamment contrasté autour du logo. Employer un seul ton par logo. Garder les aplats francs et les contours nets.

## Typographie

- Le mot-symbole est composé en **Inter Black 900**, à partir de la version statique Inter 4.1. Il utilise le crénage du fichier, un tracking de −35 unités sur 2 048, puis quatre corrections optiques supplémentaires, de −21 pour `Fe`, −23 pour `rS`, et −8 pour `St` et `ta`. Les contours serrés mesurent environ 626,46 × 96. Les glyphes sont des tracés vectoriels éditables dans le composant Sketch.
- Inter 400 sert aux textes courants, Inter 600 aux légendes et actions, Inter 700 aux titres de section. La référence Sketch utilise Corps 17/26, Légende 14/20 et Titre 22/30. Le style Display Black 50/58 est réservé aux grands titres en dehors du mot-symbole.
- Employer le SVG officiel pour afficher la marque sur le web. Les tracés conservent le crénage et se rendent sans police installée.

## Géométrie et compositions

- **Symbole.** Carré de 96 × 96, avec un rayon de 22 aux quatre coins. La lentille est centrée en (48, 48). Ses rayons extérieur et intérieur sont de 26 et 14. L’anneau ajouré s’ouvre sur 54°, entre −72° et −18° depuis l’axe horizontal dirigé vers la droite, en coordonnées écran.
- **Composition horizontale.** Symbole de 96 et mot-symbole de hauteur visible 96. L’écart entre contours est de 36. Dans le module intérieur, le symbole commence à y=2 et le mot-symbole à y=4. Ce décalage optique tient compte du jambage du `g`. Le plan d’export ajoute 24 sur les quatre côtés du module.
- **Composition carrée.** Plan de 640 × 640. Le symbole de 152 et le mot-symbole de 512 de large partagent l’axe x=320. Leurs bords supérieurs sont y=178 et y=373, avec un écart vertical de 43.
- **Protection.** Réserver au minimum 24 unités autour du symbole de référence, ou le quart de la hauteur visible du mot-symbole pour les compositions. La marge extérieure du plan carré est déjà comprise. Les icônes de plateforme ont leur propre marge optique.
- **Échelle.** Espacements de référence 4, 8, 12, 16, 24, 32, 48 et 64. Les contours, l’anneau et les espacements internes se redimensionnent ensemble.

| Variante | Taille minimale recommandée à l’écran |
| --- | --- |
| Symbole | 12 px de largeur visible. Le favicon réserve la totalité de sa grille au carré arrondi. |
| Mot-symbole | 112 px de largeur visible |
| Horizontale | 150 px de largeur visible |
| Carrée | 160 px de côté, marge comprise |

Sous le seuil du mot-symbole, utiliser le symbole seul. Sur les aplats denses, placer le logo dans une zone de respiration. Pour un fond clair choisir `-ink`, pour un fond sombre `-paper`.

## Diffusion

| Support | Source Sketch | Livrable |
| --- | --- | --- |
| README | `readme-banner`, `readme-screenshots`, `readme-settings` | Bannière PNG 1280 × 368 et deux planches PNG 1920 × 1080 |
| Aperçu GitHub | `github-social` | PNG 1280 × 640 |
| Aperçus du site | `open-graph` | PNG opaque 1200 × 630 |
| Site FR/EN | `horizontal-paper`, `app-icon`, `favicon` | SVG pour les logos et favicon réversible selon le thème, ICO 16/32/48, PNG 180 Apple et PNG 256 pour les métadonnées |
| Presse | `symbol-*`, `wordmark-*`, `horizontal-*`, `square-*` | SVG et PNG transparents de 1200 px de large, trois visuels de présentation |
| En-tête du profil Android | `profile-mark` | Tracé sur grille 24, avec le ton fourni par l’en-tête |
| Lanceur Android | `android-legacy`, `android-foreground`, `android-monochrome` | PNG 48/72/96/144/192 et vecteurs adaptatifs 108 dp avec zone sûre centrale de 66 dp |

Le motif de l’icône adaptative mesure 50 × 50, centré dans la grille 108 × 108. Les couches de premier plan et monochrome partagent le même tracé. Les ressources de lanceur sont livrées dans une archive autonome.

Les planches du README conservent les couleurs des captures, avec des coins de rayon 24 et des descriptions accessibles dans le README.

## Nommage

Les familles de composants Sketch sont `Marque / …` et `Composition / …`. Les instances de composition référencent les deux symboles de base. Les styles partagés utilisent `Typographie / …` ou `Remplissage / …`. Les plans de travail exportés et leurs fichiers SVG utilisent des minuscules séparées par des tirets. Les logos de presse suivent `feurstagram-<variant>-<ton>.<format>`.

## Press usage in English

Use the ink artwork on light backgrounds and the paper artwork on dark backgrounds. Preserve the vector proportions and clear space. For small placements use the symbol alone. Write the name as **FeurStagram**. The presentation images are sized for repository and social sharing.
