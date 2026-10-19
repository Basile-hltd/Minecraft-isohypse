# Minecraft-isohypse

Plugin [Paper](https://papermc.io) qui modifie la génération du terrain d'un monde Minecraft. Le dépôt contient le plugin, ainsi que des outils pour le tester en local (serveur de test et client).

> État actuel : le plugin n'est qu'à son début (version 0.0.1). Il détecte le type de monde demandé et arrête la génération vanilla après l'étape du relief. Les générations propres à chaque style (Realistic, Fairy…) restent à écrire.

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

Le résultat est un monde de pierre pure, qui montre le relief produit par le bruit de Minecraft. Pour l'instant, `Vanilla+` et `Fairy` se comportent pareil. `Realistic` remplace le bruit vanilla par sa propre forme : une sinusoïde sur l'axe X (amplitude 32 blocs, longueur d'onde 64 blocs, autour de y=64), remplie de pierre, avec de l'eau jusqu'au niveau de la mer vanilla (y=63) dans les creux et des grottes qui imitent celles de Minecraft (voir `CaveNoise`) : poches cheese, tunnels spaghetti et noodle. Elles s'arrêtent 8 blocs sous la surface, un lissage retire les blocs isolés et les aspérités des parois. Elles sont remplies de lave sous y=-54, et d'eau près du fond des océans.

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

Construit le plugin, télécharge Paper, puis lance un serveur local avec le plugin installé.

```bash
python tools/local_run.py --mc-version 26.3 --level-type Vanilla+ --clean
```

| Option | Rôle |
|--------|------|
| `--mc-version` | Version de Minecraft / Paper (défaut : `26.3`) |
| `--level-type` | Valeur de `level-type`, par exemple `Vanilla+`, `Realistic` ou `Fairy`. Écrit aussi l'entrée `generator: Isohypse` dans `bukkit.yml` |
| `--seed` | Seed du monde (défaut : aléatoire, affichée au démarrage). Ne s'applique qu'à un nouveau monde : à combiner avec `--clean` |
| `--clean` | Supprime `tmp/server` avant de démarrer (monde neuf) |
| `--memory` | Mémoire du serveur (défaut : `4G`) |
| `--player` | Joueur mis op (défaut : `Japrisot`) |
| `--no-eula` | N'écrit pas `eula=true` : par défaut, le script accepte l'EULA de Minecraft |

Si Java 25, Gradle ou Paper manquent, le script les télécharge dans `tmp/`. Les plugins listés dans `tools/plugins.yml` sont installés dans le serveur (voir ci-dessous).

Le serveur est préparé pour les tests : liste blanche désactivée, mode spectateur, déplacements rapides tolérés et `online-mode=false`. Ce dernier réglage supprime la vérification du compte Microsoft, ce qui permet au client hors-ligne ci-dessous de se connecter. Le joueur op reçoit son UUID hors-ligne, calculé localement. **Ne pas utiliser ces réglages pour un vrai serveur.**

#### Plugins tiers : `tools/plugins.yml`

Liste de slugs [Modrinth](https://modrinth.com) (par exemple `lod-server-support`). À chaque lancement, `local_run.py` télécharge la version Paper correspondant à `--mc-version` (cache dans `tmp/plugin-cache/`, checksum vérifié) et remplace l'ancien jar du même plugin. Si aucune version n'existe pour cette version de Minecraft, un warning est affiché et le plugin n'est pas installé. Aucun jar n'est stocké dans le dépôt.

#### Gamerules : `tools/gamerules.yml`

Les gamerules listés (par défaut : temps figé, météo figée, pas de mobs) sont envoyés au serveur dès qu'il est prêt, à chaque démarrage. Les noms sont ceux de 26.x (`advance_time`, `spawn_mobs`…). La console du serveur reste utilisable normalement.

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
python tools/local_run.py --mc-version 26.3 --level-type Vanilla+ --clean
```

Dans un autre, le client, connecté directement au serveur :

```bash
python tools/download_minecraft.py 26.3 --run --server localhost:25565
```

Avec `Vanilla+`, le monde généré est un relief de pierre pure, sans herbe, arbres ni structures.
