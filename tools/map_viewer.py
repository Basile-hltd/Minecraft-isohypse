#!/usr/bin/env python3
"""Viewer of the Realistic world: the height map for a human (land in grey, the sea in blue or not), the zones of the
intermediate map (temp_map), the biomes (at the surface, in the caves, in the deep caves) and the structures the game
is expected to generate on top, each zone, biome or structure shown or hidden.

It only reads what the plugin computed, so it shows exactly what the plugin uses (nothing is computed here), in
plugins/Isohypse/:
    world.bin       the layers of the world, one file (format: WorldFile.java): height (Y of the top block of every column),
                    temp (zone of every column), biome / cave_biome / deep_cave_biome (one cell per 4 x 4 blocks); read
                    from the disk, never loaded whole
    settings.json   the parameters used, and the results (size, lowest and highest Y, sea level, names of the zones, seed,
                    list of the biomes)

The structures are not in world.bin: Paper's own generation places them (the plugin only lets it). The viewer works out
an estimate of where, with structure_estimator.py, from the rules of the game, the data of the jar of the server (found in
the server folder, or given with --server-jar), the seed of settings.json and the heights and biomes of world.bin.

The colours are the viewer's own: the plugin only gives names.

Usage :
    pip install -r tools/requirements.txt
    python tools/map_viewer.py [folder] [--server-jar PATH]        (default folder : tmp/server/plugins/Isohypse)

Mouse : wheel = zoom, drag = move, double click = copy a /tp command to the clipboard.
The "Recharger" button reads the files again (after the server made a new map). The files are only opened to read what
is on screen, never kept open, so the server can replace them while the viewer is running.
"""
import argparse
import hashlib
import json
import struct
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk

import numpy as np
from PIL import Image, ImageTk

import structure_estimator

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "tmp" / "server" / "plugins" / "Isohypse"

WORLD_MAGIC = 0x49534F57
WORLD_VERSION = 1
DTYPES = {1: "u1", 2: ">i2"}   # type codes of WorldFile.java
MAX_SCALE = 16.0
DOT_RADIUS = 5      # screen pixels, a structure
HOVER_RADIUS = 9    # screen pixels: a structure closer than this to the mouse is named in the status bar
CAVE_DEPTH, CAVE_SEA_MARGIN, DEEP_DARK_Y = 8, 8, -24   # the rules of the caves of BiomeLayout.java
POINT = np.dtype([("kind", np.int32), ("x", np.int32), ("z", np.int32), ("y", np.int32)])

SEA_LIGHT = np.array([130, 200, 255], dtype=np.float32)   # shallow sea
SEA_DARK = np.array([10, 40, 120], dtype=np.float32)      # deep sea

# The colours of the zones of the intermediate map, by name; a name that is not here gets a colour drawn from the name.
ZONE_COLORS = {
    "ocean_cold": 0x14347A, "ocean_normal": 0x1F4FA3, "ocean_warm": 0x3FC0D0,
    "beach": 0xF0E0A0, "warm": 0xE8A23C, "normal": 0x6AA84F, "cold": 0xDDE6EE,
}

# The colours of the biomes, by name (those of the former maps).
BIOME_COLORS = {
    "badlands": 0xD94515, "bamboo_jungle": 0x768E14, "beach": 0xF0E0A0, "birch_forest": 0x307444,
    "cherry_grove": 0xF4A6C8, "cold_ocean": 0x2A6AA8, "dappled_forest": 0x4F9A3C, "dark_forest": 0x40511A,
    "deep_cold_ocean": 0x1D4A7A, "deep_dark": 0x0A2830, "deep_frozen_ocean": 0x6A92C0,
    "deep_lukewarm_ocean": 0x236B99, "deep_ocean": 0x14347A, "desert": 0xFA9418, "dripstone_caves": 0x8B5A2B,
    "flower_forest": 0x2D8E49, "forest": 0x056621, "frozen_ocean": 0x8FB8E0, "frozen_peaks": 0xFFFFFF,
    "grove": 0xC3D6CC, "ice_spikes": 0xB4DCDC, "jagged_peaks": 0xEEF2F6, "jungle": 0x537B09,
    "lukewarm_ocean": 0x2F8FC4, "lush_caves": 0x9ED82A, "mangrove_swamp": 0x67352B, "meadow": 0x83BB6D,
    "ocean": 0x1F4FA3, "old_growth_birch_forest": 0x589C6C, "old_growth_pine_taiga": 0x596651,
    "old_growth_spruce_taiga": 0x818E79, "pale_garden": 0xB9C1B0, "plains": 0x8DB360, "savanna": 0xBDB25F,
    "savanna_plateau": 0xA79D64, "snowy_plains": 0xF2F6FF, "snowy_slopes": 0xDDE6EE, "snowy_taiga": 0x31554A,
    "sparse_jungle": 0x628B17, "stony_peaks": 0x8A8A8A, "stony_shore": 0x8A8A7A, "sulfur_caves": 0xE8D820,
    "sunflower_plains": 0xB5DB88, "swamp": 0x07F9B2, "taiga": 0x0B6659, "warm_ocean": 0x3FC0D0,
    "windswept_forest": 0x507050, "windswept_gravelly_hills": 0x789878, "windswept_hills": 0x606060,
    "windswept_savanna": 0xE5DA87, "wooded_badlands": 0xB09765,
}
BIOME_COLORS["none"] = 0x8C8C8C   # a cave without a biome of its own

# The layers of biomes: (key in settings.json for the depth, title, name in world.bin)
BIOME_LAYERS = (("", "Surface", "biome"), ("cave_y", "Grottes", "cave_biome"),
                ("deep_cave_y", "Grottes profondes", "deep_cave_biome"))


def family(name: str) -> str:
    """The structures of a same kind (village_plains, village_desert...) share a colour."""
    for prefix in ("village", "ruined_portal", "ocean_ruin", "shipwreck", "abandoned_camp", "mineshaft"):
        if name.startswith(prefix):
            return prefix
    return name


def color_of(name: str) -> int:
    if name in ZONE_COLORS:
        return ZONE_COLORS[name]
    if name in BIOME_COLORS:
        return BIOME_COLORS[name]
    digest = hashlib.md5(name.encode()).digest()
    return digest[0] << 16 | digest[1] << 8 | digest[2]


def rgb_of(value: int):
    return (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF


def hex_of(value: int) -> str:
    return "#%06x" % value


class LoadError(Exception):
    pass


class Layer:
    """A layer of world.bin: where it is in the file and how to read it."""

    def __init__(self, name, dtype, step, columns, rows, offset):
        self.name, self.dtype, self.step = name, dtype, step
        self.columns, self.rows, self.offset = columns, rows, offset
        self.size = np.dtype(dtype).itemsize

    def _map(self, path):
        return np.memmap(path, dtype=self.dtype, mode="r", offset=self.offset, shape=(self.rows, self.columns))

    def region(self, path, row0, row1, col0, col1, skip):
        """The layer at the blocks row0..row1 and col0..col1, every `skip` blocks, read from the disk (the file is closed
        after): an array as big as the one of a layer with one cell per block, the cells of a coarser layer repeated."""
        data = self._map(path)
        try:
            s = self.step
            if s == 1:
                return np.array(data[row0:row1:skip, col0:col1:skip])
            r0, c0 = row0 // s, col0 // s
            block = np.array(data[r0:(row1 - 1) // s + 1, c0:(col1 - 1) // s + 1])
        finally:
            del data
        rows = np.arange(row0, row1, skip) // s - r0
        columns = np.arange(col0, col1, skip) // s - c0
        return block[np.ix_(rows, columns)]

    def counts(self, path):
        """How many cells hold each value (256 numbers)."""
        data = self._map(path)
        try:
            return np.bincount(np.array(data).ravel(), minlength=256)
        finally:
            del data

    def value(self, path, column, row):
        with open(path, "rb") as f:
            f.seek(self.offset + self.size * ((row // self.step) * self.columns + column // self.step))
            raw = f.read(self.size)
        return int(np.frombuffer(raw, dtype=self.dtype)[0])


def read_directory(path: Path):
    """(width, height, {name: Layer}) of a world.bin."""
    with open(path, "rb") as f:
        magic, version, width, height, count = struct.unpack(">iiiii", f.read(20))
        if magic != WORLD_MAGIC:
            raise LoadError("world.bin is not a world file of the plugin.")
        if version != WORLD_VERSION:
            raise LoadError(f"world.bin is of the version {version}, this viewer reads the version {WORLD_VERSION}.")
        layers = {}
        for _ in range(count):
            (length,) = struct.unpack(">B", f.read(1))
            name = f.read(length).decode("ascii")
            dtype, step, columns, rows, offset = struct.unpack(">Biiiq", f.read(21))
            layers[name] = Layer(name, DTYPES[dtype], step, columns, rows, offset)
    return width, height, layers


def load(folder: Path):
    """(settings, width, height, layers); raises LoadError if the files are missing or do not agree."""
    for name in ("world.bin", "settings.json"):
        if not (folder / name).is_file():
            raise LoadError(f"{folder / name} not found. Start a Realistic server first (python tools/local_run.py "
                            "--clean), or give the folder of the plugin as argument.")
    try:
        settings = json.loads((folder / "settings.json").read_text(encoding="utf-8"))
        width, height, layers = read_directory(folder / "world.bin")
        results = settings["results"]
        if (width, height) != (results["width"], results["height"]):
            raise LoadError("world.bin and settings.json do not have the same size: start the server again.")
        if "height" not in layers:
            raise LoadError("world.bin has no height layer.")
        return settings, width, height, layers
    except (OSError, ValueError, KeyError, struct.error) as e:
        raise LoadError(f"cannot read the world: {e}") from e


def find_server_jar(folder: Path):
    """The jar of Paper of the server whose plugin folder is `folder` (plugins/Isohypse): its newest versions/*/paper-*.jar."""
    jars = sorted(folder.resolve().parent.parent.glob("versions/*/paper-*.jar"), key=lambda jar: jar.stat().st_mtime)
    return jars[-1] if jars else None


class Scrollable(ttk.Frame):
    """A frame with a vertical scrollbar; put the widgets in .body."""

    def __init__(self, parent):
        super().__init__(parent)
        canvas = tk.Canvas(self, highlightthickness=0, width=270, height=420)
        bar = ttk.Scrollbar(self, orient="vertical", command=canvas.yview)
        self.body = ttk.Frame(canvas)
        self.body.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.create_window((0, 0), window=self.body, anchor="nw")
        canvas.configure(yscrollcommand=bar.set)
        canvas.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")
        canvas.bind("<Enter>", lambda e: canvas.bind_all("<MouseWheel>", lambda ev: canvas.yview_scroll(-ev.delta // 120, "units")))
        canvas.bind("<Leave>", lambda e: canvas.unbind_all("<MouseWheel>"))


class Viewer:
    def __init__(self, root: tk.Tk, folder: Path, settings, width, height, layers, server_jar=None):
        self.root = root
        self.folder = folder
        self.path = folder / "world.bin"
        self.server_jar = server_jar
        self.structures_state = "idle"   # idle, computing, done
        self.structures_job = 0          # tells a computation that is outdated

        self.show_sea = tk.BooleanVar(value=True)
        self.show_zones = tk.BooleanVar(value=False)
        self.zone_opacity = tk.DoubleVar(value=70)
        self.show_biomes = tk.BooleanVar(value=False)
        self.biome_opacity = tk.DoubleVar(value=70)
        self.layer_title = tk.StringVar()
        self.info = tk.StringVar()
        self.zones = []
        self.zone_vars = []
        self.zone_lut = np.zeros((256, 3), dtype=np.float32)
        self.biome_names = []       # the biomes of settings.json, in the order of their index
        self.biome_vars = {}        # shown or hidden, by name: kept when the layer changes
        self.biome_lut = np.zeros((256, 3), dtype=np.float32)
        self.biome_layers = []      # (title, name of the layer in world.bin)
        self.show_structures = tk.BooleanVar(value=False)
        self.structure_names = []   # the structures found by the estimate, by index
        self.structure_vars = {}    # shown or hidden, by name
        self.points = np.zeros(0, dtype=POINT)
        self.shown_structures = []
        self.scale = 1.0
        self.ox = 0.0   # map column at the left edge of the canvas
        self.oy = 0.0   # map row at the top edge
        self.photo = None
        self.drag = None
        self.fitted = False

        panel = ttk.Frame(root, padding=4)
        panel.pack(side="left", fill="y")
        tabs = ttk.Notebook(panel)
        tabs.pack(fill="both", expand=True)

        view = ttk.Frame(tabs, padding=8)
        tabs.add(view, text="Affichage")
        ttk.Checkbutton(view, text="Mer en bleu", variable=self.show_sea, command=self.redraw).pack(anchor="w")
        ttk.Button(view, text="Recharger", command=self.reload).pack(anchor="w", pady=(12, 0))
        ttk.Label(view, justify="left", textvariable=self.info).pack(anchor="w", pady=(16, 0))

        zones = ttk.Frame(tabs, padding=8)
        tabs.add(zones, text="Zones")
        self.zones_check = ttk.Checkbutton(zones, text="Afficher les zones (temp_map)", variable=self.show_zones,
                                           command=self.redraw)
        self.zones_check.pack(anchor="w")
        ttk.Label(zones, text="Opacité").pack(anchor="w", pady=(8, 0))
        ttk.Scale(zones, from_=0, to=100, variable=self.zone_opacity, command=lambda v: self.redraw()).pack(fill="x")
        self.zone_frame = ttk.Frame(zones)
        self.zone_frame.pack(anchor="w", pady=(8, 0))

        biomes = ttk.Frame(tabs, padding=8)
        tabs.add(biomes, text="Biomes")
        self.biomes_check = ttk.Checkbutton(biomes, text="Afficher les biomes", variable=self.show_biomes,
                                            command=self.redraw)
        self.biomes_check.pack(anchor="w")
        ttk.Label(biomes, text="Opacité").pack(anchor="w", pady=(8, 0))
        ttk.Scale(biomes, from_=0, to=100, variable=self.biome_opacity, command=lambda v: self.redraw()).pack(fill="x")
        self.layer_box = ttk.Combobox(biomes, textvariable=self.layer_title, state="readonly")
        self.layer_box.pack(fill="x", pady=(8, 4))
        self.layer_box.bind("<<ComboboxSelected>>", lambda e: self.fill_biome_list())
        buttons = ttk.Frame(biomes)
        buttons.pack(fill="x")
        ttk.Button(buttons, text="Tout", command=lambda: self.set_all_biomes(True)).pack(side="left", expand=True, fill="x")
        ttk.Button(buttons, text="Aucun", command=lambda: self.set_all_biomes(False)).pack(side="left", expand=True, fill="x")
        self.biome_list = Scrollable(biomes)
        self.biome_list.pack(fill="both", expand=True, pady=(4, 0))

        structures = ttk.Frame(tabs, padding=8)
        tabs.add(structures, text="Structures")
        self.structures_check = ttk.Checkbutton(structures, text="Afficher les structures attendues",
                                                variable=self.show_structures, command=self.on_structures_toggle)
        self.structures_check.pack(anchor="w")
        ttk.Label(structures, justify="left", wraplength=260, text=(
            "Estimation d'après les règles de Minecraft, calculée ici : la grille de placement est exacte, le point de départ "
            "et le biome suivent chaque type de structure. Paper génère les vraies.")).pack(anchor="w", pady=(6, 6))
        sbuttons = ttk.Frame(structures)
        sbuttons.pack(fill="x")
        ttk.Button(sbuttons, text="Tout", command=lambda: self.set_all_structures(True)).pack(side="left", expand=True, fill="x")
        ttk.Button(sbuttons, text="Aucun", command=lambda: self.set_all_structures(False)).pack(side="left", expand=True, fill="x")
        self.structure_list = Scrollable(structures)
        self.structure_list.pack(fill="both", expand=True, pady=(4, 0))

        self.canvas = tk.Canvas(root, bg="#202020", highlightthickness=0)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.status = tk.StringVar()
        ttk.Label(root, textvariable=self.status, anchor="w").pack(side="bottom", fill="x", before=self.canvas)

        self.apply(settings, width, height, layers)

        self.canvas.bind("<Configure>", self.on_resize)
        self.canvas.bind("<MouseWheel>", lambda e: self.zoom(e.x, e.y, 1.25 if e.delta > 0 else 0.8))
        self.canvas.bind("<Button-4>", lambda e: self.zoom(e.x, e.y, 1.25))
        self.canvas.bind("<Button-5>", lambda e: self.zoom(e.x, e.y, 0.8))
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<Motion>", self.on_move)
        self.canvas.bind("<Double-Button-1>", self.on_double)

    def apply(self, settings, width, height, layers):
        """Use a (new) world: its size, its sea level, its lowest and highest Y, its layers, and the panel."""
        results = settings["results"]
        build = settings.get("build", {})
        self.width, self.height = width, height
        self.layers = layers
        self.sea_level = settings["world"]["sea_level"]
        self.lowest = results["lowest_y"]
        self.highest = results["highest_y"]
        seed = build.get("seed")
        self.info.set(
            f"Carte : {width} x {height} blocs\n"
            f"Hauteurs : {self.lowest} à {self.highest}\n"
            f"Niveau de la mer : {self.sea_level}\n"
            f"Mer : {100 * results['sea_share']:.1f} % de la carte\n"
            f"Échelle : 1 m = {results['scale_blocks_per_m']:.3f} bloc\n"
            f"Seed : {seed if seed is not None else 'inconnue'}")

        # the zones of the intermediate map: names and ranges from the settings, colours from here
        for child in self.zone_frame.winfo_children():
            child.destroy()
        self.zones = results.get("temp_zones", []) if "temp" in layers else []
        self.zone_vars = []
        for i, zone in enumerate(self.zones):
            self.zone_lut[i] = rgb_of(color_of(zone["name"]))
            var = tk.BooleanVar(value=True)
            self.zone_vars.append(var)
            line = ttk.Frame(self.zone_frame)
            line.pack(anchor="w", fill="x")
            tk.Label(line, bg=hex_of(color_of(zone["name"])), width=2).pack(side="left", padx=(0, 4))
            ttk.Checkbutton(line, text=f"{zone['name']}  ({zone['range']})", variable=var,
                            command=self.redraw).pack(side="left")
        self.zones_check.state(["!disabled"] if self.zones else ["disabled"])

        # the biomes: the list of names from the settings, the layers from the world
        self.biome_names = build.get("biomes", [])
        self.biome_lut[:] = 0
        for i, name in enumerate(self.biome_names):
            self.biome_lut[i] = rgb_of(color_of(name))
            self.biome_vars.setdefault(name, tk.BooleanVar(value=False))
        self.biome_layers = []
        for depth_key, title, layer in BIOME_LAYERS:
            if layer in layers and self.biome_names:
                depth = f" (y={build[depth_key]})" if depth_key and depth_key in build else ""
                self.biome_layers.append((title + depth, layer))
        self.layer_box.configure(values=[title for title, _ in self.biome_layers])
        if self.layer_title.get() not in [title for title, _ in self.biome_layers]:
            self.layer_title.set(self.biome_layers[0][0] if self.biome_layers else "")
        self.biomes_check.state(["!disabled"] if self.biome_layers else ["disabled"])
        self.fill_biome_list()

        # the structures are worked out when asked for (compute_structures): a new world makes the estimate outdated
        self.seed = seed
        self.structures_job += 1
        self.structures_state = "idle"
        self.structure_names, self.points = [], np.zeros(0, dtype=POINT)
        self.fill_structure_list()

    def current_layer(self):
        """The name in world.bin of the layer of biomes chosen, None if there is none."""
        return next((layer for title, layer in self.biome_layers if title == self.layer_title.get()), None)

    def fill_biome_list(self):
        """The list of the biomes of the chosen layer, the most common first, with their share of the map."""
        for child in self.biome_list.body.winfo_children():
            child.destroy()
        name = self.current_layer()
        if name is None:
            return
        try:
            counts = self.layers[name].counts(self.path)
        except (OSError, ValueError):
            return
        total = max(1, int(counts[:len(self.biome_names)].sum()))
        rows = sorted((i for i in range(len(self.biome_names)) if counts[i] > 0), key=lambda i: -counts[i])
        for i in rows:
            biome = self.biome_names[i]
            line = ttk.Frame(self.biome_list.body)
            line.pack(anchor="w", fill="x")
            tk.Label(line, bg=hex_of(color_of(biome)), width=2).pack(side="left", padx=(0, 4))
            ttk.Checkbutton(line, text=f"{biome}  ({100 * counts[i] / total:.1f} %)", variable=self.biome_vars[biome],
                            command=self.redraw).pack(side="left")
        self.shown_biomes = [self.biome_names[i] for i in rows]
        self.redraw()

    def set_all_biomes(self, value):
        for biome in getattr(self, "shown_biomes", []):
            self.biome_vars[biome].set(value)
        self.redraw()

    def on_structures_toggle(self):
        if self.show_structures.get() and self.structures_state == "idle":
            self.compute_structures()
        self.redraw()

    def compute_structures(self):
        """Works out the structures in a thread (a few seconds), from the layers of the world and the data of the server."""
        if self.seed is None:
            self.status.set("structures : la seed du monde n'est pas dans settings.json")
            return
        jar = self.server_jar or find_server_jar(self.folder)
        if jar is None:
            self.status.set("structures : jar de Paper introuvable, voir --server-jar")
            return
        for layer in ("biome", "cave_biome", "deep_cave_biome"):
            if layer not in self.layers:
                self.status.set(f"structures : la couche {layer} manque dans world.bin")
                return
        self.structures_state = "computing"
        self.status.set("calcul des structures attendues…")
        job = self.structures_job
        result = {}
        width, height, sea, names, path, layers = self.width, self.height, self.sea_level, self.biome_names, self.path, self.layers

        def run():
            try:
                heights = layers["height"]._map(path)
                cells = {name: layers[name]._map(path) for name in ("biome", "cave_biome", "deep_cave_biome")}

                def height_at(x, z):
                    return int(heights[(z + height // 2) % height, (x + width // 2) % width])

                def cell(name, x, z):
                    step = layers[name].step
                    return int(cells[name][((z + height // 2) % height) // step, ((x + width // 2) % width) // step])

                def biome_at(x, y, z):
                    # the rules of the caves of BiomeLayout / WorldData.biomeAt, from the layers
                    if y < height_at(x, z) - CAVE_DEPTH and y < sea - CAVE_SEA_MARGIN:
                        index = cell("deep_cave_biome" if y < DEEP_DARK_Y else "cave_biome", x, z)
                        if 0 < index < len(names):
                            return names[index]
                    index = cell("biome", x, z)
                    return names[index] if 0 < index < len(names) else None

                estimator = structure_estimator.StructureEstimator(structure_estimator.JarData(jar), self.seed, height_at,
                                                                   biome_at, sea)
                found = estimator.estimate((-width // 2) // 16, (width // 2 - 1) // 16, (-height // 2) // 16,
                                           (height // 2 - 1) // 16)
                del heights, cells
                result["found"] = found
            except Exception as e:  # reported in the status bar
                result["error"] = e

        threading.Thread(target=run, daemon=True).start()
        self.root.after(200, lambda: self.poll_structures(job, result))

    def poll_structures(self, job, result):
        if job != self.structures_job:
            return  # the world was reloaded meanwhile
        if "found" not in result and "error" not in result:
            self.root.after(200, lambda: self.poll_structures(job, result))
            return
        if "error" in result:
            self.structures_state = "idle"
            self.status.set(f"structures : {result['error']}")
            return
        found = result["found"]
        names = sorted({f.structure for f in found})
        index = {name: i for i, name in enumerate(names)}
        points = np.zeros(len(found), dtype=POINT)
        for i, f in enumerate(found):
            points[i] = (index[f.structure], f.x, f.z, f.y)
        self.structure_names, self.points = names, points
        for name in names:
            self.structure_vars.setdefault(name, tk.BooleanVar(value=True))
        self.structures_state = "done"
        self.fill_structure_list()
        self.status.set(f"{len(found)} structures attendues")
        self.redraw()

    def fill_structure_list(self):
        """The list of the structures, the most numerous first, with how many there are."""
        for child in self.structure_list.body.winfo_children():
            child.destroy()
        counts = np.bincount(self.points["kind"], minlength=len(self.structure_names)) \
            if len(self.points) else np.zeros(len(self.structure_names), dtype=np.int64)
        rows = sorted((i for i in range(len(self.structure_names)) if counts[i] > 0), key=lambda i: -counts[i])
        for i in rows:
            name = self.structure_names[i]
            line = ttk.Frame(self.structure_list.body)
            line.pack(anchor="w", fill="x")
            tk.Label(line, bg=hex_of(color_of(family(name))), width=2).pack(side="left", padx=(0, 4))
            ttk.Checkbutton(line, text=f"{name}  ({counts[i]})", variable=self.structure_vars[name],
                            command=self.redraw).pack(side="left")
        self.shown_structures = [self.structure_names[i] for i in rows]

    def set_all_structures(self, value):
        for name in self.shown_structures:
            self.structure_vars[name].set(value)
        self.redraw()

    def visible_points(self):
        """The points of the structures that are shown."""
        if not self.show_structures.get() or not len(self.points):
            return self.points[:0]
        shown = np.array([self.structure_vars[n].get() for n in self.structure_names], dtype=bool)
        return self.points[shown[self.points["kind"]]]

    def structure_near(self, mx, my):
        """The shown structure closest to the map point, within HOVER_RADIUS screen pixels, as text."""
        points = self.visible_points()
        if not len(points):
            return None
        d = np.hypot(points["x"] + self.width // 2 - mx, points["z"] + self.height // 2 - my)
        k = int(d.argmin())
        if d[k] * self.scale > HOVER_RADIUS:
            return None
        point = points[k]
        return f"{self.structure_names[point['kind']]} ({point['x']}, {point['z']}, y {point['y']})"

    def reload(self):
        """Read world.bin and settings.json again; the view is kept if the map has the same size."""
        try:
            settings, width, height, layers = load(self.folder)
        except LoadError as e:
            self.status.set(f"rechargement impossible : {e}")
            return
        resized = (width, height) != (self.width, self.height)
        self.apply(settings, width, height, layers)
        if resized:
            self.fit()
        self.status.set("rechargé")
        if self.show_structures.get():
            self.compute_structures()
        self.redraw()

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

    def height_at(self, column, row):
        return self.layers["height"].value(self.path, column, row)

    def zone_at(self, column, row):
        if not self.zones:
            return None
        index = self.layers["temp"].value(self.path, column, row)
        return self.zones[index]["name"] if index < len(self.zones) else None

    def biome_at(self, layer, column, row):
        index = self.layers[layer].value(self.path, column, row)
        return self.biome_names[index] if index < len(self.biome_names) else None

    def on_move(self, event):
        hit = self.column_at(event.x, event.y)
        if not hit:
            return
        i, j = hit
        try:
            text = f"x {i - self.width // 2}  z {j - self.height // 2}  |  y {self.height_at(i, j)}"
            if self.show_zones.get() and self.zone_at(i, j):
                text += f"  |  zone {self.zone_at(i, j)}"
            if self.show_biomes.get():
                for title, layer in self.biome_layers:
                    text += f"  |  {title.lower()} : {self.biome_at(layer, i, j)}"
            near = self.structure_near(self.ox + event.x / self.scale, self.oy + event.y / self.scale)
            if near:
                text += f"  |  {near}"
        except (OSError, ValueError):  # e.g. the server is writing a new world
            return
        self.status.set(text)

    def on_double(self, event):
        hit = self.column_at(event.x, event.y)
        if hit:
            i, j = hit
            command = f"/tp @s {i - self.width // 2} {self.height_at(i, j) + 2} {j - self.height // 2}"
            self.root.clipboard_clear()
            self.root.clipboard_append(command)
            self.status.set(f"copié : {command}")

    # --- drawing ----------------------------------------------------------
    def overlay(self, rgb, ids, lut, names_shown, opacity):
        """The picture with the cells of the shown values painted over, with the opacity given."""
        shown = np.array(names_shown + [False] * (256 - len(names_shown)))
        alpha = opacity / 100
        return np.where(shown[ids][..., None], (1 - alpha) * rgb + alpha * lut[ids], rgb)

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
            sub = self.layers["height"].region(self.path, iy0, iy1, ix0, ix1, step).astype(np.float32)
        except (OSError, ValueError) as e:  # e.g. the server is writing a new world
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

        try:
            if self.show_zones.get() and self.zones:
                ids = self.layers["temp"].region(self.path, iy0, iy1, ix0, ix1, step)
                if ids.shape == sub.shape:
                    rgb = self.overlay(rgb, ids, self.zone_lut, [v.get() for v in self.zone_vars], self.zone_opacity.get())
            layer = self.current_layer()
            if self.show_biomes.get() and layer:
                ids = self.layers[layer].region(self.path, iy0, iy1, ix0, ix1, step)
                if ids.shape == sub.shape:
                    rgb = self.overlay(rgb, ids, self.biome_lut, [self.biome_vars[n].get() for n in self.biome_names],
                                       self.biome_opacity.get())
        except (OSError, ValueError):
            pass  # a layer that cannot be read for now: the map without it

        image = Image.fromarray(rgb.astype(np.uint8))
        size = (max(1, round(sub.shape[1] * step * self.scale)), max(1, round(sub.shape[0] * step * self.scale)))
        self.photo = ImageTk.PhotoImage(image.resize(size, Image.NEAREST))
        self.canvas.create_image((ix0 - self.ox) * self.scale, (iy0 - self.oy) * self.scale, image=self.photo, anchor="nw")

        points = self.visible_points()
        if len(points):
            px = (points["x"] + self.width // 2 - self.ox) * self.scale
            py = (points["z"] + self.height // 2 - self.oy) * self.scale
            inside = (px > -DOT_RADIUS) & (px < cw + DOT_RADIUS) & (py > -DOT_RADIUS) & (py < ch + DOT_RADIUS)
            for x, y, kind in zip(px[inside], py[inside], points["kind"][inside]):
                self.canvas.create_oval(x - DOT_RADIUS, y - DOT_RADIUS, x + DOT_RADIUS, y + DOT_RADIUS,
                                        fill=hex_of(color_of(family(self.structure_names[kind]))), outline="black")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", type=Path, default=DEFAULT_DIR,
                    help="plugins/Isohypse folder (default: tmp/server/plugins/Isohypse)")
    ap.add_argument("--server-jar", type=Path,
                    help="jar of Paper, for the data of the structures (default: the newest of the versions/ folder of the server)")
    args = ap.parse_args()
    try:
        settings, width, height, layers = load(args.folder)
    except LoadError as e:
        sys.exit(str(e))

    root = tk.Tk()
    root.title(f"Isohypse : {args.folder}")
    root.geometry("1400x900")
    Viewer(root, args.folder, settings, width, height, layers, args.server_jar)
    root.mainloop()


if __name__ == "__main__":
    main()
