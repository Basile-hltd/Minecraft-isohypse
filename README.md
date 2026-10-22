# Minecraft-isohypse

Plugin [Paper](https://papermc.io) qui modifie la génération du terrain d'un monde Minecraft. Le dépôt contient le plugin, ainsi que des outils pour le tester en local (serveur de test et client).

> État actuel : le plugin n'est qu'à son début (version 0.0.1). Il détecte le type de monde demandé et arrête la génération vanilla après l'étape du relief. Les générations propres à chaque style (Realistic, Fairy…) restent à écrire.
>
> **Realistic, état actuel** : au démarrage, le plugin télécharge les altitudes réelles de la zone et en fait `heightmap.bin` (la hauteur de chaque colonne, à l'échelle du jeu) et `settings.json` ; à la création de l'overworld il en tire `world.bin` (hauteurs, zones de la carte intermédiaire et biomes, voir plus bas). Le générateur lit ce fichier depuis le disque (rien n'est gardé en mémoire) : il fait le relief (pierre, eau jusqu'à y=63) et donne les biomes de surface et de grotte ; la surface, les grottes, les décorations et les mobs sont ceux de Minecraft. Les structures sont celles de Minecraft, générées par Paper d'après nos hauteurs et nos biomes (voir plus bas).

## Le plugin

### Ce qu'il fait

Au chargement (`load: STARTUP`), il lit `level-type` dans `server.properties`. Trois valeurs sont reconnues, sans tenir compte de la casse, avec ou sans le préfixe `isohypse:` :

| `level-type`           | Style      |
|------------------------|------------|
| `Vanilla+`             | `VANILLA_PLUS` |
| `Realistic`            | `REALISTIC`    |
| `Fairy`                | `FAIRY`        |

Toute autre valeur (par exemple `minecraft:normal`) laisse la génération vanilla inchangée.

Quand un style est reconnu et que le monde utilise le générateur `Isohypse`, le plugin installe le `StyleRouterGenerator`. Il ne génère rien lui-même : il redirige chaque appel vers le générateur du style (`VanillaPlusGenerator`, `RealisticGenerator` ou `FairyGenerator`). Chacun laisse pour l'instant Minecraft produire la forme du terrain (pierre, eau, grottes de bruit, aquifères) puis s'arrête. Ils sautent tout le reste :

- les règles de surface (herbe, sable, terre…) ;
- les carvers (ravins, grottes classiques) ;
- les décorations (arbres, minerais, fleurs…) ;
- les structures ;
- les mobs à la génération.

Le résultat est un monde de pierre pure, qui montre le relief produit par le bruit de Minecraft. Pour l'instant, `Vanilla+` et `Fairy` se comportent pareil. `Realistic` remplace le bruit vanilla par sa propre forme : la hauteur de chaque colonne vient de la height map (voir Configuration), remplie de pierre, avec de l'eau jusqu'au niveau de la mer vanilla (y=63) dans les creux et des grottes qui imitent celles de Minecraft (voir `CaveNoise`) : poches cheese, tunnels spaghetti et noodle. Elles s'arrêtent 8 blocs sous la surface, un lissage retire les blocs isolés et les aspérités des parois. Elles sont remplies de lave sous y=-54, et d'eau près du fond des océans. Les étapes vanilla suivantes (surface, grottes classiques, décorations, structures, mobs) s'exécutent ensuite par-dessus.

### Utilisation sur un serveur

1. Copier le jar `isohypse-<version>-minecraft-<mc>.jar` dans `plugins/`.
2. Dans `server.properties` : `level-type=Vanilla+` (ou `Realistic` / `Fairy`).
3. Dans `bukkit.yml`, dire à Bukkit d'utiliser le générateur du plugin pour le monde principal :

```yaml
worlds:
  world:
    generator: Isohypse
```

4. Démarrer le serveur sur un monde neuf. Le générateur ne s'applique pas aux chunks déjà générés.

### Configuration

Au premier lancement, le plugin crée `plugins/Isohypse/config.yml` :

| Clé | Rôle |
|-----|------|
| `realistic.latitude` | Latitude (-90 à 90) du point du monde réel placé au centre du monde (x=0, z=0) |
| `realistic.longitude` | Longitude (-180 à 180) de ce même point |
| `realistic.size` | Côté en mètres de la zone réelle récupérée (défaut 1000) |
| `world.max_height` | Hauteur maximale de l'overworld (défaut 320 = vanilla, le sol reste à y=-64). Arrondie à la valeur la plus proche acceptée par le jeu : un multiple de 16 de 128 à 2032. Le plugin écrit un datapack au lancement du serveur (`plugins/Isohypse/datapack`) ; la hauteur ne s'applique qu'à un monde qui n'existe pas encore, et seulement au style `Realistic` (`Vanilla+` et `Fairy` gardent la hauteur vanilla) |
| `structures.margin` | Blocs de plat en plus autour de chaque pièce d'un igloo, d'un avant-poste de pillards ou d'un camp abandonné (défaut 1, 0 à 16) |
| `structures.blend_distance` | Distance en blocs sur laquelle le sol passe du plat au relief naturel autour de ces structures, en courbe douce (défaut 12, 0 = bord net, 48 au plus) |
| `structures.corner_radius` | Rayon en blocs des coins arrondis du plat de chaque pièce (défaut 4, 0 = coins droits, 32 au plus) ; un coin arrondi laisse le fondu de `blend_distance` commencer plus tôt |
| `realistic.scale.*` | Mise à l'échelle des altitudes réelles : `ocean_depth` (blocs sous le niveau de la mer où est mise la mer plate, 30). Les changer refait `heightmap.bin` (et retélécharge les tuiles) |
| `realistic.temp.*` | Carte intermédiaire (zones tirées de l'altitude seule) : `beach_depth` et `beach_height` (blocs sous et sur la mer où la plage commence et finit, 3 et 5), `beach_inland_distance` (20), `ocean_blur` et `land_blur` (flou, 150 et 50), `warm_ocean_share` et `normal_ocean_share` (parts de la mer, 1/3 chacune), `warm_land_share` et `normal_land_share` (parts de la terre, 0,35 et 0,45 ; le reste est cold). Les changer ne touche pas à `heightmap.bin` : `world.bin` (refait à chaque démarrage) a d'autres zones |

La latitude et la longitude sont affichées dans la console au démarrage. Avec `Realistic`, le plugin prépare ses données au démarrage (voir plus bas).

1. Il récupère les altitudes réelles d'une zone de `size` x `size` mètres autour du point (1 point par mètre ; au-delà de 4096 points de côté, un point tous les quelques mètres, et swisstopo cède la place à Terrarium au-delà de 4 m). Sources : swisstopo swissALTI3D en Suisse, sinon AWS Terrarium. Les points hors de -500 à 9000 m (artefacts des sources) sont ramenés à ces bornes, puis un filtre médian 3 x 3 retire les points isolés ; les altitudes brutes extrêmes et leur position sont écrites dans la console. Les tuiles téléchargées sont dans `plugins/Isohypse/dem-cache/` le temps du téléchargement, puis supprimées.
2. Il met toute la carte à l'échelle avec un seul facteur, identique en x, z et en hauteur, qui ne dépend que des extrêmes des altitudes, jamais d'une part de la carte (une mer ou un grand lac donne un plat qui fausserait un pourcentage). Le terrain est simplement décalé : l'altitude de référence est mise à 30 blocs sous le niveau de la mer (`ocean_depth`) et le point le plus haut arrive à la hauteur max du monde (lue via Paper). Si au moins 1 % des points est à 0 m ou moins, la zone a une vraie mer et la référence est 0 m : la mer plate est à y=33, un fond plus bas descend encore, et la terre plus basse que `ocean_depth` / facteur mètres (quelques centaines de mètres selon l'échelle) est sous l'eau. Sinon la référence est le point le plus bas : un lac, plat, y est sous l'eau. Un fond plus bas que y=0 est écrêté à 0. La carte finale fait moins de `size` blocs si le relief est plus haut que le monde, et plus si le relief est faible.

Le terrain suit cette height map, centrée sur x=0, z=0, nord vers les z négatifs. Hors de la carte, elle est simplement répétée à l'infini.

À chaque démarrage, le plugin regarde si `plugins/Isohypse/heightmap.bin` (la hauteur de chaque colonne, à l'échelle du jeu) a été fait avec les paramètres de la config (ceux de `settings.json`). Si oui, il n'y a rien à télécharger. Sinon il télécharge les tuiles de la zone, échantillonne les altitudes réelles dans `heightmap_source.bin` (en mètres, avant mise à l'échelle), supprime `dem-cache/`, fait la mise à l'échelle (voir plus bas) et écrit `heightmap.bin`, puis **supprime `heightmap_source.bin`**. Changer un paramètre (zone, `world.max_height`, `realistic.scale.*`…) retélécharge donc les tuiles. Ensuite, **toujours**, il refait `world.bin` à partir de `heightmap.bin` (voir plus bas). **Le serveur attend** la fin de ces étapes, et s'arrête si elles échouent. Un `heightmap_source.bin` resté après une étape interrompue est utilisé sans rien télécharger s'il est de la même zone ; celui d'une autre zone est supprimé. Pour repartir sans retélécharger après un `--clean`, `--heightmap` de `local_run.py` prend un dossier avec un `heightmap.bin` et son `settings.json`.

La mise à l'échelle tire de `heightmap_source.bin` le fichier `heightmap.bin` (le Y du bloc du haut de chaque colonne, un entier 16 bits par bloc, une colonne par bloc) et `settings.json` à côté, qui liste tous les paramètres utilisés (zone, `max_height`, niveau de la mer…) et le résultat (échelle, altitude de référence, part de la carte sous le niveau de la mer, taille). Un seul facteur sert en x, z et hauteur (voir plus haut). **La carte n'est jamais rognée**, quelle que soit sa taille, et rien n'est gardé en mémoire : le `.bin` est lu depuis le disque et `heightmap.bin` est écrit par bandes.

`world.bin` est refait à chaque démarrage à partir de `heightmap.bin`, **à la création de l'overworld** (c'est alors que la seed est connue, même quand `level-seed` est vide) : un seul fichier fait de couches lues chacune à part depuis le disque (format décrit dans `WorldFile.java`), ce que le plugin utilise et que le viewer lit. Les couches : `height` (une copie des hauteurs), `temp` (la zone de la carte intermédiaire de chaque colonne, un octet) et trois couches de biomes, `biome` (surface), `cave_biome` (grottes, lues à y=0) et `deep_cave_biome` (grottes profondes, lues à y=-40), à raison d'une cellule par 4 x 4 blocs (la taille de la maille de biomes du jeu ; la cellule est le biome du bloc de son coin, comme le jeu le demande). Le côté de la carte est un multiple de 32, pour que le milieu de la carte tombe sur une limite de chunk et de cellule. `settings.json` reçoit le bloc `temp` (les paramètres `realistic.temp.*`), les noms des zones, et le bloc `build` : la seed, et la liste des biomes (l'indice 0, `none`, est une grotte sans biome propre) dont les couches donnent l'indice. Les couches de grotte ne gardent que le tirage : les règles de profondeur (un biome de grotte seulement à plus de 8 blocs sous le sol et sous le niveau de la mer moins 8, les grottes profondes sous y=-24) sont appliquées quand le biome d'un bloc est demandé, comme le faisait `BiomeLayout`, et le viewer montre donc les tirages partout.

**Structures.** C'est Paper qui les génère, avec les règles de Minecraft (`shouldGenerateStructures`) : où elles peuvent commencer vient de la seed, la hauteur au sol de `getBaseHeight` (qui distingue la surface de l'eau et le fond de la mer) et le biome de notre fournisseur de biomes. Le plugin n'en fait rien d'autre et `world.bin` n'en contient aucune. Le viewer, lui, en estime les positions (`tools/structure_estimator.py`, voir plus bas). **Limite connue : les portails en ruine** sortent trop enfoncés dans le sol. Leur hauteur est cherchée dans une colonne de terrain (`ChunkGenerator.getBaseColumn`) que Paper demande au générateur *vanilla*, pas au nôtre ; le portail descend donc jusqu'au sol du terrain vanilla à cet endroit, et se retrouve enterré là où notre relief est plus haut. Les autres structures utilisent `getBaseHeight` (le nôtre) et sont à la bonne hauteur.

### Commandes

| Commande            | Permission        | Effet                         |
|---------------------|-------------------|-------------------------------|
| `/isohypse version` | `isohypse.admin` (op par défaut) | Affiche la version du plugin |

### Versions

La version du plugin est dans le fichier `VERSION`. La version de Minecraft ciblée se choisit au build avec `-PmcVersion=26.2` (défaut : 26.1) et se retrouve dans le nom du jar. Le workflow GitHub `release.yml` construit le plugin pour 26.1, 26.2 et 26.3.

## Build

Il faut Java 25 (toolchain Gradle) :

```bash
gradle build -PmcVersion=26.3
```

Le jar est produit dans `build/libs/`.

## Outils de test local

Tout ce que les scripts téléchargent ou génèrent est rangé dans `tmp/`, ignoré par git.

Installation, à faire une fois :

```bash
python -m venv .venv && source .venv/bin/activate   # Windows : .venv\Scripts\activate
pip install -r tools/requirements.txt
```

### `tools/local_run.py` : serveur de test

Construit le plugin, télécharge Paper, puis lance un serveur local avec le plugin installé. Les réglages se font dans deux fichiers de `tools/server/`, appliqués à chaque lancement : il suffit de les modifier puis de lancer avec `--clean` pour un monde neuf.

| Fichier | Effet |
|---------|-------|
| `tools/server/config.yml` | Copié tel quel sur `plugins/Isohypse/config.yml` (coordonnées, `size`, `max_height`…) |
| `tools/server/server.properties` | Les clés qu'il contient écrasent celles du `server.properties` du serveur (`level-type`, `level-seed`, gamemode, `online-mode`…). `level-seed` vide = seed aléatoire, affichée au démarrage. Quand `level-type` est `Vanilla+`, `Realistic` ou `Fairy`, le script écrit aussi `generator: Isohypse` dans `bukkit.yml` |

```bash
python tools/local_run.py --clean
```

| Option | Rôle |
|--------|------|
| `--mc-version` | Version de Minecraft / Paper (défaut : `26.3`) |
| `--clean` | Supprime `tmp/server` avant de démarrer (monde neuf) |
| `--memory` | Mémoire du serveur (défaut : `4G`) |
| `--player` | Joueur mis op (défaut : `Japrisot`) |
| `--heightmap` | Dossier qui contient le `heightmap.bin` et le `settings.json` écrits par le plugin, copiés dans `plugins/Isohypse/` : si leurs paramètres sont ceux de la config, rien n'est téléchargé (`world.bin` est de toute façon refait à partir de `heightmap.bin` à chaque démarrage) |
| `--no-eula` | N'écrit pas `eula=true` : par défaut, le script accepte l'EULA de Minecraft |

Si Java 25, Gradle ou Paper manquent, le script les télécharge dans `tmp/`. Les plugins listés dans `tools/plugins.yml` sont installés dans le serveur (voir ci-dessous).

Le serveur est préparé pour les tests : liste blanche désactivée, mode spectateur, déplacements rapides tolérés et `online-mode=false`. Ce dernier réglage supprime la vérification du compte Microsoft, ce qui permet au client hors-ligne ci-dessous de se connecter. Le joueur op reçoit son UUID hors-ligne, calculé localement. **Ne pas utiliser ces réglages pour un vrai serveur.**

#### Plugins tiers : `tools/plugins.yml`

Liste de slugs [Modrinth](https://modrinth.com) (par exemple `lod-server-support`). À chaque lancement, `local_run.py` télécharge la version Paper correspondant à `--mc-version` (cache dans `tmp/plugin-cache/`, checksum vérifié) et remplace l'ancien jar du même plugin. Si aucune version n'existe pour cette version de Minecraft, un warning est affiché et le plugin n'est pas installé. Aucun jar n'est stocké dans le dépôt.

#### Gamerules : `tools/gamerules.yml`

Les gamerules listés (par défaut : temps figé, météo figée, pas de mobs) sont envoyés au serveur dès qu'il est prêt, à chaque démarrage. Les noms sont ceux de 26.x (`advance_time`, `spawn_mobs`…). La console du serveur reste utilisable normalement.

### `tools/map_viewer.py` : carte du monde

Fenêtre qui affiche le monde de `Realistic` pour un humain, en trois onglets. **Affichage** : la height map, la terre en gris (du niveau de la mer au point le plus haut) et la mer en bleu, du clair (peu profond) au foncé (profond) ; la case « Mer en bleu » la cache et tout est alors en gris, du point le plus bas au plus haut ; le bouton « Recharger » relit les fichiers (après un nouveau démarrage du serveur, par exemple). **Zones** : la carte intermédiaire par-dessus (océan chaud/normal/froid, plage, terre chaude/normale/froide), une case par zone et un réglage d'opacité. **Biomes** : les biomes par-dessus, au choix en surface, dans les grottes ou dans les grottes profondes, une case par biome (le plus répandu d'abord, avec sa part de la carte), « Tout » et « Aucun », et un réglage d'opacité. Molette pour zoomer, glisser pour se déplacer, double-clic pour copier une commande `/tp` vers l'endroit. La barre du bas donne x, z, la hauteur, et la zone et les biomes sous la souris quand ils sont affichés.

```bash
pip install -r tools/requirements.txt
python tools/map_viewer.py                  # lit tmp/server/plugins/Isohypse
python tools/map_viewer.py chemin/vers/dossier
```

**Il ne calcule rien de ce que le plugin utilise** : il lit `world.bin` et `settings.json`, calculés par le plugin au démarrage, donc il montre exactement ce que le plugin utilise. Seules les couleurs lui sont propres (le plugin ne donne que des noms). Les couches sont lues depuis le disque : seule la partie à l'écran est lue, quelle que soit la taille de la carte, et les fichiers ne restent pas ouverts, donc le serveur peut les remplacer pendant que la fenêtre est ouverte. Un quatrième onglet, **Structures**, affiche l'estimation des structures que Paper va générer : une case par structure avec son nombre, « Tout » et « Aucun », et la structure proche du pointeur est nommée dans la barre du bas. Le calcul se fait dans le viewer, à la demande (quelques secondes) et en tâche de fond, par `tools/structure_estimator.py` : les règles de Minecraft, lues dans le bytecode du serveur, appliquées à la seed de `settings.json`, aux hauteurs et aux biomes de `world.bin`, avec les données (groupes de structures, biomes autorisés) lues dans le jar de Paper du serveur (le plus récent de `versions/`, ou `--server-jar`). La grille de placement, la réduction de fréquence, les zones d'exclusion, le tirage de la structure d'un groupe et les strongholds sont exacts ; le point de départ et le biome vérifié suivent chaque type de structure (centre du chunk en surface, fond de la mer, hauteur fixe…). Approximations : les structures bâties à partir de modèles (villages, cités antiques, portails en ruine) partent du centre de leur première pièce, qu'on remplace par le centre du chunk. Les strongholds suivent le jeu de bout en bout (les anneaux, le tirage du hasard de chaque position, puis le déplacement vers un biome préféré dans un rayon de 112 blocs). À vérifier contre `/locate` en jeu.

### `tools/download_minecraft.py` : client

Télécharge le client officiel de Mojang et peut le lancer.

```bash
python tools/download_minecraft.py                  # liste les versions disponibles (releases)
python tools/download_minecraft.py --all            # y compris snapshots et anciennes versions
python tools/download_minecraft.py 26.3             # télécharge la version
python tools/download_minecraft.py 26.3 --run       # télécharge puis lance le jeu
python tools/download_minecraft.py 26.3 --run --server localhost:25565
```

Le script télécharge le jar du client, les bibliothèques adaptées à ton système avec extraction des natives, les assets (sons, langues…) et la config de logs. Il vérifie aussi que le JDK exigé par la version est dans `tmp/` et l'installe sinon (Temurin). Un fichier déjà présent n'est pas retéléchargé : relancer la commande reprend où elle s'est arrêtée. Le premier téléchargement est long, car il y a plus de 5000 petits fichiers d'assets.

| Option | Rôle |
|--------|------|
| `--run` | Lance le jeu après le téléchargement, en mode hors-ligne (aucun compte nécessaire) |
| `--player` | Pseudo utilisé par `--run` (défaut : `Japrisot`) |
| `--memory` | Mémoire du jeu (défaut : `2G`) |
| `--server` | Serveur à rejoindre directement, par exemple `localhost:25565` |
| `--all` | Avec la liste : inclut snapshots et anciennes versions |

Emplacements dans `tmp/` :

- `minecraft-<version>/` : `client.jar`, json de la version, natives ;
- `minecraft-libraries/` et `minecraft-assets/` : partagés entre les versions ;
- `minecraft-game/` : dossier de jeu de `--run` (options, captures d'écran) ;
- `jdk-<N>/` : JDK partagé avec `local_run.py`.

Le lancement hors-ligne ne permet de rejoindre que des serveurs en `online-mode=false`, comme celui de `local_run.py`.

### Tester le plugin de bout en bout

Dans un terminal, le serveur :

```bash
python tools/local_run.py --clean
```

Dans un autre, le client, connecté directement au serveur :

```bash
python tools/download_minecraft.py 26.3 --run --server localhost:25565
```

Avec `Vanilla+`, le monde généré est un relief de pierre pure, sans herbe, arbres ni structures.
