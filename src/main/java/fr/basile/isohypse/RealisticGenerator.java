package fr.basile.isohypse;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.HeightMap;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.util.BoundingBox;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>Everything comes from {@code plugins/Isohypse/world.bin}, made when the world is created (see
 * {@link IsohypsePlugin}) and read from the disk by {@link WorldData}: the height of every column and the biomes (of the
 * surface and of the caves). The ground is filled with stone, with water up to the vanilla sea level (63). It replaces
 * the vanilla noise stage only: the other vanilla stages (surface rules, carvers, decorations, structures, mobs) run on
 * top of it. The structures are the game's own: where they start comes from the heights ({@link #getBaseHeight}) and the
 * biomes ({@link #biomeAt}) given here.
 */
public final class RealisticGenerator extends ChunkGenerator {

    /** Vanilla sea level: the low parts of the terrain are filled with water up to this Y. */
    private static final int SEA_LEVEL = HeightScale.SEA_LEVEL;
    /** Under a sea, the caves only start this many blocks under the lowest point of the sea floor of the map. */
    private static final int CARVER_SEA_MARGIN = 8;
    /** A cavern ({@link Caverns}) stays at least this many blocks under the ground of its column. */
    private static final int CAVERN_ROOF = 8;
    /**
     * The structures under the ground that the game puts in a void it digs in its own noise (terrain adaptation
     * "beard_box"), which this generator does not use: the void is dug here ({@link #clearAround}).
     */
    private static final Set<String> CLEARED = Set.of("ancient_city");
    /** The void around a piece of such a structure reaches this many blocks out of its box, across and above. */
    private static final int CLEAR_MARGIN = 3;
    /** Under such a piece, the ground is made solid this many blocks down (a cavern or a cave could leave it hollow). */
    private static final int CLEAR_FLOOR = 6;
    /**
     * The structures under the ground that the game wraps in solid rock in its own noise (terrain adaptation
     * "encapsulate", "bury"): the caverns and the caves around them are filled here ({@link #encase}).
     */
    private static final Set<String> ENCASED = Set.of("trial_chambers", "stronghold");
    /** The rock around a piece of such a structure is this many blocks thick. */
    private static final int ENCASE_MARGIN = 3;
    /** Under a village, the caves only start this many blocks under its ground (over the whole box of the village). */
    private static final int VILLAGE_CAVE_MARGIN = 8;

    /** The most blocks of margin or of blend (config), so that the structures asked for are within the chunks the game has ready. */
    private static final int MAX_BLEND = 48;
    private static final int MAX_MARGIN = 16;
    private static final int MAX_CORNER_RADIUS = 32;

    /** The village types of the game ({@code village_<type>}) that have their own values in the config. */
    private static final List<String> VILLAGE_TYPES = List.of("plains", "desert", "savanna", "snowy", "taiga");

    /**
     * How a flat is made: the blocks around each piece that are flat too ({@code margin}), the blocks over which the flat
     * fades into the natural ground ({@code blend}, 0 = an edge), and the radius in blocks of its rounded corners.
     */
    private record Shape(int margin, int blend, int cornerRadius) {

        /** The values of {@code section}, those of {@code fallback} for the keys it lacks, each within its limits. */
        static Shape read(ConfigurationSection section, Shape fallback) {
            if (section == null) {
                return fallback;
            }
            return new Shape(Math.clamp(section.getInt("margin", fallback.margin()), 0, MAX_MARGIN),
                    Math.clamp(section.getInt("blend_distance", fallback.blend()), 0, MAX_BLEND),
                    Math.clamp(section.getInt("corner_radius", fallback.cornerRadius()), 0, MAX_CORNER_RADIUS));
        }

        /** How far from a chunk a piece can be and still touch it, in chunks. */
        int reach() {
            return Math.ceilDiv(margin + blend, 16);
        }
    }

    private final IsohypsePlugin plugin;
    /** The igloos, the outposts and the camps (config {@code structures.margin}, {@code blend_distance}, {@code corner_radius}). */
    private final Shape shape;
    /** The villages of each type (config {@code structures.village.types.<type>}, by default {@code structures.village}). */
    private final Map<String, Shape> villageShapes = new HashMap<>();
    private final Shape villageShape;
    /** How many chunks around are searched for structures: what the widest of the shapes reaches. */
    private final int searchRadius;
    private volatile boolean lookupFailed;
    /** The caverns of the seed of the world, made with the first chunk. */
    private volatile Caverns caverns;

    RealisticGenerator(IsohypsePlugin plugin) {
        this.plugin = plugin;
        ConfigurationSection config = plugin.getConfig();
        this.shape = Shape.read(config.getConfigurationSection("structures"), new Shape(1, 12, 4));
        this.villageShape = Shape.read(config.getConfigurationSection("structures.village"), new Shape(1, 12, 0));
        int reach = Math.max(shape.reach(), villageShape.reach());
        for (String type : VILLAGE_TYPES) {
            Shape typeShape = Shape.read(config.getConfigurationSection("structures.village.types." + type), villageShape);
            villageShapes.put(type, typeShape);
            reach = Math.max(reach, typeShape.reach());
        }
        this.searchRadius = reach;
    }

    /** The shape of the flats of a village ({@code village_plains}...), the default one for a type that has none. */
    private Shape villageShape(String name) {
        return villageShapes.getOrDefault(name.startsWith("village_") ? name.substring("village_".length()) : name, villageShape);
    }

    /**
     * The part of a flattened structure (the box of one of its pieces): the ground there is at {@code level}, and fades
     * into the natural ground over {@code blend} blocks (0: an edge). Where two flats overlap, the lowest ground wins, or
     * the highest if {@code raise} (so that a flat never takes away what another one has put).
     */
    private record Flat(int minX, int minZ, int maxX, int maxZ, int level, int radius, int blend, boolean raise) {

        /**
         * How much of the flat is in the ground at (x, z): 1 on the flat, then falling to 0 over {@code blend} blocks (a
         * smoothstep of the distance to the flat), so that the flat fades into the natural ground without an edge.
         */
        double weight(int x, int z) {
            // The box with rounded corners: the distance to the box shrunk by the radius, less the radius.
            double dx = Math.max(Math.max(minX + radius - x, x - (maxX - radius)), 0);
            double dz = Math.max(Math.max(minZ + radius - z, z - (maxZ - radius)), 0);
            double distance = Math.sqrt(dx * dx + dz * dz) - radius;
            if (distance <= 0) {
                return 1;
            }
            if (distance >= blend) {
                return 0;
            }
            double t = distance / blend;
            return 1 - t * t * (3 - 2 * t);
        }
    }

    /** Whether the ground under this structure is made flat: the small ones, which need it. */
    private static boolean flattened(String name) {
        return name.equals("igloo") || name.equals("pillager_outpost") || name.startsWith("abandoned_camp")
                || name.startsWith("village");
    }

    /**
     * The structures that touch the chunk or are near enough to be blended into it (those of the chunks around, as far as
     * the margin and the blend reach), asked to the game itself: when the noise of a chunk is made, the starts of the
     * structures (their pieces, hence their boxes) are already known, they are only placed later. The ground of each is
     * flat at the height {@link #getBaseHeight} gave the game where it measured the start (the middle of the chunk for the
     * igloo, the middle of the first piece for a jigsaw structure), so that every chunk of the same structure agrees and the structure is on the ground the game measured.
     * Nothing is flattened (and a warning is logged once) if the game does not answer.
     */
    private List<Flat> flats(WorldInfo worldInfo, WorldData data, int chunkX, int chunkZ) {
        if (lookupFailed || !(worldInfo instanceof World world)) {
            return List.of();
        }
        try {
            int radius = searchRadius;
            Map<String, GeneratedStructure> structures = new LinkedHashMap<>();
            Map<String, String> names = new HashMap<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (GeneratedStructure structure : world.getStructures(chunkX + dx, chunkZ + dz)) {
                        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE)
                                .getKey(structure.getStructure());
                        String name = key == null ? "" : key.getKey();
                        if (flattened(name)) {
                            String id = name + structure.getBoundingBox();
                            if (structures.putIfAbsent(id, structure) == null) {
                                names.put(id, name);
                            }
                        }
                    }
                }
            }
            List<Flat> flats = new ArrayList<>();
            for (Map.Entry<String, GeneratedStructure> entry : structures.entrySet()) {
                GeneratedStructure structure = entry.getValue();
                String name = names.get(entry.getKey());
                addFlats(name, structure, data, flats);
            }
            return flats;
        } catch (RuntimeException | LinkageError e) {
            lookupFailed = true;
            plugin.getLogger().log(Level.WARNING, "Structures: World#getStructures fails while the noise is made, "
                    + "no ground is flattened", e);
            return List.of();
        }
    }

    /**
     * The rule of each flattened structure: the flats (box and level of the ground) it adds to {@code out}.
     * <ul>
     * <li>pillager outpost: each piece is flat with its bottom on the ground ({@code minY - 1}), except the big low
     * plates, which follow the ground by themselves (terrain_matching);</li>
     * <li>igloo: whole at the terrain of the middle of its chunk;</li>
     * <li>abandoned camps (rigid): whole at the level of their first piece;</li>
     * <li>villages: each house (under its bottom, {@code minY - 1}), fountain and meeting point (at {@code minY}) is flat, by
     * size (see {@link #VILLAGE_FLAT_BELOW}), with the margin, the blend and the corners of its type of village, and the
     * highest ground wins where they overlap.</li>
     * </ul>
     */
    private void addFlats(String name, GeneratedStructure structure, WorldData data, List<Flat> out) {
        List<StructurePiece> pieces = List.copyOf(structure.getPieces());
        if (name.equals("pillager_outpost")) {
            for (StructurePiece piece : pieces) {
                BoundingBox box = piece.getBoundingBox();
                boolean plate = (box.getHeight() < box.getWidthX() || box.getHeight() < box.getWidthZ()) && box.getVolume() > 200;
                if (!plate) {
                    out.add(flat(box, (int) Math.floor(box.getMinY()) - 1));
                }
            }
            return;
        }
        if (name.startsWith("village")) {
            Shape village = villageShape(name);
            Set<String> below = VILLAGE_FLAT_BELOW.getOrDefault(name, Set.of());
            Set<String> at = VILLAGE_FLAT_AT.getOrDefault(name, Set.of());
            Set<String> above = VILLAGE_FLAT_ABOVE.getOrDefault(name, Set.of());
            for (StructurePiece piece : pieces) {
                BoundingBox box = piece.getBoundingBox();
                String size = villageSize(box);
                int bottom = (int) Math.floor(box.getMinY());
                if (below.contains(size)) {
                    out.add(flat(box, bottom - 1, village, true));
                } else if (at.contains(size)) {
                    out.add(flat(box, bottom, village, true));
                } else if (above.contains(size)) {
                    out.add(flat(box, bottom + 1, village, true));
                }
            }
            return;
        }
        BoundingBox first = pieces.isEmpty() ? structure.getBoundingBox() : pieces.get(0).getBoundingBox();
        boolean igloo = name.equals("igloo");
        // The igloo is put on the middle of its chunk; a jigsaw structure (the camps) on the ground at the middle of its
        // first piece, wherever that is: measured there, not at the middle of the chunk.
        int startX = igloo ? Math.floorDiv((int) Math.floor(first.getCenterX()), 16) * 16 + 8
                : ((int) Math.floor(first.getMinX()) + (int) Math.ceil(first.getMaxX()) - 1) / 2;
        int startZ = igloo ? Math.floorDiv((int) Math.floor(first.getCenterZ()), 16) * 16 + 8
                : ((int) Math.floor(first.getMinZ()) + (int) Math.ceil(first.getMaxZ()) - 1) / 2;
        int terrain = Math.max(data.heightAt(startX, startZ), SEA_LEVEL);
        // The game puts the first piece of a jigsaw structure with its bottom on the ground it measured: that Y is read
        // from the piece itself rather than worked out again.
        int level = igloo || pieces.isEmpty() ? terrain : (int) Math.floor(first.getMinY());
        if (pieces.isEmpty()) {
            out.add(flat(structure.getBoundingBox(), level));
        }
        for (StructurePiece piece : pieces) {
            out.add(flat(piece.getBoundingBox(), level));
        }
    }

    private Flat flat(BoundingBox box, int level) {
        return flat(box, level, shape, false);
    }

    private static Flat flat(BoundingBox box, int level, Shape shape, boolean raise) {
        int margin = shape.margin();
        Flat square = new Flat((int) Math.floor(box.getMinX()) - margin, (int) Math.floor(box.getMinZ()) - margin,
                (int) Math.ceil(box.getMaxX()) - 1 + margin, (int) Math.ceil(box.getMaxZ()) - 1 + margin, level, 0,
                shape.blend(), raise);
        // A radius cannot be more than half the smallest side of the piece.
        int radius = Math.min(shape.cornerRadius(),
                Math.min(square.maxX() - square.minX() + 1, square.maxZ() - square.minZ() + 1) / 2);
        return new Flat(square.minX(), square.minZ(), square.maxX(), square.maxZ(), level, radius, shape.blend(), raise);
    }

    /** The world data, made when the world was created. */
    private WorldData data() {
        WorldData data = plugin.worldData();
        if (data == null) {
            throw new IllegalStateException("Realistic: world.bin is not ready");
        }
        return data;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int minY = chunkData.getMinHeight();
        List<Flat> flats = flats(worldInfo, data, chunkX, chunkZ);
        // The lakes and the rivers are already dug into the heights of world.bin and their water is in its layer water
        // (Carver, at build time): here we only read the top of every column, flatten it under the structures (never in a
        // lake or a river) and fill stone up to it, then the water of a lake or a river over it, and sea water where it is
        // under the sea level.
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                int water = data.waterAt(x, z);
                int top = water > 0 ? data.heightAt(x, z) : groundAt(flats, x, z, data.heightAt(x, z));
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (water > 0) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, top + water + 1, localZ + 1, Material.WATER);
                }
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
            }
        }
        caverns(worldInfo.getSeed()).carve(chunkData, chunkX, chunkZ, (x, z) -> cavernCeiling(data, x, z));
    }

    private Caverns caverns(long seed) {
        Caverns made = caverns;
        if (made == null) {
            made = new Caverns(seed);
            caverns = made;
        }
        return made;
    }

    /**
     * The highest Y a cavern reaches at the column (x, z): {@link #CAVERN_ROOF} under its ground; under a lake, a river or
     * the sea, no higher than where {@link #generateCaves} starts filling what the carvers dug, so that it is not cut flat.
     */
    private static int cavernCeiling(WorldData data, int x, int z) {
        int top = data.heightAt(x, z);
        if (data.waterAt(x, z) > 0 || top < SEA_LEVEL) {
            return data.lowest() - CARVER_SEA_MARGIN;
        }
        return top - CAVERN_ROOF;
    }

    /**
     * The water of the lakes and the rivers is placed still by {@link #generateNoise}: a block of it next to air (a step
     * down a river, a bank lower than the water) would hang there until a block next to it changes. This asks the game to
     * let each of those flow once the chunk is done, as it does for the water of its own aquifers.
     */
    private final class WaterFlow extends BlockPopulator {

        @Override
        public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            WorldData data = data();
            for (int x = chunkX * 16; x < chunkX * 16 + 16; x++) {
                for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
                    int water = data.waterAt(x, z);
                    if (water == 0) {
                        continue;
                    }
                    int top = data.heightAt(x, z);
                    // The water blocks from the lowest air next to the column up are those that can flow.
                    int open = Math.min(Math.min(surfaceAt(data, x + 1, z), surfaceAt(data, x - 1, z)),
                            Math.min(surfaceAt(data, x, z + 1), surfaceAt(data, x, z - 1))) + 1;
                    for (int y = Math.max(top + 1, open); y <= top + water; y++) {
                        region.scheduleFluidUpdate(x, y, z);
                    }
                }
            }
        }
    }

    /** The Y of the highest block of the column (x, z) that is not air: its top, or the water over it (the sea's too). */
    private static int surfaceAt(WorldData data, int x, int z) {
        int top = data.heightAt(x, z);
        return Math.max(top + data.waterAt(x, z), Math.max(top, SEA_LEVEL));
    }

    /**
     * The Y of the top block of the column (x, z) once the flats are in the ground, for a natural ground at {@code natural}.
     * The flats are merged first, then the natural ground is blended into the merged level:
     * <ul>
     * <li>on a flat (weight 1) the ground is at its level (where flats overlap: the highest of those that {@code raise},
     * else the lowest);</li>
     * <li>elsewhere the flats around pull the ground to their levels, each with the weight w / (1 - w), which grows
     * without limit at the edge of its flat, so the merged level is continuous with each flat and changes smoothly from one
     * flat to the other;</li>
     * <li>the merged level is then blended with the natural ground by the weight of the strongest flat.</li>
     * </ul>
     * It depends on the column alone, so every chunk agrees on the border.
     */
    private static int groundAt(List<Flat> flats, int x, int z, int natural) {
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        double weight = 0;
        double pulled = 0;
        double pull = 0;
        for (Flat flat : flats) {
            double w = flat.weight(x, z);
            if (w >= 1) {
                if (flat.raise()) {
                    highest = Math.max(highest, flat.level());
                } else {
                    lowest = Math.min(lowest, flat.level());
                }
            } else if (w > 0) {
                double u = w / (1 - w);
                pulled += u * flat.level();
                pull += u;
                weight = Math.max(weight, w);
            }
        }
        if (highest != Integer.MIN_VALUE) {
            return highest;
        }
        if (lowest != Integer.MAX_VALUE) {
            return lowest;
        }
        return pull > 0 ? (int) Math.round(pulled / pull * weight + natural * (1 - weight)) : natural;
    }

    /** The whole box (x and z) of a village, in blocks. */
    private record Area(int minX, int minZ, int maxX, int maxZ) {

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** The villages whose box touches the chunk (the game lists a structure in every chunk its box intersects). */
    private List<Area> villageAreas(WorldInfo worldInfo, int chunkX, int chunkZ) {
        if (lookupFailed || !(worldInfo instanceof World world)) {
            return List.of();
        }
        try {
            var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
            List<Area> areas = new ArrayList<>();
            for (GeneratedStructure structure : world.getStructures(chunkX, chunkZ)) {
                NamespacedKey key = registry.getKey(structure.getStructure());
                if (key != null && key.getKey().startsWith("village")) {
                    BoundingBox box = structure.getBoundingBox();
                    areas.add(new Area((int) Math.floor(box.getMinX()), (int) Math.floor(box.getMinZ()),
                            (int) Math.ceil(box.getMaxX()) - 1, (int) Math.ceil(box.getMaxZ()) - 1));
                }
            }
            return areas;
        } catch (RuntimeException | LinkageError e) {
            return List.of(); // the failure is warned about by flats()
        }
    }

    /**
     * Villages: per village, the sizes of the only pieces that are flattened, as {@link #villageSize} writes them, with
     * the level of the flat in the three lists: under the bottom of the box ({@code minY - 1}) for the houses, at the bottom
     * ({@code minY}) for the fountain and the meeting points, and above it ({@code minY + 1}) for what needs it. They were
     * worked out from the template pools logged by VillagePoolLog (the sizes of the templates, minus 1 on each side, as the
     * box of a piece does not count the last block). A size shared by a house and a meeting point is a house here.
     */
    private static final Map<String, Set<String>> VILLAGE_FLAT_BELOW = Map.of(
            "village_desert", Set.of("4x4x4", "4x4x16", "4x5x4", "4x5x5", "4x5x17", "4x6x5", "4x8x6", "4x10x5", "5x6x5", "5x6x9",
                    "5x11x11", "6x6x6", "6x7x4", "6x9x5", "6x9x6", "6x10x8", "7x7x4", "7x10x5", "8x8x8", "9x10x6",
                    "9x11x5", "10x12x6"),
            "village_plains", Set.of("2x4x1", "6x6x6", "6x7x6", "6x8x5", "6x9x7", "6x9x11", "6x10x6", "6x10x10", "6x12x5", "6x14x11",
                    "6x16x5", "7x8x6", "7x8x7", "7x8x9", "7x9x6", "8x8x10", "8x10x6", "8x10x7", "8x11x5", "8x12x5",
                    "8x15x6", "9x10x8", "10x11x7", "10x12x7", "10x16x9"),
            "village_savanna", Set.of("5x6x6", "5x8x6", "6x6x6", "6x6x9", "6x8x6", "6x9x7", "6x10x6", "7x8x5", "7x8x7", "7x8x10",
                    "7x9x6", "7x9x7", "7x14x6", "8x8x5", "8x10x6", "8x12x6", "8x12x7", "8x12x9", "9x10x7", "10x10x7",
                    "10x12x13"),
            "village_snowy", Set.of("4x5x3", "4x5x4", "4x6x4", "4x8x4", "5x6x4", "5x6x5", "5x6x6", "6x6x5", "6x6x7", "6x6x8", "6x7x4",
                    "6x7x5", "6x7x7", "6x8x5", "6x8x7", "6x9x13", "6x10x6", "6x12x9", "6x13x8", "7x8x8", "8x8x9",
                    "8x9x4", "8x9x6", "8x9x7"),
            "village_taiga", Set.of("5x6x4", "6x6x6", "6x6x7", "6x6x8", "6x7x5", "6x7x6", "6x7x9", "6x7x10", "6x8x6", "6x8x7", "6x9x6",
                    "7x8x6", "7x10x5", "7x10x9", "7x12x6", "8x8x5", "8x8x6", "8x10x6", "9x9x6", "9x10x5", "9x10x6",
                    "9x11x7", "10x12x13"));
    private static final Map<String, Set<String>> VILLAGE_FLAT_AT = Map.of(
            "village_desert", Set.of("7x9x5", "8x16x5", "11x11x5", "14x14x5"),
            "village_plains", Set.of("4x5x7", "7x10x5", "7x14x4", "8x8x3", "8x8x5", "9x9x6", "9x10x5", "9x15x6", "10x10x8"),
            "village_savanna", Set.of("7x8x4", "8x8x4", "8x10x5", "10x10x5", "11x12x6", "11x13x4", "11x13x5"),
            "village_snowy", Set.of("6x6x6", "7x8x5", "7x11x7", "8x10x4", "8x10x5"),
            "village_taiga", Set.of("7x12x4", "17x21x2", "17x21x5"));
    private static final Map<String, Set<String>> VILLAGE_FLAT_ABOVE = Map.of("village_plains", Set.of("7x8x8"));

    /** The size of a piece: the shorter side, the longer side and the height, so that the rotation does not matter. */
    private static String villageSize(BoundingBox box) {
        int w = (int) Math.round(box.getWidthX());
        int d = (int) Math.round(box.getWidthZ());
        return Math.min(w, d) + "x" + Math.max(w, d) + "x" + Math.round(box.getHeight());
    }

    /** Biome at (x, y, z), from the layers of {@code world.bin}. */
    Biome biomeAt(int x, int y, int z) {
        Biome biome = data().biomeAt(x, y, z);
        return biome != null ? biome : Biome.PLAINS; // a biome that this version of the game does not have
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new HeightBiomeProvider(this);
    }

    /**
     * The first free Y above the column. The ocean floor maps (what a structure under the sea is placed on) stop at the
     * seabed; the others (the surface of the world, what stops a movement) count the water, so above a sea they are at
     * the sea level. The game asks for it to place the structures.
     */
    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        // The lakes and the rivers are already dug into the heights and their water is in its own layer (Carver): the bed
        // is the seabed, the water over it counts for the other maps, as the sea does.
        int top = data().heightAt(x, z);
        boolean seabed = heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG;
        return (seabed ? top : Math.max(top + data().waterAt(x, z), SEA_LEVEL)) + 1;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    /**
     * The carvers of the game have made their caves (the caverns are already there, from {@link #generateNoise}): what
     * they did to the villages, the lakes, the rivers and the sea is undone. Under the villages, the ground is given back
     * ({@link #protectVillages}). A lake or a river is kept whole, as the sea: stone under its bed (no cave opens under
     * it, the water would drain into it), water up to its surface, and none above (the aquifers of the carvers can leave
     * some there). Under the sea, the caves only start {@link #CARVER_SEA_MARGIN} blocks under the lowest point of the
     * map, and the water stops at the sea level.
     */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int limit = Math.max(chunkData.getMinHeight(), data.lowest() - CARVER_SEA_MARGIN);
        int maxY = chunkData.getMaxHeight();
        List<Area> villages = villageAreas(worldInfo, chunkX, chunkZ);
        if (!villages.isEmpty()) {
            protectVillages(chunkData, chunkX, chunkZ, villages, flats(worldInfo, data, chunkX, chunkZ), data);
        }
        Map<String, List<BoundingBox>> underground = pieces(worldInfo, chunkX, chunkZ);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                int top = data.heightAt(x, z);
                int water = data.waterAt(x, z);
                if (water > 0) {
                    // A lake or a river (down at the sea, its water joins the sea's).
                    int surface = Math.max(top + water, top < SEA_LEVEL ? SEA_LEVEL : top);
                    for (int y = limit + 1; y <= surface; y++) {
                        if (carved(chunkData.getType(localX, y, localZ))) {
                            chunkData.setBlock(localX, y, localZ, y <= top ? Material.STONE : Material.WATER);
                        }
                    }
                    for (int y = surface + 1; y < maxY; y++) {
                        if (chunkData.getType(localX, y, localZ) == Material.WATER) {
                            chunkData.setBlock(localX, y, localZ, Material.AIR);
                        }
                    }
                    continue;
                }
                if (top >= SEA_LEVEL) {
                    continue; // land
                }
                for (int y = limit + 1; y <= top; y++) {
                    if (carved(chunkData.getType(localX, y, localZ))) {
                        chunkData.setBlock(localX, y, localZ, Material.STONE);
                    }
                }
                for (int y = top + 1; y <= SEA_LEVEL; y++) {
                    if (chunkData.getType(localX, y, localZ).isAir()) {
                        chunkData.setBlock(localX, y, localZ, Material.WATER);
                    }
                }
                // the carvers' aquifers can also leave water above the sea (a ravine cut through the surface): none is wanted
                for (int y = SEA_LEVEL + 1; y < maxY; y++) {
                    if (chunkData.getType(localX, y, localZ) == Material.WATER) {
                        chunkData.setBlock(localX, y, localZ, Material.AIR);
                    }
                }
            }
        }
        List<BoundingBox> encased = new ArrayList<>();
        List<BoundingBox> cleared = new ArrayList<>();
        underground.forEach((name, boxes) -> (CLEARED.contains(name) ? cleared : encased).addAll(boxes));
        if (!encased.isEmpty()) {
            encase(chunkData, chunkX, chunkZ, encased, data);
        }
        if (!cleared.isEmpty()) {
            clearAround(chunkData, chunkX, chunkZ, cleared);
        }
    }

    /**
     * The boxes of the pieces of the structures of {@link #CLEARED} and {@link #ENCASED} that reach the chunk, by the
     * name of the structure, asked to the game (the chunk and the ones around, so that the margins reach over their
     * border). Empty if the game does not answer.
     */
    private Map<String, List<BoundingBox>> pieces(WorldInfo worldInfo, int chunkX, int chunkZ) {
        if (lookupFailed || !(worldInfo instanceof World world)) {
            return Map.of();
        }
        try {
            var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE);
            Map<String, GeneratedStructure> found = new LinkedHashMap<>();
            Map<String, String> names = new HashMap<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (GeneratedStructure structure : world.getStructures(chunkX + dx, chunkZ + dz)) {
                        NamespacedKey key = registry.getKey(structure.getStructure());
                        if (key != null && (CLEARED.contains(key.getKey()) || ENCASED.contains(key.getKey()))) {
                            String id = key.getKey() + structure.getBoundingBox();
                            if (found.putIfAbsent(id, structure) == null) {
                                names.put(id, key.getKey());
                            }
                        }
                    }
                }
            }
            Map<String, List<BoundingBox>> boxes = new HashMap<>();
            for (Map.Entry<String, GeneratedStructure> entry : found.entrySet()) {
                List<BoundingBox> list = boxes.computeIfAbsent(names.get(entry.getKey()), name -> new ArrayList<>());
                for (StructurePiece piece : entry.getValue().getPieces()) {
                    list.add(piece.getBoundingBox());
                }
            }
            return boxes;
        } catch (RuntimeException | LinkageError e) {
            return Map.of(); // the failure is warned about by flats()
        }
    }

    /**
     * Wraps these pieces (trial chambers, a stronghold) in rock as the game would have: what the caverns and the caves
     * dug in their box and within {@link #ENCASE_MARGIN} blocks of it (rounded at the edges) is filled, never above the
     * ground of the column (a stronghold can come up close to a sea or a lake, whose water stays). The pieces are placed
     * later and put their own rooms and corridors there.
     */
    private static void encase(ChunkData chunkData, int chunkX, int chunkZ, List<BoundingBox> pieces, WorldData data) {
        int minHeight = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        int x0 = chunkX * 16;
        int z0 = chunkZ * 16;
        for (BoundingBox box : pieces) {
            int minX = (int) Math.floor(box.getMinX());
            int maxX = (int) Math.ceil(box.getMaxX()) - 1;
            int minZ = (int) Math.floor(box.getMinZ());
            int maxZ = (int) Math.ceil(box.getMaxZ()) - 1;
            int bottom = (int) Math.floor(box.getMinY());
            int top = (int) Math.ceil(box.getMaxY()) - 1;
            for (int x = Math.max(minX - ENCASE_MARGIN, x0); x <= Math.min(maxX + ENCASE_MARGIN, x0 + 15); x++) {
                int dx = Math.max(Math.max(minX - x, x - maxX), 0);
                for (int z = Math.max(minZ - ENCASE_MARGIN, z0); z <= Math.min(maxZ + ENCASE_MARGIN, z0 + 15); z++) {
                    int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
                    int from = Math.max(bottom - ENCASE_MARGIN, minHeight + 1);
                    int to = Math.min(Math.min(top + ENCASE_MARGIN, maxY - 1), data.heightAt(x, z));
                    for (int y = from; y <= to; y++) {
                        int dy = Math.max(Math.max(bottom - y, y - top), 0);
                        if (dx * dx + dy * dy + dz * dz <= ENCASE_MARGIN * ENCASE_MARGIN
                                && carved(chunkData.getType(x - x0, y, z - z0))) {
                            chunkData.setBlock(x - x0, y, z - z0, y < 0 ? Material.DEEPSLATE : Material.STONE);
                        }
                    }
                }
            }
        }
    }

    /**
     * Digs the void the game would have dug around these pieces (an ancient city): first the ground under each is made
     * solid over {@link #CLEAR_FLOOR} blocks, then everything within {@link #CLEAR_MARGIN} blocks of a piece, from its
     * bottom up, is air (rounded at the edges). The pieces are placed later and fill their box as they are.
     */
    private static void clearAround(ChunkData chunkData, int chunkX, int chunkZ, List<BoundingBox> pieces) {
        int minHeight = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        int x0 = chunkX * 16;
        int z0 = chunkZ * 16;
        for (BoundingBox box : pieces) {
            int minX = (int) Math.floor(box.getMinX()) - CLEAR_MARGIN;
            int maxX = (int) Math.ceil(box.getMaxX()) - 1 + CLEAR_MARGIN;
            int minZ = (int) Math.floor(box.getMinZ()) - CLEAR_MARGIN;
            int maxZ = (int) Math.ceil(box.getMaxZ()) - 1 + CLEAR_MARGIN;
            int bottom = (int) Math.floor(box.getMinY());
            for (int x = Math.max(minX, x0); x <= Math.min(maxX, x0 + 15); x++) {
                for (int z = Math.max(minZ, z0); z <= Math.min(maxZ, z0 + 15); z++) {
                    for (int y = Math.max(minHeight + 1, bottom - CLEAR_FLOOR); y < Math.min(bottom, maxY); y++) {
                        if (carved(chunkData.getType(x - x0, y, z - z0))) {
                            chunkData.setBlock(x - x0, y, z - z0, y < 0 ? Material.DEEPSLATE : Material.STONE);
                        }
                    }
                }
            }
        }
        for (BoundingBox box : pieces) {
            int minX = (int) Math.floor(box.getMinX());
            int maxX = (int) Math.ceil(box.getMaxX()) - 1;
            int minZ = (int) Math.floor(box.getMinZ());
            int maxZ = (int) Math.ceil(box.getMaxZ()) - 1;
            int bottom = (int) Math.floor(box.getMinY());
            int top = (int) Math.ceil(box.getMaxY()) - 1;
            for (int x = Math.max(minX - CLEAR_MARGIN, x0); x <= Math.min(maxX + CLEAR_MARGIN, x0 + 15); x++) {
                int dx = Math.max(Math.max(minX - x, x - maxX), 0);
                for (int z = Math.max(minZ - CLEAR_MARGIN, z0); z <= Math.min(maxZ + CLEAR_MARGIN, z0 + 15); z++) {
                    int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
                    for (int y = Math.max(bottom, minHeight + 1); y <= Math.min(top + CLEAR_MARGIN, maxY - 1); y++) {
                        int dy = Math.max(y - top, 0);
                        if (dx * dx + dy * dy + dz * dz <= CLEAR_MARGIN * CLEAR_MARGIN
                                && !chunkData.getType(x - x0, y, z - z0).isAir()) {
                            chunkData.setBlock(x - x0, y, z - z0, Material.AIR);
                        }
                    }
                }
            }
        }
    }

    /** The surface of a village the last time an intact column was seen (top block first), for a chunk that has none. */
    private volatile Material[] lastSurface;

    private static boolean carved(Material material) {
        return material.isAir() || material == Material.WATER || material == Material.LAVA;
    }

    /**
     * Over the whole box of the villages, what the carvers took from the {@link #VILLAGE_CAVE_MARGIN} blocks under the
     * ground (the flats included) is given back. The surface rules have already run, so the blocks are not all stone: a
     * carved block takes the material the same depth has in the nearest column of the chunk that the carvers left whole
     * (grass, then dirt, then stone), or in the last such column seen when the chunk has none.
     */
    private void protectVillages(ChunkData chunkData, int chunkX, int chunkZ, List<Area> villages, List<Flat> flats,
                                 WorldData data) {
        int minHeight = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        int[][] ground = new int[16][16];
        boolean[][] inside = new boolean[16][16];
        Material[][][] whole = new Material[16][16][];
        List<int[]> donors = new ArrayList<>();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                if (!inAny(villages, x, z)) {
                    continue;
                }
                inside[localX][localZ] = true;
                int g = groundAt(flats, x, z, data.heightAt(x, z));
                ground[localX][localZ] = g;
                Material[] column = new Material[VILLAGE_CAVE_MARGIN];
                boolean intact = true;
                for (int depth = 0; depth < VILLAGE_CAVE_MARGIN; depth++) {
                    int y = g - depth;
                    if (y < minHeight || y >= maxY) {
                        column[depth] = Material.STONE;
                        continue;
                    }
                    column[depth] = chunkData.getType(localX, y, localZ);
                    intact &= !carved(column[depth]);
                }
                if (intact) {
                    whole[localX][localZ] = column;
                    donors.add(new int[]{localX, localZ});
                    lastSurface = column;
                }
            }
        }
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                if (!inside[localX][localZ] || whole[localX][localZ] != null) {
                    continue;
                }
                Material[] donor = lastSurface;
                int nearest = Integer.MAX_VALUE;
                for (int[] candidate : donors) {
                    int dx = candidate[0] - localX;
                    int dz = candidate[1] - localZ;
                    if (dx * dx + dz * dz < nearest) {
                        nearest = dx * dx + dz * dz;
                        donor = whole[candidate[0]][candidate[1]];
                    }
                }
                for (int depth = 0; depth < VILLAGE_CAVE_MARGIN; depth++) {
                    int y = ground[localX][localZ] - depth;
                    if (y >= minHeight && y < maxY && carved(chunkData.getType(localX, y, localZ))) {
                        chunkData.setBlock(localX, y, localZ, donor != null ? donor[depth] : Material.STONE);
                    }
                }
            }
        }
    }

    private static boolean inAny(List<Area> areas, int x, int z) {
        for (Area area : areas) {
            if (area.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    /**
     * The water of the lakes and the rivers is let flow ({@link WaterFlow}). The ruined portals of the game come out
     * buried (README): ours are placed on the ground instead.
     */
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new WaterFlow(), plugin.ruinedPortals());
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }
}
