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
    /** Blocks under the height of a lake (its water level) where its bed is, away from the shore, before the noise. */
    private static final int LAKE_DEPTH = 6;
    /** Blocks of depth gained per block from the shore: the bed goes down in a slope, not in a wall. */
    private static final double LAKE_SLOPE = 0.5;
    /** The distance from the shore, in blocks, from which the bed is at its deepest. */
    private static final int LAKE_RADIUS = (int) Math.ceil(LAKE_DEPTH / LAKE_SLOPE);
    /** How far the noise moves the bed, up or down, in blocks, where the lake is at its deepest (none at the shore). */
    private static final double LAKE_NOISE = 1.0;
    /** The size, in blocks, of the cells of the first octave of the noise of the bed (the second is a third of it). */
    private static final int LAKE_NOISE_CELL = 12;
    /** The depth, in blocks, of the channel of a river under its water level (less at the banks). */
    private static final int RIVER_DEPTH = 3;
    /** How far from a river, in blocks, the ground is lowered to its banks (fading out, so there is no wall at the end). */
    private static final int RIVER_REACH = 16;
    /** The water level of a river is the lowest river column within this radius, so it is flat across the river. */
    private static final int RIVER_LEVEL_RADIUS = 2;
    /** How far the noise moves the bed of a river, up or down, in blocks, where it is deepest. */
    private static final double RIVER_NOISE = 1.0;
    /** Marks a column that the rivers leave as it is. */
    private static final int NO_RIVER = Integer.MIN_VALUE;
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
        int[] lakeDepths = lakeDepths(data, worldInfo.getSeed(), chunkX * 16, chunkZ * 16, 16, 16);
        River rivers = riverTops(data, worldInfo.getSeed(), chunkX * 16, chunkZ * 16, 16, 16);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                int top = data.heightAt(x, z);
                if (lakeDepths != null && lakeDepths[localX * 16 + localZ] >= 0) {
                    // A lake: the height of the map is the water level, the bed is under it.
                    int bed = top - lakeDepths[localX * 16 + localZ];
                    chunkData.setRegion(localX, minY, localZ, localX + 1, bed + 1, localZ + 1, Material.STONE);
                    chunkData.setRegion(localX, bed + 1, localZ, localX + 1, top + 1, localZ + 1, Material.WATER);
                    continue;
                }
                int index = localX * 16 + localZ;
                boolean carved = rivers != null && rivers.top(index, top) != top;
                if (rivers != null) {
                    top = rivers.top(index, top); // the channel of a river and its banks
                }
                top = groundAt(flats, x, z, top);
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (rivers != null && rivers.isRiver(index) && rivers.levels()[index] > top) {
                    // A river: the bed is the ground, the water is at the level of the river.
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, rivers.levels()[index] + 1, localZ + 1,
                            Material.WATER);
                } else if (top < SEA_LEVEL && !carved) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
            }
        }
    }

    /**
     * The depth of the water of the lake columns of the area of {@code width} x {@code height} columns from (x0, z0), by
     * {@code (x - x0) * height + (z - z0)}: -1 for a column that is not a lake, else the blocks under the water level
     * where its bed is. The depth grows by {@link #LAKE_SLOPE} per block from the shore (the nearest column that is not a
     * lake, within {@link #LAKE_RADIUS} blocks) up to {@link #LAKE_DEPTH}, so the bed goes down in a slope; a noise then
     * moves the bed, by {@link #LAKE_NOISE} at most where it is deepest and not at all at the shore, so that it is not
     * flat. Null when there is no lake in the area.
     */
    private static int[] lakeDepths(WorldData data, long seed, int x0, int z0, int width, int height) {
        int around = 2 * LAKE_RADIUS;
        int stride = height + around;
        boolean[] lake = new boolean[(width + around) * stride]; // the area and LAKE_RADIUS columns around it
        boolean any = false;
        for (int i = 0; i < width + around; i++) {
            for (int j = 0; j < stride; j++) {
                boolean is = data.lakeAt(x0 - LAKE_RADIUS + i, z0 - LAKE_RADIUS + j);
                lake[i * stride + j] = is;
                any |= is && i >= LAKE_RADIUS && i < LAKE_RADIUS + width && j >= LAKE_RADIUS && j < LAKE_RADIUS + height;
            }
        }
        if (!any) {
            return null;
        }
        int[] depths = new int[width * height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                if (!lake[(i + LAKE_RADIUS) * stride + j + LAKE_RADIUS]) {
                    depths[i * height + j] = -1;
                    continue;
                }
                int nearest = Integer.MAX_VALUE; // squared distance to the nearest column that is not a lake
                for (int di = -LAKE_RADIUS; di <= LAKE_RADIUS; di++) {
                    for (int dj = -LAKE_RADIUS; dj <= LAKE_RADIUS; dj++) {
                        int distance = di * di + dj * dj;
                        if (distance < nearest && !lake[(i + LAKE_RADIUS + di) * stride + j + LAKE_RADIUS + dj]) {
                            nearest = distance;
                        }
                    }
                }
                double base = nearest == Integer.MAX_VALUE ? LAKE_DEPTH
                        : Math.min(LAKE_DEPTH, Math.floor(Math.sqrt(nearest) * LAKE_SLOPE));
                double noise = bedNoise(seed, x0 + i, z0 + j) * LAKE_NOISE * base / LAKE_DEPTH;
                depths[i * height + j] = Math.max(0, (int) Math.round(base + noise));
            }
        }
        return depths;
    }

    /**
     * The Y of the top block of the columns of the area of {@code width} x {@code height} columns from (x0, z0), by
     * {@code (x - x0) * height + (z - z0)}, once the rivers have cut their channel: {@link #NO_RIVER} for a column that
     * they leave as it is, null when there is none in the area. The water level of a river column is the lowest river
     * column around it (flat across the river, following the course down); its bed is under it, from 1 block at the
     * banks of the river up to {@link #RIVER_DEPTH} in the middle, moved by a noise. The ground next to the river is
     * lowered to a bank that rises by 1 block per block from the water level, the lowering fading out to nothing
     * {@link #RIVER_REACH} blocks from the river. The ground is only ever lowered.
     */
    private static River riverTops(WorldData data, long seed, int x0, int z0, int width, int height) {
        int margin = RIVER_REACH + RIVER_LEVEL_RADIUS;
        int stride = height + 2 * margin;
        int size = (width + 2 * margin) * stride;
        int[] river = new int[size]; // the height of the river columns of the area and the margin around it, else NO_RIVER
        int count = 0;
        for (int i = 0; i < width + 2 * margin; i++) {
            for (int j = 0; j < stride; j++) {
                boolean is = data.riverAt(x0 - margin + i, z0 - margin + j);
                river[i * stride + j] = is ? data.heightAt(x0 - margin + i, z0 - margin + j) : NO_RIVER;
                count += is ? 1 : 0;
            }
        }
        if (count == 0) {
            return null;
        }
        int[] cells = new int[count]; // the index of each river column
        count = 0;
        for (int index = 0; index < size; index++) {
            if (river[index] != NO_RIVER) {
                cells[count++] = index;
            }
        }
        int[] tops = new int[width * height];
        int[] levels = new int[width * height];
        boolean any = false;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                int top = data.heightAt(x0 + i, z0 + j);
                int self = (i + margin) * stride + j + margin;
                boolean is = river[self] != NO_RIVER;
                int result = is ? channel(river, stride, self, top, seed, x0 + i, z0 + j)
                        : bank(river, stride, cells, self, top);
                tops[i * height + j] = result == top ? NO_RIVER : result;
                levels[i * height + j] = is ? waterLevel(river, stride, self) : NO_RIVER;
                any |= is || result != top;
            }
        }
        return any ? new River(tops, levels) : null;
    }

    /**
     * What the rivers do to an area of columns (indexed as in {@link #riverTops}): {@code tops} the Y of the top block of
     * a column they change, else {@link #NO_RIVER}; {@code levels} the Y of the water of a river column, else
     * {@link #NO_RIVER}.
     */
    private record River(int[] tops, int[] levels) {

        /** The top block of the column {@code index}, {@code natural} when the rivers leave it as it is. */
        int top(int index, int natural) {
            return tops[index] == NO_RIVER ? natural : tops[index];
        }

        boolean isRiver(int index) {
            return levels[index] != NO_RIVER;
        }
    }

    /** The water level of a river column: the lowest river column within {@link #RIVER_LEVEL_RADIUS} of {@code index}. */
    private static int waterLevel(int[] river, int stride, int index) {
        int level = Integer.MAX_VALUE;
        for (int di = -RIVER_LEVEL_RADIUS; di <= RIVER_LEVEL_RADIUS; di++) {
            for (int dj = -RIVER_LEVEL_RADIUS; dj <= RIVER_LEVEL_RADIUS; dj++) {
                int other = index + di * stride + dj;
                if (other >= 0 && other < river.length && river[other] != NO_RIVER) {
                    level = Math.min(level, river[other]);
                }
            }
        }
        return level;
    }

    /** The top of a river column: its bed, {@code 1} to {@link #RIVER_DEPTH} blocks under the water level. */
    private static int channel(int[] river, int stride, int self, int top, long seed, int x, int z) {
        int level = waterLevel(river, stride, self);
        int nearest = Integer.MAX_VALUE; // squared distance to the nearest column that is not a river, within the depth
        for (int di = -RIVER_DEPTH; di <= RIVER_DEPTH; di++) {
            for (int dj = -RIVER_DEPTH; dj <= RIVER_DEPTH; dj++) {
                int other = self + di * stride + dj;
                if (di * di + dj * dj < nearest && other >= 0 && other < river.length && river[other] == NO_RIVER) {
                    nearest = di * di + dj * dj;
                }
            }
        }
        double base = nearest == Integer.MAX_VALUE ? RIVER_DEPTH : Math.min(RIVER_DEPTH, Math.floor(Math.sqrt(nearest)));
        double noise = bedNoise(seed, x, z) * RIVER_NOISE * base / RIVER_DEPTH;
        int depth = Math.max(0, (int) Math.round(base + noise));
        return Math.min(top, level - depth);
    }

    /** The top of a column next to a river: the natural ground, lowered to the bank of the nearest river column. */
    private static int bank(int[] river, int stride, int[] cells, int self, int top) {
        int nearest = Integer.MAX_VALUE;
        int from = -1;
        int si = self / stride;
        int sj = self % stride;
        for (int cell : cells) {
            int di = cell / stride - si;
            int dj = cell % stride - sj;
            int distance = di * di + dj * dj;
            if (distance < nearest) {
                nearest = distance;
                from = cell;
            }
        }
        if (nearest > RIVER_REACH * RIVER_REACH) {
            return top;
        }
        double distance = Math.sqrt(nearest);
        int cap = waterLevel(river, stride, from) + (int) Math.floor(distance) - 1;
        double fade = Math.clamp((RIVER_REACH - distance) / (RIVER_REACH - 2), 0, 1);
        return top - (int) Math.round(Math.max(0, top - cap) * fade);
    }

    /** Smooth noise in [-1, 1] at the column (x, z): two octaves of value noise, from the seed. */
    private static double bedNoise(long seed, int x, int z) {
        return (valueNoise(seed, x, z, LAKE_NOISE_CELL) * 2 + valueNoise(seed + 1, x, z, LAKE_NOISE_CELL / 3)) / 3;
    }

    private static double valueNoise(long seed, int x, int z, int cell) {
        int cx = Math.floorDiv(x, cell);
        int cz = Math.floorDiv(z, cell);
        double fx = (double) Math.floorMod(x, cell) / cell;
        double fz = (double) Math.floorMod(z, cell) / cell;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double top = lerp(corner(seed, cx, cz), corner(seed, cx + 1, cz), fx);
        double bottom = lerp(corner(seed, cx, cz + 1), corner(seed, cx + 1, cz + 1), fx);
        return lerp(top, bottom, fz);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** A pseudo-random value in [-1, 1] for a corner of the grid of the noise. */
    private static double corner(long seed, int cx, int cz) {
        long h = seed ^ cx * 0x9E3779B97F4A7C15L ^ cz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h & 0xFFFF) / 32767.5 - 1;
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
        int top = data().heightAt(x, z);
        boolean seabed = heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG;
        River river = riverTops(data(), worldInfo.getSeed(), x, z, 1, 1);
        if (river != null) {
            top = river.top(0, top); // the channel of a river and its banks
            if (!seabed && river.isRiver(0)) {
                top = Math.max(top, river.levels()[0]); // the water of the river
            }
        }
        if (seabed) {
            int[] depth = lakeDepths(data(), worldInfo.getSeed(), x, z, 1, 1);
            if (depth != null && depth[0] >= 0) {
                top -= depth[0]; // the bed of a lake, under its water
            }
        }
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
     * removed. A lake is treated the same way: stone up to its bed, whole water to its surface, nothing above. On the land the caves are as the carvers made them (the water of the underground lakes stays), except under
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
        int[] lakeDepths = lakeDepths(data, worldInfo.getSeed(), chunkX * 16, chunkZ * 16, 16, 16);
        River rivers = riverTops(data, worldInfo.getSeed(), chunkX * 16, chunkZ * 16, 16, 16);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                if (rivers != null && rivers.isRiver(localX * 16 + localZ)) {
                    // A river, as a lake: stone under the bed, whole water to its level, no water above.
                    int level = rivers.levels()[localX * 16 + localZ];
                    int bed = rivers.top(localX * 16 + localZ, top);
                    for (int y = limit + 1; y <= level; y++) {
                        if (carved(chunkData.getType(localX, y, localZ))) {
                            chunkData.setBlock(localX, y, localZ, y <= bed ? Material.STONE : Material.WATER);
                        }
                    }
                    for (int y = level + 1; y < maxY; y++) {
                        if (chunkData.getType(localX, y, localZ) == Material.WATER) {
                            chunkData.setBlock(localX, y, localZ, Material.AIR);
                        }
                    }
                    continue;
                }
                if (lakeDepths != null && lakeDepths[localX * 16 + localZ] >= 0) {
                    // A lake, as a sea: stone under the bed, whole water over it, no air or water above the lake.
                    int bed = top - lakeDepths[localX * 16 + localZ];
                    for (int y = limit + 1; y <= top; y++) {
                        if (carved(chunkData.getType(localX, y, localZ))) {
                            chunkData.setBlock(localX, y, localZ, y <= bed ? Material.STONE : Material.WATER);
                        }
                    }
                    for (int y = top + 1; y < maxY; y++) {
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
