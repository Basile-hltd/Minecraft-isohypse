package fr.basile.isohypse;

import java.util.function.IntPredicate;
import java.util.stream.IntStream;

/**
 * For every column of the height map, its distance to the nearest source column (the columns whose height satisfies a
 * test: the edge of the sea, the sea for the inland beach), and whether it is more than {@code minDistance} blocks
 * away. The distance is the straight one, computed exactly (Felzenszwalb and Huttenlocher, one pass along the rows
 * then one along the columns), kept to a quarter of a block, and wraps around the map as the map repeats endlessly
 * (this is not true of the rotated copies of tiling (config {@code realistic.tiling}), which are ignored here).
 */
final class ShoreDistance {

    private static final double INFINITY = 1e20;

    private final int width;
    private final int height;
    private final short[] quarters; // the distance in quarters of a block, at most Short.MAX_VALUE
    private final int minQuarters;

    /** Working arrays of one thread, sized for a line repeated three times (to wrap around). */
    private record Buffers(double[] f, double[] d, int[] v, double[] z) {
        Buffers(int length) {
            this(new double[length], new double[length], new int[length], new double[length + 1]);
        }
    }

    /**
     * @param isSource whether a column of this height is a source, which the distance is measured from
     */
    ShoreDistance(HeightMapData data, IntPredicate isSource, int minDistance) {
        this.width = data.width();
        this.height = data.height();
        float[] horizontal = new float[width * height]; // squared distance to the nearest source of the same row
        ThreadLocal<Buffers> buffers = ThreadLocal.withInitial(() -> new Buffers(3 * Math.max(width, height)));

        IntStream.range(0, height).parallel().forEach(row -> {
            Buffers b = buffers.get();
            for (int i = 0; i < 3 * width; i++) {
                b.f[i] = isSource.test(data.valueAt(i % width, row)) ? 0 : INFINITY;
            }
            squaredDistances(b, 3 * width);
            for (int column = 0; column < width; column++) {
                horizontal[row * width + column] = (float) Math.min(b.d[width + column], INFINITY);
            }
        });

        this.quarters = new short[width * height];
        this.minQuarters = 4 * minDistance;
        IntStream.range(0, width).parallel().forEach(column -> {
            Buffers b = buffers.get();
            for (int i = 0; i < 3 * height; i++) {
                b.f[i] = horizontal[(i % height) * width + column];
            }
            squaredDistances(b, 3 * height);
            for (int row = 0; row < height; row++) {
                quarters[row * width + column] = (short) Math.min(Short.MAX_VALUE,
                        Math.round(Math.sqrt(b.d[height + row]) * 4));
            }
        });
    }

    /** Whether the column at world (x, z) is far from the sources; the map repeats outside its bounds. */
    boolean isFar(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        return quarters[row * width + column] > minQuarters;
    }

    /** The distance of the column of the map itself at (column, row), in quarters of a block. */
    int quarterBlocks(int column, int row) {
        return quarters[row * width + column];
    }

    /** 1D squared distance transform of {@code b.f} (0 at the sources, a huge value elsewhere) into {@code b.d}. */
    private static void squaredDistances(Buffers b, int n) {
        double[] f = b.f;
        int[] v = b.v;
        double[] z = b.z;
        int k = 0;
        v[0] = 0;
        z[0] = -INFINITY;
        z[1] = INFINITY;
        for (int q = 1; q < n; q++) {
            double s;
            while (true) {
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
                if (s <= z[k] && k > 0) {
                    k--;
                } else {
                    break;
                }
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            b.d[q] = (double) (q - v[k]) * (q - v[k]) + f[v[k]];
        }
    }
}
