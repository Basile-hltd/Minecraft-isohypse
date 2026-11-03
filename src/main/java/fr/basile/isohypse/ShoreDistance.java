package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.function.IntPredicate;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * For every column of the height map, its distance to the nearest source column (the columns whose height satisfies a
 * test: the edge of the sea, the sea for the inland beach), and whether it is more than {@code minDistance} blocks
 * away. The distance is the straight one, computed exactly (Felzenszwalb and Huttenlocher, one pass along the rows
 * then one along the columns), kept to a quarter of a block. The map does not wrap around: what is beyond its edges is
 * nothing, neither a source nor a column, so the edges do not change any distance.
 *
 * <p>The maps are in scratch files ({@link MappedFile}), not in the heap: the pass along the rows writes the squared
 * distances to a file, the pass along the columns reads them, block of columns by block of columns, and writes the
 * result, which stays mapped until {@link #close}.
 */
final class ShoreDistance implements AutoCloseable {

    private static final double INFINITY = 1e20;
    /** Columns handled one after the other by a thread, so that the pages of the rows it walks through are reused. */
    private static final int COLUMN_BLOCK = 128;

    private final int width;
    private final int height;
    private final MappedFile quartersFile; // the distance in quarters of a block, at most Short.MAX_VALUE
    private final MemorySegment quarters;
    private final int minQuarters;

    /** Working arrays of one thread, sized for the longest line. */
    private record Buffers(double[] f, double[] d, int[] v, double[] z) {
        Buffers(int length) {
            this(new double[length], new double[length], new int[length], new double[length + 1]);
        }
    }

    /**
     * @param isSource whether a column of this height is a source, which the distance is measured from
     * @param dir the folder of the scratch files
     */
    ShoreDistance(HeightMapData data, IntPredicate isSource, int minDistance, File dir, Logger logger, String label)
            throws IOException {
        this.width = data.width();
        this.height = data.height();
        this.minQuarters = 4 * minDistance;
        long size = (long) width * height;
        Progress progress = new Progress(logger, label, (long) width + height);
        ThreadLocal<Buffers> buffers = ThreadLocal.withInitial(() -> new Buffers(Math.max(width, height)));
        this.quartersFile = MappedFile.scratch(dir, 2 * size);
        this.quarters = quartersFile.segment();

        // squared distance to the nearest source of the same row
        try (MappedFile horizontalFile = MappedFile.scratch(dir, 4 * size)) {
            MemorySegment horizontal = horizontalFile.segment();
            IntStream.range(0, height).parallel().forEach(row -> {
                Buffers b = buffers.get();
                for (int i = 0; i < width; i++) {
                    b.f[i] = isSource.test(data.valueAt(i, row)) ? 0 : INFINITY;
                }
                squaredDistances(b, width);
                for (int column = 0; column < width; column++) {
                    horizontal.set(MappedFile.FLOAT, 4 * ((long) row * width + column),
                            (float) Math.min(b.d[column], INFINITY));
                }
                progress.advance(1);
            });

            int blocks = (width + COLUMN_BLOCK - 1) / COLUMN_BLOCK;
            IntStream.range(0, blocks).parallel().forEach(block -> {
                Buffers b = buffers.get();
                int first = block * COLUMN_BLOCK;
                int end = Math.min(width, first + COLUMN_BLOCK);
                // The block is read in whole row pieces, worked on in the heap and written back in row pieces: the file is
                // never walked one column at a time (a page per row), which is what makes it slow when the map is bigger
                // than the memory.
                float[][] columns = new float[end - first][height];
                short[][] result = new short[end - first][height];
                for (int row = 0; row < height; row++) {
                    for (int k = 0; k < end - first; k++) {
                        columns[k][row] = horizontal.get(MappedFile.FLOAT, 4 * ((long) row * width + first + k));
                    }
                }
                for (int k = 0; k < end - first; k++) {
                    for (int i = 0; i < height; i++) {
                        b.f[i] = columns[k][i];
                    }
                    squaredDistances(b, height);
                    for (int row = 0; row < height; row++) {
                        result[k][row] = (short) Math.min(Short.MAX_VALUE, Math.round(Math.sqrt(b.d[row]) * 4));
                    }
                    progress.advance(1);
                }
                for (int row = 0; row < height; row++) {
                    for (int k = 0; k < end - first; k++) {
                        quarters.set(MappedFile.SHORT, 2 * ((long) row * width + first + k), result[k][row]);
                    }
                }
            });
        } catch (IOException | RuntimeException | Error e) {
            quartersFile.close();
            throw e;
        }
    }

    private int quarterAt(int column, int row) {
        return quarters.get(MappedFile.SHORT, 2 * ((long) row * width + column));
    }

    /** Whether the column at world (x, z) is far from the sources (only meaningful on the map). */
    boolean isFar(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        return quarterAt(column, row) > minQuarters;
    }

    /** The distance of the column of the map itself at (column, row), in quarters of a block. */
    int quarterBlocks(int column, int row) {
        return quarterAt(column, row);
    }

    /** Frees the scratch file of the distances. */
    @Override
    public void close() {
        quartersFile.close();
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
