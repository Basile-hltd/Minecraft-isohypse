# Minecraft-isohypse

Plugin [Paper](https://papermc.io) qui modifie la génération du terrain d'un monde Minecraft. Le dépôt contient le plugin, ainsi que des outils pour le tester en local (serveur de test et client).

> État actuel : le plugin n'est qu'à son début (version 0.0.1). Il détecte le type de monde demandé et arrête la génération vanilla après l'étape du relief. Les générations propres à chaque style (Realistic, Fairy…) restent à écrire.
>
> **Realistic est désactivé pour le moment, sauf une étape** : au démarrage, le plugin télécharge les altitudes réelles de la zone et en fait `heightmap.bin` (la hauteur de chaque colonne, à l'échelle du jeu) et `settings.json` (voir plus bas), puis le monde est généré par Minecraft, comme en vanilla. Tout ce qui décrit la suite (terrain, biomes, anciennes cartes) est du code en pause, qui n'est plus appelé.

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
| `realistic.tiling` | `true` : une carte carrée est entourée de huit copies, chacune dans une orientation tirée au hasard avec la seed du monde (voir plus bas), aux raccords lissés ; `false` (défaut) : la carte est simplement répétée à l'infini |
| `realistic.blend_width` | Demi-largeur en blocs de la bande lissée de chaque côté d'un raccord entre copies de la carte (défaut 200, 0 = aucun lissage) |

La latitude et la longitude sont affichées dans la console au démarrage. Avec `Realistic`, le plugin fait, à la première génération de chunk, une height map `plugins/Isohypse/heightmap.png` (PNG 16 bits, 1 pixel = 1 bloc, valeur = hauteur Y en blocs) à partir de `heightmap_source.bin` (voir plus bas), et **le serveur attend** qu'elle soit prête (ça peut durer plusieurs minutes pour une grande zone). Elle est refaite à chaque démarrage. Un `map.png` (carte à regarder seulement : un pixel par bloc, colorié selon le biome, avec à droite un glossaire des biomes présents et, en rouge, des biomes de Paper absents de la carte), puis un `cave_map.png` (les biomes de grotte à deux profondeurs, y=0 et y=-40, côte à côte) un `structure.png` (la height map en gris, sans la mer en bleu, avec un point de couleur par structure du jeu : positions vanilla tirées de la seed, gardées si le biome du plugin les accepte ; glossaire à droite avec le nombre de chacune) et un `temp_map.png` (carte intermédiaire : sept zones tirées de la seule altitude, sans lien avec les biomes, avec leur plage de Y en glossaire) est écrit à côté.

1. Il récupère les altitudes réelles d'une zone de `size` x `size` mètres autour du point (1 point par mètre ; au-delà de 4096 points de côté, un point tous les quelques mètres, et swisstopo cède la place à Terrarium au-delà de 4 m). Sources : swisstopo swissALTI3D en Suisse, sinon AWS Terrarium. Les tuiles téléchargées sont dans `plugins/Isohypse/dem-cache/` le temps du téléchargement, puis supprimées.
2. Il met toute la carte à l'échelle avec un seul facteur, identique en x, z et en hauteur : le point le plus bas arrive à 30 blocs sous le niveau de la mer et le plus haut à la hauteur max du monde (lue via Paper). Si la mer (les colonnes sous y=60, soit 3 blocs sous le niveau de la mer) ne couvre pas entre 12 et 22 % de la carte, l'altitude réelle placée au bord de la mer est déplacée (recherche par dichotomie, visant 17 %), le point le plus haut restant au plafond, avec toujours un seul facteur en x, z et hauteur. Si le fond descend sous y=0, il est écrêté à 0 (le PNG ne stocke pas de Y négatif). La carte finale fait donc moins de `size` blocs si le relief est plus haut que le monde, et plus si le relief est faible. Au-delà de 10000 blocs de côté, la carte garde son échelle et ses bords sont coupés (autour de son centre), si bien que le relief peut perdre ses points les plus bas ou les plus hauts.

Le terrain suit cette height map, centrée sur x=0, z=0, nord vers les z négatifs. Hors de la carte, si elle est carrée et si `realistic.tiling` est à `true` (désactivé par défaut : sinon la carte est simplement répétée à l'infini, et `map.png` ainsi que `heightmap_human.png` ne montrent qu'elle), elle est entourée de huit copies, chacune dans une orientation tirée au hasard avec la seed du monde parmi les huit symétries du carré (quatre rotations de 90° et leurs miroirs ; la carte d'origine garde la sienne, et deux copies voisines n'ont jamais la même) et ce bloc de 3x3 se répète à l'infini ; une carte non carrée est simplement répétée. Les raccords entre copies sont lissés par un filtre passe-bas appliqué une fois le bloc de 3x3 assemblé, uniquement sur une bande de `realistic.blend_width` blocs de chaque côté du raccord (200 par défaut, limité à la moitié de la carte ; 0 désactive le lissage). Le lissage vaut 1 sur le raccord et diminue jusqu'à 0 au bord de la bande, donc le reste de la carte n'est pas modifié. `map.png` et `heightmap_human.png` montrent le résultat lissé. Si `heightmap.png` existe déjà, le plugin en vérifie quand même l'échelle (plus bas et plus haut points contre 30 blocs sous la mer et la hauteur max du monde), la corrige si besoin avec un seul facteur en x, z et hauteur, et régénère toujours `map.png`. Sans `heightmap_source.bin`, la correction se fait sur la carte elle-même, donc un peu moins précise sur les hauteurs.

À chaque démarrage, le plugin regarde si `plugins/Isohypse/heightmap.bin` a été fait avec les paramètres de la config (ceux de `settings.json`). Si oui, il n'y a rien à faire. Sinon il refait tout : il télécharge les tuiles de la zone, échantillonne les altitudes réelles dans `heightmap_source.bin` (en mètres, avant mise à l'échelle), supprime `dem-cache/`, fait la mise à l'échelle (voir plus bas), puis **supprime `heightmap_source.bin`**. Changer un paramètre (zone, `world.max_height`…) retélécharge donc les tuiles. **Le serveur attend** la fin de cette étape, et s'arrête si elle échoue. Un `heightmap_source.bin` resté après une étape interrompue est utilisé sans rien télécharger s'il est de la même zone ; celui d'une autre zone est supprimé. Pour repartir sans retélécharger après un `--clean`, `--heightmap` de `local_run.py` prend un dossier avec un `heightmap.bin` et son `settings.json`.

La mise à l'échelle tire de `heightmap_source.bin` le fichier `heightmap.bin` (le Y du bloc du haut de chaque colonne, un entier 16 bits par bloc, une colonne par bloc) et `settings.json` à côté, qui liste tous les paramètres utilisés (zone, `max_height`, niveau de la mer, part de mer visée…) et le résultat (échelle, altitude placée au bord de la mer, taille). Un seul facteur sert en x, z et hauteur : le point le plus haut arrive à la hauteur max du monde, et la mer (sous y=60) couvre entre 12 et 22 % de la carte. Le point le plus bas est mis à 30 blocs sous le niveau de la mer si la part de mer le permet ; sinon l'altitude du bord de la mer est celle qui donne 17 % de mer (un centile des altitudes, lu dans un histogramme). **La carte n'est jamais rognée**, quelle que soit sa taille, et rien n'est gardé en mémoire : le `.bin` est lu depuis le disque et `heightmap.bin` est écrit par bandes. Un fond plus bas que y=0 est aplati à 0.

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
| `tools/server/config.yml` | Copié tel quel sur `plugins/Isohypse/config.yml` (coordonnées, `size`, `max_height`, `blend_width`…) |
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
| `--heightmap` | Dossier qui contient le `heightmap.bin` et le `settings.json` écrits par le plugin, copiés dans `plugins/Isohypse/` : si leurs paramètres sont ceux de la config, rien n'est téléchargé ni calculé |
| `--no-eula` | N'écrit pas `eula=true` : par défaut, le script accepte l'EULA de Minecraft |

Si Java 25, Gradle ou Paper manquent, le script les télécharge dans `tmp/`. Les plugins listés dans `tools/plugins.yml` sont installés dans le serveur (voir ci-dessous).

Le serveur est préparé pour les tests : liste blanche désactivée, mode spectateur, déplacements rapides tolérés et `online-mode=false`. Ce dernier réglage supprime la vérification du compte Microsoft, ce qui permet au client hors-ligne ci-dessous de se connecter. Le joueur op reçoit son UUID hors-ligne, calculé localement. **Ne pas utiliser ces réglages pour un vrai serveur.**

#### Plugins tiers : `tools/plugins.yml`

Liste de slugs [Modrinth](https://modrinth.com) (par exemple `lod-server-support`). À chaque lancement, `local_run.py` télécharge la version Paper correspondant à `--mc-version` (cache dans `tmp/plugin-cache/`, checksum vérifié) et remplace l'ancien jar du même plugin. Si aucune version n'existe pour cette version de Minecraft, un warning est affiché et le plugin n'est pas installé. Aucun jar n'est stocké dans le dépôt.

#### Gamerules : `tools/gamerules.yml`

Les gamerules listés (par défaut : temps figé, météo figée, pas de mobs) sont envoyés au serveur dès qu'il est prêt, à chaque démarrage. Les noms sont ceux de 26.x (`advance_time`, `spawn_mobs`…). La console du serveur reste utilisable normalement.

### `tools/map_viewer.py` : carte de la height map

Fenêtre qui affiche la height map de `Realistic` pour un humain : la terre en gris (du niveau de la mer au point le plus haut), la mer en bleu, du clair (peu profond) au foncé (profond). La case « Mer en bleu » la cache : tout est alors en gris, du point le plus bas au plus haut. Molette pour zoomer, glisser pour se déplacer, double-clic pour copier une commande `/tp` vers l'endroit. La barre du bas donne x, z et la hauteur sous la souris. Le bouton « Recharger » relit `heightmap.bin` et `settings.json` (après un nouveau démarrage du serveur, par exemple) ; les fichiers ne sont ouverts que le temps de lire ce qui est à l'écran, donc le serveur peut les remplacer pendant que la fenêtre est ouverte.

```bash
pip install -r tools/requirements.txt
python tools/map_viewer.py                  # lit tmp/server/plugins/Isohypse
python tools/map_viewer.py chemin/vers/dossier
```

Il lit `heightmap.bin` et `settings.json`, que le plugin écrit au démarrage. La carte reste sur le disque : seule la partie à l'écran est lue, quelle que soit la taille de la carte. Les anciennes cartes (biomes, structures, grottes) ne sont plus produites pour le moment.

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
