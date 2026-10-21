"""Where Minecraft will put its structures in a world, worked out from the rules of the game, for tools/map_viewer.py.

It places nothing: Paper's own generation does (the plugin only lets it, with shouldGenerateStructures). This is an estimate
that follows the game step by step. The rules were read in the bytecode of the server (26.3, with javap), and the data
(the structure sets, the structures and the biome tags) are read from the jar of the server itself:

* Where a structure may start (RandomSpreadStructurePlacement, AbstractSpreadingStructurePlacement,
  ConcentricRingsStructurePlacement): the grid of `spacing` chunks with one draw per cell, drawn from the seed, the salt
  and the cell; the frequency reduction (the legacy ways of the pillager outposts, the buried treasures and the
  mineshafts); the exclusion zone around another set (a square of chunks, tested on the placement alone, whatever the other
  set really generated); the rings of the strongholds. All of it is exact.
* The strongholds (ChunkGeneratorStructureState.generateRingPositions): the rings, and for each position the move to a
  preferred biome within 112 blocks (BiomeSource.findBiomeHorizontal: one of the matching cells of the square, drawn with
  a random forked from the one of the rings, which every position takes a long from). Exact.
* Which structure of the set (ChunkGenerator.createStructures): with several structures in a set, one is drawn by weight
  with the random of the chunk; if it cannot start there, it is taken out of the list and another is drawn. Exact.
* Whether it starts (Structure.findValidGenerationPoint): the structure finds its start block, and the biome at that block
  (the cell of 4 x 4 x 4 blocks that holds it) has to be one of its biomes. The start block follows the rule of each kind
  (the centre of the chunk on the surface, on the ocean floor, a fixed Y...); for the structures that build themselves from
  a template (villages, ancient cities, ruined portals...), whose start is the centre of a piece placed with a random
  rotation, the centre of the chunk stands for it, which is an approximation.

Not followed: the ruined portals are placed on the surface whatever their setup.
"""
import json
import math
import zipfile
from dataclasses import dataclass, field

import numpy as np

SETS = ["abandoned_camp", "ancient_cities", "buried_treasures", "desert_pyramids", "igloos", "jungle_temples", "mineshafts",
        "ocean_monuments", "ocean_ruins", "pillager_outposts", "ruined_portals", "shipwrecks", "swamp_huts", "trail_ruins",
        "trial_chambers", "villages", "woodland_mansions", "strongholds"]
DATA = "data/minecraft/"


class JavaRandom:
    """java.util.Random (the LegacyRandomSource of Minecraft is the same generator)."""

    _MULT = 0x5DEECE66D
    _MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ self._MULT) & self._MASK

    def _next(self, bits):
        self.seed = (self.seed * self._MULT + 0xB) & self._MASK
        value = self.seed >> (48 - bits)
        if bits == 32 and value >= 1 << 31:
            value -= 1 << 32
        return value

    def next_int(self, bound=None):
        if bound is None:
            return self._next(32)
        if bound <= 0:
            raise ValueError("bound must be positive")
        r = self._next(31)
        m = bound - 1
        if bound & m == 0:
            return (bound * r) >> 31
        u = r
        r = u % bound
        while u - r + m >= 1 << 31:
            u = self._next(31)
            r = u % bound
        return r

    def next_long(self):
        return (self._next(32) << 32) + self._next(32)

    def next_float(self):
        return self._next(24) / float(1 << 24)

    def next_double(self):
        return ((self._next(26) << 27) + self._next(27)) * (1.0 / (1 << 53))


def f32(value):
    return float(np.float32(value))


def java_round(value):
    return math.floor(value + 0.5)


@dataclass(frozen=True)
class Found:
    set: str
    structure: str
    x: int
    y: int
    z: int


@dataclass
class Structure:
    name: str
    type: str
    weight: int
    biomes: set
    json: dict


@dataclass
class Placement:
    rings: bool
    spacing: int
    separation: int
    salt: int
    triangular: bool
    frequency: float
    reduction: str
    excluded_set: str
    excluded_chunks: int
    count: int
    distance: int
    spread: int


@dataclass
class StructureSet:
    name: str
    placement: Placement
    structures: list
    preferred: set = field(default_factory=set)   # the biomes the strongholds are moved to


def _id(namespaced):
    return namespaced.split(":", 1)[1] if ":" in namespaced else namespaced


class JarData:
    """The vanilla data of the server: reads a file of the jar of Paper."""

    def __init__(self, jar_path):
        self.zip = zipfile.ZipFile(jar_path)

    def read(self, path):
        try:
            return self.zip.read(path).decode("utf-8")
        except KeyError:
            return None


class StructureEstimator:
    def __init__(self, data, seed, height_at, biome_at, sea_level):
        """
        data: JarData (or anything with read(path) -> str | None)
        height_at(x, z): Y of the top block of the column
        biome_at(x, y, z): name of the biome at a block, None for none
        """
        self.height_at = height_at
        self.biome_at = biome_at
        self.seed = seed
        self.sea_level = sea_level
        self.sets = {}
        tags = {}
        for name in SETS:
            text = data.read(f"{DATA}worldgen/structure_set/{name}.json")
            if text is None:
                continue  # a version without this set
            js = json.loads(text)
            structures = []
            for entry in js["structures"]:
                structure_name = _id(entry["structure"])
                structure_text = data.read(f"{DATA}worldgen/structure/{structure_name}.json")
                if structure_text is None:
                    continue
                structure = json.loads(structure_text)
                structures.append(Structure(structure_name, _id(structure["type"]), entry["weight"],
                                            self._biome_set(data, structure["biomes"], tags), structure))
            preferred = (self._biome_set(data, js["placement"]["preferred_biomes"], tags)
                         if "preferred_biomes" in js["placement"] else set())
            self.sets[name] = StructureSet(name, self._placement(js["placement"]), structures, preferred)
        self.monument_surroundings = self._biome_set(data, "#minecraft:required_ocean_monument_surrounding", tags)

    @staticmethod
    def _placement(js):
        exclusion = js.get("exclusion_zone")
        return Placement(
            rings=_id(js["type"]) == "concentric_rings", spacing=js.get("spacing", 1), separation=js.get("separation", 0),
            salt=js.get("salt", 0), triangular=_id(js.get("spread_type", "linear")) == "triangular",
            frequency=f32(js.get("frequency", 1.0)), reduction=_id(js.get("frequency_reduction_method", "default")),
            excluded_set=_id(exclusion["other_set"]) if exclusion else None,
            excluded_chunks=exclusion["chunk_count"] if exclusion else 0,
            count=js.get("count", 0), distance=js.get("distance", 0), spread=js.get("spread", 0))

    def _biome_set(self, data, field, cache):
        """The biomes (names) of a `biomes` field: a tag (with its tags inside), a list or a single name."""
        names = set()
        if isinstance(field, list):
            for element in field:
                names |= self._biome_set(data, element, cache)
        elif isinstance(field, dict):
            names |= self._biome_set(data, field["id"], cache)
        elif field.startswith("#"):
            tag = _id(field[1:])
            if tag not in cache:
                cache[tag] = set()
                text = data.read(f"{DATA}tags/worldgen/biome/{tag}.json")
                if text is not None:
                    for element in json.loads(text)["values"]:
                        cache[tag] |= self._biome_set(data, element, cache)
            names |= cache[tag]
        else:
            names.add(_id(field))
        return names

    # ---- placement -------------------------------------------------------------------------------------------

    def _with_salt(self, x, z, salt):
        """WorldgenRandom.setLargeFeatureWithSalt: the seed of a random from a chunk (or cell) and a salt."""
        return x * 341873128712 + z * 132897987541 + self.seed + salt

    def _large_feature_seed(self, chunk_x, chunk_z):
        """WorldgenRandom.setLargeFeatureSeed: the seed of the random of a chunk."""
        random = JavaRandom(self.seed)
        a = random.next_long()
        b = random.next_long()
        return chunk_x * a ^ chunk_z * b ^ self.seed

    def _potential_chunk(self, p, cell_x, cell_z):
        """RandomSpreadStructurePlacement.getPotentialStructureChunk: the chunk where the cell draws its structure."""
        r = p.spacing - p.separation
        if r == 1:
            dx = dz = 0  # the draw is nextInt(1) = 0
        else:
            random = JavaRandom(self._with_salt(cell_x, cell_z, p.salt))
            if p.triangular:
                dx = (random.next_int(r) + random.next_int(r)) // 2
                dz = (random.next_int(r) + random.next_int(r)) // 2
            else:
                dx = random.next_int(r)
                dz = random.next_int(r)
        return cell_x * p.spacing + dx, cell_z * p.spacing + dz

    def _keeps(self, p, chunk_x, chunk_z):
        """FrequencyReductionMethod.shouldGenerate. The salts of the legacy ways are the ones of the server configuration
        (spigot.yml): 10387320 for the buried treasures, none for the mineshafts."""
        if p.reduction == "legacy_type_1":
            random = JavaRandom(((chunk_x >> 4) ^ ((chunk_z >> 4) << 4)) ^ self.seed)
            random.next_int()
            return random.next_int(int(np.float32(1.0) / np.float32(p.frequency))) == 0
        if p.reduction == "legacy_type_2":
            return JavaRandom(self._with_salt(chunk_x, chunk_z, 10387320)).next_float() < p.frequency
        if p.reduction == "legacy_type_3":
            return JavaRandom(self._large_feature_seed(chunk_x, chunk_z)).next_double() < p.frequency
        # the salt takes the place of the first coordinate, as in the game
        return JavaRandom(self._with_salt(p.salt, chunk_x, chunk_z)).next_float() < p.frequency

    def _is_structure_chunk(self, structure_set, chunk_x, chunk_z):
        """isStructureChunk: the grid, then the frequency reduction, then the exclusion zone."""
        p = structure_set.placement
        if self._potential_chunk(p, chunk_x // p.spacing, chunk_z // p.spacing) != (chunk_x, chunk_z):
            return False
        if p.frequency < 1.0 and not self._keeps(p, chunk_x, chunk_z):
            return False
        if p.excluded_set is not None:
            other = self.sets.get(p.excluded_set)
            if other is not None:
                for x in range(chunk_x - p.excluded_chunks, chunk_x + p.excluded_chunks + 1):
                    for z in range(chunk_z - p.excluded_chunks, chunk_z + p.excluded_chunks + 1):
                        if self._is_structure_chunk(other, x, z):
                            return False
        return True

    def estimate(self, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z, progress=None):
        """Every structure that starts in the chunks of the rectangle (both ends included), in the order of the sets."""
        found = []
        for index, name in enumerate(SETS):
            if progress:
                progress(name, index, len(SETS))
            structure_set = self.sets.get(name)
            if structure_set is None:
                continue
            p = structure_set.placement
            if p.rings:
                self._rings(structure_set, min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z, found)
                continue
            for cell_x in range(min_chunk_x // p.spacing, max_chunk_x // p.spacing + 1):
                for cell_z in range(min_chunk_z // p.spacing, max_chunk_z // p.spacing + 1):
                    cx, cz = self._potential_chunk(p, cell_x, cell_z)
                    if cx < min_chunk_x or cx > max_chunk_x or cz < min_chunk_z or cz > max_chunk_z:
                        continue
                    if self._is_structure_chunk(structure_set, cx, cz):
                        self._select(structure_set, cx, cz, found)
        return found

    def _select(self, structure_set, chunk_x, chunk_z, found):
        """ChunkGenerator.createStructures: the structure of the set that starts in this chunk, if any."""
        candidates = list(structure_set.structures)
        if len(candidates) == 1:
            self._try_start(structure_set, candidates[0], chunk_x, chunk_z, found)
            return
        random = JavaRandom(self._large_feature_seed(chunk_x, chunk_z))
        total = sum(s.weight for s in candidates)
        while candidates:
            draw = random.next_int(total)
            index = 0
            for structure in candidates:
                draw -= structure.weight
                if draw < 0:
                    break
                index += 1
            chosen = candidates[index]
            if self._try_start(structure_set, chosen, chunk_x, chunk_z, found):
                return
            del candidates[index]
            total -= chosen.weight

    # ---- the start of a structure ----------------------------------------------------------------------------

    def _surface(self, x, z):
        """The first free Y above the column for the surface of the world (the water counts)."""
        return max(self.height_at(x, z), self.sea_level)

    def _valid(self, structure, x, y, z):
        biome = self.biome_at(x, y, z)
        return biome is not None and biome in structure.biomes

    def _lowest_corner(self, min_x, min_z, width, depth):
        """SinglePieceStructure: the lowest of the heights at the four corners of the footprint."""
        return min(self._surface(min_x, min_z), self._surface(min_x, min_z + depth),
                   self._surface(min_x + width, min_z), self._surface(min_x + width, min_z + depth))

    def _try_start(self, structure_set, structure, chunk_x, chunk_z, found):
        """Structure.findValidGenerationPoint: the start block of the structure, and the biome there."""
        min_x, min_z = chunk_x * 16, chunk_z * 16
        x, z = min_x + 8, min_z + 8
        kind = structure.type
        if kind == "desert_pyramid":
            if self._lowest_corner(min_x, min_z, 21, 21) < self.sea_level:
                return False
            y = self._surface(x, z)
        elif kind == "jungle_temple":
            if self._lowest_corner(min_x, min_z, 12, 15) < self.sea_level:
                return False
            y = self._surface(x, z)
        elif kind in ("igloo", "swamp_hut"):
            y = self._surface(x, z)
        elif kind in ("buried_treasure", "ocean_ruin"):
            y = self.height_at(x, z)
        elif kind == "shipwreck":
            y = self._surface(x, z) if structure.json.get("is_beached") else self.height_at(x, z)
        elif kind == "ocean_monument":
            center_x, center_z = min_x + 9, min_z + 9
            for bx in range((center_x - 29) // 4, (center_x + 29) // 4 + 1):
                for bz in range((center_z - 29) // 4, (center_z + 29) // 4 + 1):
                    around = self.biome_at(bx * 4, self.sea_level, bz * 4)
                    if around is None or around not in self.monument_surroundings:
                        return False
            y = self.height_at(x, z)
        elif kind == "woodland_mansion":
            rotation = JavaRandom(self._large_feature_seed(chunk_x, chunk_z)).next_int(4)
            dx = -5 if rotation in (1, 2) else 5
            dz = -5 if rotation in (2, 3) else 5
            x, z = min_x + 7, min_z + 7
            y = min(self._surface(x, z), self._surface(x, z + dz), self._surface(x + dx, z), self._surface(x + dx, z + dz))
            if y < 60:
                return False
        elif kind == "mineshaft":
            z = min_z
            y = 50
        elif kind == "jigsaw":
            y = self._jigsaw_y(structure, chunk_x, chunk_z, x, z)
        else:
            y = self._surface(x, z)  # the ruined portals: on the surface, whatever their setup
        if not self._valid(structure, x, y, z):
            return False
        found.append(Found(structure_set.name, structure.name, x, y, z))
        return True

    def _jigsaw_y(self, structure, chunk_x, chunk_z, x, z):
        """The Y of the start of a jigsaw structure: its start_height (a number, or one drawn from the random of the
        chunk), above the surface when the start is projected on a heightmap."""
        js = structure.json
        height = 0
        provider = js.get("start_height")
        if isinstance(provider, dict):
            if "absolute" in provider:
                height = provider["absolute"]
            elif "min_inclusive" in provider and "max_inclusive" in provider:
                low = provider["min_inclusive"]["absolute"]
                high = provider["max_inclusive"]["absolute"]
                height = low + JavaRandom(self._large_feature_seed(chunk_x, chunk_z)).next_int(high - low + 1)
        return height + self._surface(x, z) + 1 if "project_start_to_heightmap" in js else height

    # ---- strongholds -----------------------------------------------------------------------------------------

    def _rings(self, structure_set, min_cx, max_cx, min_cz, max_cz, found):
        """ChunkGeneratorStructureState.generateRingPositions."""
        p = structure_set.placement
        random = JavaRandom(self.seed)
        angle = random.next_double() * math.pi * 2
        in_ring = ring = 0
        spread = p.spread
        reach = 7   # chunks: the move to a preferred biome is at most 112 blocks
        for i in range(p.count):
            distance = float(4 * p.distance + p.distance * ring * 6) + (random.next_double() - 0.5) * (p.distance * 2.5)
            chunk_x = java_round(math.cos(angle) * distance)
            chunk_z = java_round(math.sin(angle) * distance)
            fork = JavaRandom(random.next_long())   # RandomSource.fork(): a new random seeded with a long of this one
            if min_cx - reach <= chunk_x <= max_cx + reach and min_cz - reach <= chunk_z <= max_cz + reach:
                chunk_x, chunk_z = self._move_to_preferred(structure_set.preferred, chunk_x, chunk_z, fork)
                if min_cx <= chunk_x <= max_cx and min_cz <= chunk_z <= max_cz:
                    x, z = chunk_x * 16 + 8, chunk_z * 16 + 8
                    found.append(Found(structure_set.name, structure_set.structures[0].name, x, self.height_at(x, z), z))
            angle += math.pi * 2 / spread
            in_ring += 1
            if in_ring == spread:
                ring += 1
                in_ring = 0
                spread += 2 * spread // (ring + 1)
                spread = min(spread, p.count - i)
                angle += random.next_double() * math.pi * 2

    def _move_to_preferred(self, preferred, chunk_x, chunk_z, random):
        """BiomeSource.findBiomeHorizontal(x, 0, z, 112, preferred): of the cells (4 x 4 blocks, at Y 0) of the square of 112
        blocks around the middle of the chunk whose biome is preferred, one is drawn with the random (the first, then each
        new one replaces it with the chance 1/n); the stronghold is in the chunk of that cell. None found: it stays."""
        quart_x, quart_z, radius = (chunk_x * 16 + 8) >> 2, (chunk_z * 16 + 8) >> 2, 112 >> 2
        best = None
        matches = 0
        for dz in range(-radius, radius + 1):
            for dx in range(-radius, radius + 1):
                biome = self.biome_at((quart_x + dx) * 4, 0, (quart_z + dz) * 4)
                if biome is not None and biome in preferred:
                    if best is None or random.next_int(matches + 1) == 0:
                        best = (quart_x + dx, quart_z + dz)
                    matches += 1
        return (best[0] >> 2, best[1] >> 2) if best else (chunk_x, chunk_z)
