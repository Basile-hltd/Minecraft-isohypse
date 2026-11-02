package fr.basile.isohypse;

import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator.ChunkData;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;
import java.util.function.IntBinaryOperator;

/**
 * Big caverns under Y {@link #TOP}: the ground is hollow where a 3D noise of the seed is over {@link #THRESHOLD}, the
 * noise being wider than high so that the caverns are pockets rather than shafts. They close smoothly towards their
 * ceiling (Y {@link #TOP}, or less where the column asks for it: under the water, under the surface) and towards the
 * bottom of the world, and the air under Y {@link #LAVA_LEVEL} is lava, as in the caves of the game.
 *
 * <p>The noise is sampled every {@link #STEP} blocks and interpolated in between, as the game does, which is fast and
 * smooth; a sample depends on its position alone, so the chunks agree on their borders. Thread safe.
 */
final class Caverns {

    /**
     * The highest Y of a cavern: where the deepslate starts (the surface rules of the game turn the stone into deepslate
     * from Y 8, a little more at each block down, and it is all deepslate under Y 0).
     */
    static final int TOP = 8;
    /** The caverns close over this many blocks under their ceiling (at {@link #TOP}: through the mix of the two)... */
    private static final int FADE = 8;
    /** ...and over this many blocks above {@link #FLOOR}... */
    private static final int BOTTOM_FADE = 8;
    /** ...which is how many blocks above the bottom of the world nothing is hollow (the bedrock is there). */
    private static final int FLOOR = 5;
    /** The air of a cavern at this Y and under is lava. */
    static final int LAVA_LEVEL = -55;
    /** The size, in blocks, of the biggest features of the noise, across and up. */
    private static final double SCALE_XZ = 128;
    private static final double SCALE_Y = 64;
    /** Hollow where the noise (about -1 to 1) is over this: the higher, the fewer and smaller the caverns. */
    private static final double THRESHOLD = 0.35;
    /** Blocks between two samples of the noise. */
    private static final int STEP = 4;

    private final SimplexOctaveGenerator noise;

    Caverns(long seed) {
        noise = new SimplexOctaveGenerator(new Random(seed ^ 0x6A09E667F3BCC909L), 3);
        noise.setXScale(1 / SCALE_XZ);
        noise.setYScale(1 / SCALE_Y);
        noise.setZScale(1 / SCALE_XZ);
    }

    /**
     * Hollows the caverns of the chunk out of {@code chunkData}, whose ground is already there.
     *
     * @param ceilings the highest Y a cavern can reach at the column (x, z), {@link #TOP} at most
     */
    void carve(ChunkData chunkData, int chunkX, int chunkZ, IntBinaryOperator ceilings) {
        int minY = chunkData.getMinHeight();
        int levels = (TOP - minY) / STEP + 2; // one more, for the blocks at TOP itself
        int x0 = chunkX * 16;
        int z0 = chunkZ * 16;
        int corners = 16 / STEP + 1;
        double[][][] density = new double[corners][corners][levels];
        int[][] cornerCeilings = new int[corners][corners];
        for (int i = 0; i < corners; i++) {
            for (int k = 0; k < corners; k++) {
                int x = x0 + i * STEP;
                int z = z0 + k * STEP;
                int ceiling = Math.min(TOP, ceilings.applyAsInt(x, z));
                cornerCeilings[i][k] = ceiling;
                for (int l = 0; l < levels; l++) {
                    density[i][k][l] = density(x, minY + l * STEP, z, ceiling, minY);
                }
            }
        }
        int[] columnCeilings = new int[256];
        int highest = minY;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int ceiling = Math.min(TOP, ceilings.applyAsInt(x0 + lx, z0 + lz));
                columnCeilings[lx * 16 + lz] = ceiling;
                highest = Math.max(highest, ceiling);
            }
        }
        for (int lx = 0; lx < 16; lx++) {
            int i = lx / STEP;
            double fx = (double) (lx % STEP) / STEP;
            for (int lz = 0; lz < 16; lz++) {
                int k = lz / STEP;
                double fz = (double) (lz % STEP) / STEP;
                int ceiling = columnCeilings[lx * 16 + lz];
                for (int y = minY + FLOOR; y <= Math.min(ceiling, highest); y++) {
                    int l = (y - minY) / STEP;
                    double fy = (double) ((y - minY) % STEP) / STEP;
                    double d = lerp(fz,
                            lerp(fx, lerp(fy, density[i][k][l], density[i][k][l + 1]),
                                    lerp(fy, density[i + 1][k][l], density[i + 1][k][l + 1])),
                            lerp(fx, lerp(fy, density[i][k + 1][l], density[i][k + 1][l + 1]),
                                    lerp(fy, density[i + 1][k + 1][l], density[i + 1][k + 1][l + 1])));
                    if (d > 0) {
                        chunkData.setBlock(lx, y, lz, y <= LAVA_LEVEL ? Material.LAVA : Material.AIR);
                    }
                }
            }
        }
    }

    /**
     * Over 0 where the ground is hollow at (x, y, z): the noise less the threshold, less how far into the fade under the
     * {@code ceiling} or above the floor the position is (a full 1 at the ceiling or the floor, so it is closed there).
     */
    double density(int x, int y, int z, int ceiling, int minY) {
        double d = noise.noise(x, y, z, 2, 0.5, true) - THRESHOLD;
        d -= Math.max(0, (double) (y - (ceiling - FADE)) / FADE);
        d -= Math.max(0, (double) (minY + FLOOR + BOTTOM_FADE - y) / BOTTOM_FADE);
        return d;
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}
