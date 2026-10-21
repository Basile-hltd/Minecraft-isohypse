#!/usr/bin/env python3
"""Interactive map of the Realistic world: height map, structures and biomes, each one shown or hidden at will.

Reads the files the plugin writes in plugins/Isohypse/ when it generates the height map:
    heightmap.png    altitudes (16 bits, value = Y)
    biomes*.png      one picture per layer (surface, caves at two depths): biome of every column (8 bits, index in map_data.json)
    map_data.json    size, sea level, the layers with their biomes and colours, place of every structure

Usage :
    pip install -r tools/requirements.txt
    python tools/map_viewer.py [folder]        (default : tmp/server/plugins/Isohypse)

Mouse : wheel = zoom, drag = move, double click = copy a /tp command to the clipboard.
"""
import argparse
import json
import sys
import tkinter as tk
from pathlib import Path
from tkinter import ttk

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "tmp" / "server" / "plugins" / "Isohypse"
Image.MAX_IMAGE_PIXELS = None  # the maps are bigger than Pillow's default safety limit

DOT_RADIUS = 5      # screen pixels
HOVER_RADIUS = 9    # screen pixels : a dot closer than this to the mouse is named in the status bar
MAX_SCALE = 16.0


def rgb_of(value: int) -> tuple[int, int, int]:
    return (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF


def hex_of(value: int) -> str:
    return "#%06x" % value


def load(folder: Path):
    for name in ("heightmap.png", "map_data.json"):
        if not (folder / name).is_file():
            sys.exit(f"{folder / name} not found. Generate a Realistic world first (python tools/local_run.py --clean), "
                     "or give the folder of the plugin as argument.")
    heights = np.asarray(Image.open(folder / "heightmap.png"))
    info = json.loads((folder / "map_data.json").read_text(encoding="utf-8"))
    if "layers" not in info:
        sys.exit("map_data.json is from an older version of the plugin: regenerate the maps.")
    layers = []
    for layer in info["layers"]:
        if not (folder / layer["file"]).is_file():
            sys.exit(f"{folder / layer['file']} not found: regenerate the maps.")
        layers.append(Layer(layer, np.asarray(Image.open(folder / layer["file"]))))
    if any(l.ids.shape != heights.shape for l in layers) or heights.shape != (info["height"], info["width"]):
        sys.exit("The pictures and map_data.json do not have the same size: regenerate the maps.")
    return heights, layers, info


class Layer:
    """A map of biomes: the surface, or the caves at one depth."""

    def __init__(self, info: dict, ids: np.ndarray):
        self.name = info["name"]
        self.biomes = info["biomes"]
        self.ids = ids
        counts = np.bincount(ids.ravel(), minlength=len(self.biomes))
        self.share = counts / counts.sum()
        self.palette = np.zeros((256, 3), dtype=np.float32)
        for i, b in enumerate(self.biomes):
            self.palette[i] = rgb_of(b["rgb"])
        self.shown = [tk.BooleanVar(value=False) for _ in self.biomes]  # needs the Tk root, made before the layers


class Scrollable(ttk.Frame):
    """A frame with a vertical scrollbar; put the widgets in .body."""

    def __init__(self, parent):
        super().__init__(parent)
        canvas = tk.Canvas(self, highlightthickness=0, width=250)
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
    def __init__(self, root: tk.Tk, heights, layers, info):
        self.root = root
        self.heights = heights
        self.layers = layers
        self.layer = layers[0]
        self.info = info
        self.width = info["width"]
        self.height = info["height"]
        self.sea_level = info["sea_level"]
        self.lowest = int(heights.min())
        self.highest = int(heights.max())

        self.structures = info["structures"]
        self.points = [np.array(s["points"], dtype=np.int32).reshape(-1, 2) for s in self.structures]

        self.show_structure = [tk.BooleanVar(value=True) for _ in self.structures]
        self.show_sea = tk.BooleanVar(value=True)
        self.opacity = tk.DoubleVar(value=70)

        self.scale = 1.0
        self.ox = 0.0   # map column at the left edge of the canvas
        self.oy = 0.0   # map row at the top edge
        self.photo = None
        self.drag = None

        self.canvas_ready = False
        self.build_panel()
        self.canvas_ready = True
        self.canvas = tk.Canvas(root, bg="#202020", highlightthickness=0)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.status = tk.StringVar()
        ttk.Label(root, textvariable=self.status, anchor="w").pack(side="bottom", fill="x", before=self.canvas)

        self.canvas.bind("<Configure>", self.on_resize)
        self.canvas.bind("<MouseWheel>", self.on_wheel)
        self.canvas.bind("<Button-4>", lambda e: self.zoom(e.x, e.y, 1.25))
        self.canvas.bind("<Button-5>", lambda e: self.zoom(e.x, e.y, 0.8))
        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<Motion>", self.on_move)
        self.canvas.bind("<Double-Button-1>", self.on_double)
        self.fitted = False

    # --- side panel -------------------------------------------------------
    def build_panel(self):
        panel = ttk.Frame(self.root)
        panel.pack(side="left", fill="y")
        tabs = ttk.Notebook(panel)
        tabs.pack(fill="both", expand=True)

        view = ttk.Frame(tabs, padding=8)
        tabs.add(view, text="Affichage")
        ttk.Checkbutton(view, text="Mer en bleu", variable=self.show_sea, command=self.redraw).pack(anchor="w")
        ttk.Label(view, text="Opacité des biomes").pack(anchor="w", pady=(12, 0))
        ttk.Scale(view, from_=0, to=100, variable=self.opacity, command=lambda v: self.redraw()).pack(fill="x")

        self.build_list(tabs, "Structures", self.structure_rows(), self.show_structure)

        biomes = ttk.Frame(tabs, padding=4)
        tabs.add(biomes, text="Biomes")
        self.layer_name = tk.StringVar(value=self.layer.name)
        chooser = ttk.Combobox(biomes, textvariable=self.layer_name, state="readonly",
                               values=[l.name for l in self.layers])
        chooser.pack(fill="x", pady=(0, 4))
        chooser.bind("<<ComboboxSelected>>", lambda e: self.choose_layer())
        self.biome_holder = ttk.Frame(biomes)
        self.biome_holder.pack(fill="both", expand=True)
        self.choose_layer()
        tabs.select(1)

    def choose_layer(self):
        """Show the list of biomes of the chosen layer (the surface, or the caves at a depth)."""
        self.layer = next(l for l in self.layers if l.name == self.layer_name.get())
        for child in self.biome_holder.winfo_children():
            child.destroy()
        self.build_list(self.biome_holder, None, self.biome_rows(), self.layer.shown)
        if self.canvas_ready:
            self.redraw()

    def structure_rows(self):
        rows = [(i, s["name"], s["rgb"], f"{len(s['points'])}") for i, s in enumerate(self.structures)]
        return sorted(rows, key=lambda r: -int(r[3]))

    def biome_rows(self):
        layer = self.layer
        rows = [(i, b["name"], b["rgb"], f"{100 * layer.share[i]:.1f} %") for i, b in enumerate(layer.biomes)]
        return sorted(rows, key=lambda r: -layer.share[r[0]])

    def build_list(self, tabs, title, rows, variables):
        if title is None:  # inside an existing frame
            frame = tabs
        else:
            frame = ttk.Frame(tabs, padding=4)
            tabs.add(frame, text=title)
        buttons = ttk.Frame(frame)
        buttons.pack(fill="x")

        def set_all(value):
            for v in variables:
                v.set(value)
            self.redraw()

        ttk.Button(buttons, text="Tout", command=lambda: set_all(True)).pack(side="left", expand=True, fill="x")
        ttk.Button(buttons, text="Aucun", command=lambda: set_all(False)).pack(side="left", expand=True, fill="x")
        scroll = Scrollable(frame)
        scroll.pack(fill="both", expand=True, pady=(4, 0))
        for index, name, rgb, extra in rows:
            line = ttk.Frame(scroll.body)
            line.pack(fill="x", anchor="w")
            tk.Label(line, bg=hex_of(rgb), width=2).pack(side="left", padx=(0, 4))
            ttk.Checkbutton(line, text=f"{name}  ({extra})", variable=variables[index],
                            command=self.redraw).pack(side="left", anchor="w")

    # --- view -------------------------------------------------------------
    def on_resize(self, event):
        if not self.fitted:
            self.fitted = True
            self.fit()
        self.redraw()

    def fit(self):
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        self.scale = min(cw / self.width, ch / self.height)
        self.ox = -(cw / self.scale - self.width) / 2
        self.oy = -(ch / self.scale - self.height) / 2

    def on_wheel(self, event):
        self.zoom(event.x, event.y, 1.25 if event.delta > 0 else 0.8)

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

    def to_map(self, sx, sy):
        return self.ox + sx / self.scale, self.oy + sy / self.scale

    def on_move(self, event):
        mx, my = self.to_map(event.x, event.y)
        i, j = int(mx), int(my)
        if not (0 <= i < self.width and 0 <= j < self.height):
            self.status.set("")
            return
        x, z = i - self.width // 2, j - self.height // 2
        biomes = "  ".join(f"{l.name}: {l.biomes[l.ids[j, i]]['name']}" for l in self.layers)
        text = f"x {x}  z {z}  |  y {int(self.heights[j, i])}  |  {biomes}"
        hit = self.structure_near(mx, my)
        if hit:
            text += f"  |  {hit}"
        self.status.set(text)

    def structure_near(self, mx, my):
        limit = HOVER_RADIUS / self.scale
        best, best_distance = None, limit
        for k, (s, pts) in enumerate(zip(self.structures, self.points)):
            if not len(pts) or not self.show_structure[k].get():
                continue
            d = np.hypot(pts[:, 0] + self.width // 2 - mx, pts[:, 1] + self.height // 2 - my)
            k = int(d.argmin())
            if d[k] < best_distance:
                best_distance = d[k]
                best = f"{s['name']} ({pts[k, 0]}, {pts[k, 1]})"
        return best

    def on_double(self, event):
        mx, my = self.to_map(event.x, event.y)
        i, j = int(mx), int(my)
        if 0 <= i < self.width and 0 <= j < self.height:
            command = f"/tp @s {i - self.width // 2} {int(self.heights[j, i]) + 2} {j - self.height // 2}"
            self.root.clipboard_clear()
            self.root.clipboard_append(command)
            self.status.set(f"copié : {command}")

    # --- drawing ----------------------------------------------------------
    def redraw(self):
        from PIL import ImageTk  # imported late: only needed once the window exists

        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        self.canvas.delete("all")
        ix0, iy0 = max(0, int(self.ox)), max(0, int(self.oy))
        ix1 = min(self.width, int(self.ox + cw / self.scale) + 2)
        iy1 = min(self.height, int(self.oy + ch / self.scale) + 2)
        if ix1 <= ix0 or iy1 <= iy0:
            return
        step = max(1, int(1 / self.scale))  # zoomed out: one map pixel out of `step`
        sub = self.heights[iy0:iy1:step, ix0:ix1:step].astype(np.float32)
        grey = np.clip((sub - self.lowest) / max(1, self.highest - self.lowest), 0, 1) * 255
        if self.show_sea.get():
            # land stretched from the sea level to the top, sea in blue from light (shallow) to dark (deep)
            land = np.clip((sub - self.sea_level) / max(1, self.highest - self.sea_level), 0, 1) * 255
            depth = np.clip((self.sea_level - sub) / max(1, self.sea_level - self.lowest), 0, 1)[..., None]
            blue = (1 - depth) * np.array([130, 200, 255]) + depth * np.array([10, 40, 120])
            rgb = np.where((sub < self.sea_level)[..., None], blue, land[..., None].repeat(3, axis=2))
        else:
            rgb = grey[..., None].repeat(3, axis=2)

        layer = self.layer
        selected = np.array([v.get() for v in layer.shown] + [False] * (256 - len(layer.shown)))
        if selected.any():
            ids = layer.ids[iy0:iy1:step, ix0:ix1:step]
            alpha = self.opacity.get() / 100
            mask = selected[ids][..., None]
            rgb = np.where(mask, (1 - alpha) * rgb + alpha * layer.palette[ids], rgb)

        image = Image.fromarray(rgb.astype(np.uint8))
        size = (max(1, round(sub.shape[1] * step * self.scale)), max(1, round(sub.shape[0] * step * self.scale)))
        self.photo = ImageTk.PhotoImage(image.resize(size, Image.NEAREST))
        self.canvas.create_image((ix0 - self.ox) * self.scale, (iy0 - self.oy) * self.scale, image=self.photo, anchor="nw")

        for k, (s, pts) in enumerate(zip(self.structures, self.points)):
            if not self.show_structure[k].get() or not len(pts):
                continue
            px = (pts[:, 0] + self.width // 2 - self.ox) * self.scale
            py = (pts[:, 1] + self.height // 2 - self.oy) * self.scale
            visible = (px > -DOT_RADIUS) & (px < cw + DOT_RADIUS) & (py > -DOT_RADIUS) & (py < ch + DOT_RADIUS)
            color = hex_of(s["rgb"])
            for x, y in zip(px[visible], py[visible]):
                self.canvas.create_oval(x - DOT_RADIUS, y - DOT_RADIUS, x + DOT_RADIUS, y + DOT_RADIUS,
                                        fill=color, outline="black")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", type=Path, default=DEFAULT_DIR,
                    help="plugins/Isohypse folder (default: tmp/server/plugins/Isohypse)")
    args = ap.parse_args()
    root = tk.Tk()  # first: the layers hold Tk variables
    heights, layers, info = load(args.folder)
    root.title(f"Isohypse : {args.folder}")
    root.geometry("1400x900")
    Viewer(root, heights, layers, info)
    root.mainloop()


if __name__ == "__main__":
    main()
