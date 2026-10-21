package fr.basile.isohypse;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.io.File;
import java.util.Random;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>The terrain height of every column comes from the height map (plugins/Isohypse/heightmap.png, generated on
 * the first chunk if missing, which makes the server wait for it), surrounded by rotated copies of itself outside the map. The ground is
 * filled with stone, water up to the vanilla sea level (63) and noise caves ({@link CaveNoise}). It replaces the
 * vanilla noise stage only: the other vanilla stages (surface rules, carvers, decorations, structures, mobs) run on
 * top of it.
 */
public final class RealisticGenerator extends ChunkGenerator {

    /** Vanilla sea level: the low parts of the terrain are filled with water up to this Y. */
    private static final int SEA_LEVEL = 63;
    /** Under a sea, the caves only start this many blocks under the lowest point of the sea floor of the map. */
    private static final int CARVER_SEA_MARGIN = 8;
    /** The lowest point of the height map is this many blocks under the sea level (deep ocean floor). */
    private static final int OCEAN_DEPTH = 30;
    /** Cave voids this close to the seabed of an ocean (below sea level) are flooded, like vanilla aquifers. */
    private static final int FLOOD_DEPTH = 16;
    /** Below this Y, cave voids are lava instead of air (vanilla lava aquifers). */
    private static final int LAVA_LEVEL = -54;

    private final IsohypsePlugin plugin;
    private volatile CaveNoise caves;
    private volatile long cavesSeed;
    private volatile HeightMapData heightMap;
    private volatile BiomeLayout layout;

    RealisticGenerator(IsohypsePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The height map, generated first if the file does not exist yet. The world height limit comes from Paper
     * ({@code worldInfo}), so this runs on the first chunk generation: the thread waits, hence the server too.
     */
    private HeightMapData heightMap(WorldInfo worldInfo) {
        HeightMapData current = heightMap;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (heightMap == null) {
                File file = new File(plugin.getDataFolder(), "heightmap.png");
                try {
                    int bottomY = SEA_LEVEL - OCEAN_DEPTH;
                    int topY = worldInfo.getMaxHeight() - 1;
                    HeightMapGenerator generator = new HeightMapGenerator(plugin.getLogger(),
                            plugin.getDataFolder().toPath().resolve("dem-cache"), SEA_LEVEL, worldInfo.getSeed(),
                            plugin.getConfig().getBoolean("realistic.tiling", false),
                            plugin.getConfig().getInt("realistic.blend_width", 200));
                    if (!file.exists()) {
                        plugin.getLogger().info("Realistic: generating the height map (area "
                                + plugin.getConfig().getInt("realistic.size") + " m, heights " + bottomY + " to "
                                + topY + "), the server waits for it...");
                        generator.generate(plugin.getConfig().getDouble("realistic.latitude"),
                                plugin.getConfig().getDouble("realistic.longitude"),
                                plugin.getConfig().getInt("realistic.size"), file, bottomY, topY);
                    } else {
                        // Existing height map: check its scale against the world height, adjust it if needed.
                        generator.adjust(file, bottomY, topY);
                    }
                    HeightMapData read = HeightMapData.read(file, plugin.getConfig().getBoolean("realistic.tiling", false),
                            plugin.getConfig().getInt("realistic.blend_width", 200), worldInfo.getSeed());
                    layout = new BiomeLayout(SEA_LEVEL, worldInfo.getSeed(), TempLayout.build(read, SEA_LEVEL)); // before heightMap, which unlocks the fast path
                    heightMap = read;
                } catch (Exception e) {
                    throw new IllegalStateException("Realistic: height map unavailable", e);
                }
                plugin.getLogger().info("Realistic: height map " + heightMap.width() + "x" + heightMap.height()
                        + " blocks loaded, surrounded by rotated copies of itself outside the map");
            }
            return heightMap;
        }
    }

    private synchronized CaveNoise cavesFor(WorldInfo worldInfo) {
        if (caves == null || cavesSeed != worldInfo.getSeed()) {
            cavesSeed = worldInfo.getSeed();
            caves = new CaveNoise(cavesSeed);
        }
        return caves;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        //CaveNoise caveNoise = cavesFor(worldInfo);
        HeightMapData map = heightMap(worldInfo);
        int minY = chunkData.getMinHeight();
        //int height = worldInfo.getMaxHeight() - minY;

        // Carved blocks of the chunk plus a one-block border, so that smoothing gives the same result on both
        // sides of a chunk boundary. Index: [(x + 1) * 18 + (z + 1)][y - minY].
        //boolean[][] carved = new boolean[18 * 18][height];
        //for (int x = -1; x <= 16; x++) {
        //    for (int z = -1; z <= 16; z++) {
        //        int top = map.heightAt(chunkX * 16 + x, chunkZ * 16 + z);
        //        boolean[] column = carved[(x + 1) * 18 + z + 1];
        //        for (int y = minY + 1; y <= top; y++) {
        //            column[y - minY] = caveNoise.isCave(chunkX * 16 + x, y, chunkZ * 16 + z, top - y);
        //        }
        //    }
        //}

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = map.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
                
                //for (int y = minY + 1; y <= top; y++) {
                //    if (smoothed(carved, localX, y - minY, localZ)) {
                //        chunkData.setBlock(localX, y, localZ, voidFill(y, top));
                //    }
                //}
                
            }
        }
    }

    /**
     * Smoothing of the cave walls: a solid block with at least 4 cave neighbours (of 6) is carved, so single blocks,
     * thin pillars and bumps disappear, and a cave block with no cave neighbour is filled.
     */
    private static boolean smoothed(boolean[][] carved, int x, int yIndex, int z) {
        int open = 0;
        open += carved[(x + 2) * 18 + z + 1][yIndex] ? 1 : 0;
        open += carved[x * 18 + z + 1][yIndex] ? 1 : 0;
        open += carved[(x + 1) * 18 + z + 2][yIndex] ? 1 : 0;
        open += carved[(x + 1) * 18 + z][yIndex] ? 1 : 0;
        open += yIndex + 1 < carved[0].length && carved[(x + 1) * 18 + z + 1][yIndex + 1] ? 1 : 0;
        open += yIndex > 0 && carved[(x + 1) * 18 + z + 1][yIndex - 1] ? 1 : 0;
        return carved[(x + 1) * 18 + z + 1][yIndex] ? open > 0 : open >= 4;
    }

    /** What fills a carved block: lava deep down, water near the seabed under the sea, air otherwise. */
    private static Material voidFill(int y, int top) {
        if (y < LAVA_LEVEL) {
            return Material.LAVA;
        }
        return top < SEA_LEVEL && y <= SEA_LEVEL && top - y < FLOOD_DEPTH ? Material.WATER : Material.AIR;
    }

    /** Biome at (x, y, z), from the altitude of the column in the height map (loaded first if needed). */
    Biome biomeAt(WorldInfo worldInfo, int x, int y, int z) {
        HeightMapData map = heightMap(worldInfo);
        return layout.at(x, y, z, map.heightAt(x, z)).biome();
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new HeightBiomeProvider(this);
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return heightMap(worldInfo).heightAt(x, z) + 1;
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
     * is whole again, so the sea floor is not broken into. On the land the caves are as the carvers made them.
     */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        HeightMapData map = heightMap(worldInfo);
        int limit = Math.max(chunkData.getMinHeight(), map.lowest() - CARVER_SEA_MARGIN);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = map.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
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
            }
        }
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
