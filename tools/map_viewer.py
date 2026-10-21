#!/usr/bin/env python3
"""Viewer of the height map of the Realistic world, for a human: land in grey, the sea in blue or not, and the zones of
the intermediate map (temp_map) on top, each one shown or hidden.

Reads the files the plugin writes in plugins/Isohypse/ at startup:
    heightmap.bin   Y of the top block of every column (16 bits, one per block, read from the disk, never loaded whole)
    settings.json   the parameters used to make it, and the result (size, lowest and highest Y, sea level)

The zones are computed by the Java code of the plugin (class TempMapTool), the same as the server uses, so there is one
source of truth: the viewer runs it (java -cp build/... fr.basile.isohypse.TempMapTool <folder>) and shows the result
(temp_map.bin and temp_map.json, written next to the height map). It needs the plugin compiled (python
tools/local_run.py builds it, or gradle build) and Java 25 (JAVA_HOME, the PATH or tmp/jdk-*).

Usage :
    pip install -r tools/requirements.txt
    python tools/map_viewer.py [folder] [--memory 4G]       (default folder : tmp/server/plugins/Isohypse)

Mouse : wheel = zoom, drag = move, double click = copy a /tp command to the clipboard.
The "Recharger" button reads the files again (after the server made a new map). The files are only opened to read what
is on screen, never kept open, so the server can replace them while the viewer is running.
"""
import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk

import numpy as np
from PIL import Image, ImageTk

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "tmp" / "server" / "plugins" / "Isohypse"
IS_WINDOWS = os.name == "nt"
JAVA_VERSION = 25  # the toolchain of build.gradle.kts

HEIGHTMAP_MAGIC = 0x49534F4D
ZONES_MAGIC = 0x49534F54
HEADER = 12          # magic, width, height (big-endian ints), then the data
MAX_SCALE = 16.0
TOOL_CLASS = "fr.basile.isohypse.TempMapTool"

SEA_LIGHT = np.array([130, 200, 255], dtype=np.float32)   # shallow sea
SEA_DARK = np.array([10, 40, 120], dtype=np.float32)      # deep sea


class LoadError(Exception):
    pass


def load(folder: Path):
    """The settings of the map and its size; raises LoadError if the files are missing or do not agree."""
    for name in ("heightmap.bin", "settings.json"):
        if not (folder / name).is_file():
            raise LoadError(f"{folder / name} not found. Start a Realistic server first (python tools/local_run.py "
                            "--clean), or give the folder of the plugin as argument.")
    try:
        settings = json.loads((folder / "settings.json").read_text(encoding="utf-8"))
        with open(folder / "heightmap.bin", "rb") as f:
            magic, width, height = struct.unpack(">iii", f.read(HEADER))
        results = settings["results"]
        if magic != HEIGHTMAP_MAGIC:
            raise LoadError("heightmap.bin is not a height map of the plugin.")
        if (width, height) != (results["width"], results["height"]):
            raise LoadError("heightmap.bin and settings.json do not have the same size: start the server again.")
        return settings, width, height
    except (OSError, ValueError, KeyError, struct.error) as e:
        raise LoadError(f"cannot read the height map: {e}") from e


def rgb_of(value: int):
    return (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF


def hex_of(value: int) -> str:
    return "#%06x" % value


# --- the Java tool -----------------------------------------------------------
def java_major(java: str) -> int:
    try:
        out = subprocess.run([java, "-version"], capture_output=True, text=True).stderr
    except OSError:
        return 0
    m = re.search(r'version "(\d+)', out)
    return int(m.group(1)) if m else 0


def find_java():
    exe = "java.exe" if IS_WINDOWS else "java"
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / exe)
    on_path = shutil.which("java")
    if on_path:
        candidates.append(Path(on_path))
    for jdk in sorted((ROOT / "tmp").glob("jdk-*")):
        candidates += [jdk / "bin" / exe, jdk / "Contents" / "Home" / "bin" / exe]
    for candidate in candidates:
        if candidate.is_file() and java_major(str(candidate)) >= JAVA_VERSION:
            return str(candidate)
    return None


def find_classpath():
    """The most recent build of the plugin: the compiled classes, or the newest jar."""
    found = []
    classes = ROOT / "build" / "classes" / "java" / "main"
    tool = classes / "fr" / "basile" / "isohypse" / "TempMapTool.class"
    if tool.is_file():
        found.append((tool.stat().st_mtime, str(classes)))
    for jar in (ROOT / "build" / "libs").glob("isohypse-*.jar"):
        if not jar.name.endswith(("-sources.jar", "-javadoc.jar")):
            found.append((jar.stat().st_mtime, str(jar)))
    return max(found)[1] if found else None


class Viewer:
    def __init__(self, root: tk.Tk, folder: Path, memory: str, settings, width, height):
        self.root = root
        self.folder = folder
        self.memory = memory
        self.path = folder / "heightmap.bin"
        self.zones_path = folder / "temp_map.bin"

        self.show_sea = tk.BooleanVar(value=True)
        self.show_zones = tk.BooleanVar(value=False)
        self.zone_opacity = tk.DoubleVar(value=70)
        self.info = tk.StringVar()
        self.zones = None           # the zones of temp_map.json, None until loaded
        self.zone_vars = []
        self.zone_lut = np.zeros((256, 3), dtype=np.float32)
        self.computing = False
        self.scale = 1.0
        self.ox = 0.0   # map column at the left edge of the canvas
        self.oy = 0.0   # map row at the top edge
        self.photo = None
        self.drag = None
        self.fitted = False

        panel = ttk.Frame(root, padding=8)
        panel.pack(side="left", fill="y")
        ttk.Checkbutton(panel, text="Mer en bleu", variable=self.show_sea, command=self.redraw).pack(anchor="w")
        ttk.Button(panel, text="Recharger", command=self.reload).pack(anchor="w", pady=(12, 0))
        ttk.Label(panel, justify="left", textvariable=self.info).pack(anchor="w", pady=(16, 0))
        ttk.Separator(panel).pack(fill="x", pady=12)
        ttk.Checkbutton(panel, text="Zones (temp_map)", variable=self.show_zones,
                        command=self.on_zones_toggle).pack(anchor="w")
        ttk.Label(panel, text="Opacité des zones").pack(anchor="w", pady=(8, 0))
        ttk.Scale(panel, from_=0, to=100, variable=self.zone_opacity, command=lambda v: self.redraw()).pack(fill="x")
        self.zone_frame = ttk.Frame(panel)
        self.zone_frame.pack(anchor="w", pady=(8, 0))

        self.canvas = tk.Canvas(root, bg="#202020", highlightthickness=0)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.status = tk.StringVar()
        ttk.Label(root, textvariable=self.status, anchor="w").pack(side="bottom", fill="x", before=self.canvas)

        self.apply(settings, width, height)

        self.canvas.bind("<Configure>", self.on_resize)
        self.canvas.bind("<MouseWheel>", lambda e: self.zoom(e.x, e.y, 1.25 if e.delta > 0 else 0.8))
        self.canvas.bind("<Button-4>", lambda e: self.zoom(e.x, e.y, 1.25))
        self.canvas.bind("<Button-5>", lambda e: self.zoom(e.x, e.y, 0.8))
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<Motion>", self.on_move)
        self.canvas.bind("<Double-Button-1>", self.on_double)

    def apply(self, settings, width, height):
        """Use a (new) map: its size, its sea level, its lowest and highest Y, and the text of the panel."""
        results = settings["results"]
        self.width, self.height = width, height
        self.sea_level = settings["world"]["sea_level"]
        self.lowest = results["lowest_y"]
        self.highest = results["highest_y"]
        self.info.set(
            f"Carte : {width} x {height} blocs\n"
            f"Hauteurs : {self.lowest} à {self.highest}\n"
            f"Niveau de la mer : {self.sea_level}\n"
            f"Mer : {100 * results['sea_share']:.1f} % de la carte\n"
            f"Échelle : 1 m = {results['scale_blocks_per_m']:.3f} bloc")

    def reload(self):
        """Read heightmap.bin and settings.json again; the view is kept if the map has the same size."""
        try:
            settings, width, height = load(self.folder)
        except LoadError as e:
            self.status.set(f"rechargement impossible : {e}")
            return
        resized = (width, height) != (self.width, self.height)
        self.apply(settings, width, height)
        if resized:
            self.fit()
        self.zones = None
        self.clear_zone_list()
        self.status.set("rechargé")
        if self.show_zones.get():
            self.ensure_zones()
        self.redraw()

    # --- the zones --------------------------------------------------------
    def clear_zone_list(self):
        for child in self.zone_frame.winfo_children():
            child.destroy()
        self.zone_vars = []

    def on_zones_toggle(self):
        if self.show_zones.get():
            self.ensure_zones()
        self.redraw()

    def zones_fresh(self):
        """Whether temp_map.bin and temp_map.json exist, are newer than the height map and its settings, and fit it."""
        zones_json = self.folder / "temp_map.json"
        if not (self.zones_path.is_file() and zones_json.is_file()):
            return False
        made = min(self.zones_path.stat().st_mtime, zones_json.stat().st_mtime)
        if made < max(self.path.stat().st_mtime, (self.folder / "settings.json").stat().st_mtime):
            return False
        try:
            with open(self.zones_path, "rb") as f:
                magic, width, height = struct.unpack(">iii", f.read(HEADER))
        except (OSError, struct.error):
            return False
        return magic == ZONES_MAGIC and (width, height) == (self.width, self.height)

    def ensure_zones(self):
        """Show the zones: read them if they are fresh, else have the Java tool compute them."""
        if self.zones is not None:
            return
        if self.zones_fresh():
            self.load_zones()
        else:
            self.compute_zones()

    def load_zones(self):
        data = json.loads((self.folder / "temp_map.json").read_text(encoding="utf-8"))
        self.zones = data["zones"]
        self.clear_zone_list()
        for i, zone in enumerate(self.zones):
            self.zone_lut[i] = rgb_of(zone["rgb"])
            var = tk.BooleanVar(value=True)
            self.zone_vars.append(var)
            line = ttk.Frame(self.zone_frame)
            line.pack(anchor="w", fill="x")
            tk.Label(line, bg=hex_of(zone["rgb"]), width=2).pack(side="left", padx=(0, 4))
            ttk.Checkbutton(line, text=f"{zone['name']}  ({zone['range']})", variable=var,
                            command=self.redraw).pack(side="left")

    def compute_zones(self):
        if self.computing:
            return
        java = find_java()
        if not java:
            self.status.set(f"Java {JAVA_VERSION} introuvable (JAVA_HOME, PATH ou tmp/jdk-*)")
            return
        classpath = find_classpath()
        if not classpath:
            self.status.set("plugin non compilé (build/) : lancer python tools/local_run.py ou gradle build")
            return
        self.computing = True
        self.status.set("calcul des zones par le code Java du plugin…")
        result = {}

        def run():
            process = subprocess.Popen([java, f"-Xmx{self.memory}", "-Djava.awt.headless=true", "-cp", classpath,
                                        TOOL_CLASS, str(self.folder)],
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                       errors="replace")
            lines = []
            for line in process.stdout:
                lines.append(line.rstrip())
                result["last"] = lines[-1]
            result["code"] = process.wait()
            result["output"] = "\n".join(lines[-15:])

        threading.Thread(target=run, daemon=True).start()
        self.root.after(300, lambda: self.poll_compute(result))

    def poll_compute(self, result):
        if "code" not in result:
            if "last" in result:
                self.status.set(f"calcul des zones : {result['last']}")
            self.root.after(300, lambda: self.poll_compute(result))
            return
        self.computing = False
        if result["code"] != 0:
            self.status.set("le calcul des zones a échoué (voir le terminal)")
            print(result["output"], file=sys.stderr)
            return
        self.status.set("zones calculées")
        self.load_zones()
        self.redraw()

    # --- reading the map --------------------------------------------------
    def region(self, path, dtype, row0, row1, col0, col1, step):
        """A part of a map file (every `step` blocks), read from the disk; the file is closed after."""
        data = np.memmap(path, dtype=dtype, mode="r", offset=HEADER, shape=(self.height, self.width))
        try:
            return np.array(data[row0:row1:step, col0:col1:step])
        finally:
            del data

    def value_at(self, path, size, fmt, column, row):
        with open(path, "rb") as f:
            f.seek(HEADER + size * (row * self.width + column))
            return struct.unpack(fmt, f.read(size))[0]

    def height_at(self, column, row):
        return self.value_at(self.path, 2, ">h", column, row)

    def zone_at(self, column, row):
        if self.zones is None or not self.zones_path.is_file():
            return None
        try:
            index = self.value_at(self.zones_path, 1, ">B", column, row)
        except (OSError, struct.error):
            return None
        return self.zones[index]["name"] if index < len(self.zones) else None

    # --- view -------------------------------------------------------------
    def fit(self):
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        self.scale = min(cw / self.width, ch / self.height)
        self.ox = -(cw / self.scale - self.width) / 2
        self.oy = -(ch / self.scale - self.height) / 2

    def on_resize(self, event):
        if not self.fitted:
            self.fitted = True
            self.fit()
        self.redraw()

    def zoom(self, sx, sy, factor):
        new = min(MAX_SCALE, self.scale * factor)
        if new == self.scale:
            return
        # keep the map point under the mouse where it is
        mx, my = self.ox + sx / self.scale, self.oy + sy / self.scale
        self.scale = new
        self.ox, self.oy = mx - sx / new, my - sy / new
        self.redraw()

    def on_press(self, event):
        self.drag = (event.x, event.y, self.ox, self.oy)

    def on_drag(self, event):
        if self.drag:
            x, y, ox, oy = self.drag
            self.ox = ox - (event.x - x) / self.scale
            self.oy = oy - (event.y - y) / self.scale
            self.redraw()

    def column_at(self, sx, sy):
        """(column, row) of the map under the canvas point, None outside the map."""
        i, j = int(self.ox + sx / self.scale), int(self.oy + sy / self.scale)
        return (i, j) if 0 <= i < self.width and 0 <= j < self.height else None

    def on_move(self, event):
        hit = self.column_at(event.x, event.y)
        if not hit:
            return
        i, j = hit
        text = f"x {i - self.width // 2}  z {j - self.height // 2}  |  y {self.height_at(i, j)}"
        zone = self.zone_at(i, j) if self.show_zones.get() else None
        self.status.set(text + (f"  |  {zone}" if zone else ""))

    def on_double(self, event):
        hit = self.column_at(event.x, event.y)
        if hit:
            i, j = hit
            command = f"/tp @s {i - self.width // 2} {self.height_at(i, j) + 2} {j - self.height // 2}"
            self.root.clipboard_clear()
            self.root.clipboard_append(command)
            self.status.set(f"copié : {command}")

    # --- drawing ----------------------------------------------------------
    def redraw(self):
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        self.canvas.delete("all")
        ix0, iy0 = max(0, int(self.ox)), max(0, int(self.oy))
        ix1 = min(self.width, int(self.ox + cw / self.scale) + 2)
        iy1 = min(self.height, int(self.oy + ch / self.scale) + 2)
        if ix1 <= ix0 or iy1 <= iy0:
            return
        step = max(1, int(1 / self.scale))  # zoomed out: one block out of `step`
        try:
            sub = self.region(self.path, ">i2", iy0, iy1, ix0, ix1, step).astype(np.float32)
        except (OSError, ValueError) as e:  # e.g. the server is writing a new map
            self.status.set(f"carte illisible pour le moment ({e}), essayer Recharger")
            return

        if self.show_sea.get():
            # land stretched from the sea level to the highest point, the sea in blue from light (shallow) to dark (deep)
            span = max(1, self.highest - self.sea_level)
            land = np.clip((sub - self.sea_level) / span, 0, 1) * 255
            depth = np.clip((self.sea_level - sub) / max(1, self.sea_level - self.lowest), 0, 1)[..., None]
            sea = (1 - depth) * SEA_LIGHT + depth * SEA_DARK
            rgb = np.where((sub < self.sea_level)[..., None], sea, np.repeat(land[..., None], 3, axis=2))
        else:
            grey = np.clip((sub - self.lowest) / max(1, self.highest - self.lowest), 0, 1) * 255
            rgb = np.repeat(grey[..., None], 3, axis=2)

        if self.show_zones.get() and self.zones is not None:
            try:
                ids = self.region(self.zones_path, "u1", iy0, iy1, ix0, ix1, step)
            except (OSError, ValueError):
                ids = None
            if ids is not None:
                shown = np.array([v.get() for v in self.zone_vars] + [False] * (256 - len(self.zone_vars)))
                alpha = self.zone_opacity.get() / 100
                rgb = np.where(shown[ids][..., None], (1 - alpha) * rgb + alpha * self.zone_lut[ids], rgb)

        image = Image.fromarray(rgb.astype(np.uint8))
        size = (max(1, round(sub.shape[1] * step * self.scale)), max(1, round(sub.shape[0] * step * self.scale)))
        self.photo = ImageTk.PhotoImage(image.resize(size, Image.NEAREST))
        self.canvas.create_image((ix0 - self.ox) * self.scale, (iy0 - self.oy) * self.scale, image=self.photo, anchor="nw")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", type=Path, default=DEFAULT_DIR,
                    help="plugins/Isohypse folder (default: tmp/server/plugins/Isohypse)")
    ap.add_argument("--memory", default="4G", help="memory of the Java tool that computes the zones (default: 4G)")
    args = ap.parse_args()
    try:
        settings, width, height = load(args.folder)
    except LoadError as e:
        sys.exit(str(e))

    root = tk.Tk()
    root.title(f"Isohypse : {args.folder}")
    root.geometry("1400x900")
    Viewer(root, args.folder, args.memory, settings, width, height)
    root.mainloop()


if __name__ == "__main__":
    main()
