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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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
                if (DEBUG_VILLAGE_LOG && name.startsWith("village")) {
                    logVillage(name, structure);
                }
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
     * <li>villages: each piece that is not ignored ({@link #villageIgnored}) is flat under its bottom ({@code minY - 1}), with the
     * margin, the blend and the corners of its type of village, and the highest ground wins where they overlap.</li>
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
            for (StructurePiece piece : pieces) {
                BoundingBox box = piece.getBoundingBox();
                if (!villageIgnored(box)) {
                    out.add(flat(box, (int) Math.floor(box.getMinY()) - 1, village, true));
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
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                int top = data.heightAt(x, z);
                top = groundAt(flats, x, z, top);
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
            }
        }
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

    /** Villages: the sizes (longer side x shorter side x height, as {@link #logVillage} writes them) of pieces with no building. */
    private static final Set<String> VILLAGE_IGNORED = Set.of("10x8x13", "12x10x13", "15x15x13", "15x15x5", "15x9x6",
            "17x7x13", "19x16x13", "20x17x13", "2x2x3", "4x4x5");
    /** Debug: log the sizes of the pieces of each village. */
    private static final boolean DEBUG_VILLAGE_LOG = true;
    /** Debug: the villages already logged, so that each is told once and not for every chunk. */
    private final Set<String> loggedVillages = ConcurrentHashMap.newKeySet();

    /** Whether the piece has nothing to be flat under: one side of 1 block or less, or a size known to hold no building. */
    private static boolean villageIgnored(BoundingBox box) {
        int w = (int) Math.round(box.getWidthX());
        int d = (int) Math.round(box.getWidthZ());
        int h = (int) Math.round(box.getHeight());
        return w <= 1 || d <= 1 || h <= 1 || VILLAGE_IGNORED.contains(Math.max(w, d) + "x" + Math.min(w, d) + "x" + h);
    }

    /**
     * Debug: the pieces of a village grouped by size (the longer side, the shorter side, the height: the same whatever the
     * rotation), with how many there are and where one of them is, to tell the models apart in the game.
     */
    private void logVillage(String name, GeneratedStructure structure) {
        BoundingBox whole = structure.getBoundingBox();
        // Without the Y: the height of the box of a village changes while its pieces are placed.
        if (!loggedVillages.add(name + (int) whole.getMinX() + "," + (int) whole.getMinZ() + ","
                + (int) whole.getMaxX() + "," + (int) whole.getMaxZ())) {
            return;
        }
        WorldData data = data();
        Map<String, List<BoundingBox>> bySize = new TreeMap<>();
        for (StructurePiece piece : structure.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            int w = (int) Math.round(b.getWidthX());
            int d = (int) Math.round(b.getWidthZ());
            int h = (int) Math.round(b.getHeight());
            bySize.computeIfAbsent(Math.max(w, d) + "x" + Math.min(w, d) + "x" + h, k -> new ArrayList<>()).add(b);
        }
        plugin.getLogger().info("Village " + name + " " + structure.getBoundingBox() + ": " + structure.getPieces().size()
                + " pieces, " + bySize.size() + " sizes (long x court x haut)");
        for (Map.Entry<String, List<BoundingBox>> entry : bySize.entrySet()) {
            BoundingBox b = entry.getValue().get(0);
            int ground = data.heightAt((int) Math.floor(b.getCenterX()), (int) Math.floor(b.getCenterZ()));
            plugin.getLogger().info("  " + entry.getKey() + " x" + entry.getValue().size() + "  e.g. x " + (int) b.getMinX()
                    + " y " + (int) b.getMinY() + " z " + (int) b.getMinZ() + " (ground y=" + ground + ")");
        }
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
        int top = data().heightAt(x, z);
        boolean seabed = heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG;
        return (seabed ? top : Math.max(top, SEA_LEVEL)) + 1;
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
     * The vanilla caves and ravines have just been carved in {@code chunkData}: under the seas, only what is below the
     * lowest point of the sea floor minus {@link #CARVER_SEA_MARGIN} is kept. Above it, in a column covered by water,
     * every carved block (air, water or lava where there was stone) is stone again, and the water above the sea floor
     * is whole again, so the sea floor is not broken into, and the water the carvers left above the sea level is
     * removed. On the land the caves are as the carvers made them (the water of the underground lakes stays), except under
     * a village: over its whole box, nothing is carved in the {@link #VILLAGE_CAVE_MARGIN} blocks under its ground.
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
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                if (top >= SEA_LEVEL) {
                    continue; // land
                }
                for (int y = limit + 1; y <= top; y++) {
                    Material material = chunkData.getType(localX, y, localZ);
                    if (material.isAir() || material == Material.WATER || material == Material.LAVA) {
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

    /** The ruined portals of the game come out buried (README): ours are placed on the ground instead. */
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(plugin.ruinedPortals());
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }
}
