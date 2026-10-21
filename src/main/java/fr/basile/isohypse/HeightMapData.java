package fr.basile.isohypse;

import javax.imageio.ImageIO;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.stream.IntStream;

/**
 * A height map read from heightmap.png (see {@link HeightMapGenerator}): 1 pixel = 1 block, value = Y level of the
 * top block, the middle of the map being the world origin (x=0, z=0), north (row 0) towards negative z.
 *
 * <p>Outside the map, a square map is surrounded by eight rotated copies of itself (each a random
 * symmetry of the square, see {@link #symmetries}) and this 3x3 block repeats endlessly; a non-square map is simply repeated, as rotating it would not fit the grid.
 *
 * <p>Once the block is assembled, the seams between two copies are smoothed with a low-pass filter. Seams are the
 * lines every {@code n} blocks (n = side of the map), in x then in z. Along a seam the height is split in two:
 * <ul>
 * <li>its <b>low frequencies</b> (three box blurs, close to a gaussian of sigma {@code blend / 3}). Here the blur
 * across the seam replaces the blur of each copy taken alone (mirrored at the seam), with a share that goes from 1 on
 * the seam to 0 at {@code blend} blocks, along a smoothstep: the big shapes of the two copies fade into each other;</li>
 * <li>its <b>details</b> (the height minus its own low frequencies: valleys, ridges, shores), kept at full strength
 * except within {@code blend / 4} blocks of the seam, where they fade out so that both sides meet exactly. A plain
 * blur would also wash the details out over the whole band and leave visible featureless stripes.</li>
 * </ul>
 * Further than {@code blend} blocks from a seam nothing changes. The smoothed bands are computed once, in the
 * constructor; the object is read-only afterwards.
 */
final class HeightMapData {

    /**
     * Symmetry of the copy at [row][column] of the 3x3 block (north-west first, the original in the middle, always
     * 0): 0 to 3 = that many quarter turns clockwise, 4 to 7 = the same after a left-right mirror. Drawn from the world
     * seed, so that no copy has the same symmetry as one of the four copies it touches (the block repeats, so the
     * edges wrap around).
     */
    private final int[][] symmetries = new int[3][3];

    private final int width;
    private final int height;
    private final int[] values;
    private final int highest;
    private final int lowest;

    /** Half width of a smoothed band (blocks), 0 when nothing is smoothed. */
    private final int blend;
    /** Whether the copies around the map are rotated and smoothed (config {@code realistic.tiling}); always false if not square. */
    private final boolean tiled;
    /** Side of the 3x3 block: the pattern, rotations included, repeats every {@code period} blocks. */
    private final int period;
    /** Smoothing weight (share of the blurred height) by offset from the seam, index offset + blend. */
    private final double[] weights;
    /** Share of the details kept by offset from the seam (0 on the seam, 1 from {@code blend / 4} away), same index. */
    private final double[] details;
    /** Heights after the pass across the vertical seams, by [band slot * period + row] (see {@link #bandSlot}). */
    private char[] vertical;
    /** Final heights in the bands along the horizontal seams, by [band slot * period + column]. */
    private char[] horizontal;

    private HeightMapData(int width, int height, int[] values, boolean tiling, int blendWidth, long seed) {
        this.width = width;
        this.height = height;
        this.values = values;
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        for (int v : values) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        this.highest = max;
        this.lowest = min;
        drawSymmetries(seed);
        this.period = 3 * width;
        this.tiled = tiling && width == height;
        this.blend = tiled ? Math.min(blendWidth, width / 2) : 0;
        this.weights = new double[2 * blend];
        this.details = new double[2 * blend];
        double detailFade = Math.max(1, blend / 4.0);
        for (int i = 0; i < weights.length; i++) {
            int offset = i - blend;
            double distance = offset >= 0 ? offset + 0.5 : -offset - 0.5;
            double t = distance / blend;
            weights[i] = 1 - t * t * (3 - 2 * t);
            double d = Math.min(1, distance / detailFade);
            details[i] = d * d * (3 - 2 * d);
        }
        if (blend > 0) {
            smoothSeams();
        }
    }

    private void drawSymmetries(long seed) {
        java.util.Random random = new java.util.Random(seed ^ 0x5DEECE66DL);
        boolean[][] done = new boolean[3][3];
        done[1][1] = true; // the original keeps its orientation
        int[][] order = {{0, 1}, {1, 2}, {2, 1}, {1, 0}, {0, 0}, {0, 2}, {2, 2}, {2, 0}}; // cardinals, then corners
        for (int[] cell : order) {
            boolean[] taken = new boolean[8];
            int[][] neighbours = {{cell[0] + 2, cell[1]}, {cell[0] + 1, cell[1]}, {cell[0], cell[1] + 2}, {cell[0], cell[1] + 1}};
            for (int[] other : neighbours) {
                int row = other[0] % 3;
                int column = other[1] % 3;
                if (done[row][column]) {
                    taken[symmetries[row][column]] = true;
                }
            }
            int pick;
            do {
                pick = random.nextInt(8);
            } while (taken[pick]);
            symmetries[cell[0]][cell[1]] = pick;
            done[cell[0]][cell[1]] = true;
        }
    }

    /** How many columns of the map (before smoothing) have each height: the count of height {@code y} is at index y. */
    int[] histogram() {
        int[] counts = new int[highest + 1];
        for (int v : values) {
            counts[v]++;
        }
        return counts;
    }

    /** The height of the pixel (column, row) of the map itself, before smoothing. */
    int valueAt(int column, int row) {
        return values[row * width + column];
    }

    /** Y of the lowest column (before smoothing). */
    int lowest() {
        return lowest;
    }

    /** Y of the highest column (before smoothing). */
    int highest() {
        return highest;
    }

    /**
     * @param tiling true = a square map is surrounded by eight rotated or mirrored copies and their seams are smoothed
     *               (everything described above); false = the map is simply repeated endlessly, as it is, and the
     *               previews show the map alone
     * @param blendWidth half width in blocks of the smoothed band along each seam, 0 for none
     * @param seed world seed, which draws the symmetry of the eight copies
     */
    static HeightMapData read(File file, boolean tiling, int blendWidth, long seed) throws IOException {
        var image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Height map illisible : " + file);
        }
        return from(image.getRaster(), tiling, blendWidth, seed);
    }

    /** A height map from a one-band raster (the value of a pixel is the Y level). */
    static HeightMapData from(Raster raster, boolean tiling, int blendWidth, long seed) {
        int width = raster.getWidth();
        int height = raster.getHeight();
        int[] values = new int[width * height];
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                values[j * width + i] = raster.getSample(i, j, 0);
            }
        }
        return new HeightMapData(width, height, values, tiling, blendWidth, seed);
    }

    /** Y level of the top block at world column (x, z), in the rotated copies outside the map, seams smoothed. */
    int heightAt(int x, int z) {
        int shiftedX = x + width / 2;
        int shiftedZ = z + height / 2;
        if (blend == 0) {
            return rawAt(shiftedX, shiftedZ);
        }
        int slot = bandSlot(shiftedZ);
        if (slot >= 0) {
            return horizontal[slot * period + Math.floorMod(shiftedX, period)];
        }
        return acrossVertical(shiftedX, shiftedZ);
    }

    /** Height before any smoothing, at the shifted coordinates (world x + width / 2, z + height / 2). */
    private int rawAt(int shiftedX, int shiftedZ) {
        int u = Math.floorMod(shiftedX, width);
        int v = Math.floorMod(shiftedZ, height);
        if (!tiled) {
            return values[v * width + u];
        }
        int n = width;
        int symmetry = symmetries[Math.floorMod(Math.floorDiv(shiftedZ, n) + 1, 3)][Math.floorMod(Math.floorDiv(shiftedX, n) + 1, 3)];
        if (symmetry >= 4) {
            u = n - 1 - u; // left-right mirror, then the quarter turns
        }
        // Source pixel (column, row) of the copy turned clockwise by that many quarter turns.
        return switch (symmetry & 3) {
            case 1 -> values[(n - 1 - u) * n + v];
            case 2 -> values[(n - 1 - v) * n + (n - 1 - u)];
            case 3 -> values[u * n + (n - 1 - v)];
            default -> values[v * n + u];
        };
    }

    /**
     * Which smoothed band a shifted coordinate (along one axis) falls in: the seam (0 to 2 in a period) times
     * {@code 2 * blend}, plus the offset from the seam shifted by {@code blend}; -1 if it is further than
     * {@code blend} blocks from every seam.
     */
    private int bandSlot(int coordinate) {
        int n = width;
        int u = Math.floorMod(coordinate, period);
        int seam = u / n;
        int offset = u % n;
        if (offset >= n - offset) { // closer to the next seam
            seam = (seam + 1) % 3;
            offset -= n;
        }
        if (offset < -blend || offset >= blend) {
            return -1;
        }
        return seam * 2 * blend + offset + blend;
    }

    /** First pass: heights smoothed across the vertical seams (a raw height away from them). */
    private int acrossVertical(int shiftedX, int shiftedZ) {
        int slot = bandSlot(shiftedX);
        return slot < 0 ? rawAt(shiftedX, shiftedZ) : vertical[slot * period + Math.floorMod(shiftedZ, period)];
    }

    private void smoothSeams() {
        int n = width;
        int slots = 3 * 2 * blend;
        int length = lineLength();

        // Pass 1, across the vertical seams: for every row, a line of raw heights through each seam.
        vertical = new char[slots * period];
        IntStream.range(0, period).parallel().forEach(row -> {
            double[] line = new double[length];
            for (int seam = 0; seam < 3; seam++) {
                int first = seam * n - blend - lineMargin();
                for (int j = 0; j < length; j++) {
                    line[j] = rawAt(first + j, row);
                }
                double[] smoothed = smoothLine(line);
                for (int i = 0; i < 2 * blend; i++) {
                    vertical[(seam * 2 * blend + i) * period + row] = (char) Math.round(smoothed[i]);
                }
            }
        });

        // Pass 2, across the horizontal seams, on the result of pass 1 (so the corners get both).
        horizontal = new char[slots * period];
        IntStream.range(0, period).parallel().forEach(column -> {
            double[] line = new double[length];
            for (int seam = 0; seam < 3; seam++) {
                int first = seam * n - blend - lineMargin();
                for (int j = 0; j < length; j++) {
                    line[j] = acrossVertical(column, first + j);
                }
                double[] smoothed = smoothLine(line);
                for (int i = 0; i < 2 * blend; i++) {
                    horizontal[(seam * 2 * blend + i) * period + column] = (char) Math.round(smoothed[i]);
                }
            }
        });
    }

    /** Half width of each of the three box blurs. */
    private int boxHalf() {
        return Math.max(1, blend / 3);
    }

    /** How far the three box blurs together look around a position. */
    private int lineMargin() {
        return 3 * boxHalf();
    }

    /** Length of a line through a seam: the band plus the margin of the blur on both sides. */
    private int lineLength() {
        return 2 * blend + 2 * lineMargin();
    }

    /**
     * Smooths one line of heights crossing a seam: {@code line[j]} is the height at offset {@code j - blend - margin}
     * from the seam (the seam being between the offsets -1 and 0). Returns the new heights for the offsets
     * {@code -blend} to {@code blend - 1}, at the indexes 0 to {@code 2 * blend - 1}.
     */
    private double[] smoothLine(double[] line) {
        int half = boxHalf();
        int margin = lineMargin();
        int length = line.length;
        int middle = length / 2; // index of the offset 0
        // Each side alone: the other side is replaced by the mirror image of this one.
        double[] left = new double[length];
        double[] right = new double[length];
        for (int j = 0; j < length; j++) {
            int mirror = length - 1 - j;
            left[j] = j < middle ? line[j] : line[mirror];
            right[j] = j >= middle ? line[j] : line[mirror];
        }
        double[] across = boxBlur3(line, half);
        double[] leftOnly = boxBlur3(left, half);
        double[] rightOnly = boxBlur3(right, half);
        double[] result = new double[2 * blend];
        for (int i = 0; i < 2 * blend; i++) {
            int j = margin + i;
            double own = j < middle ? leftOnly[j] : rightOnly[j]; // low frequencies of this copy alone
            double detail = line[j] - own;
            double macro = own + (across[j] - own) * weights[i];
            result[i] = macro + detail * details[i];
        }
        return result;
    }

    /**
     * Three successive box blurs of width {@code 2 * half + 1} (close to a gaussian). Only the indexes from
     * {@code 3 * half} to {@code line.length - 3 * half - 1} are valid in the result.
     */
    private static double[] boxBlur3(double[] line, int half) {
        double[] current = line;
        for (int pass = 0; pass < 3; pass++) {
            double[] prefix = new double[current.length + 1];
            for (int i = 0; i < current.length; i++) {
                prefix[i + 1] = prefix[i] + current[i];
            }
            double[] next = new double[current.length];
            double size = 2 * half + 1;
            for (int i = half; i < current.length - half; i++) {
                next[i] = (prefix[i + half + 1] - prefix[i - half]) / size;
            }
            current = next;
        }
        return current;
    }

    /** Number of maps along each axis of the block the previews show: 3 when tiled (the map and its copies), else 1. */
    int tilesPerSide() {
        return tiled ? 3 : 1;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }
}
