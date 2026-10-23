package fr.basile.isohypse;

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
 */
final class HeightSmoothing {

    private final int width;
    private final int height;
    /** The smoothed value of every column of the mask, the raw one elsewhere. */
    private final short[] smoothed;
    private final boolean[] inRange;

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
     */
    HeightSmoothing(HeightMapData data, Values values, Mask mask, int sigma) {
        this.width = data.width();
        this.height = data.height();
        int half = Math.max(1, Math.min(sigma, (Math.min(width, height) - 1) / 2)); // each box is 2 * half + 1 wide
        int size = width * height;
        this.inRange = new boolean[size];
        this.smoothed = new short[size];
        float[] sum = new float[size];
        float[] count = new float[size];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int i = row * width + column;
                int value = values.at(column, row);
                smoothed[i] = (short) value;
                if (mask.test(column, row)) {
                    inRange[i] = true;
                    sum[i] = value;
                    count[i] = 1;
                }
            }
        }
        blur(sum, half);
        blur(count, half);
        for (int i = 0; i < size; i++) {
            if (inRange[i] && count[i] > 1e-4f) {
                smoothed[i] = (short) Math.round(sum[i] / count[i]);
            }
        }
    }

    /** The smoothed value of the column at world (x, z); the map repeats outside its bounds. */
    int at(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        return smoothed[row * width + column];
    }

    /** How many columns of the mask have each smoothed value: the count of value {@code v} is at index v. */
    int[] histogram() {
        int highest = 0;
        for (int i = 0; i < smoothed.length; i++) {
            if (inRange[i]) {
                highest = Math.max(highest, smoothed[i]);
            }
        }
        int[] counts = new int[highest + 1];
        for (int i = 0; i < smoothed.length; i++) {
            if (inRange[i]) {
                counts[smoothed[i]]++;
            }
        }
        return counts;
    }

    /** Three box blurs along the rows, then along the columns, in place. */
    private void blur(float[] values, int half) {
        blurLines(values, height, width, width, 1, half);
        blurLines(values, width, height, 1, width, half);
    }

    /**
     * Blurs every line of {@code values}: the line {@code l} starts at {@code l * lineStride}, and its {@code length}
     * elements are {@code elementStride} apart.
     */
    private static void blurLines(float[] values, int lines, int length, int lineStride, int elementStride, int half) {
        ThreadLocal<double[]> buffer = ThreadLocal.withInitial(() -> new double[2 * (length + 2 * half) + 2]);
        IntStream.range(0, lines).parallel().forEach(line -> {
            double[] work = buffer.get();
            int start = line * lineStride;
            for (int pass = 0; pass < 3; pass++) {
                // work[0 .. length + 2 * half) is the line wrapped around; its running sums follow, from index base.
                int base = length + 2 * half;
                work[base] = 0;
                for (int i = 0; i < length + 2 * half; i++) {
                    int source = Math.floorMod(i - half, length);
                    work[i] = values[start + source * elementStride];
                    work[base + i + 1] = work[base + i] + work[i];
                }
                double width = 2 * half + 1;
                for (int i = 0; i < length; i++) {
                    values[start + i * elementStride] = (float) ((work[base + i + 2 * half + 1] - work[base + i]) / width);
                }
            }
        });
    }
}
