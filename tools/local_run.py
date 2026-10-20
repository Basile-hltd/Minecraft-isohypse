#!/usr/bin/env python3
"""Build the plugin, download Paper and start a local test server (Windows, Linux, macOS).

Everything is created in tmp/ at the repo root (ignored by git):
    tmp/paper-<mc>-<build>.jar   downloaded Paper jar
    tmp/gradle-<ver>/            Gradle, downloaded if "gradle" is not on the PATH
    tmp/jdk-<ver>/               JDK (Temurin), downloaded if no recent enough Java is found
    tmp/server/                  server working directory (world, plugins, logs)

Usage :
    python -m venv .venv && source .venv/bin/activate    (Windows : .venv\\Scripts\\activate)
    pip install -r tools/requirements.txt
    python tools/local_run.py --mc-version 26.2 [--seed 1234] [--clean] [--no-eula]
"""
import argparse
import hashlib
import json
import os
import platform
import random
import re
import shutil
import subprocess
import sys
import tarfile
import threading
import time
import uuid
import zipfile
from pathlib import Path

import requests
import yaml

GRADLE_VERSION = "9.2.0"
JAVA_VERSION = 25  # must match the toolchain in build.gradle.kts

ROOT = Path(__file__).resolve().parent.parent
TMP = ROOT / "tmp"
SERVER_DIR = TMP / "server"
IS_WINDOWS = os.name == "nt"


def download(url: str, dest: Path) -> None:
    print(f"Downloading {url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    with requests.get(url, stream=True, timeout=60) as r:
        r.raise_for_status()
        total = int(r.headers.get("Content-Length", 0))
        done, last = 0, 0.0
        with open(dest, "wb") as f:
            for chunk in r.iter_content(1 << 16):
                f.write(chunk)
                done += len(chunk)
                if time.time() - last > 0.2:
                    last = time.time()
                    pct = f" ({100 * done // total}%)" if total else ""
                    print(f"\r  {done / 1e6:.1f}/{total / 1e6:.1f} MB{pct}" if total else f"\r  {done / 1e6:.1f} MB",
                          end="", flush=True)
        print(f"\r  {done / 1e6:.1f} MB downloaded" + " " * 20)


def extract(archive: Path, dest: Path) -> None:
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as z:
            z.extractall(dest)
    else:
        with tarfile.open(archive) as t:
            t.extractall(dest)
    archive.unlink()


# --- Java ---------------------------------------------------------------
def java_major(java: str) -> int:
    try:
        out = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
        m = re.search(r'version "(\d+)', out)
        return int(m.group(1)) if m else 0
    except OSError:
        return 0


def java_home_of(java: str) -> Path | None:
    """Real java.home of a java executable (the PATH one can be a shim, e.g. Oracle's javapath)."""
    try:
        out = subprocess.run([java, "-XshowSettings:properties", "-version"], capture_output=True, text=True).stderr
    except OSError:
        return None
    m = re.search(r"java\.home = (.+)", out)
    return Path(m.group(1).strip()) if m else None


def ensure_java() -> Path:
    """Return JAVA_HOME (system one if recent enough, otherwise a Temurin JDK in tmp/)."""
    env_home = os.environ.get("JAVA_HOME")
    if env_home:
        exe = Path(env_home) / "bin" / ("java.exe" if IS_WINDOWS else "java")
        if exe.exists() and java_major(str(exe)) >= JAVA_VERSION:
            return Path(env_home)
    on_path = shutil.which("java")
    if on_path and java_major(on_path) >= JAVA_VERSION:
        home = java_home_of(on_path)
        if home and (home / "bin").exists():
            return home

    jdk_dir = TMP / f"jdk-{JAVA_VERSION}"
    if not jdk_dir.exists():
        system = {"Windows": "windows", "Linux": "linux", "Darwin": "mac"}[platform.system()]
        machine = platform.machine().lower()
        arch = "aarch64" if machine in ("arm64", "aarch64") else "x64"
        url = f"https://api.adoptium.net/v3/binary/latest/{JAVA_VERSION}/ga/{system}/{arch}/jdk/hotspot/normal/eclipse"
        archive = TMP / ("jdk.zip" if IS_WINDOWS else "jdk.tar.gz")
        download(url, archive)
        tmp_extract = TMP / "jdk-extract"
        shutil.rmtree(tmp_extract, ignore_errors=True)
        extract(archive, tmp_extract)
        shutil.move(str(next(tmp_extract.iterdir())), jdk_dir)
        shutil.rmtree(tmp_extract, ignore_errors=True)
    home = jdk_dir / "Contents" / "Home" if (jdk_dir / "Contents" / "Home").exists() else jdk_dir
    return home


# --- Gradle -------------------------------------------------------------
def ensure_gradle() -> str:
    system_gradle = shutil.which("gradle")
    if system_gradle:
        return system_gradle
    gradle_home = TMP / f"gradle-{GRADLE_VERSION}"
    exe = gradle_home / "bin" / ("gradle.bat" if IS_WINDOWS else "gradle")
    if not exe.exists():
        archive = TMP / f"gradle-{GRADLE_VERSION}-bin.zip"
        download(f"https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip", archive)
        extract(archive, TMP)
        if not IS_WINDOWS:
            exe.chmod(0o755)
    return str(exe)


def resolve_paper_api(mc_version: str) -> str | None:
    """Same resolution as the CI: latest stable Paper API version for mc_version."""
    try:
        xml = requests.get(
            "https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml",
            timeout=30,
        ).text
        found = re.findall(r"<version>(" + re.escape(mc_version) + r"\.[^<]*-stable)</version>", xml)
        key = lambda v: [int(x) for x in re.findall(r"\d+", re.match(r"^(\d+(?:\.\d+)*)", v).group(1))]
        return sorted(found, key=key)[-1] if found else None
    except requests.RequestException:
        return None


def build_plugin(gradle: str, java_home: Path, mc_version: str) -> Path:
    args = [gradle, "build", f"-PmcVersion={mc_version}"]
    api = resolve_paper_api(mc_version)
    if api:
        args.append(f"-PpaperApi={api}")
    else:
        print("Paper API not resolved, using the build default.", file=sys.stderr)

    print(f"Building the plugin (Minecraft {mc_version})...")
    env = {**os.environ, "JAVA_HOME": str(java_home)}
    if subprocess.run(args, cwd=ROOT, env=env).returncode != 0:
        sys.exit("Gradle build failed.")

    jars = sorted((ROOT / "build" / "libs").glob(f"isohypse-*-minecraft-{mc_version}.jar"),
                  key=lambda p: p.stat().st_mtime)
    if not jars:
        sys.exit("Plugin jar not found in build/libs.")
    return jars[-1]


# --- Paper --------------------------------------------------------------
def download_paper(mc_version: str) -> Path:
    # "26.1" is a family: take the latest stable version (no rc/pre), e.g. 26.1.2.
    project = requests.get("https://fill.papermc.io/v3/projects/paper", timeout=30).json()
    versions = [v for v in project["versions"].get(mc_version, []) if not re.search(r"-(rc|pre)", v)]
    if not versions:
        sys.exit(f"No stable Paper version for {mc_version}.")
    paper_version = versions[0]

    build = requests.get(
        f"https://fill.papermc.io/v3/projects/paper/versions/{paper_version}/builds/latest", timeout=30
    ).json()
    dl = build["downloads"]["server:default"]
    jar = TMP / f"paper-{paper_version}-{build['id']}.jar"
    if not jar.exists():
        print(f"Paper {paper_version} build {build['id']}")
        download(dl["url"], jar)
        sha = hashlib.sha256(jar.read_bytes()).hexdigest()
        if sha.lower() != dl["checksums"]["sha256"].lower():
            jar.unlink()
            sys.exit("Invalid Paper SHA-256 checksum.")
    return jar


# --- Third-party plugins ------------------------------------------------
PLUGINS_FILE = ROOT / "tools" / "plugins.yml"
PLUGIN_CACHE = TMP / "plugin-cache"
GAMERULES_FILE = ROOT / "tools" / "gamerules.yml"


def install_plugins(plugins_dir: Path, mc_version: str, paper_version: str) -> None:
    """Install the plugins listed in tools/plugins.yml (Modrinth slugs) in the version matching Minecraft.

    A plugin with no release for this version is skipped with a warning.
    """
    if not PLUGINS_FILE.exists():
        return
    slugs = (yaml.safe_load(PLUGINS_FILE.read_text(encoding="utf-8")) or {}).get("plugins") or []
    versions = list(dict.fromkeys([paper_version, mc_version]))
    for slug in slugs:
        try:
            r = requests.get(f"https://api.modrinth.com/v2/project/{slug}/version", timeout=30,
                             params={"loaders": json.dumps(["paper"]), "game_versions": json.dumps(versions)})
            r.raise_for_status()
            releases = [v for v in r.json() if v["version_type"] == "release"] or r.json()
        except requests.RequestException as e:
            print(f"WARNING: plugin '{slug}': Modrinth request failed ({e}).", file=sys.stderr)
            continue
        if not releases:
            print(f"WARNING: plugin '{slug}': no version found for Minecraft {mc_version}, not installed.",
                  file=sys.stderr)
            continue
        file = next((f for f in releases[0]["files"] if f["primary"]), releases[0]["files"][0])
        jar = PLUGIN_CACHE / file["filename"]
        if not jar.exists():
            download(file["url"], jar)
            if hashlib.sha512(jar.read_bytes()).hexdigest() != file["hashes"]["sha512"].lower():
                jar.unlink()
                print(f"WARNING: plugin '{slug}': invalid checksum, not installed.", file=sys.stderr)
                continue
        # Replace an older jar of the same plugin (jar names start with the slug).
        for old in plugins_dir.glob(f"{slug}*.jar"):
            if old.name != jar.name:
                old.unlink()
        shutil.copy2(jar, plugins_dir)
        print(f"Plugin {slug}: {releases[0]['version_number']}")


# --- Serveur ------------------------------------------------------------
def set_properties(path: Path, props: dict[str, str]) -> None:
    lines = path.read_text().splitlines() if path.exists() else []
    for key, value in props.items():
        line = f"{key}={value}"
        for i, existing in enumerate(lines):
            if existing.startswith(f"{key}="):
                lines[i] = line
                break
        else:
            lines.append(line)
    path.write_text("\n".join(lines) + "\n")


def offline_uuid(player: str) -> str:
    """UUID a server in offline mode (online-mode=false) gives to a player: Java's UUID.nameUUIDFromBytes."""
    return str(uuid.UUID(bytes=hashlib.md5(f"OfflinePlayer:{player}".encode()).digest(), version=3))


def write_ops(player: str) -> None:
    ops = [{"uuid": offline_uuid(player), "name": player, "level": 4, "bypassesPlayerLimit": True}]
    (SERVER_DIR / "ops.json").write_text(json.dumps(ops, indent=2))


def parse_coord(coord: list[str] | None) -> tuple[float | None, float | None]:
    """Parse "46.168358, 6.934330" (quoted or not, comma and/or space separated) into (latitude, longitude)."""
    if not coord:
        return None, None
    try:
        latitude, longitude = (float(v) for v in re.split(r"[,\s;]+", " ".join(coord).strip()))
    except ValueError:
        sys.exit('--coord expects "latitude, longitude", e.g. --coord 46.168358, 6.934330')
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        sys.exit("--coord: latitude must be in [-90, 90] and longitude in [-180, 180].")
    return latitude, longitude


def write_plugin_config(latitude: float | None, longitude: float | None, size: int | None, max_height: int | None) -> None:
    """Set realistic.latitude / longitude / size and world.max_height in plugins/Isohypse/config.yml (created from the plugin's default)."""
    if latitude is None and longitude is None and size is None and max_height is None:
        return
    config = SERVER_DIR / "plugins" / "Isohypse" / "config.yml"
    if not config.exists():
        config.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "src" / "main" / "resources" / "config.yml", config)
    text = config.read_text(encoding="utf-8")
    for key, value in (("latitude", latitude), ("longitude", longitude), ("size", size), ("max_height", max_height)):
        if value is not None:
            text = re.sub(rf"^(\s*{key}:)\s*[-\d.]+", lambda m: f"{m.group(1)} {value}", text, flags=re.MULTILINE)
    config.write_text(text, encoding="utf-8")
    print(f"Plugin config: latitude {latitude}, longitude {longitude}, size {size}, max height {max_height} (None = unchanged; "
          "the plugin rounds the max height to the closest valid one)")


def install_heightmap(path: str | None) -> None:
    """Copy a height map given with --heightmap into plugins/Isohypse, so that the plugin does not fetch it again.

    A .png is a finished height map (the plugin still checks its scale and adjusts it if needed); anything else is the real-altitude file the plugin wrote as
    heightmap_source.bin, from which it only redoes the scaling (the old height map is removed so that it does).
    """
    if not path:
        return
    src = Path(path).expanduser().resolve()
    if not src.is_file():
        sys.exit(f"--heightmap: file not found: {src}")
    dest_dir = SERVER_DIR / "plugins" / "Isohypse"
    dest_dir.mkdir(parents=True, exist_ok=True)
    if src.suffix.lower() == ".png":
        shutil.copy2(src, dest_dir / "heightmap.png")
        print(f"Height map copied: {src} -> plugins/Isohypse/heightmap.png (the plugin checks its scale)")
    else:
        shutil.copy2(src, dest_dir / "heightmap_source.bin")
        for stale in ("heightmap.png", "heightmap_human.png", "map.png", "cave_map.png", "temp_map.png"):
            (dest_dir / stale).unlink(missing_ok=True)
        print(f"Real altitudes copied: {src} -> plugins/Isohypse/heightmap_source.bin (the plugin only rescales)")


def load_gamerules() -> dict:
    if not GAMERULES_FILE.exists():
        return {}
    return (yaml.safe_load(GAMERULES_FILE.read_text(encoding="utf-8")) or {}).get("gamerules") or {}


def run_server(java: Path, memory: str, paper_jar: Path, gamerules: dict) -> None:
    """Run the server with its console forwarded; apply the gamerules once it is ready."""
    sys.stdout.reconfigure(errors="replace")  # server logs can hold characters the console encoding lacks
    proc = subprocess.Popen([str(java), f"-Xms{memory}", f"-Xmx{memory}", "-Disohypse.maxFlySpeed=true",  # max fly speed on join
                             "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-jar", str(paper_jar), "--nogui"],
                            cwd=SERVER_DIR, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")

    def send(command: str) -> None:
        try:
            proc.stdin.write(command + "\n")
            proc.stdin.flush()
        except OSError:
            pass

    def forward_console() -> None:
        for line in sys.stdin:
            send(line.rstrip("\n"))

    threading.Thread(target=forward_console, daemon=True).start()
    ready = False
    for line in proc.stdout:
        print(line, end="", flush=True)
        if not ready and re.search(r"Done \(.*\)! For help", line):
            ready = True
            for rule, value in gamerules.items():
                send(f"gamerule {rule} {str(value).lower()}")
    proc.wait()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mc-version", default="26.3", help="target Minecraft / Paper version (default: 26.3)")
    ap.add_argument("--memory", default="4G", help="server memory (default: 4G)")
    ap.add_argument("--player", default="Japrisot", help="player to make op")
    ap.add_argument("--clean", action="store_true", help="delete tmp/server before starting (fresh world)")
    ap.add_argument("--seed", help="world seed (default: random, printed at startup)")
    ap.add_argument("--level-type", help="level-type value for server.properties (e.g. Realistic, Fairy, Vanilla+)")
    ap.add_argument("--coord", nargs="+", metavar="LAT, LON",
                    help='Realistic: "latitude, longitude" of the world center (x=0, z=0), e.g. --coord 46.168358, 6.934330')
    ap.add_argument("--size", type=int, help="Realistic: side of the height map in blocks (default: plugin config, 1000)")
    ap.add_argument("--max-height", type=int,
                    help="maximum height of the overworld (default: plugin config, 320 = vanilla); the plugin rounds it to "
                         "the closest height the game accepts and adds the datapack at launch; needs --clean for an existing world")
    ap.add_argument("--heightmap", metavar="PATH",
                    help="Realistic: height map to copy into the plugin folder (a .png is checked and rescaled if needed; "
                         "the heightmap_source.bin the plugin wrote is only rescaled), so nothing is fetched again")
    ap.add_argument("--no-eula", action="store_true",
                    help="do not accept the Minecraft EULA (by default eula=true is written to eula.txt)")
    args = ap.parse_args()

    TMP.mkdir(exist_ok=True)
    java_home = ensure_java()
    java = java_home / "bin" / ("java.exe" if IS_WINDOWS else "java")
    gradle = ensure_gradle()
    plugin_jar = build_plugin(gradle, java_home, args.mc_version)
    paper_jar = download_paper(args.mc_version)
    paper_version = re.match(r"paper-(.+)-\d+\.jar", paper_jar.name).group(1)

    if args.clean:
        shutil.rmtree(SERVER_DIR, ignore_errors=True)
    plugins = SERVER_DIR / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)

    # Only one version of the plugin in the server at a time.
    for old in plugins.glob("isohypse-*.jar"):
        old.unlink()
    shutil.copy2(plugin_jar, plugins)

    install_plugins(plugins, args.mc_version, paper_version)
    write_plugin_config(*parse_coord(args.coord), args.size, args.max_height)
    install_heightmap(args.heightmap)

    if not args.no_eula:
        (SERVER_DIR / "eula.txt").write_text("eula=true\n")

    # Whitelist disabled, offline mode, spectator by default (Paper fills in server.properties on first launch).
    props = {"white-list": "false", "enforce-whitelist": "false", "gamemode": "spectator",
             "online-mode": "false"}  # no account check: lets the offline client of download_minecraft.py --run in
    if args.level_type:
        props["level-type"] = args.level_type
    seed = args.seed if args.seed is not None else str(random.randint(-2**63, 2**63 - 1))
    props["level-seed"] = seed
    set_properties(SERVER_DIR / "server.properties", props)
    print(f"World seed: {seed}")
    if (SERVER_DIR / "world" / "level.dat").exists():
        print("A world already exists: the seed only applies to a new world (use --clean).")

    # Fast flight / teleports: no more "moved too quickly" (throwaway local server).
    spigot = SERVER_DIR / "spigot.yml"
    if not spigot.exists():
        spigot.write_text("settings:\n  moved-too-quickly-multiplier: 1000.0\n  moved-wrongly-threshold: 1000.0\n")

    # Tell Bukkit to use the plugin's generator for the overworld when a custom level-type is requested.
    # bukkit.yml is rewritten (Paper refills the defaults); otherwise a stale generator line is removed.
    bukkit = SERVER_DIR / "bukkit.yml"
    if args.level_type:
        bukkit.write_text("worlds:\n  world:\n    generator: Isohypse\n")
    elif bukkit.exists() and "generator: Isohypse" in bukkit.read_text():
        bukkit.unlink()

    write_ops(args.player)

    print("Starting the server (type 'stop' to stop it)...")
    run_server(java, args.memory, paper_jar, load_gamerules())


if __name__ == "__main__":
    main()
