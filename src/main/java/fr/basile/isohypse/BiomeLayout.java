package fr.basile.isohypse;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.util.noise.PerlinNoiseGenerator;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Which biome sits at a world position. Shared by {@link HeightBiomeProvider} (the real world) and the readable maps,
 * so both always agree.
 *
 * <p><b>Surface</b>: {@link TempLayout} (the intermediate map) tells, for a column, the pool of biomes, and a noise
 * picks the biome in the pool:
 * <ul>
 * <li>in the sea, the {@link OceanType}, and the noise picks the biome in the pool (a deep variant from
 * {@link #DEEP_OCEAN} blocks under the sea level);</li>
 * <li>on land, the {@link LandType} (the beach, warm, normal, cold), and the biome is the one of the cell, drawn in
 * the pool. The drawing is the old Minecraft way: the world is cut in cells of about {@link #CELL} blocks (a random
 * point in each square of a grid, every position belongs to the closest point), each cell gets one biome by a hash of
 * the seed, and the cell borders are wobbled by a noise so that they look like natural, irregular edges.</li>
 * </ul>
 *
 * <p><b>Underground</b>: deeper than {@link #CAVE_DEPTH} blocks under the surface <i>and</i> under the sea level minus
 * {@link #CAVE_SEA_MARGIN}, a cave can have its own biome, but most of it has none and keeps the surface biome. Never
 * above that Y, whatever the surface. The cave biomes are big cells like the land ones (see {@link #caveCell}), and
 * their shares depend on the depth:
 * <ul>
 * <li>from {@link #DEEP_DARK_Y} up: {@link #UPPER_NONE} none, then lush caves, dripstone caves and sulfur caves in the
 * proportions of {@link #UPPER_LUSH}, {@link #UPPER_DRIPSTONE} and what is left;</li>
 * <li>under it: {@link #LOWER_DEEP_DARK} deep dark (small cells, spread out), {@link #LOWER_LUSH} lush caves, the rest
 * none.</li>
 * </ul>
 *
 * <p>Read-only after construction, so thread safe.
 */
final class BiomeLayout {

    /** A biome and the colour that stands for it in the preview. */
    record Entry(Biome biome, int rgb) {}

    /** From this many blocks under the sea level, the ocean is a deep ocean. */
    private static final int DEEP_OCEAN = 12;
    /** A cave biome only starts this many blocks under the surface... */
    private static final int CAVE_DEPTH = 8;
    /** ...and this many blocks under the sea level (so none above Y 63 - 8 = 55, whatever the surface). */
    private static final int CAVE_SEA_MARGIN = 8;
    /** The cave biomes change at this Y: above, the upper shares, under, the lower ones. */
    private static final int DEEP_DARK_Y = -24;
    /** Above it, the share of the caves that have no biome of their own (the surface one)... */
    private static final double UPPER_NONE = 0.50;
    /** ...lush caves... */
    private static final double UPPER_LUSH = 0.20;
    /** ...dripstone caves; the rest (0.10) is sulfur caves. */
    private static final double UPPER_DRIPSTONE = 0.20;
    /** Under it, the share of deep dark... */
    private static final double LOWER_DEEP_DARK = 0.04;
    /** ...and of lush caves (the rest, 0.86, has no biome). */
    private static final double LOWER_LUSH = 0.10;
    /** Side (blocks) of the cave cells of the upper layer and of the lush caves below, twice as big as the land ones... */
    private static final int CAVE_CELL = 1000;
    /** ...and of the deep dark cells, which are small and rare, so that the patches are spread over the map. */
    private static final int DEEP_DARK_CELL = 500;
    /** The border of a cave cell is wobbled by a noise this long (in cells) and this strong (in cells). */
    private static final double CAVE_WARP_SCALE = 1.0;
    private static final double CAVE_WARP_STRENGTH = 0.74;
    /** Size (blocks) of the biggest noise features; the two next octaves are twice and four times smaller. */
    private static final double CLIMATE_SCALE = 600;
    /** The raw noise rarely leaves [-0.6, 0.6]: stretched so that the climate spans -1 to 1. */
    private static final double CLIMATE_GAIN = 1.8;
    /** Side (blocks) of the squares of the grid whose points are the centres of the biome cells. */
    private static final int CELL = 512;
    /** Size (blocks) of the biggest wobble of the cell borders... */
    private static final double WARP_SCALE = 500;
    /** ...and how far (blocks, about) it pushes a position. */
    private static final double WARP_STRENGTH = 380;

    // Oceans by temperature (cold to warm), shallow then deep.
    private static final List<Entry> OCEANS = List.of(
            new Entry(Biome.FROZEN_OCEAN, 0x8fb8e0), new Entry(Biome.COLD_OCEAN, 0x2a6aa8),
            new Entry(Biome.OCEAN, 0x1f4fa3), new Entry(Biome.LUKEWARM_OCEAN, 0x2f8fc4),
            new Entry(Biome.WARM_OCEAN, 0x3fc0d0));
    private static final List<Entry> DEEP_OCEANS = List.of(
            new Entry(Biome.DEEP_FROZEN_OCEAN, 0x6a92c0), new Entry(Biome.DEEP_COLD_OCEAN, 0x1d4a7a),
            new Entry(Biome.DEEP_OCEAN, 0x14347a), new Entry(Biome.DEEP_LUKEWARM_OCEAN, 0x236b99),
            new Entry(Biome.WARM_OCEAN, 0x3fc0d0)); // no deep warm ocean in vanilla
    /** The ocean biomes of each type, shallow and deep (the pool a noise picks in). */
    private static final Map<OceanType, List<Entry>> SHALLOW_POOLS = pools(OCEANS);
    private static final Map<OceanType, List<Entry>> DEEP_POOLS = pools(DEEP_OCEANS);
    private static final Entry BEACH = new Entry(Biome.BEACH, 0xf0e0a0);
    private static final Entry STONY_SHORE = new Entry(Biome.STONY_SHORE, 0x8a8a7a);

    /** Entries with weights, drawn from by a number between 0 and 1. */
    private record WeightedPool(List<Entry> entries, double[] ends) {

        /** The entry at {@code unit} (from 0 included to 1 excluded) along the weights laid end to end. */
        Entry pick(double unit) {
            double target = unit * ends[ends.length - 1];
            for (int i = 0; i < ends.length; i++) {
                if (target < ends[i]) {
                    return entries.get(i);
                }
            }
            return entries.get(entries.size() - 1);
        }
    }

    private static Map<LandType, WeightedPool> landPools() {
        List<Entry> known = concat(List.of(BEACH, STONY_SHORE), LANDS);
        Map<LandType, WeightedPool> pools = new EnumMap<>(LandType.class);
        for (LandType type : LandType.values()) {
            List<Entry> entries = new ArrayList<>();
            List<Double> ends = new ArrayList<>();
            double total = 0;
            for (LandType.Weighted weighted : type.weighted()) {
                for (Entry entry : known) {
                    if (entry.biome().equals(weighted.biome())) {
                        total += weighted.weight();
                        entries.add(entry);
                        ends.add(total);
                        break;
                    }
                }
            }
            pools.put(type, new WeightedPool(List.copyOf(entries), ends.stream().mapToDouble(Double::doubleValue).toArray()));
        }
        return pools;
    }

    private static Map<OceanType, List<Entry>> pools(List<Entry> oceans) {
        Map<OceanType, List<Entry>> pools = new EnumMap<>(OceanType.class);
        for (OceanType type : OceanType.values()) {
            pools.put(type, oceans.stream().filter(entry -> type.biomes().contains(entry.biome())).toList());
        }
        return pools;
    }

    /** The hot biomes. */
    private static final List<Entry> HOT = List.of(
            new Entry(Biome.DESERT, 0xfa9418),
            new Entry(Biome.BADLANDS, 0xd94515), new Entry(Biome.WOODED_BADLANDS, 0xb09765),
            new Entry(Biome.WINDSWEPT_SAVANNA, 0xe5da87), new Entry(Biome.SAVANNA, 0xbdb25f),
            new Entry(Biome.SAVANNA_PLATEAU, 0xa79d64), new Entry(Biome.JUNGLE, 0x537b09),
            new Entry(Biome.SPARSE_JUNGLE, 0x628b17), new Entry(Biome.BAMBOO_JUNGLE, 0x768e14));
    /** The swamps. */
    private static final List<Entry> SWAMPS = List.of(
            new Entry(Biome.SWAMP, 0x07f9b2), new Entry(Biome.MANGROVE_SWAMP, 0x67352b));
    /** The biomes of the mountains, with the snowy ones. */
    private static final List<Entry> ALPINE = List.of(
            new Entry(Biome.WINDSWEPT_HILLS, 0x606060), new Entry(Biome.WINDSWEPT_GRAVELLY_HILLS, 0x789878),
            new Entry(Biome.WINDSWEPT_FOREST, 0x507050), new Entry(Biome.STONY_PEAKS, 0x8a8a8a),
            new Entry(Biome.SNOWY_SLOPES, 0xdde6ee), new Entry(Biome.GROVE, 0xc3d6cc),
            new Entry(Biome.JAGGED_PEAKS, 0xeef2f6), new Entry(Biome.FROZEN_PEAKS, 0xffffff),
            new Entry(Biome.SNOWY_PLAINS, 0xf2f6ff), new Entry(Biome.SNOWY_TAIGA, 0x31554a),
            new Entry(Biome.ICE_SPIKES, 0xb4dcdc));
    /** The other land biomes. */
    private static final List<Entry> OTHER = other();
    /** Every land biome: the cells draw from this list. */
    private static final List<Entry> LANDS = concat(OTHER, HOT, SWAMPS, ALPINE);
    /** The biomes of each type of land (a biome can be in several), with their weights: the pool the cells draw from. */
    private static final Map<LandType, WeightedPool> LAND_POOLS = landPools();
    private static final Entry DEEP_DARK = new Entry(Biome.DEEP_DARK, 0x0a2830);

    /** dappled_forest is an ordinary forest; it only exists in recent versions. */
    private static List<Entry> other() {
        List<Entry> list = new ArrayList<>(List.of(
                new Entry(Biome.PLAINS, 0x8db360), new Entry(Biome.SUNFLOWER_PLAINS, 0xb5db88),
                new Entry(Biome.FOREST, 0x056621), new Entry(Biome.FLOWER_FOREST, 0x2d8e49),
                new Entry(Biome.BIRCH_FOREST, 0x307444), new Entry(Biome.OLD_GROWTH_BIRCH_FOREST, 0x589c6c),
                new Entry(Biome.DARK_FOREST, 0x40511a), new Entry(Biome.PALE_GARDEN, 0xb9c1b0),
                new Entry(Biome.MEADOW, 0x83bb6d), new Entry(Biome.CHERRY_GROVE, 0xf4a6c8),
                new Entry(Biome.TAIGA, 0x0b6659), new Entry(Biome.OLD_GROWTH_PINE_TAIGA, 0x596651),
                new Entry(Biome.OLD_GROWTH_SPRUCE_TAIGA, 0x818e79)));
        Biome dappled = biome("dappled_forest");
        if (dappled != null) {
            list.add(new Entry(dappled, 0x4f9a3c));
        }
        return List.copyOf(list);
    }

    @SafeVarargs
    private static List<Entry> concat(List<Entry>... lists) {
        List<Entry> all = new ArrayList<>();
        for (List<Entry> list : lists) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }

    /** sulfur_caves only exists in recent versions: null without it. */
    private static final Entry SULFUR_CAVES = sulfurCaves();
    private static final Entry LUSH_CAVES = new Entry(Biome.LUSH_CAVES, 0x9ed82a);
    private static final Entry DRIPSTONE_CAVES = new Entry(Biome.DRIPSTONE_CAVES, 0x8b5a2b);

    private static Entry sulfurCaves() {
        Biome sulfur = biome("sulfur_caves");
        return sulfur == null ? null : new Entry(sulfur, 0xe8d820);
    }

    /** A biome by its name in the running server's registry, null if this version does not have it. */
    static Biome biome(String name) {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(NamespacedKey.minecraft(name));
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private final int seaLevel;
    private final long seed;
    private final TempLayout temp;
    private final PerlinNoiseGenerator[] temperature = new PerlinNoiseGenerator[3];
    // humidity and deepDark are not used any more, but are still drawn: the other noises of a seed stay as they were.
    private final PerlinNoiseGenerator[] humidity = new PerlinNoiseGenerator[3];
    private final PerlinNoiseGenerator[] deepDark = new PerlinNoiseGenerator[3];
    private final PerlinNoiseGenerator[] warpX = new PerlinNoiseGenerator[3];
    private final PerlinNoiseGenerator[] warpZ = new PerlinNoiseGenerator[3];

    /**
     * @param seed world seed, which the noises and the biome cells are drawn from
     * @param temp the intermediate map, which tells the type of ocean of a sea column
     */
    BiomeLayout(int seaLevel, long seed, TempLayout temp) {
        this.temp = temp;
        Random random = new Random(seed ^ 0x2545F4914F6CDD1DL);
        for (int i = 0; i < temperature.length; i++) {
            temperature[i] = new PerlinNoiseGenerator(random.nextLong());
            humidity[i] = new PerlinNoiseGenerator(random.nextLong());
        }
        for (int i = 0; i < deepDark.length; i++) {
            deepDark[i] = new PerlinNoiseGenerator(random.nextLong());
        }
        for (int i = 0; i < warpX.length; i++) {
            warpX[i] = new PerlinNoiseGenerator(random.nextLong());
            warpZ[i] = new PerlinNoiseGenerator(random.nextLong());
        }
        this.seaLevel = seaLevel;
        this.seed = seed;
    }

    /** The biome at (x, y, z) in a column whose top block is at Y {@code top}: the cave biome if there is one, else the surface one. */
    Entry at(int x, int y, int z, int top) {
        Entry cave = caveAt(x, y, z, top);
        return cave != null ? cave : at(x, z, top);
    }

    /**
     * The cave biome at (x, y, z) in a column whose top block is at Y {@code top}; null if there is none (not deep
     * enough, or a cave without a biome of its own).
     */
    Entry caveAt(int x, int y, int z, int top) {
        if (y >= top - CAVE_DEPTH || y >= seaLevel - CAVE_SEA_MARGIN) {
            return null;
        }
        if (y < DEEP_DARK_Y) {
            if (caveCell(x, z, DEEP_DARK_CELL, 20) < LOWER_DEEP_DARK) {
                return DEEP_DARK;
            }
            // the lush caves are LOWER_LUSH of the whole, so this share of what the deep dark leaves
            return caveCell(x, z, CAVE_CELL, 30) < LOWER_LUSH / (1 - LOWER_DEEP_DARK) ? LUSH_CAVES : null;
        }
        double draw = caveCell(x, z, CAVE_CELL, 40);
        if (draw < UPPER_NONE) {
            return null;
        } else if (draw < UPPER_NONE + UPPER_LUSH) {
            return LUSH_CAVES;
        } else if (draw < UPPER_NONE + UPPER_LUSH + UPPER_DRIPSTONE) {
            return DRIPSTONE_CAVES;
        }
        return SULFUR_CAVES; // null in a version without it: no biome
    }

    /**
     * The draw (0 to 1) of the cell that the position belongs to, in a grid of cells of {@code cell} blocks whose
     * borders are wobbled (the same way as for the land): the same number all over a cell.
     *
     * @param salt tells the grids apart (each uses {@code salt + 1} to {@code salt + 3})
     */
    private double caveCell(int x, int z, int cell, int salt) {
        double scale = cell * CAVE_WARP_SCALE;
        double strength = cell * CAVE_WARP_STRENGTH;
        double px = x + noise(warpX, x, z, scale) * strength;
        double pz = z + noise(warpZ, x, z, scale) * strength;
        int cellX = (int) Math.floor(px / cell);
        int cellZ = (int) Math.floor(pz / cell);
        double best = Double.MAX_VALUE;
        int bestX = cellX;
        int bestZ = cellZ;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                double centreX = (cx + unit(cx, cz, salt + 1)) * cell;
                double centreZ = (cz + unit(cx, cz, salt + 2)) * cell;
                double distance = (px - centreX) * (px - centreX) + (pz - centreZ) * (pz - centreZ);
                if (distance < best) {
                    best = distance;
                    bestX = cx;
                    bestZ = cz;
                }
            }
        }
        return unit(bestX, bestZ, salt + 3);
    }

    /** The surface biome of the column (x, z) whose top block is at Y {@code top}. */
    Entry at(int x, int z, int top) {
        if (top < TempLayout.beachStart(seaLevel)) {
            double coast = climate(temperature, x, z); // a smooth noise, from -1 to 1
            List<Entry> pool = (top <= seaLevel - DEEP_OCEAN ? DEEP_POOLS : SHALLOW_POOLS).get(temp.oceanTypeAt(x, z));
            return pool.get(Math.min(pool.size() - 1, (int) ((coast + 1) / 2 * pool.size())));
        }
        return randomLand(x, z, LAND_POOLS.get(temp.landTypeAt(x, z, top)));
    }

    /** The biome of the cell the position belongs to (after the wobble of the borders), drawn in the pool. */
    private Entry randomLand(int x, int z, WeightedPool pool) {
        double px = x + noise(warpX, x, z, WARP_SCALE) * WARP_STRENGTH;
        double pz = z + noise(warpZ, x, z, WARP_SCALE) * WARP_STRENGTH;
        int cellX = (int) Math.floor(px / CELL);
        int cellZ = (int) Math.floor(pz / CELL);
        double best = Double.MAX_VALUE;
        int bestX = cellX;
        int bestZ = cellZ;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                double centreX = (cx + unit(cx, cz, 1)) * CELL;
                double centreZ = (cz + unit(cx, cz, 2)) * CELL;
                double distance = (px - centreX) * (px - centreX) + (pz - centreZ) * (pz - centreZ);
                if (distance < best) {
                    best = distance;
                    bestX = cx;
                    bestZ = cz;
                }
            }
        }
        return pool.pick(unit(bestX, bestZ, 3));
    }

    /** A number from 0 (included) to 1 (excluded), fixed by the seed, the cell and the purpose. */
    private double unit(int cellX, int cellZ, int purpose) {
        long h = seed + cellX * 0x9E3779B97F4A7C15L + cellZ * 0xC2B2AE3D27D4EB4FL + purpose * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Three octaves of smooth noise, the raw value (about -0.6 to 0.6), the biggest features {@code scale} blocks wide. */
    private static double noise(PerlinNoiseGenerator[] octaves, int x, int z, double scale) {
        double sum = 0;
        double weight = 1;
        double total = 0;
        double frequency = 1 / scale;
        for (PerlinNoiseGenerator octave : octaves) {
            sum += weight * octave.noise(x * frequency, 0, z * frequency);
            total += weight;
            weight /= 2;
            frequency *= 2;
        }
        return sum / total;
    }

    /** Three octaves of smooth noise, stretched to [-1, 1]. */
    private static double climate(PerlinNoiseGenerator[] octaves, int x, int z) {
        return Math.clamp(noise(octaves, x, z, CLIMATE_SCALE) * CLIMATE_GAIN, -1, 1);
    }

    /** Every surface entry {@link #at(int, int, int)} can return, bottom to top, one per biome. */
    static List<Entry> allEntries() {
        return distinct(List.of(DEEP_OCEANS, OCEANS, List.of(BEACH, STONY_SHORE), LANDS));
    }

    /** Every underground entry {@link #at(int, int, int, int)} can return, one per biome. */
    static List<Entry> caveEntries() {
        List<Entry> all = new ArrayList<>(List.of(LUSH_CAVES, DRIPSTONE_CAVES));
        if (SULFUR_CAVES != null) {
            all.add(SULFUR_CAVES);
        }
        all.add(DEEP_DARK);
        return all;
    }

    /** Every biome this layout can return, surface or underground (the list a BiomeProvider has to declare). */
    static List<Biome> allBiomes() {
        List<Entry> all = new ArrayList<>(allEntries());
        all.addAll(caveEntries());
        return all.stream().map(Entry::biome).toList();
    }

    private static List<Entry> distinct(List<List<Entry>> bands) {
        List<Entry> all = new ArrayList<>();
        for (List<Entry> band : bands) {
            for (Entry entry : band) {
                if (all.stream().noneMatch(known -> known.biome().equals(entry.biome()))) {
                    all.add(entry);
                }
            }
        }
        return all;
    }
}
