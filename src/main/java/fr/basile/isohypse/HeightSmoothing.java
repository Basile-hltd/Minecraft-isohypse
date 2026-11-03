package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * A low-pass filter of one value per column (a height, a distance to the shore) over one kind of columns, used to tell
 * the zones of {@link TempLayout} apart on the large shapes rather than on every detail (the sea with a very wide
 * filter, the land with a narrower one).
 *
 * <p>Only the columns of a mask (the ocean, the land) are filtered, and only among themselves: the smoothed value
 * of a column is the mean value of the columns of the mask around it, so the other kind of column does not leak in
 * (a normalised convolution: the blurred values divided by the blurred count of columns of the mask). The blur is three box blurs, close to a gaussian. The map does not wrap around: beyond its edges there are no
 * columns, so they weigh nothing and the edges do not pull the values.
 *
 * <p>The filter is wide (tens or hundreds of blocks), so what it gives is smooth at the scale of a few blocks: it is
 * made on a reduced map, one cell per {@code factor} x {@code factor} blocks ({@code factor} about the eighth of the
 * width of the filter), and the value of a column is read from it by bilinear interpolation. The cost is that of
 * reading the map once, not that of filtering it at full size, and the filtered map is a few megabytes whatever the
 * size of the world. (A filter narrower than 8 blocks is made at full size, factor 1.)
 *
 * <p>The reduced maps are scratch files ({@link MappedFile}); the working one is deleted once the filter is done, the
 * result when {@link #close} is called.
 */
final class HeightSmoothing implements AutoCloseable {

    /** Reduced lines handled one after the other by a thread when they are the columns, so that the pages they share are reused. */
    private static final int COLUMN_BLOCK = 64;
    /** The filter is this many reduced cells wide (half of a box), once the map is reduced. */
    private static final int REDUCED_HALF = 8;
    /** Rows handled at once by a thread when the histogram is made. */
    private static final int ROW_BLOCK = 256;
    /** The most a value can be (a short, kept positive): the histogram has this many entries. */
    private static final int MAX_VALUE = Short.MAX_VALUE;

    private final int width;
    private final int height;
    private final int factor;
    private final int reducedWidth;
    private final int reducedHeight;
    /** The smoothed value of every reduced cell (NaN where no column of the mask is near). */
    private final MappedFile ratioFile;
    private final MemorySegment ratio;
    private final int[] histogram;

    /** The value to filter, by column and row of the map itself. */
    interface Values {
        int at(int column, int row);
    }

    /** Which columns are filtered, by column and row of the map itself. */
    interface Mask {
        boolean test(int column, int row);
    }

    /**
     * @param values the value of every column
     * @param mask which columns are filtered (the others are left out of the filter)
     * @param sigma about the standard deviation, in blocks, of the filter
     * @param dir the folder of the scratch files
     */
    HeightSmoothing(HeightMapData data, Values values, Mask mask, int sigma, File dir, Logger logger, String label)
            throws IOException {
        this.width = data.width();
        this.height = data.height();
        this.factor = Math.max(1, sigma / REDUCED_HALF);
        this.reducedWidth = (width + factor - 1) / factor;
        this.reducedHeight = (height + factor - 1) / factor;
        int half = Math.max(1, Math.min(Math.round((float) sigma / factor), (Math.min(reducedWidth, reducedHeight) - 1) / 2));
        int rw = reducedWidth;
        int rh = reducedHeight;
        int blocks = (height + ROW_BLOCK - 1) / ROW_BLOCK;
        long cells = (long) rw * rh;
        // reading the map, two blurs (the sum, the count) each along the rows then along the columns, then the histogram
        Progress progress = new Progress(logger, label, (long) rh + 2L * (rw + rh) + blocks);
        this.ratioFile = MappedFile.scratch(dir, 4 * cells);
        this.ratio = ratioFile.segment();
        try (MappedFile countFile = MappedFile.scratch(dir, 4 * cells)) {
            MemorySegment sum = ratio; // the sums, then the smoothed values in place
            MemorySegment count = countFile.segment();
            IntStream.range(0, rh).parallel().forEach(reducedRow -> {
                double[] s = new double[rw];
                double[] c = new double[rw];
                for (int row = reducedRow * factor; row < Math.min(height, (reducedRow + 1) * factor); row++) {
                    for (int column = 0; column < width; column++) {
                        if (mask.test(column, row)) {
                            s[column / factor] += values.at(column, row);
                            c[column / factor] += 1;
                        }
                    }
                }
                for (int i = 0; i < rw; i++) {
                    long cell = (long) reducedRow * rw + i;
                    sum.set(MappedFile.FLOAT, 4 * cell, (float) s[i]);
                    count.set(MappedFile.FLOAT, 4 * cell, (float) c[i]);
                }
                progress.advance(1);
            });
            blur(sum, half, progress);
            blur(count, half, progress);
            IntStream.range(0, rh).parallel().forEach(reducedRow -> {
                for (int i = 0; i < rw; i++) {
                    long cell = (long) reducedRow * rw + i;
                    float c = count.get(MappedFile.FLOAT, 4 * cell);
                    sum.set(MappedFile.FLOAT, 4 * cell, c > 1e-4f ? sum.get(MappedFile.FLOAT, 4 * cell) / c : Float.NaN);
                }
            });
        } catch (IOException | RuntimeException | Error e) {
            close();
            throw e;
        }
        // how many columns of the mask have each smoothed value, now that the smoothed values can be read
        int[] counts = IntStream.range(0, blocks).parallel().mapToObj(block -> {
            int[] local = new int[MAX_VALUE + 1];
            for (int row = block * ROW_BLOCK; row < Math.min(height, (block + 1) * ROW_BLOCK); row++) {
                for (int column = 0; column < width; column++) {
                    if (mask.test(column, row)) {
                        local[Math.clamp(valueAt(column, row), 0, MAX_VALUE)]++;
                    }
                }
            }
            progress.advance(1);
            return local;
        }).reduce(new int[MAX_VALUE + 1], (a, b) -> {
            int[] total = new int[MAX_VALUE + 1];
            for (int i = 0; i < total.length; i++) {
                total[i] = a[i] + b[i];
            }
            return total;
        });
        int highest = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                highest = i;
            }
        }
        this.histogram = Arrays.copyOf(counts, highest + 1);
    }

    /** The smoothed value of the column of the map itself at (column, row): bilinear between the reduced cells around it. */
    private int valueAt(int column, int row) {
        double u = (column + 0.5) / factor - 0.5;
        double v = (row + 0.5) / factor - 0.5;
        int u0 = (int) Math.floor(u);
        int v0 = (int) Math.floor(v);
        double fu = u - u0;
        double fv = v - v0;
        double total = 0;
        double weight = 0;
        for (int dv = 0; dv <= 1; dv++) {
            for (int du = 0; du <= 1; du++) {
                int cu = Math.clamp(u0 + du, 0, reducedWidth - 1);
                int cv = Math.clamp(v0 + dv, 0, reducedHeight - 1);
                float r = ratio.get(MappedFile.FLOAT, 4 * ((long) cv * reducedWidth + cu));
                if (!Float.isNaN(r)) {
                    double w = (du == 0 ? 1 - fu : fu) * (dv == 0 ? 1 - fv : fv);
                    total += w * r;
                    weight += w;
                }
            }
        }
        return weight > 1e-9 ? (int) Math.round(total / weight) : 0;
    }

    /** The smoothed value of the column at world (x, z); only meaningful for a column of the map and of the mask. */
    int at(int x, int z) {
        return valueAt(Math.clamp(x + width / 2, 0, width - 1), Math.clamp(z + height / 2, 0, height - 1));
    }

    /** How many columns of the mask have each smoothed value: the count of value {@code v} is at index v. */
    int[] histogram() {
        return histogram.clone();
    }

    /** Frees the scratch file of the result. */
    @Override
    public void close() {
        ratioFile.close();
    }

    /** Three box blurs along the rows, then along the columns, in place, of a reduced map. */
    private void blur(MemorySegment values, int half, Progress progress) {
        blurLines(values, reducedHeight, reducedWidth, reducedWidth, 1, 1, half, progress);
        blurLines(values, reducedWidth, reducedHeight, 1, reducedWidth, COLUMN_BLOCK, half, progress);
    }

    /**
     * Blurs every line of {@code values} (floats): the line {@code l} starts at {@code l * lineStride}, and its
     * {@code length} elements are {@code elementStride} apart. A thread takes {@code block} lines at a time.
     */
    private static void blurLines(MemorySegment values, int lines, int length, long lineStride, long elementStride,
                                  int block, int half, Progress progress) {
        ThreadLocal<double[]> buffer = ThreadLocal.withInitial(() -> new double[2 * (length + 2 * half) + 2]);
        IntStream.range(0, (lines + block - 1) / block).parallel().forEach(blockIndex -> {
            double[] work = buffer.get();
            int end = Math.min(lines, (blockIndex + 1) * block);
            for (int line = blockIndex * block; line < end; line++) {
                long start = line * lineStride;
                for (int pass = 0; pass < 3; pass++) {
                    // work[0 .. length + 2 * half) is the line with half zeros (nothing) on each side; its running sums follow, from index base.
                    int base = length + 2 * half;
                    work[base] = 0;
                    for (int i = 0; i < length + 2 * half; i++) {
                        int source = i - half;
                        work[i] = source < 0 || source >= length ? 0
                                : values.get(MappedFile.FLOAT, 4 * (start + source * elementStride));
                        work[base + i + 1] = work[base + i] + work[i];
                    }
                    double boxWidth = 2 * half + 1;
                    for (int i = 0; i < length; i++) {
                        values.set(MappedFile.FLOAT, 4 * (start + i * elementStride),
                                (float) ((work[base + i + 2 * half + 1] - work[base + i]) / boxWidth));
                    }
                }
                progress.advance(1);
            }
        });
    }
}
