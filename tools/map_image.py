#!/usr/bin/env python3
"""Picture of the Realistic world, as a square PNG (3840 x 3840 by default): the height map, coloured like map_viewer.py
(land in grey from the sea level to the highest point, the sea in blue from light to dark), a compass rose in the top
right corner, the size of the map in x (below it) and in z (on its left), the spawn, the lakes and the rivers, the
contour lines of the Y (a thicker one every few), the most important places of the real area (towns, summits, lakes,
passes, capes, bays, islands: OpenStreetMap ranked by Wikidata, see osm_pois.py), and a legend under the map. No biome,
structure or coordinate grid: the players find their way by the relief.

Like map_viewer.py it only reads what the plugin computed, in plugins/Isohypse/ (or a copy of it):
    world.bin       its height layer (Y of the top block of every column) and its zones (temp: the lakes and the rivers),
                    read a band of rows at a time
    settings.json   the size of the map, the sea level, the lowest and highest Y, the names of the zones, the real area
The places are fetched once (network) and kept in osm_pois.json in the folder; pois.json there can leave some out or add
some (see osm_pois.overrides).

The spawn is the one of the game, at x=0, z=0 (the middle of the map, the plugin does not move it); north is towards the
negative z, at the top of the picture.

Usage :
    pip install -r tools/requirements.txt
    python tools/map_image.py FOLDER [-o map.png] [--size 3840] [--spawn X Z] [--contour 50] [--major 5] [--no-water]
                                     [--pois 6] [--refresh-pois] [--exclude NOM]... [--per-kind N] [--spacing %]
"""
import argparse
import json
import struct
import sys
import time
from pathlib import Path

import numpy as np
import requests
from PIL import Image, ImageDraw, ImageFont

import osm_pois

WORLD_MAGIC = 0x49534F57
WORLD_VERSION = 1
DTYPES = {1: "u1", 2: ">i2"}   # type codes of WorldFile.java
BAND_BLOCKS = 64               # rows of pixels averaged at once, so that a big map never needs much RAM
SUPERSAMPLE = 2                # the marks are drawn this many times bigger, then the picture is reduced (smooth edges)

SEA_LIGHT = np.array([130, 200, 255], dtype=np.float32)   # shallow sea (same as map_viewer.py)
SEA_DARK = np.array([10, 40, 120], dtype=np.float32)      # deep sea
WATER = np.array([55, 135, 235], dtype=np.float32)        # lakes and rivers
WATER_GAIN = 2.5     # a pixel half covered by a river is shown as water: a river narrower than a pixel stays a line
CONTOUR = np.array([200, 115, 50], dtype=np.float32)      # contour lines, brown as on a printed map
CONTOUR_OPACITY = 0.6, 1.0         # of a contour line, and of a thick one
CONTOUR_HALF_WIDTH = 0.6, 1.2      # half the width (pixels at 4K) of a contour line, and of a thick one
WATER_ZONES = ("lake", "river")    # zones of the intermediate map shown as water
BACKGROUND = (24, 26, 31)
INK = (225, 228, 235)          # text and dimension lines
INK_DIM = (150, 155, 165)
SPAWN_COLOR = (230, 40, 40)
SPAWN_RADIUS = 11              # pixels at 4K
POI_RADIUS = 14                # pixels at 4K, half the size of the symbol of a place
POI_TEXT = 30                  # pixels at 4K, the height of the names of the places
POI_DARK = (25, 27, 32)
BAY_INK = (175, 220, 255)      # the names of the bays, over the sea
TEXT_ONLY = ("bay", "island")  # places named without a symbol (the shape is on the map): a bay on it, an island beside it
SETTLE_RADIUS = {"peak": 60, "city": 150, "pass": 150, "cape": 200, "station": 300}   # blocks around the OSM point where it may move
ROSE_LIGHT = (240, 240, 240)
ROSE_DARK = (60, 64, 72)
ROSE_NORTH = (210, 45, 45)

FONT_FILES = ("DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "Arial.ttf", "arial.ttf")
BOLD_FILES = ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "Arial Bold.ttf",
              "arialbd.ttf")


def font(size, bold=False):
    for name in BOLD_FILES if bold else FONT_FILES:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default(size)


def number(n):
    """43904 -> '43 904', -21952 -> '−21 952'."""
    text = f"{abs(n):,}".replace(",", " ")
    return ("−" if n < 0 else "") + text


def load(folder: Path):
    """(settings, width, height, {name: memmap} of the layers of one cell per block) of the plugin folder; exits with a
    message if it cannot."""
    for name in ("world.bin", "settings.json"):
        if not (folder / name).is_file():
            sys.exit(f"{folder / name} not found: give the folder of the plugin (plugins/Isohypse) as argument.")
    try:
        settings = json.loads((folder / "settings.json").read_text(encoding="utf-8"))
        with open(folder / "world.bin", "rb") as f:
            magic, version, width, height, count = struct.unpack(">iiiii", f.read(20))
            if magic != WORLD_MAGIC:
                sys.exit("world.bin is not a world file of the plugin.")
            if version != WORLD_VERSION:
                sys.exit(f"world.bin is of the version {version}, this script reads the version {WORLD_VERSION}.")
            layers = {}
            for _ in range(count):
                (length,) = struct.unpack(">B", f.read(1))
                name = f.read(length).decode("ascii")
                dtype, step, columns, rows, offset = struct.unpack(">Biiiq", f.read(21))
                if step == 1 and name in ("height", "temp"):
                    layers[name] = np.memmap(folder / "world.bin", dtype=DTYPES[dtype], mode="r", offset=offset,
                                             shape=(rows, columns))
        results = settings["results"]
        if (width, height) != (results["width"], results["height"]):
            sys.exit("world.bin and settings.json do not have the same size: start the server again.")
        if "height" not in layers:
            sys.exit("world.bin has no height layer of one cell per block.")
        return settings, width, height, layers
    except (OSError, ValueError, KeyError, struct.error) as e:
        sys.exit(f"cannot read the world: {e}")


def reduce(heights, zones, water_ids, size):
    """The height layer reduced to about `size` x `size` pixels, each the mean of its blocks, and the share of the blocks
    of each pixel that are water (a zone of `water_ids`, None for no water), a band at a time."""
    rows, columns = heights.shape
    k = max(1, max(rows, columns) // size)   # blocks per pixel, a whole number: the picture is then reduced a little
    col_starts = np.arange(0, columns, k)
    col_counts = np.diff(np.append(col_starts, columns)).astype(np.float32)
    shape = (len(range(0, rows, k)), len(col_starts))
    mean = np.empty(shape, dtype=np.float32)
    water = np.zeros(shape, dtype=np.float32)
    band = k * BAND_BLOCKS
    started = time.time()

    def average(block, row_starts, row_counts):
        sums = np.add.reduceat(np.add.reduceat(block, row_starts, axis=0), col_starts, axis=1)
        return sums / row_counts[:, None] / col_counts[None, :]

    for r0 in range(0, rows, band):
        r1 = min(rows, r0 + band)
        block = np.asarray(heights[r0:r1], dtype=np.float32)
        row_starts = np.arange(0, block.shape[0], k)
        row_counts = np.diff(np.append(row_starts, block.shape[0])).astype(np.float32)
        mean[r0 // k:r0 // k + len(row_starts)] = average(block, row_starts, row_counts)
        if water_ids is not None:
            wet = water_ids[np.asarray(zones[r0:r1])].astype(np.float32)
            water[r0 // k:r0 // k + len(row_starts)] = average(wet, row_starts, row_counts)
        print(f"\rlecture de la carte : {100 * r1 / rows:5.1f} %  ({time.time() - started:.0f} s)", end="", flush=True)
    print()
    return mean, water


def colour(heights, water, sea_level, lowest, highest):
    """The colours of map_viewer.py with the sea shown, the lakes and the rivers over the land."""
    land = np.clip((heights - sea_level) / max(1, highest - sea_level), 0, 1) * 255
    depth = np.clip((sea_level - heights) / max(1, sea_level - lowest), 0, 1)[..., None]
    sea = (1 - depth) * SEA_LIGHT + depth * SEA_DARK
    rgb = np.where((heights < sea_level)[..., None], sea, np.repeat(land[..., None], 3, axis=2))
    wet = water_alpha(water)[..., None]
    return ((1 - wet) * rgb + wet * WATER).astype(np.uint8)


def water_alpha(water):
    return np.clip(water * WATER_GAIN, 0, 1)


def contours(heights, dry, sea_level, interval, major, unit):
    """(opacity of the contour lines, of the same size as `heights`), on the land out of the water (`dry`, 0 to 1): a line
    every `interval` Y, a thick one every `major` lines. Each pixel is shaded by its distance to the nearest level (the
    gap of height over the slope), so the lines are smooth and as wide on a steep slope as on a gentle one."""
    level = heights / interval
    gy, gx = np.gradient(level)
    slope = np.maximum(np.hypot(gx, gy), 1e-6)              # levels per pixel
    nearest = np.round(level)
    distance = np.abs(level - nearest) / slope               # pixels to the line
    thick = (nearest.astype(np.int64) % major) == 0
    half = np.where(thick, CONTOUR_HALF_WIDTH[1], CONTOUR_HALF_WIDTH[0]) * unit
    opacity = np.where(thick, CONTOUR_OPACITY[1], CONTOUR_OPACITY[0])
    alpha = np.clip(half + 0.5 - distance, 0, 1) * opacity
    alpha[(nearest * interval <= sea_level) | (heights < sea_level)] = 0   # no line in the sea nor on the shore
    return alpha * dry


def arrow_head(draw, tip, direction, size, fill):
    """A filled triangle pointing along `direction` (a unit vector) with its tip at `tip`."""
    (x, y), (dx, dy) = tip, direction
    back = (x - dx * size, y - dy * size)
    side = (-dy * size * 0.45, dx * size * 0.45)
    draw.polygon([tip, (back[0] + side[0], back[1] + side[1]), (back[0] - side[0], back[1] - side[1])], fill=fill)


def dimension(canvas, start, end, offset, label, ends, s):
    """A dimension line from `start` to `end` (the edge of the map), `offset` pixels away from it, with its ticks, its
    arrows, the length in the middle and the coordinates of both ends. Horizontal below the map, vertical on its left."""
    draw = ImageDraw.Draw(canvas)
    horizontal = start[1] == end[1]
    width = max(2, 3 * s)
    tick = 22 * s
    big, small = font(44 * s, bold=True), font(32 * s)
    if horizontal:
        y = start[1] + offset
        draw.line([(start[0], start[1] + 6 * s), (start[0], y + tick)], fill=INK_DIM, width=width)
        draw.line([(end[0], end[1] + 6 * s), (end[0], y + tick)], fill=INK_DIM, width=width)
        draw.line([(start[0], y), (end[0], y)], fill=INK, width=width)
        arrow_head(draw, (start[0], y), (-1, 0), 26 * s, INK)
        arrow_head(draw, (end[0], y), (1, 0), 26 * s, INK)
        middle = (start[0] + end[0]) / 2
        draw.text((middle, y - 14 * s), label, font=big, fill=INK, anchor="md")
        draw.text((start[0], y + tick + 8 * s), ends[0], font=small, fill=INK_DIM, anchor="la")
        draw.text((end[0], y + tick + 8 * s), ends[1], font=small, fill=INK_DIM, anchor="ra")
        return
    # vertical: the texts are drawn on a strip turned a quarter turn (read from the bottom up)
    x = start[0] - offset
    draw.line([(start[0] - 6 * s, start[1]), (x - tick, start[1])], fill=INK_DIM, width=width)
    draw.line([(end[0] - 6 * s, end[1]), (x - tick, end[1])], fill=INK_DIM, width=width)
    draw.line([(x, start[1]), (x, end[1])], fill=INK, width=width)
    arrow_head(draw, (x, start[1]), (0, -1), 26 * s, INK)
    arrow_head(draw, (x, end[1]), (0, 1), 26 * s, INK)
    length = int(end[1] - start[1])
    strip = Image.new("RGBA", (length, int(offset + tick + 80 * s)), (0, 0, 0, 0))
    sd = ImageDraw.Draw(strip)
    base = strip.height - offset   # the line, in the strip (its bottom is the edge of the map)
    sd.text((length / 2, base - 14 * s), label, font=big, fill=INK, anchor="md")
    sd.text((0, base - tick - 8 * s), ends[1], font=small, fill=INK_DIM, anchor="ld")
    sd.text((length, base - tick - 8 * s), ends[0], font=small, fill=INK_DIM, anchor="rd")
    turned = strip.rotate(90, expand=True)
    canvas.paste(turned, (int(start[0] - strip.height), int(start[1])), turned)


def compass_rose(canvas, centre, radius, s):
    """An eight point rose, north up (negative z), with N, E, S, O, on a round dark backing."""
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    cx, cy = centre
    backing = radius * 1.32
    draw.ellipse([cx - backing, cy - backing, cx + backing, cy + backing], fill=(20, 22, 27, 190),
                 outline=(*INK_DIM, 255), width=max(2, 2 * s))
    draw.ellipse([cx - radius * 0.62, cy - radius * 0.62, cx + radius * 0.62, cy + radius * 0.62],
                 outline=(*INK_DIM, 255), width=max(2, 2 * s))

    def point(angle, length, half_width, light, dark):
        # a point split along its axis, one half light and one dark, as on a printed rose
        a = np.radians(angle)
        ux, uy = np.sin(a), -np.cos(a)          # towards the point (0 degrees = up)
        px, py = -uy, ux                        # across it
        tip = (cx + ux * length, cy + uy * length)
        left = (cx + px * half_width, cy + py * half_width)
        right = (cx - px * half_width, cy - py * half_width)
        draw.polygon([(cx, cy), tip, left], fill=light)
        draw.polygon([(cx, cy), tip, right], fill=dark)

    for angle in (45, 135, 225, 315):
        point(angle, radius * 0.62, radius * 0.11, ROSE_LIGHT, ROSE_DARK)
    for angle in (90, 180, 270):
        point(angle, radius, radius * 0.16, ROSE_LIGHT, ROSE_DARK)
    point(0, radius, radius * 0.16, ROSE_NORTH, (140, 25, 25))
    letters = font(int(radius * 0.30), bold=True)
    gap = radius * 1.15
    for text, (dx, dy) in (("N", (0, -1)), ("E", (1, 0)), ("S", (0, 1)), ("O", (-1, 0))):
        draw.text((cx + dx * gap, cy + dy * gap), text, font=letters, fill=ROSE_NORTH if text == "N" else INK,
                  anchor="mm")
    canvas.alpha_composite(layer)


def spawn_mark(draw, centre, s):
    """The spawn: a small red point ringed in white, without text (it is named in the legend)."""
    x, y = centre
    r = SPAWN_RADIUS * s
    draw.ellipse([x - r, y - r, x + r, y + r], fill=SPAWN_COLOR, outline=(255, 255, 255), width=max(1, 3 * s))


def poi_symbol(draw, kind, centre, s):
    """The symbol of a kind of place: a square for a town, a triangle for a summit, a diamond for a pass, a blue point for
    a lake, a snowflake for a ski resort, a white point for a cape."""
    x, y = centre
    r = POI_RADIUS * s
    edge = max(1, 3 * s)
    if kind == "city":
        draw.rectangle([x - r * 0.8, y - r * 0.8, x + r * 0.8, y + r * 0.8], fill=(255, 255, 255), outline=POI_DARK, width=edge)
    elif kind == "peak":
        draw.polygon([(x, y - r * 1.1), (x + r, y + r * 0.7), (x - r, y + r * 0.7)], fill=POI_DARK, outline=(255, 255, 255),
                     width=edge)
    elif kind == "pass":
        draw.polygon([(x, y - r), (x + r, y), (x, y + r), (x - r, y)], fill=(255, 255, 255), outline=POI_DARK, width=edge)
    elif kind == "lake":
        draw.ellipse([x - r * 0.85, y - r * 0.85, x + r * 0.85, y + r * 0.85], fill=tuple(int(c) for c in WATER),
                     outline=(255, 255, 255), width=edge)
    elif kind == "station":
        # a snowflake: three white strokes on a dark one, so it shows on the grey and on the white of the summits
        strokes = [((x + r * np.cos(a), y + r * np.sin(a)), (x - r * np.cos(a), y - r * np.sin(a)))
                   for a in np.radians((90, 30, 150))]
        for line in strokes:
            draw.line(line, fill=POI_DARK, width=edge * 3)
        for line in strokes:
            draw.line(line, fill=(255, 255, 255), width=edge)
    else:
        draw.ellipse([x - r * 0.7, y - r * 0.7, x + r * 0.7, y + r * 0.7], fill=(255, 255, 255), outline=POI_DARK, width=edge)


def settle_places(layers, area, sea_level, lake_ids):
    """The settle function of osm_pois.choose: where a place is drawn, on the terrain of the world, or None to leave it out.
    A summit goes to the highest column near its point, a town, a pass or a cape must be on land (or land near it), a lake
    on water (a lake or the sea: a lagoon is the sea in the world), a bay on the sea, an island on land."""
    heights, zones = layers["height"], layers.get("temp")
    half_w, half_h = area.width // 2, area.height // 2

    def window(layer, x, z, radius, stride=1):
        c0, c1 = max(0, x + half_w - radius), min(area.width, x + half_w + radius + 1)
        r0, r1 = max(0, z + half_h - radius), min(area.height, z + half_h + radius + 1)
        return np.asarray(layer[r0:r1:stride, c0:c1:stride]), c0 - half_w, r0 - half_h

    def nearest(mask, cx, cz, x, z, stride):
        """The block of the true cell of `mask` (a window from cx, cz) nearest to (x, z), None if none."""
        rows, cols = np.nonzero(mask)
        if not len(rows):
            return None
        bx, bz = cx + cols * stride, cz + rows * stride
        i = int(np.argmin((bx - x) ** 2 + (bz - z) ** 2))
        return int(bx[i]), int(bz[i])

    def settle(place, x, z):
        kind = place["kind"]
        if kind == "peak":
            h, cx, cz = window(heights, x, z, SETTLE_RADIUS["peak"])
            row, col = np.unravel_index(int(np.argmax(h)), h.shape)
            return (cx + int(col), cz + int(row)) if h[row, col] >= sea_level else None
        radius = SETTLE_RADIUS.get(kind) or max(200, min(1500, round(place["extent_m"] * area.blocks_per_m / 2)))
        stride = max(1, radius // 150)
        h, cx, cz = window(heights, x, z, radius, stride)
        if kind == "lake":
            wet = h < sea_level
            if zones is not None and lake_ids is not None:
                wet |= lake_ids[window(zones, x, z, radius, stride)[0]]
            mask = wet
        elif kind == "bay":
            mask = h < sea_level
        else:
            mask = h >= sea_level
        return nearest(mask, cx, cz, x, z, stride)

    return settle


def place_names(canvas, places, to_canvas, size_of, obstacles, frame, s):
    """Draw the places: the symbols first, then each name where it hides nothing (another name, a symbol, the rose, the
    spawn) and stays on the map, tried right, left, above, below, then in the corners. A bay or an island has no symbol: the
    name of a bay is on it, the name of an island beside it (`size_of(metres)`: canvas pixels, for the size of the island)."""
    draw = ImageDraw.Draw(canvas)
    name_font = font(POI_TEXT * s, bold=True)
    stroke = 4 * s
    r = POI_RADIUS * s
    gap = 10 * s
    boxes = list(obstacles)
    spots = []
    for place, x, z in places:
        px, py = to_canvas(x, z)
        spots.append((place, px, py))
        if place["kind"] not in TEXT_ONLY:
            poi_symbol(draw, place["kind"], (px, py), s)
            boxes.append((px - r * 1.2, py - r * 1.2, px + r * 1.2, py + r * 1.2))

    def free(box):
        inside = frame[0] <= box[0] and frame[1] <= box[1] and box[2] <= frame[2] and box[3] <= frame[3]
        return inside and not any(box[0] < b[2] and b[0] < box[2] and box[1] < b[3] and b[1] < box[3] for b in boxes)

    for place, px, py in spots:
        if place["kind"] == "bay":
            d = r + gap
            tries = [((px, py), "mm"), ((px, py - d), "md"), ((px, py + d), "ma")]
        else:
            d = (size_of(place["extent_m"]) / 2 if place["kind"] == "island" else r) + gap
            tries = [((px + d, py), "lm"), ((px - d, py), "rm"), ((px, py - d), "md"), ((px, py + d), "ma"),
                     ((px + d * 0.8, py - d * 0.8), "ld"), ((px + d * 0.8, py + d * 0.8), "la"),
                     ((px - d * 0.8, py - d * 0.8), "rd"), ((px - d * 0.8, py + d * 0.8), "ra")]
        chosen = tries[0]
        for position, anchor in tries:
            box = draw.textbbox(position, place["name"], font=name_font, anchor=anchor, stroke_width=stroke)
            if free(box):
                chosen = (position, anchor)
                break
        box = draw.textbbox(chosen[0], place["name"], font=name_font, anchor=chosen[1], stroke_width=stroke)
        boxes.append(box)
        draw.text(chosen[0], place["name"], font=name_font, anchor=chosen[1], fill=BAY_INK if place["kind"] == "bay"
                  else (255, 255, 255), stroke_width=stroke, stroke_fill=(0, 0, 0))


def legend(canvas, centre, row_gap, sea_level, highest, interval, major, water, spawn, kinds, s):
    """Under the map, a first line: the grey of the heights from the sea level to the highest point (with the Y of the
    thick contour lines), a contour line, a thick one and the water; a second line: the spawn and the symbols of the kinds
    of places shown (`kinds`), each with its text."""
    draw = ImageDraw.Draw(canvas)
    first = centre
    text, small = font(32 * s), font(26 * s)
    bar_w, bar_h, sample, gap, pad = 640 * s, 28 * s, 70 * s, 70 * s, 18 * s
    items = []   # (width, draw function taking the left x)

    def bar(x):
        y0 = first[1] - bar_h / 2
        ramp = np.linspace(0, 255, bar_w, dtype=np.float32).astype(np.uint8)
        canvas.paste(Image.fromarray(np.repeat(ramp[None, :], bar_h, axis=0)).convert("RGBA"), (round(x), round(y0)))
        draw.rectangle([x, y0, x + bar_w, y0 + bar_h], outline=INK_DIM, width=max(1, s))
        step = interval * major
        marks = [sea_level] + ([y for y in range(0, highest, step) if y > sea_level] if step else []) + [highest]
        for y in marks:
            px = x + bar_w * (y - sea_level) / max(1, highest - sea_level)
            draw.line([(px, y0 + bar_h), (px, y0 + bar_h + 10 * s)], fill=INK_DIM, width=max(1, s))
            draw.text((px, y0 + bar_h + 14 * s), f"{y}", font=small, fill=INK_DIM, anchor="ma")

    label = "Altitude (Y)"
    items.append((draw.textlength(label, font=text), lambda x: draw.text((x, first[1]), label, font=text, fill=INK,
                                                                          anchor="lm")))
    items.append((bar_w, bar))
    if interval > 0:
        for thick, words in ((False, f"courbe tous les {interval} Y"), (True, f"tous les {interval * major} Y")):
            width = round(4 * CONTOUR_HALF_WIDTH[thick] * s)   # twice the map's, to be seen alone
            opacity = CONTOUR_OPACITY[thick]
            ink = tuple(int(c) for c in (1 - opacity) * np.array(BACKGROUND) + opacity * CONTOUR)
            def line(x, width=width, ink=ink, words=words):
                draw.line([(x, first[1]), (x + sample, first[1])], fill=ink, width=max(1, width))
                draw.text((x + sample + pad, first[1]), words, font=text, fill=INK, anchor="lm")
            items.append((sample + pad + draw.textlength(words, font=text), line))
    if water:
        words = "lacs et rivières"
        def swatch(x, words=words):
            box = 30 * s
            draw.rectangle([x, first[1] - box / 2, x + box, first[1] + box / 2], fill=tuple(int(c) for c in WATER),
                           outline=INK_DIM, width=max(1, s))
            draw.text((x + box + pad, first[1]), words, font=text, fill=INK, anchor="lm")
        items.append((30 * s + pad + draw.textlength(words, font=text), swatch))

    total = sum(width for width, _ in items) + gap * (len(items) - 1) - gap // 2   # the label sits closer to its bar
    x = first[0] - total / 2
    for i, (width, paint) in enumerate(items):
        paint(x)
        x += width + (gap // 2 if i == 0 else gap)

    second, items = first[1] + row_gap, []
    if spawn:
        words = "spawn (environ x = 0, z = 0)"
        def point(x, words=words):
            spawn_mark(draw, (x + SPAWN_RADIUS * s, second), s)
            draw.text((x + 2 * SPAWN_RADIUS * s + pad, second), words, font=text, fill=INK, anchor="lm")
        items.append((2 * SPAWN_RADIUS * s + pad + draw.textlength(words, font=text), point))
    for kind in kinds:
        words = osm_pois.KIND_NAMES[kind]
        def symbol(x, kind=kind, words=words):
            poi_symbol(draw, kind, (x + POI_RADIUS * s, second), s)
            draw.text((x + 2 * POI_RADIUS * s + pad, second), words, font=text, fill=INK, anchor="lm")
        items.append((2 * POI_RADIUS * s + pad + draw.textlength(words, font=text), symbol))
    total = sum(width for width, _ in items) + gap * (len(items) - 1)
    x = first[0] - total / 2
    for width, paint in items:
        paint(x)
        x += width + gap


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("folder", type=Path, help="plugins/Isohypse folder (with world.bin and settings.json)")
    ap.add_argument("-o", "--output", type=Path, default=Path("map.png"), help="PNG to write (default: map.png)")
    ap.add_argument("--size", type=int, default=3840, help="side of the square picture in pixels (default: 3840, 4K)")
    ap.add_argument("--spawn", type=int, nargs=2, metavar=("X", "Z"), default=(0, 0),
                    help="block of the spawn (default: 0 0, the middle of the map)")
    ap.add_argument("--contour", type=int, default=50, help="Y between two contour lines (default: 50; 0: none)")
    ap.add_argument("--major", type=int, default=5, help="a thick contour line every so many (default: 5)")
    ap.add_argument("--no-water", action="store_true", help="do not show the lakes and the rivers")
    ap.add_argument("--pois", type=int, default=6, help="places of interest shown (default: 6; 0: none)")
    ap.add_argument("--refresh-pois", action="store_true",
                    help="fetch the places from OpenStreetMap and Wikidata again (else the cache osm_pois.json is used)")
    ap.add_argument("--exclude", action="append", default=[], metavar="NOM",
                    help="a place not to show, by its name (case, accents and apostrophes do not matter); repeat it for "
                         "several, they add to the exclude list of pois.json")
    ap.add_argument("--per-kind", type=int, help="at most so many places of a kind (default: 2 for 6 places, more beyond)")
    ap.add_argument("--spacing", type=float,
                    help="smallest distance between two places, in %% of the side of the map (default: 10 for 6, less beyond)")
    args = ap.parse_args()
    if args.contour < 0 or args.major < 1:
        sys.exit("--contour must be 0 or more and --major 1 or more.")

    folder = args.folder.expanduser()
    settings, width, height, layers = load(folder)
    heights = layers["height"]
    results = settings["results"]
    sea_level = settings["world"]["sea_level"]
    lowest, highest = results["lowest_y"], results["highest_y"]

    # the frame: a margin on the left and below for the dimensions, a thin one on the top and the right
    size = args.size
    unit = size / 3840                     # the marks are sized for 4K and follow the size asked
    left, bottom, top, right = round(250 * unit), round(390 * unit), round(70 * unit), round(70 * unit)
    room = min(size - left - right, size - top - bottom)
    scale = room / max(width, height)      # pixels per block
    map_w, map_h = round(width * scale), round(height * scale)
    x0 = left + (size - left - right - map_w) // 2
    y0 = top + (size - top - bottom - map_h) // 2

    first_x, first_z = -(width // 2), -(height // 2)
    sx, sz = args.spawn
    column, row = sx - first_x, sz - first_z
    spawn_y = int(heights[row, column]) if 0 <= column < width and 0 <= row < height else None
    # the lakes and the rivers: the zones of the intermediate map of those names
    water_ids = None
    if not args.no_water and "temp" in layers:
        water_ids = np.zeros(256, dtype=bool)
        for i, zone in enumerate(results.get("temp_zones", [])):
            water_ids[i] = zone["name"] in WATER_ZONES
        if not water_ids.any():
            water_ids = None
    reduced, water = reduce(heights, layers.get("temp"), water_ids, max(map_w, map_h))

    places = []
    if args.pois > 0:
        try:
            area = osm_pois.Area(settings)
            exclude, added = osm_pois.overrides(folder)
            exclude |= set(args.exclude)
            lake_ids = np.zeros(256, dtype=bool)
            for i, zone in enumerate(results.get("temp_zones", [])):
                lake_ids[i] = zone["name"] == "lake"
            candidates = added + osm_pois.candidates(folder, settings, args.refresh_pois)
            for name, close in osm_pois.unknown_names(exclude, candidates):
                print(f"exclusion : aucun lieu ne s'appelle « {name} »"
                      + (f" (proches : {', '.join(close)})" if close else ""))
            per_kind, spacing = osm_pois.default_rules(args.pois)
            per_kind = args.per_kind or per_kind
            spacing = spacing if args.spacing is None else args.spacing / 100
            places = osm_pois.choose(candidates, args.pois, area, settle_places(layers, area, sea_level, lake_ids),
                                     per_kind=per_kind, spacing=spacing, exclude=exclude,
                                     touch=2.5 * POI_RADIUS * unit / scale)   # blocks: two symbols side by side
            for place, x, z in places:
                print(f"  {osm_pois.KIND_NAMES[place['kind']]:7} {place['name']}  ({place['sitelinks']} pages Wikipédia)")
            if len(places) < args.pois:
                print(f"{len(places)} lieux sur {args.pois} demandés : les autres sont trop près d'un lieu déjà pris "
                      f"(--spacing, {100 * spacing:.1f} %), d'un type déjà pris {per_kind} fois (--per-kind), ou pas sur "
                      "le terrain attendu")
        except (requests.RequestException, OSError, ValueError, KeyError) as e:
            print(f"points d'intérêt impossibles à lire, la carte est faite sans : {e}")
            places = []
    del heights, layers
    picture = Image.fromarray(colour(reduced, water, sea_level, lowest, highest)).resize((map_w, map_h), Image.LANCZOS)

    if args.contour:
        # the lines are worked out at the size of the map on the picture, from the heights spread smoothly over it
        fine = np.asarray(Image.fromarray(reduced).resize((map_w, map_h), Image.BILINEAR))
        dry = 1 - water_alpha(np.asarray(Image.fromarray(water).resize((map_w, map_h), Image.BILINEAR)))
        alpha = contours(fine, dry, sea_level, args.contour, args.major, unit)[..., None]
        picture = Image.fromarray(((1 - alpha) * np.asarray(picture, dtype=np.float32) + alpha * CONTOUR).astype(np.uint8))

    # everything else is drawn SUPERSAMPLE times bigger, then reduced: smooth lines and letters
    s = SUPERSAMPLE
    S = lambda v: v * s * unit           # a size given for 4K, on the big canvas
    canvas = Image.new("RGBA", (size * s, size * s), (*BACKGROUND, 255))
    canvas.paste(picture.resize((map_w * s, map_h * s), Image.NEAREST), (x0 * s, y0 * s))
    ImageDraw.Draw(canvas).rectangle([x0 * s - 1, y0 * s - 1, (x0 + map_w) * s, (y0 + map_h) * s],
                                     outline=INK_DIM, width=max(2, round(S(2))))
    k = max(1, round(s * unit))           # integer factor of the marks (they use whole pixel widths)

    dimension(canvas, (x0 * s, (y0 + map_h) * s), ((x0 + map_w) * s, (y0 + map_h) * s), S(90),
              f"X : {number(width)} blocs", (f"x = {number(first_x)}", f"x = {number(first_x + width - 1)}"), k)
    dimension(canvas, (x0 * s, y0 * s), (x0 * s, (y0 + map_h) * s), S(90),
              f"Z : {number(height)} blocs", (f"z = {number(first_z)}", f"z = {number(first_z + height - 1)}"), k)

    kinds = [kind for kind, _, _ in osm_pois.KINDS
             if kind not in TEXT_ONLY and any(place["kind"] == kind for place, _, _ in places)]
    legend(canvas, ((x0 + map_w / 2) * s, (y0 + map_h) * s + S(225)), S(90), sea_level, highest, args.contour,
           args.major, water_ids is not None, spawn_y is not None, kinds, k)

    radius = S(150)
    rose = ((x0 + map_w) * s - radius * 1.32 - S(50), y0 * s + radius * 1.32 + S(50))
    compass_rose(canvas, rose, radius, k)
    obstacles = [(rose[0] - radius * 1.4, rose[1] - radius * 1.4, rose[0] + radius * 1.4, rose[1] + radius * 1.4)]

    def to_canvas(x, z):
        return (x0 + (x + width // 2 + 0.5) * scale) * s, (y0 + (z + height // 2 + 0.5) * scale) * s

    if spawn_y is not None:
        spot = to_canvas(sx, sz)
        spawn_mark(ImageDraw.Draw(canvas), spot, k)
        r = SPAWN_RADIUS * k * 1.3
        obstacles.append((spot[0] - r, spot[1] - r, spot[0] + r, spot[1] + r))
    else:
        print(f"le spawn ({sx}, {sz}) est hors de la carte : il n'est pas dessiné")
    place_names(canvas, places, to_canvas, lambda metres: metres * width / settings["source"]["samples_per_side"]
                / settings["source"]["step_m"] * scale * s, obstacles, (x0 * s, y0 * s, (x0 + map_w) * s, (y0 + map_h) * s), k)

    out = canvas.convert("RGB").resize((size, size), Image.LANCZOS)
    output = args.output.expanduser()
    out.save(output, optimize=True)
    print(f"{output} : {size} x {size} px, carte de {width} x {height} blocs, {1 / scale:.2f} blocs par pixel")


if __name__ == "__main__":
    main()
