# Sources de l’identité FeurStagram

Le document natif [FeurStagram.sketch](sketch/FeurStagram.sketch) est la source des composants, compositions et variables couleur. Les [règles de marque](guidelines/brand-guidelines.md) donnent les valeurs, dimensions et seuils de lisibilité. Le dossier [svg/](svg/) contient les plans vectoriels autonomes pour les outils qui lisent le SVG. Les fichiers publiés dans le site, la documentation et le pack d’icônes Android sont des produits de l’export.

| Page Sketch | Contenu |
| --- | --- |
| 01 · Règles | Fonds clair et sombre, palette, géométrie, typographie, protection et diffusion |
| 02 · Composants | Symbole et mot-symbole éditables, compositions horizontale et carrée en symboles imbriqués |
| 03 · Logos | Huit plans transparents, quatre compositions en deux tons |
| 04 · Bannières et partage | Bannière du README, deux planches de captures, aperçu du site et aperçu GitHub |
| 05 · Icônes | Favicon, icône carrée, tracé de profil, aperçu adaptatif et plans de lanceur |

## Modifier la marque dans Sketch

1. Ouvrir [FeurStagram.sketch](sketch/FeurStagram.sketch). Le fichier a été créé dans Sketch 2025.3.4. Les glyphes du mot-symbole sont des tracés et la page Règles utilise du texte éditable. La police Inter est disponible dans [fonts/](fonts/) sous licence SIL OFL. La police variable sert au web, la face statique Black 900 est la référence du mot-symbole. [Distribution Inter 4.1](https://github.com/rsms/inter/releases/tag/v4.1).
2. Modifier le symbole ou les glyphes dans les sources **Marque / Symbole** et **Marque / Mot-symbole**. Les instances des symboles répercutent le changement dans les variantes, bannières et icônes. Si les dimensions du symbole changent, vérifier sa zone de protection et les plans d’export. Une correction de couleur passe par les variables du document.
3. Dans les plans `readme-screenshots` et `readme-settings`, remplacer les calques d’image à l’intérieur de leurs masques arrondis. Les captures couleur sont incorporées aux plans. Elles proviennent de la version Android 456.0.0.53.78 installée avec Unlock timer. Les données des autres comptes et le contenu privé des messages ont été masqués avant intégration. L’[accueil](../docs/assets/img/after.png), les [contenus bloqués](../docs/assets/img/blocked-surfaces.png) et le [minuteur](../docs/assets/img/unlock-timer.png) existent aussi en PNG pleine résolution pour le site. Le script produit leurs miniatures WebP.
4. Enregistrer le document, documenter les nouvelles valeurs si nécessaire et lancer l’export. Les noms des plans exportés constituent les clés de correspondance du script.

## Régénérer les exports

Depuis la racine du dépôt, créer un environnement Python 3.10 ou plus récent et installer les dépendances épinglées.

```sh
python3 -m venv ../feurstagram-design-venv
. ../feurstagram-design-venv/bin/activate
python -m pip install -r design/requirements.txt
```

Sur macOS, après enregistrement du document natif, la commande suivante actualise les SVG, puis tous les livrables. Définir `SKETCHTOOL` si Sketch est installé hors de `/Applications/Sketch.app`.

```sh
python design/export.py --from-sketch
python design/export.py --from-sketch --check
```

Sur macOS, Linux ou Windows sans Sketch, les SVG dans `design/svg/` conservent les tracés, couleurs et images nécessaires. La palette du web est lue dans l’archive du fichier Sketch, sans lancer l’application.

```sh
python design/export.py
python design/export.py --check
```

Un éditeur SVG peut modifier un plan portable et le script régénère les fichiers qui en dépendent. Pour répercuter une modification de composant sur toutes les compositions, réimporter le tracé modifié dans le symbole source de Sketch, puis actualiser les SVG avec `--from-sketch`. Les logos utilisent des tracés sans dépendance aux polices. Sur les plans Android, conserver des formes pleines sur fond transparent, avec les creux inclus comme sous-tracés. L’export Android utilise le remplissage pair-impair et accepte les translations, échelles et matrices.

## Correspondance des livrables

| Destination | Entrée |
| --- | --- |
| [README](../Readme.md), [bannière](../docs/assets/brand/readme-banner.png), planches [vues principales](../docs/assets/brand/screenshots.png) et [réglages](../docs/assets/brand/settings-screenshots.png) | `readme-banner.svg`, `readme-screenshots.svg`, `readme-settings.svg` |
| [Aperçu social du dépôt](../assets/social-preview.png) | `github-social.svg` |
| [Pages FR](../docs/fr/index.html) et [EN](../docs/index.html), [ressources web](../docs/assets/brand/) | `horizontal-paper.svg`, `app-icon.svg`, `favicon.svg`, `open-graph.svg`, variables Sketch et Inter variable |
| [Kit presse](../docs/assets/brand/feurstagram-press-kit.zip) | Huit plans de logos SVG et leurs PNG transparents, aperçu de partage, deux planches de captures et règles |
| [Tracé de profil Android](svg/profile-mark.svg) | Plan `profile-mark` dans Sketch |
| [Pack de lanceur Android](android-launcher.zip) | `android-legacy.svg`, `android-foreground.svg`, `android-monochrome.svg`, variable `ink` |

Le pack de lanceur peut être prévisualisé ou compilé comme ressources Android autonomes. Son intégration dans l’APK du projet relève d’un changement distinct. L’aperçu social GitHub se règle dans les paramètres du dépôt après décision de publication. Voir le [guide du dépôt](../docs/REPOSITORY_SETTINGS.md).
