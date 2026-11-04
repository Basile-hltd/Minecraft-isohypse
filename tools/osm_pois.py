"""The points of interest of the area of a Realistic world, from OpenStreetMap, ranked by Wikidata: for map_image.py.

The candidates are the named places of OpenStreetMap with a Wikidata item, of a few kinds (KINDS): towns, summits, lakes,
passes, capes, bays, islands, and the ski resorts (with or without a Wikidata item: they seldom have one). One Overpass
query fetches them over the area of the map, then Wikidata gives the number of Wikipedia (and sister) pages of each, its
sitelinks: the more languages describe a place, the more important it is. The ski resorts, few and without pages, come
first while there is room, the largest first, kept apart from each other but not from the other places (see choose). The
result is kept in a cache file next to world.bin, so that the next pictures need no network and are always the same.

Where a place falls: the plugin samples the real area on a flat grid around (latitude, longitude) of settings.json
(HeightMapGenerator: 111320 m per degree of latitude, times cos(latitude) per degree of longitude), n samples every r metres,
spread over the side of the map (HeightScale): side / (n * r) blocks per metre, x to the east, z to the south, 0 in the middle.
"""
import difflib
import json
import math
import time
import unicodedata
from pathlib import Path

import requests

OVERPASS_URLS = ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")   # the second if the first fails
WIKIDATA_URL = "https://www.wikidata.org/w/api.php"
USER_AGENT = "Minecraft-isohypse map_image.py (https://github.com/Basile-hltd/Minecraft-isohypse)"
M_PER_DEG_LAT = 111320.0        # as HeightMapGenerator.java
CACHE_FILE = "osm_pois.json"
CACHE_VERSION = 3

# The kinds of places: (key, French name shown in the legend, Overpass filters). A place of several kinds takes the first.
KINDS = (
    ("city", "ville", ('node["place"~"^(city|town)$"]',)),
    ("peak", "sommet", ('node["natural"="peak"]',)),
    ("lake", "lac", ('nwr["natural"="water"]["water"~"^(lake|reservoir|lagoon)$"]',)),
    ("pass", "col", ('node["mountain_pass"="yes"]', 'node["natural"="saddle"]')),
    ("cape", "cap", ('node["natural"="cape"]',)),
    ("bay", "golfe", ('nwr["natural"="bay"]',)),
    ("island", "île", ('nwr["place"~"^(island|islet)$"]',)),
    ("station", "station de ski", ('nwr["landuse"="winter_sports"]',)),
)
WITHOUT_WIKIDATA = ("station",)   # kinds fetched even without a Wikidata item, and shown first (see choose)
KIND_NAMES = {key: name for key, name, _ in KINDS}


class Area:
    """The real area of the map and where a place of it falls, in blocks."""

    def __init__(self, settings):
        source, results = settings["source"], settings["results"]
        self.lat, self.lon = source["latitude"], source["longitude"]
        self.metres = source["samples_per_side"] * source["step_m"]        # side of the area sampled
        self.width, self.height = results["width"], results["height"]
        self.blocks_per_m = self.width / self.metres
        self.cos = math.cos(math.radians(self.lat))

    def block(self, lat, lon):
        """(x, z) of the block where (lat, lon) falls; x = 0, z = 0 in the middle of the map."""
        east = (lon - self.lon) * M_PER_DEG_LAT * self.cos
        north = (lat - self.lat) * M_PER_DEG_LAT
        return round(east * self.blocks_per_m - 0.5), round(-north * self.blocks_per_m - 0.5)

    def bbox(self):
        """(south, west, north, east) of the area, in degrees."""
        half = self.metres / 2
        dlat, dlon = half / M_PER_DEG_LAT, half / (M_PER_DEG_LAT * self.cos)
        return self.lat - dlat, self.lon - dlon, self.lat + dlat, self.lon + dlon

    def key(self):
        return {"version": CACHE_VERSION, "latitude": self.lat, "longitude": self.lon, "metres": self.metres}


def kind_of(tags):
    if tags.get("place") in ("city", "town"):
        return "city"
    if tags.get("natural") == "peak":
        return "peak"
    if tags.get("natural") == "water":
        return "lake"
    if tags.get("mountain_pass") == "yes" or tags.get("natural") == "saddle":
        return "pass"
    if tags.get("natural") == "cape":
        return "cape"
    if tags.get("natural") == "bay":
        return "bay"
    if tags.get("place") in ("island", "islet"):
        return "island"
    if tags.get("landuse") == "winter_sports":
        return "station"
    return None


def overpass(area):
    """The named places of the kinds of KINDS in the area, with a Wikidata item (but those of WITHOUT_WIKIDATA): a list
    of dicts."""
    south, west, north, east = area.bbox()
    box = f"({south:.6f},{west:.6f},{north:.6f},{east:.6f})"
    parts = "".join(f'{f}["name"]{"" if kind in WITHOUT_WIKIDATA else "[\"wikidata\"]"}{box};'
                    for kind, _, filters in KINDS for f in filters)
    query = f"[out:json][timeout:180];({parts});out tags bb;"   # bb: the box of a way or a relation (its middle is used)
    for url in OVERPASS_URLS:
        try:
            response = requests.post(url, data={"data": query}, headers={"User-Agent": USER_AGENT}, timeout=240)
            response.raise_for_status()
            elements = response.json()["elements"]
            break
        except (requests.RequestException, ValueError, KeyError) as e:
            print(f"points d'intérêt : {url} : {e}", flush=True)
    else:
        raise requests.RequestException("aucun serveur Overpass n'a répondu")
    places = []
    for element in elements:
        tags = element.get("tags", {})
        kind = kind_of(tags)
        if kind is None:
            continue
        if element["type"] == "node":
            lat, lon, extent = element["lat"], element["lon"], 0.0
        elif "bounds" in element:
            b = element["bounds"]
            lat, lon = (b["minlat"] + b["maxlat"]) / 2, (b["minlon"] + b["maxlon"]) / 2
            extent = max((b["maxlat"] - b["minlat"]) * M_PER_DEG_LAT, (b["maxlon"] - b["minlon"]) * M_PER_DEG_LAT * area.cos)
        else:
            continue
        places.append({"name": tags.get("name:fr") or tags["name"], "kind": kind, "lat": lat, "lon": lon,
                       "extent_m": round(extent), "wikidata": tags.get("wikidata", "").split(";")[0].strip()})
    return places


def sitelinks(ids):
    """{Wikidata id: number of sitelinks}, 50 ids per request."""
    counts = {}
    ids = sorted(set(ids))
    for i in range(0, len(ids), 50):
        response = requests.get(WIKIDATA_URL, params={"action": "wbgetentities", "ids": "|".join(ids[i:i + 50]),
                                                      "props": "sitelinks", "format": "json"},
                                headers={"User-Agent": USER_AGENT}, timeout=60)
        response.raise_for_status()
        for qid, entity in response.json().get("entities", {}).items():
            counts[qid] = len(entity.get("sitelinks", {}))
        time.sleep(0.2)
    return counts


def candidates(folder: Path, settings, refresh=False):
    """The places of the area with their sitelinks, the most important first: from the cache of `folder` if it is of this
    area (and `refresh` is false), else from OpenStreetMap and Wikidata, then written to the cache."""
    area = Area(settings)
    cache = folder / CACHE_FILE
    if not refresh and cache.is_file():
        try:
            data = json.loads(cache.read_text(encoding="utf-8"))
            if data.get("area") == area.key():
                return data["places"]
        except (OSError, ValueError):
            pass
    print("points d'intérêt : requête OpenStreetMap (Overpass)…", flush=True)
    places = overpass(area)
    print(f"points d'intérêt : {len(places)} lieux, importance lue dans Wikidata…", flush=True)
    counts = sitelinks(p["wikidata"] for p in places if p["wikidata"])
    for p in places:
        p["sitelinks"] = counts.get(p["wikidata"], 0)
    places.sort(key=lambda p: (-p["sitelinks"], p["name"]))
    cache.write_text(json.dumps({"area": area.key(), "places": places}, ensure_ascii=False, indent=1), encoding="utf-8")
    return places


def simple(name):
    """A name to compare: no case, no accent, one kind of apostrophe, single spaces ("Étang d’Urbino" = "etang d'urbino")."""
    name = unicodedata.normalize("NFKD", name.replace("’", "'").replace("`", "'"))
    return " ".join("".join(c for c in name if not unicodedata.combining(c)).lower().split())


def unknown_names(names, places):
    """The names among `names` that are no place, each with the closest names of the places (to tell the user)."""
    known = {simple(p["name"]): p["name"] for p in places}
    return [(name, [known[k] for k in difflib.get_close_matches(simple(name), known, n=3, cutoff=0.6)])
            for name in names if simple(name) not in known]


def default_rules(count):
    """(per_kind, spacing) for `count` places: 2 of a kind and a tenth of the map apart for 6, looser for more."""
    return max(2, math.ceil(count / 3)), 0.10 * math.sqrt(6 / max(6, count))


def choose(places, count, area, settle, per_kind=2, spacing=0.10, largest=0.25, exclude=(), touch=0):
    """The `count` most important places, at most `per_kind` of a kind, each at least `spacing` times the side of the map
    from the others, none wider than `largest` times the side (the island the map is about, a whole sea...), none whose
    name is in `exclude` (compared with simple()). `settle(place, x, z)` gives the block where the place is drawn, or None
    to leave it out (in the sea, not in the world...). Returns (place, x, z) tuples.

    The places added by hand come first and always. The ski resorts (WITHOUT_WIKIDATA: no pages to rank them) have their
    room kept, the largest first, apart from each other only; but a resort closer than `touch` blocks to another place
    (its symbol would be on the other's, as the resort of a pass at the pass) gives its room to the next place."""
    exclude = {simple(name) for name in exclude}
    gap = spacing * max(area.width, area.height)
    resorts = sorted((p for p in places if p["kind"] in WITHOUT_WIKIDATA and not p.get("forced")),
                     key=lambda p: -p["extent_m"])
    others = [p for p in places if p.get("forced")] + [p for p in places
                                                        if p["kind"] not in WITHOUT_WIKIDATA and not p.get("forced")]

    def pick(pool, room, avoid):
        """The places of `pool` in order, while there is `room`, kept `gap` apart and `touch` away from `avoid`."""
        chosen, per = [], {}
        for place in pool:
            if len(chosen) >= room and not place.get("forced"):
                break
            if (simple(place["name"]) in exclude or per.get(place["kind"], 0) >= per_kind
                    or place["extent_m"] > largest * area.metres):
                continue
            x, z = area.block(place["lat"], place["lon"])
            if not (-area.width // 2 <= x < area.width - area.width // 2
                    and -area.height // 2 <= z < area.height - area.height // 2):
                continue
            spot = settle(place, x, z)
            if spot is None:
                continue
            x, z = spot
            if not place.get("forced") and (any(math.hypot(x - cx, z - cz) < gap for _, cx, cz in chosen)
                                            or any(math.hypot(x - cx, z - cz) < touch for _, cx, cz in avoid)):
                continue
            chosen.append((place, x, z))
            per[place["kind"]] = per.get(place["kind"], 0) + 1
        return chosen

    kept = pick(resorts, count, [])
    main = pick(others, count - len(kept), [])
    near = lambda r: any(math.hypot(r[1] - x, r[2] - z) < touch for _, x, z in main)
    if any(near(r) for r in kept):
        kept = [r for r in kept if not near(r)]
        main = pick(others, count - len(kept), kept)   # the same places, and more in the room freed
    return main + kept


def overrides(folder: Path):
    """(names to leave out, places to add) of the optional pois.json of `folder`:
        {"exclude": ["Bastia"], "add": [{"name": "Chez nous", "kind": "city", "lat": 42.1, "lon": 9.0}]}
    An added place is always shown, before the others; its kind is one of KINDS."""
    path = folder / "pois.json"
    if not path.is_file():
        return set(), []
    data = json.loads(path.read_text(encoding="utf-8"))
    added = []
    for p in data.get("add", []):
        if p.get("kind") not in KIND_NAMES:
            raise ValueError(f"pois.json : {p.get('name')} : kind doit être l'un de {', '.join(KIND_NAMES)}")
        added.append({"name": p["name"], "kind": p["kind"], "lat": float(p["lat"]), "lon": float(p["lon"]),
                      "extent_m": 0, "sitelinks": 0, "forced": True})
    return set(data.get("exclude", [])), added
