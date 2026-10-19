package fr.basile.isohypse;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>The terrain shape is our own: a sine wave along the X axis (see {@link #heightAt}), filled with stone, water up to the vanilla sea level (63) and
 * noise caves that can open onto the surface ({@link CaveNoise}). It replaces the vanilla noise stage entirely. Surface rules, carvers, decorations, structures and mobs are
 * skipped, so the world is bare stone for now.
 */
public final class RealisticGenerator extends ChunkGenerator {

    private static final int BASE_HEIGHT = 64;
    private static final double AMPLITUDE = 32;
    private static final double WAVELENGTH = 64;
    /** Vanilla sea level: the troughs of the wave are filled with water up to this Y. */
    private static final int SEA_LEVEL = 63;
    /** Cave voids this close to the seabed of an ocean (below sea level) are flooded, like vanilla aquifers. */
    private static final int FLOOD_DEPTH = 16;
    /** Below this Y, cave voids are lava instead of air (vanilla lava aquifers). */
    private static final int LAVA_LEVEL = -54;

    private volatile CaveNoise caves;
    private volatile long cavesSeed;

    private synchronized CaveNoise cavesFor(WorldInfo worldInfo) {
        if (caves == null || cavesSeed != worldInfo.getSeed()) {
            cavesSeed = worldInfo.getSeed();
            caves = new CaveNoise(cavesSeed);
        }
        return caves;
    }

    /** Height of the top stone block at world column x (the wave only depends on X). */
    private static int heightAt(int x) {
        return BASE_HEIGHT + (int) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * x / WAVELENGTH));
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        CaveNoise caveNoise = cavesFor(worldInfo);
        int minY = chunkData.getMinHeight();
        int height = BASE_HEIGHT + (int) AMPLITUDE + 2 - minY;

        // Carved blocks of the chunk plus a one-block border, so that smoothing gives the same result on both
        // sides of a chunk boundary. Index: [(x + 1) * 18 + (z + 1)][y - minY].
        boolean[][] carved = new boolean[18 * 18][height];
        for (int x = -1; x <= 16; x++) {
            int top = heightAt(chunkX * 16 + x);
            for (int z = -1; z <= 16; z++) {
                boolean[] column = carved[(x + 1) * 18 + z + 1];
                for (int y = minY + 1; y <= top; y++) {
                    column[y - minY] = caveNoise.isCave(chunkX * 16 + x, y, chunkZ * 16 + z, top - y);
                }
            }
        }

        for (int localX = 0; localX < 16; localX++) {
            int top = heightAt(chunkX * 16 + localX);
            chunkData.setRegion(localX, minY, 0, localX + 1, top + 1, 16, Material.STONE);
            if (top < SEA_LEVEL) {
                chunkData.setRegion(localX, top + 1, 0, localX + 1, SEA_LEVEL + 1, 16, Material.WATER);
            }
            for (int localZ = 0; localZ < 16; localZ++) {
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
        return heightAt(x) + 1;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
