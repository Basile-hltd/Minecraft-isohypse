#!/usr/bin/env python3
"""Viewer of the height map of the Realistic world, for a human: land in grey, the sea in blue or not.

Reads the files the plugin writes in plugins/Isohypse/ at startup:
    heightmap.bin   Y of the top block of every column (16 bits, one per block, read from the disk, never loaded whole)
    settings.json   the parameters used to make it, and the result (size, lowest and highest Y, sea level)

Usage :
    pip install -r tools/requirements.txt
    python tools/map_viewer.py [folder]        (default : tmp/server/plugins/Isohypse)

Mouse : wheel = zoom, drag = move, double click = copy a /tp command to the clipboard.
The "Recharger" button reads both files again (after the server made a new map). The files are only opened to read what
is on screen, never kept open, so the server can replace them while the viewer is running.
"""
import argparse
import json
import struct
import sys
import tkinter as tk
from pathlib import Path
from tkinter import ttk

import numpy as np
from PIL import Image, ImageTk

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DIR = ROOT / "tmp" / "server" / "plugins" / "Isohypse"

HEIGHTMAP_MAGIC = 0x49534F4D
HEADER = 12          # magic, width, height (big-endian ints), then the big-endian 16-bit heights
MAX_SCALE = 16.0

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


class Viewer:
    def __init__(self, root: tk.Tk, folder: Path, settings, width, height):
        self.root = root
        self.path = folder / "heightmap.bin"
        self.folder = folder

        self.show_sea = tk.BooleanVar(value=True)
        self.info = tk.StringVar()
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
        self.status.set("rechargé")
        self.redraw()

    # --- reading the map --------------------------------------------------
    def region(self, row0, row1, col0, col1, step):
        """The heights of a part of the map (every `step` blocks), read from the disk; the file is closed after."""
        heights = np.memmap(self.path, dtype=">i2", mode="r", offset=HEADER, shape=(self.height, self.width))
        try:
            return np.array(heights[row0:row1:step, col0:col1:step], dtype=np.float32)
        finally:
            del heights

    def height_at(self, column, row):
        with open(self.path, "rb") as f:
            f.seek(HEADER + 2 * (row * self.width + column))
            return struct.unpack(">h", f.read(2))[0]

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
            self.status.set("")
            return
        i, j = hit
        self.status.set(f"x {i - self.width // 2}  z {j - self.height // 2}  |  y {self.height_at(i, j)}")

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
            sub = self.region(iy0, iy1, ix0, ix1, step)
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

        image = Image.fromarray(rgb.astype(np.uint8))
        size = (max(1, round(sub.shape[1] * step * self.scale)), max(1, round(sub.shape[0] * step * self.scale)))
        self.photo = ImageTk.PhotoImage(image.resize(size, Image.NEAREST))
        self.canvas.create_image((ix0 - self.ox) * self.scale, (iy0 - self.oy) * self.scale, image=self.photo, anchor="nw")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", nargs="?", type=Path, default=DEFAULT_DIR,
                    help="plugins/Isohypse folder (default: tmp/server/plugins/Isohypse)")
    args = ap.parse_args()
    try:
        settings, width, height = load(args.folder)
    except LoadError as e:
        sys.exit(str(e))

    root = tk.Tk()
    root.title(f"Isohypse : {args.folder}")
    root.geometry("1400x900")
    Viewer(root, args.folder, settings, width, height)
    root.mainloop()


if __name__ == "__main__":
    main()
