package fr.basile.isohypse;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.io.File;
import java.util.Random;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>The terrain height of every column comes from the height map (plugins/Isohypse/heightmap.png, generated on
 * the first chunk if missing, which makes the server wait for it), repeated endlessly outside the map. The ground is
 * filled with stone, water up to the vanilla sea level (63) and noise caves ({@link CaveNoise}). It replaces the
 * vanilla noise stage only: the other vanilla stages (surface rules, carvers, decorations, structures, mobs) run on
 * top of it.
 */
public final class RealisticGenerator extends ChunkGenerator {

    /** Vanilla sea level: the low parts of the terrain are filled with water up to this Y. */
    private static final int SEA_LEVEL = 63;
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
                            plugin.getDataFolder().toPath().resolve("dem-cache"), SEA_LEVEL);
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
                    heightMap = HeightMapData.read(file);
                } catch (Exception e) {
                    throw new IllegalStateException("Realistic: height map unavailable", e);
                }
                plugin.getLogger().info("Realistic: height map " + heightMap.width() + "x" + heightMap.height()
                        + " blocks loaded, repeated outside the map");
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
        CaveNoise caveNoise = cavesFor(worldInfo);
        HeightMapData map = heightMap(worldInfo);
        int minY = chunkData.getMinHeight();
        int height = worldInfo.getMaxHeight() - minY;

        // Carved blocks of the chunk plus a one-block border, so that smoothing gives the same result on both
        // sides of a chunk boundary. Index: [(x + 1) * 18 + (z + 1)][y - minY].
        boolean[][] carved = new boolean[18 * 18][height];
        for (int x = -1; x <= 16; x++) {
            for (int z = -1; z <= 16; z++) {
                int top = map.heightAt(chunkX * 16 + x, chunkZ * 16 + z);
                boolean[] column = carved[(x + 1) * 18 + z + 1];
                for (int y = minY + 1; y <= top; y++) {
                    column[y - minY] = caveNoise.isCave(chunkX * 16 + x, y, chunkZ * 16 + z, top - y);
                }
            }
        }

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = map.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
                for (int y = minY + 1; y <= top; y++) {
                    if (smoothed(carved, localX, y - minY, localZ)) {
                        chunkData.setBlock(localX, y, localZ, voidFill(y, top));
                    }
                }
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
        return true;
    }
}
