package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * Which columns of the height map are a lake: a flat, that is columns of exactly the same height side by side. A flat is
 * found from the runs of at least {@code minSize} columns of the same height in a row; runs of the same height in two
 * consecutive rows that overlap are joined, and the joined runs are a lake when they cover at least {@code minSize}
 * rows and {@code minSize}^2 columns. (A lake is flat in the real altitudes; the columns of a gentle slope are also at the
 * same height by steps, so the size must be big enough to leave those out.) The columns at or under {@code excludeUpTo}
 * are already the sea of the map, never a lake. The runs are short lists and the mask is one bit per column in a scratch file
 * ({@link MappedFile}): nothing big is in the heap. The map is not wrapped around: a flat across its edge is cut in two.
 */
final class LakeMask implements AutoCloseable {

    /** Rows handled at once by a thread when looking for the runs. */
    private static final int ROW_BLOCK = 64;

    private final int width;
    private final int height;
    private MappedFile bitsFile;
    private MemorySegment bits;

    /** Runs of equal height in a row, sorted by row then by start. */
    private static final class Runs {
        int[] row = new int[64];
        int[] start = new int[64];
        int[] length = new int[64];
        int[] y = new int[64];
        int size;

        void add(int r, int s, int l, int v) {
            if (size == row.length) {
                int capacity = size * 2;
                row = Arrays.copyOf(row, capacity);
                start = Arrays.copyOf(start, capacity);
                length = Arrays.copyOf(length, capacity);
                y = Arrays.copyOf(y, capacity);
            }
            row[size] = r;
            start[size] = s;
            length[size] = l;
            y[size++] = v;
        }
    }

    /**
     * @param minSize the least width and height, in columns, of a flat; 0 for no lake at all
     * @param smoothing the radius of the filter that rounds the corners of the lakes, see {@link #smooth}; 0 for none
     * @param blur about the standard deviation of the blur of the lakes that follows, see {@link #blur}; 0 for none
     * @param excludeUpTo the columns at or under this height are never a lake (the sea level: what is not above it is already sea)
     */
    LakeMask(HeightMapData data, int minSize, int smoothing, int blur, int excludeUpTo, File dir, Logger logger,
             String label) throws IOException {
        this.width = data.width();
        this.height = data.height();
        this.bitsFile = MappedFile.scratch(dir, ((long) width * height + 7) / 8);
        this.bits = bitsFile.segment();
        if (minSize <= 0) {
            return;
        }
        try {
            find(data, minSize, smoothing, blur, excludeUpTo, dir, logger, label);
        } catch (IOException | RuntimeException | Error e) {
            bitsFile.close();
            throw e;
        }
    }

    private void find(HeightMapData data, int minSize, int smoothing, int blur, int excludeUpTo, File dir, Logger logger,
                      String label) throws IOException {
        Progress progress = new Progress(logger, label, height);
        int blocks = (height + ROW_BLOCK - 1) / ROW_BLOCK;
        Runs[] found = new Runs[blocks];
        IntStream.range(0, blocks).parallel().forEach(block -> {
            Runs runs = new Runs();
            int end = Math.min(height, (block + 1) * ROW_BLOCK);
            for (int row = block * ROW_BLOCK; row < end; row++) {
                int start = 0;
                int value = data.valueAt(0, row);
                for (int column = 1; column <= width; column++) {
                    int next = column < width ? data.valueAt(column, row) : Integer.MIN_VALUE;
                    if (next != value) {
                        if (column - start >= minSize && value > excludeUpTo) {
                            runs.add(row, start, column - start, value);
                        }
                        start = column;
                        value = next;
                    }
                }
                progress.advance(1);
            }
            found[block] = runs;
        });
        Runs runs = new Runs();
        for (Runs part : found) {
            for (int i = 0; i < part.size; i++) {
                runs.add(part.row[i], part.start[i], part.length[i], part.y[i]);
            }
        }

        // Join the runs of the same height that overlap in two consecutive rows.
        int[] parent = new int[runs.size];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        int rowStart = 0;
        while (rowStart < runs.size) {
            int rowEnd = rowStart;
            while (rowEnd < runs.size && runs.row[rowEnd] == runs.row[rowStart]) {
                rowEnd++;
            }
            int nextEnd = rowEnd;
            while (nextEnd < runs.size && runs.row[nextEnd] == runs.row[rowStart] + 1) {
                nextEnd++;
            }
            int a = rowStart;
            int b = rowEnd;
            while (a < rowEnd && b < nextEnd) {
                int aEnd = runs.start[a] + runs.length[a];
                int bEnd = runs.start[b] + runs.length[b];
                if (runs.start[a] < bEnd && runs.start[b] < aEnd && runs.y[a] == runs.y[b]) {
                    union(parent, a, b);
                }
                if (aEnd <= bEnd) {
                    a++;
                } else {
                    b++;
                }
            }
            rowStart = rowEnd;
        }

        long[] area = new long[runs.size];
        int[] firstRow = new int[runs.size];
        int[] lastRow = new int[runs.size];
        Arrays.fill(firstRow, Integer.MAX_VALUE);
        Arrays.fill(lastRow, Integer.MIN_VALUE);
        for (int i = 0; i < runs.size; i++) {
            int root = root(parent, i);
            area[root] += runs.length[i];
            firstRow[root] = Math.min(firstRow[root], runs.row[i]);
            lastRow[root] = Math.max(lastRow[root], runs.row[i]);
        }
        long minArea = (long) minSize * minSize;
        long columns = 0;
        int lakes = 0;
        boolean[] lakeRows = new boolean[height]; // the rows with a lake
        for (int i = 0; i < runs.size; i++) {
            int root = root(parent, i);
            if (area[root] < minArea || lastRow[root] - firstRow[root] + 1 < minSize) {
                continue;
            }
            long first = (long) runs.row[i] * width + runs.start[i];
            for (long index = first; index < first + runs.length[i]; index++) {
                bits.set(MappedFile.BYTE, index >> 3, (byte) (bits.get(MappedFile.BYTE, index >> 3) | 1 << (index & 7)));
            }
            columns += runs.length[i];
            lakeRows[runs.row[i]] = true;
            if (root == i) {
                lakes++; // the root of a lake is one of its own runs
            }
        }
        if (smoothing > 0 && columns > 0) {
            columns = smooth(data, smoothing, excludeUpTo, lakeRows, dir);
        }
        if (blur > 0 && columns > 0) {
            columns = blur(data, blur, excludeUpTo, dir, logger, label + " (flou)");
        }
        if (columns > 0) {
            long removed = separate(data, logger, label + " (séparation)");
            columns -= removed;
        }
        logger.info("lacs : " + lakes + " plat(s) d'au moins " + minSize + " x " + minSize + " blocs à la même hauteur ("
                + runs.size + " segments plats examinés), " + columns + " colonnes");
    }

    /**
     * Rounds the corners of the lakes: a column is a lake if more than half of the columns within {@code radius} blocks
     * (a square) are. A convex corner and a thin tip are cut, a hollow corner is filled; but only with columns at the
     * height of a lake column next to them, so that a lake keeps one water level. Returns the number of columns.
     * Done row by row with a sliding window, and only around the rows with a lake.
     */
    private long smooth(HeightMapData data, int radius, int excludeUpTo, boolean[] lakeRows, File dir) throws IOException {
        MappedFile smoothFile = MappedFile.scratch(dir, ((long) width * height + 7) / 8);
        MemorySegment out = smoothFile.segment();
        long columns = 0;
        try {
            int area = (2 * radius + 1) * (2 * radius + 1);
            int[] sum = new int[width]; // lake columns in the window of rows and columns around each column
            int[] prefix = new int[width + 1];
            for (int row = 0; row < radius && row < height; row++) {
                addRow(row, 1, radius, sum, prefix, lakeRows);
            }
            for (int row = 0; row < height; row++) {
                if (row + radius < height) {
                    addRow(row + radius, 1, radius, sum, prefix, lakeRows);
                }
                if (row - radius - 1 >= 0) {
                    addRow(row - radius - 1, -1, radius, sum, prefix, lakeRows);
                }
                boolean near = false;
                for (int r = Math.max(0, row - radius); r <= Math.min(height - 1, row + radius) && !near; r++) {
                    near = lakeRows[r];
                }
                if (!near) {
                    continue;
                }
                for (int column = 0; column < width; column++) {
                    if (sum[column] * 2 <= area) {
                        continue;
                    }
                    if (!bit(row, column) && !joinsALake(data, row, column, excludeUpTo)) {
                        continue;
                    }
                    long index = (long) row * width + column;
                    out.set(MappedFile.BYTE, index >> 3, (byte) (out.get(MappedFile.BYTE, index >> 3) | 1 << (index & 7)));
                    columns++;
                }
            }
        } catch (RuntimeException | Error e) {
            smoothFile.close();
            throw e;
        }
        bitsFile.close();
        bitsFile = smoothFile;
        bits = out;
        return columns;
    }

    /**
     * Blurs the lakes like the sea and the land ({@link HeightSmoothing}: three box blurs, close to a gaussian, about
     * {@code sigma} blocks), which is rounder and has no square grain, unlike the window of {@link #smooth}: the mask
     * (1 for a lake column, 0 elsewhere) is blurred, and a column is a lake when the result is over a half. Only
     * columns at the height of a lake column next to them are added, so that a lake keeps one water level; the
     * specks and the stripes the height map leaves in the outline are cut or filled. The lakes are few, so only the tiles
     * of the map with a lake in or near them are blurred (in the heap, a tile and the margin the blur reaches at a
     * time), not the whole map. Returns the number of columns.
     */
    private long blur(HeightMapData data, int sigma, int excludeUpTo, File dir, Logger logger, String label)
            throws IOException {
        long started = System.nanoTime();
        int half = Math.max(1, sigma);
        int reach = 3 * half; // three boxes: how far a column changes another
        int tilesAcross = (width + BLUR_TILE - 1) / BLUR_TILE;
        int tilesDown = (height + BLUR_TILE - 1) / BLUR_TILE;
        // the tiles that hold a lake column
        boolean[] hasLake = new boolean[tilesAcross * tilesDown];
        IntStream.range(0, tilesDown).parallel().forEach(tileRow -> {
            long first = (long) tileRow * BLUR_TILE * width;
            long last = Math.min((long) height, (long) (tileRow + 1) * BLUR_TILE) * width - 1;
            for (long byteIndex = first >> 3; byteIndex <= last >> 3; byteIndex++) {
                byte value = bits.get(MappedFile.BYTE, byteIndex);
                for (int bit = 0; value != 0 && bit < 8; bit++) {
                    long index = byteIndex * 8 + bit;
                    if ((value >> bit & 1) != 0 && index >= first && index <= last) {
                        hasLake[(int) (index / width / BLUR_TILE) * tilesAcross + (int) (index % width) / BLUR_TILE] = true;
                    }
                }
            }
        });
        int ring = (reach + BLUR_TILE - 1) / BLUR_TILE;
        List<long[]> found = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, tilesAcross * tilesDown).parallel().forEach(tile -> {
            int tileColumn = tile % tilesAcross;
            int tileRow = tile / tilesAcross;
            boolean near = false;
            for (int r = Math.max(0, tileRow - ring); r <= Math.min(tilesDown - 1, tileRow + ring) && !near; r++) {
                for (int c = Math.max(0, tileColumn - ring); c <= Math.min(tilesAcross - 1, tileColumn + ring) && !near; c++) {
                    near = hasLake[r * tilesAcross + c];
                }
            }
            if (near) {
                found.add(blurTile(data, tileColumn, tileRow, half, reach, excludeUpTo));
            }
        });
        MappedFile blurredFile = MappedFile.scratch(dir, ((long) width * height + 7) / 8);
        MemorySegment out = blurredFile.segment();
        long columns = 0;
        for (long[] indexes : found) {
            for (long index : indexes) {
                out.set(MappedFile.BYTE, index >> 3, (byte) (out.get(MappedFile.BYTE, index >> 3) | 1 << (index & 7)));
            }
            columns += indexes.length;
        }
        bitsFile.close();
        bitsFile = blurredFile;
        bits = out;
        logger.info(label + " : " + found.size() + " tuile(s) de " + BLUR_TILE + " x " + BLUR_TILE + " blocs floutée(s) sur "
                + tilesAcross * tilesDown + ", " + columns + " colonnes (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
        return columns;
    }

    /**
     * Keeps two lakes of different heights apart. A lake has one water level, but the smoothing and the blur round the
     * outlines, and two lakes close to each other can touch or merge into one blob of two heights, whose water would
     * step from one level to the other. Every lake column that has a lake column of another height among its 8
     * neighbours is taken out of the lake (on both sides, so at least two columns of land are left between them): the
     * lakes stay apart, each at its own level, and the rivers ({@link RiverMask}) join them, from the outlet of the higher
     * one down. Returns the number of columns taken out.
     */
    private long separate(HeightMapData data, Logger logger, String label) {
        int tilesDown = (height + BLUR_TILE - 1) / BLUR_TILE;
        List<long[]> clashes = Collections.synchronizedList(new ArrayList<>());
        IntStream.range(0, tilesDown).parallel().forEach(tileRow -> {
            long first = (long) tileRow * BLUR_TILE * width;
            long last = Math.min((long) height, (long) (tileRow + 1) * BLUR_TILE) * width - 1;
            long[] found = new long[16];
            int size = 0;
            for (long byteIndex = first >> 3; byteIndex <= last >> 3; byteIndex++) {
                byte value = bits.get(MappedFile.BYTE, byteIndex);
                for (int bit = 0; value != 0 && bit < 8; bit++) {
                    long index = byteIndex * 8 + bit;
                    if ((value >> bit & 1) == 0 || index < first || index > last) {
                        continue;
                    }
                    int row = (int) (index / width);
                    int column = (int) (index % width);
                    if (touchesAnotherLevel(data, row, column)) {
                        if (size == found.length) {
                            found = Arrays.copyOf(found, size * 2);
                        }
                        found[size++] = index;
                    }
                }
            }
            clashes.add(Arrays.copyOf(found, size));
        });
        long removed = 0;
        for (long[] indexes : clashes) {
            for (long index : indexes) {
                bits.set(MappedFile.BYTE, index >> 3, (byte) (bits.get(MappedFile.BYTE, index >> 3) & ~(1 << (index & 7))));
            }
            removed += indexes.length;
        }
        logger.info(label + " : " + removed + " colonne(s) retirée(s) là où deux lacs de hauteurs différentes se touchent");
        return removed;
    }

    /** Whether a lake column has, among its 8 neighbours, a lake column of another height. */
    private boolean touchesAnotherLevel(HeightMapData data, int row, int column) {
        int value = data.valueAt(column, row);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int r = row + dz;
                int c = column + dx;
                if ((dx != 0 || dz != 0) && r >= 0 && r < height && c >= 0 && c < width && bit(r, c)
                        && data.valueAt(c, r) != value) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The side, in blocks, of the tiles that {@link #blur} works on. */
    private static final int BLUR_TILE = 256;

    /**
     * The columns of a tile that are a lake once blurred (the tile with the margin the blur reaches, clipped to the map,
     * is blurred in the heap), as their index in the map.
     */
    private long[] blurTile(HeightMapData data, int tileColumn, int tileRow, int half, int reach, int excludeUpTo) {
        int column0 = tileColumn * BLUR_TILE;
        int row0 = tileRow * BLUR_TILE;
        int column1 = Math.min(width, column0 + BLUR_TILE);
        int row1 = Math.min(height, row0 + BLUR_TILE);
        int from = Math.max(0, column0 - reach);
        int to = Math.min(width, column1 + reach);
        int top = Math.max(0, row0 - reach);
        int bottom = Math.min(height, row1 + reach);
        int w = to - from;
        int h = bottom - top;
        float[] lake = new float[w * h];
        float[] inside = new float[w * h]; // 1 on the map: the weight, so that the edge of the map does not pull the blur down
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                lake[r * w + c] = bit(top + r, from + c) ? 1f : 0f;
                inside[r * w + c] = 1f;
            }
        }
        blurAll(lake, w, h, half);
        blurAll(inside, w, h, half);
        long[] kept = new long[16];
        int size = 0;
        for (int row = row0; row < row1; row++) {
            for (int column = column0; column < column1; column++) {
                int i = (row - top) * w + column - from;
                if (lake[i] / inside[i] <= 0.5f) {
                    continue;
                }
                if (!bit(row, column) && !joinsALake(data, row, column, excludeUpTo)) {
                    continue;
                }
                if (size == kept.length) {
                    kept = Arrays.copyOf(kept, size * 2);
                }
                kept[size++] = (long) row * width + column;
            }
        }
        return Arrays.copyOf(kept, size);
    }

    /** Three box blurs of half-width {@code half} along the rows, then along the columns, in place; nothing beyond the edges. */
    private static void blurAll(float[] values, int w, int h, int half) {
        double[] line = new double[Math.max(w, h)];
        double[] prefix = new double[Math.max(w, h) + 1];
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                line[c] = values[r * w + c];
            }
            boxes(line, w, half, prefix);
            for (int c = 0; c < w; c++) {
                values[r * w + c] = (float) line[c];
            }
        }
        for (int c = 0; c < w; c++) {
            for (int r = 0; r < h; r++) {
                line[r] = values[r * w + c];
            }
            boxes(line, h, half, prefix);
            for (int r = 0; r < h; r++) {
                values[r * w + c] = (float) line[r];
            }
        }
    }

    private static void boxes(double[] line, int n, int half, double[] prefix) {
        double box = 2 * half + 1;
        for (int pass = 0; pass < 3; pass++) {
            prefix[0] = 0;
            for (int i = 0; i < n; i++) {
                prefix[i + 1] = prefix[i] + line[i];
            }
            for (int i = 0; i < n; i++) {
                line[i] = (prefix[Math.min(n, i + half + 1)] - prefix[Math.max(0, i - half)]) / box;
            }
        }
    }

    /** Adds ({@code sign} 1) or removes (-1) the lake columns of a row, counted within {@code radius} of each column. */
    private void addRow(int row, int sign, int radius, int[] sum, int[] prefix, boolean[] lakeRows) {
        if (!lakeRows[row]) {
            return;
        }
        for (int column = 0; column < width; column++) {
            prefix[column + 1] = prefix[column] + (bit(row, column) ? 1 : 0);
        }
        if (prefix[width] == 0) {
            return;
        }
        for (int column = 0; column < width; column++) {
            sum[column] += sign * (prefix[Math.min(width, column + radius + 1)] - prefix[Math.max(0, column - radius)]);
        }
    }

    private boolean bit(int row, int column) {
        long index = (long) row * width + column;
        return (bits.get(MappedFile.BYTE, index >> 3) >> (index & 7) & 1) != 0;
    }

    /** Whether a column that is not a lake has a lake column next to it (8 neighbours) at the same height. */
    private boolean joinsALake(HeightMapData data, int row, int column, int excludeUpTo) {
        int value = data.valueAt(column, row);
        if (value <= excludeUpTo) {
            return false;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int r = row + dz;
                int c = column + dx;
                if ((dx != 0 || dz != 0) && r >= 0 && r < height && c >= 0 && c < width && bit(r, c)
                        && data.valueAt(c, r) == value) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = root(parent, a);
        int rb = root(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    /** Whether the column at world (x, z) is a lake; the map repeats outside its bounds. */
    boolean contains(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        long index = (long) row * width + column;
        return (bits.get(MappedFile.BYTE, index >> 3) >> (index & 7) & 1) != 0;
    }

    /** Whether the pixel (column, row) of the map itself is a lake. */
    boolean at(int column, int row) {
        return bit(row, column);
    }

    /** The mask from the pixel {@code 8 * index}, a bit each (0 for 8 pixels without lake): to skip a map without lakes. */
    byte byteAt(long index) {
        return bits.get(MappedFile.BYTE, index);
    }

    /** Frees the scratch file of the mask. */
    @Override
    public void close() {
        bitsFile.close();
    }
}
