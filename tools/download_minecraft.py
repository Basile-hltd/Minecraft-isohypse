#!/usr/bin/env python3
"""Download the Minecraft client (official Mojang jar) and make sure the matching Java is installed in tmp/.

Everything goes in tmp/ at the repo root (ignored by git), shared with local_run.py:
    tmp/minecraft-<mc>/   client.jar and the version json (libraries, assets, java version)
    tmp/minecraft-libraries/, tmp/minecraft-assets/   shared by all versions
    tmp/minecraft-game/   game directory used by --run (options, saves, screenshots)
    tmp/jdk-<ver>/        Temurin JDK required by that Minecraft version

Usage :
    python tools/download_minecraft.py             list the available versions (releases)
    python tools/download_minecraft.py --all       list releases, snapshots and old versions
    python tools/download_minecraft.py 1.21.8      download that version (jar, libraries, assets) + check its Java
    python tools/download_minecraft.py 1.21.8 --run [--player NAME] [--server localhost:25565]
                                                   ... and start it in offline mode (no account needed;
                                                   the server must have online-mode=false, as local_run.py sets)
"""
import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import shutil
import sys
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

from local_run import IS_WINDOWS, TMP, download, extract, java_major, offline_uuid

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
TEMURIN_VERSIONS = (8, 11, 17, 21, 25)


def get_manifest() -> dict:
    return requests.get(MANIFEST, timeout=30).json()


def list_versions(show_all: bool) -> None:
    manifest = get_manifest()
    kinds = ("release", "snapshot", "old_beta", "old_alpha") if show_all else ("release",)
    versions = [v["id"] for v in manifest["versions"] if v["type"] in kinds]
    print(f"Available versions (latest first, latest release: {manifest['latest']['release']}):")
    print("  " + ", ".join(versions))
    print(f"\nUsage: {Path(sys.argv[0]).name} <version>" + ("" if show_all else "   (--all to include snapshots)"))


def java_exe(home: Path) -> Path:
    return home / "bin" / ("java.exe" if IS_WINDOWS else "java")


def ensure_java(version: int) -> Path:
    jdk_dir = TMP / f"jdk-{version}"
    home = jdk_dir / "Contents" / "Home" if (jdk_dir / "Contents" / "Home").exists() else jdk_dir
    if java_exe(home).exists() and java_major(str(java_exe(home))) == version:
        print(f"Java {version} already installed: {jdk_dir}")
        return java_exe(home)
        return

    print(f"Java {version} not found in tmp/, installing it.")
    system = {"Windows": "windows", "Linux": "linux", "Darwin": "mac"}[platform.system()]
    machine = platform.machine().lower()
    arch = "aarch64" if machine in ("arm64", "aarch64") else "x64"
    url = f"https://api.adoptium.net/v3/binary/latest/{version}/ga/{system}/{arch}/jdk/hotspot/normal/eclipse"
    archive = TMP / ("jdk.zip" if IS_WINDOWS else "jdk.tar.gz")
    try:
        download(url, archive)
    except requests.HTTPError:
        # Temurin only publishes LTS builds (e.g. Mojang asks for Java 16): use the next one.
        fallback = next((v for v in TEMURIN_VERSIONS if v > version), None)
        if fallback is None:
            raise
        print(f"No Temurin build for Java {version}, using Java {fallback} instead.")
        return ensure_java(fallback)
    tmp_extract = TMP / "jdk-extract"
    shutil.rmtree(tmp_extract, ignore_errors=True)
    extract(archive, tmp_extract)
    shutil.rmtree(jdk_dir, ignore_errors=True)
    shutil.move(str(next(tmp_extract.iterdir())), jdk_dir)
    shutil.rmtree(tmp_extract, ignore_errors=True)
    print(f"Java {version} installed: {jdk_dir}")
    return java_exe(jdk_dir / "Contents" / "Home" if (jdk_dir / "Contents" / "Home").exists() else jdk_dir)


LIBRARIES = TMP / "minecraft-libraries"
ASSETS = TMP / "minecraft-assets"
GAME_DIR = TMP / "minecraft-game"
RESOURCES_URL = "https://resources.download.minecraft.net"
OS_NAME = {"Windows": "windows", "Linux": "linux", "Darwin": "osx"}[platform.system()]
IS_ARM = platform.machine().lower() in ("arm64", "aarch64")
WORKERS = 32
SESSION = requests.Session()  # keeps connections open: thousands of small assets would otherwise pay a TLS handshake each
SESSION.mount("https://", requests.adapters.HTTPAdapter(pool_connections=4, pool_maxsize=WORKERS))
FEATURES = {"has_quick_plays_support": True}  # every other launcher feature (demo, custom resolution...) is off


def sha1(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def fetch(url: str, dest: Path, sha: str | None = None, size: int | None = None) -> int:
    """Download url to dest unless it is already there (and valid); return the bytes downloaded."""
    if dest.exists() and (size is None or dest.stat().st_size == size) and (sha is None or sha1(dest) == sha):
        return 0
    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(5):
        try:
            r = SESSION.get(url, timeout=60)
            r.raise_for_status()
            break
        except requests.RequestException:
            if attempt == 4:
                raise
            time.sleep(1 + attempt)
    if sha and hashlib.sha1(r.content).hexdigest() != sha:
        raise RuntimeError(f"Invalid SHA-1 for {url}")
    dest.write_bytes(r.content)
    return len(r.content)


def fetch_all(items: list[tuple], label: str) -> None:
    """items: (url, dest, sha1, size) tuples, downloaded in parallel with a progress line."""
    total, start = len(items), time.time()
    print(f"{label}: {total} files (already present ones are skipped)")
    done = fetched = received = 0
    with ThreadPoolExecutor(WORKERS) as pool:
        for n in pool.map(lambda i: fetch(*i), items):
            done += 1
            fetched += n > 0
            received += n
            print(f"\r  {done}/{total} ({100 * done // total}%) - {fetched} downloaded, {received / 1e6:.1f} MB, "
                  f"{time.time() - start:.0f}s", end="", flush=True)
    print()


# --- Mojang json rules ---------------------------------------------------
def rules_match(rules: list | None, features: dict = FEATURES) -> bool:
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os", {})
        ok = (not os_rule.get("name") or os_rule["name"] == OS_NAME)
        if os_rule.get("arch"):  # "x86" = 32 bits, never us
            ok = ok and os_rule["arch"] == ("arm64" if IS_ARM else "x86_64")
        ok = ok and all(features.get(k, False) == v for k, v in rule.get("features", {}).items())
        if ok:
            allowed = rule["action"] == "allow"
    return allowed


def library_files(meta: dict) -> tuple[list[Path], list[tuple], list[Path]]:
    """(classpath jars, downloads, native jars to extract) of the version."""
    classpath, downloads, natives = [], [], []
    for lib in meta["libraries"]:
        if not rules_match(lib.get("rules")):
            continue
        files = []
        artifact = lib.get("downloads", {}).get("artifact")
        if artifact:
            files.append((artifact, True))
        classifier = lib.get("natives", {}).get(OS_NAME)  # old versions: natives in a separate jar
        if classifier:
            native = lib["downloads"]["classifiers"].get(classifier.replace("${arch}", "64"))
            if native:
                files.append((native, False))
        for f, on_classpath in files:
            path = LIBRARIES / f["path"]
            downloads.append((f["url"], path, f["sha1"], f["size"]))
            (classpath if on_classpath else natives).append(path)
    return classpath, downloads, natives


def extract_natives(jars: list[Path], dest: Path) -> None:
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if not name.endswith("/") and not name.startswith("META-INF/"):
                    (dest / Path(name).name).write_bytes(z.read(name))


def download_assets(meta: dict) -> str:
    """Download the asset index and objects (sounds, languages...); return the assets root to give the game."""
    index_info = meta["assetIndex"]
    index_path = ASSETS / "indexes" / f"{index_info['id']}.json"
    fetch(index_info["url"], index_path, index_info["sha1"])
    objects = json.loads(index_path.read_text())["objects"]
    unique = {o["hash"]: o["size"] for o in objects.values()}
    fetch_all([(f"{RESOURCES_URL}/{h[:2]}/{h}", ASSETS / "objects" / h[:2] / h, h, s) for h, s in unique.items()],
              "Assets")
    index = json.loads(index_path.read_text())
    if index.get("virtual") or index.get("map_to_resources"):  # pre-1.7 versions read plain files
        root = ASSETS / "virtual" / index_info["id"]
        for name, o in objects.items():
            target = root / name
            if not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ASSETS / "objects" / o["hash"][:2] / o["hash"], target)
        return str(root)
    return str(ASSETS)


# --- Download ------------------------------------------------------------
def download_version(mc_version: str) -> tuple[dict, Path, Path]:
    """Download everything the game needs; return (version json, java executable, assets root)."""
    entry = next((v for v in get_manifest()["versions"] if v["id"] == mc_version), None)
    if entry is None:
        sys.exit(f"Unknown version '{mc_version}'. Run without argument to list the versions.")
    meta = requests.get(entry["url"], timeout=30).json()
    client = meta["downloads"]["client"]

    dest = TMP / f"minecraft-{mc_version}"
    jar = dest / "client.jar"
    if jar.exists() and sha1(jar) == client["sha1"]:
        print(f"Already downloaded: {jar}")
    else:
        print(f"Minecraft client {mc_version}")
        download(client["url"], jar)
        if sha1(jar) != client["sha1"]:
            jar.unlink()
            sys.exit("Invalid SHA-1 checksum.")
    (dest / f"{mc_version}.json").write_text(json.dumps(meta, indent=2))

    _, downloads, natives = library_files(meta)
    logging = meta.get("logging", {}).get("client")
    if logging:
        f = logging["file"]
        downloads.append((f["url"], ASSETS / "log_configs" / f["id"], f["sha1"], f["size"]))
    fetch_all(downloads, "Libraries")
    print("Extracting natives...")
    extract_natives(natives, dest / "natives")
    print("Fetching the asset index...")
    assets_root = download_assets(meta)

    required_java = meta.get("javaVersion", {}).get("majorVersion", 8)
    print(f"Minecraft {mc_version} requires Java {required_java}.")
    java = ensure_java(required_java)
    print(f"\nMinecraft {mc_version} is ready. Start it with: python tools/download_minecraft.py {mc_version} --run")
    return meta, java, assets_root


# --- Launch --------------------------------------------------------------
def build_command(meta: dict, java: Path, assets_root: str, player: str, memory: str, server: str | None) -> list[str]:
    version = meta["id"]
    classpath, _, _ = library_files(meta)
    classpath.append(TMP / f"minecraft-{version}" / "client.jar")
    variables = {
        "auth_player_name": player,
        "auth_uuid": offline_uuid(player),
        "auth_access_token": "0",
        "auth_session": "0",
        "clientid": "",
        "auth_xuid": "",
        "user_type": "legacy",
        "user_properties": "{}",
        "version_name": version,
        "version_type": meta["type"],
        "game_directory": str(GAME_DIR),
        "assets_root": assets_root,
        "game_assets": assets_root,
        "assets_index_name": meta["assetIndex"]["id"],
        "natives_directory": str(TMP / f"minecraft-{version}" / "natives"),
        "library_directory": str(LIBRARIES),
        "classpath_separator": os.pathsep,
        "classpath": os.pathsep.join(map(str, classpath)),
        "launcher_name": "isohypse",
        "launcher_version": "1",
        "quickPlayMultiplayer": server or "",
    }

    def expand(parts: list) -> list[str]:
        out = []
        for p in parts:
            if isinstance(p, dict):
                if not rules_match(p.get("rules"), {**FEATURES, "is_quick_play_multiplayer": bool(server)}):
                    continue
                p = p["value"]
            for s in [p] if isinstance(p, str) else p:
                out.append(re.sub(r"\$\{(\w+)\}", lambda m: variables.get(m.group(1), m.group(0)), s))
        return out

    if "arguments" in meta:
        jvm, game = expand(meta["arguments"]["jvm"]), expand(meta["arguments"]["game"])
    else:  # versions before 1.13
        jvm = expand(["-Djava.library.path=${natives_directory}", "-cp", "${classpath}"])
        game = expand(meta["minecraftArguments"].split())
        if server:
            host, _, port = server.partition(":")
            game += ["--server", host, "--port", port or "25565"]
    logging = meta.get("logging", {}).get("client")
    if logging:
        jvm.append(logging["argument"].replace("${path}", str(ASSETS / "log_configs" / logging["file"]["id"])))
    return [str(java), f"-Xmx{memory}", *jvm, meta["mainClass"], *game]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("version", nargs="?", help="Minecraft version (e.g. 1.21.8); omit to list the versions")
    ap.add_argument("--all", action="store_true", help="when listing, include snapshots and old alpha/beta versions")
    ap.add_argument("--run", action="store_true", help="start the game (offline mode) once downloaded")
    ap.add_argument("--player", default="Japrisot", help="player name used by --run (default: Japrisot)")
    ap.add_argument("--memory", default="2G", help="game memory used by --run (default: 2G)")
    ap.add_argument("--server", help="server to join directly with --run (e.g. localhost:25565)")
    args = ap.parse_args()
    TMP.mkdir(exist_ok=True)
    if not args.version:
        list_versions(args.all)
        return
    meta, java, assets_root = download_version(args.version)
    if args.run:
        GAME_DIR.mkdir(parents=True, exist_ok=True)
        print(f"Starting Minecraft {args.version} as {args.player} (offline mode)...")
        subprocess.run(build_command(meta, java, assets_root, args.player, args.memory, args.server), cwd=GAME_DIR)


if __name__ == "__main__":
    main()
