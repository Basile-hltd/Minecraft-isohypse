package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
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
     * @param excludeUpTo the columns at or under this height are never a lake (the sea level: what is not above it is already sea)
     */
    LakeMask(HeightMapData data, int minSize, int smoothing, int excludeUpTo, File dir, Logger logger, String label)
            throws IOException {
        this.width = data.width();
        this.height = data.height();
        this.bitsFile = MappedFile.scratch(dir, ((long) width * height + 7) / 8);
        this.bits = bitsFile.segment();
        if (minSize <= 0) {
            return;
        }
        try {
            find(data, minSize, smoothing, excludeUpTo, dir, logger, label);
        } catch (IOException | RuntimeException | Error e) {
            bitsFile.close();
            throw e;
        }
    }

    private void find(HeightMapData data, int minSize, int smoothing, int excludeUpTo, File dir, Logger logger,
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
