package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * A low-pass filter of one value per column (a height, a distance to the shore) over one kind of columns, used to tell
 * the zones of {@link TempLayout} apart on the large shapes rather than on every detail (the sea with a very wide
 * filter, the land with a narrower one).
 *
 * <p>Only the columns of a mask (the ocean, the land) are filtered, and only among themselves: the smoothed value
 * of a column is the mean value of the columns of the mask around it, so the other kind of column does not leak in
 * (a normalised convolution: the blurred values divided by the blurred count of columns of the mask). The blur is three box blurs, close to a gaussian, wrapping around the map as the map
 * repeats endlessly.
 *
 * <p>Nothing is in the heap: the working maps (the sums and the counts) and the result are scratch files
 * ({@link MappedFile}); the working maps are deleted once the filter is done, the result when {@link #close} is called.
 */
final class HeightSmoothing implements AutoCloseable {

    /** Lines handled one after the other by a thread when they are the columns, so that the pages they share are reused. */
    private static final int COLUMN_BLOCK = 256;

    private final int width;
    private final int height;
    /** The smoothed value of every column of the mask, the raw one elsewhere. */
    private final MappedFile smoothedFile;
    private final MappedFile inRangeFile;
    private final MemorySegment smoothed;
    private final MemorySegment inRange;

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
     * @param mask which columns are filtered (the others are left out of the filter and keep their value)
     * @param sigma about the standard deviation, in blocks, of the filter
     * @param dir the folder of the scratch files
     */
    HeightSmoothing(HeightMapData data, Values values, Mask mask, int sigma, File dir, Logger logger, String label)
            throws IOException {
        this.width = data.width();
        this.height = data.height();
        // Two blurs (the sum, the count), each along the rows then along the columns.
        Progress progress = new Progress(logger, label, 2L * (width + height));
        int half = Math.max(1, Math.min(sigma, (Math.min(width, height) - 1) / 2)); // each box is 2 * half + 1 wide
        long size = (long) width * height;
        this.smoothedFile = MappedFile.scratch(dir, 2 * size);
        try {
            this.inRangeFile = MappedFile.scratch(dir, size);
        } catch (IOException | RuntimeException | Error e) {
            smoothedFile.close();
            throw e;
        }
        this.smoothed = smoothedFile.segment();
        this.inRange = inRangeFile.segment();
        try (MappedFile sumFile = MappedFile.scratch(dir, 4 * size); MappedFile countFile = MappedFile.scratch(dir, 4 * size)) {
            MemorySegment sum = sumFile.segment();
            MemorySegment count = countFile.segment();
            IntStream.range(0, height).parallel().forEach(row -> {
                for (int column = 0; column < width; column++) {
                    long i = (long) row * width + column;
                    int value = values.at(column, row);
                    smoothed.set(MappedFile.SHORT, 2 * i, (short) value);
                    if (mask.test(column, row)) {
                        inRange.set(MappedFile.BYTE, i, (byte) 1);
                        sum.set(MappedFile.FLOAT, 4 * i, value);
                        count.set(MappedFile.FLOAT, 4 * i, 1f);
                    }
                }
            });
            blur(sum, half, progress);
            blur(count, half, progress);
            IntStream.range(0, height).parallel().forEach(row -> {
                for (int column = 0; column < width; column++) {
                    long i = (long) row * width + column;
                    float c = count.get(MappedFile.FLOAT, 4 * i);
                    if (inRange.get(MappedFile.BYTE, i) != 0 && c > 1e-4f) {
                        smoothed.set(MappedFile.SHORT, 2 * i, (short) Math.round(sum.get(MappedFile.FLOAT, 4 * i) / c));
                    }
                }
            });
        } catch (IOException | RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    /** The smoothed value of the column at world (x, z); the map repeats outside its bounds. */
    int at(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        return smoothed.get(MappedFile.SHORT, 2 * ((long) row * width + column));
    }

    /** How many columns of the mask have each smoothed value: the count of value {@code v} is at index v. */
    int[] histogram() {
        long size = (long) width * height;
        int highest = 0;
        for (long i = 0; i < size; i++) {
            if (inRange.get(MappedFile.BYTE, i) != 0) {
                highest = Math.max(highest, smoothed.get(MappedFile.SHORT, 2 * i));
            }
        }
        int[] counts = new int[highest + 1];
        for (long i = 0; i < size; i++) {
            if (inRange.get(MappedFile.BYTE, i) != 0) {
                counts[smoothed.get(MappedFile.SHORT, 2 * i)]++;
            }
        }
        return counts;
    }

    /** Frees the scratch files of the result. */
    @Override
    public void close() {
        smoothedFile.close();
        inRangeFile.close();
    }

    /** Three box blurs along the rows, then along the columns, in place. */
    private void blur(MemorySegment values, int half, Progress progress) {
        blurLines(values, height, width, width, 1, 1, half, progress);
        blurLines(values, width, height, 1, width, COLUMN_BLOCK, half, progress);
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
                    // work[0 .. length + 2 * half) is the line wrapped around; its running sums follow, from index base.
                    int base = length + 2 * half;
                    work[base] = 0;
                    for (int i = 0; i < length + 2 * half; i++) {
                        int source = Math.floorMod(i - half, length);
                        work[i] = values.get(MappedFile.FLOAT, 4 * (start + source * elementStride));
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
