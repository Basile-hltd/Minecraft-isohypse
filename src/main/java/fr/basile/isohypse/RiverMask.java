package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Which columns of the height map are a river. A river starts at the outlet of a lake (the lowest column around it) and
 * follows the relief down, as water does: at each step it goes to the lowest column next to it when there is one lower
 * than its level; else it spills over the lowest way out, climbing at most {@code climb} blocks over its level (the
 * digger of the river cuts such a pass); when there is no way out, the river stops. It ends in a lower lake, in the sea
 * (the columns at or under {@code excludeUpTo}), or in another river (a confluence). It also ends in a lake it merely
 * passes close to: when a lake is within {@code snap} blocks of its path, the river is carried straight to it. The path is then smoothed and its
 * columns are put in the mask with a width that grows at each confluence: {@code riverWidth} blocks for one source,
 * times the square root of the number of sources upstream. The paths are short lists of columns and the mask is one bit
 * per column in a scratch file ({@link MappedFile}): nothing big is in the heap. The map is not wrapped around.
 */
final class RiverMask implements AutoCloseable {

    /** The most columns a search for a way out visits before the river stops. */
    private static final int MAX_EXPAND = 200_000;
    /** The most columns of a river. */
    private static final int MAX_LENGTH = 1_000_000;
    /** Half the number of columns of the moving average that smooths a path (on each side). */
    private static final int SMOOTH = 6;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};

    private final int width;
    private final int height;
    private final MappedFile bitsFile;
    private final MemorySegment bits;

    /** The columns (row * width + column) of all the rivers, one after the other. */
    private static final class Longs {
        long[] values = new long[256];
        int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }
    }

    /** A map from a column to its position among the columns of all the rivers (open addressing). */
    private static final class PositionMap {
        long[] keys = new long[1024];
        int[] positions = new int[1024];
        int size;

        PositionMap() {
            Arrays.fill(keys, -1);
        }

        int get(long key) {
            int slot = slot(key, keys.length);
            while (keys[slot] != -1) {
                if (keys[slot] == key) {
                    return positions[slot];
                }
                slot = (slot + 1) & (keys.length - 1);
            }
            return -1;
        }

        void putIfAbsent(long key, int position) {
            if (size * 2 >= keys.length) {
                grow();
            }
            int slot = slot(key, keys.length);
            while (keys[slot] != -1) {
                if (keys[slot] == key) {
                    return;
                }
                slot = (slot + 1) & (keys.length - 1);
            }
            keys[slot] = key;
            positions[slot] = position;
            size++;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldPositions = positions;
            keys = new long[oldKeys.length * 2];
            positions = new int[keys.length];
            Arrays.fill(keys, -1);
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != -1) {
                    int slot = slot(oldKeys[i], keys.length);
                    while (keys[slot] != -1) {
                        slot = (slot + 1) & (keys.length - 1);
                    }
                    keys[slot] = oldKeys[i];
                    positions[slot] = oldPositions[i];
                }
            }
        }

        private static int slot(long key, int length) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h >>> 40) & (length - 1);
        }
    }

    /** The rivers found: where each starts among all the columns, and where it ends. */
    private final Longs cells = new Longs();
    private final PositionMap onRiver = new PositionMap();
    /** For each river: {where it starts among the columns, its length, the position of the column of another river where it ends or -1}. */
    private final List<int[]> rivers = new ArrayList<>();
    /** For each river: the column of another river where it ends, or -1 (a position can not be told back to a column). */
    private final List<long[]> joinedCell = new ArrayList<>();

    /**
     * @param climb the most blocks a river climbs over its level to get out of a hollow
     * @param riverWidth the width, in blocks, of a river with one source; 0 for no river at all
     * @param snap a river that comes within this many blocks of a lake connects to it and ends there; 0 for none
     * @param excludeUpTo the columns at or under this height are the sea: a river ends there and is never put there
     */
    RiverMask(HeightMapData data, LakeMask lakes, int climb, int riverWidth, int snap, int excludeUpTo, File dir,
              Logger logger, String label) throws IOException {
        this.width = data.width();
        this.height = data.height();
        this.bitsFile = MappedFile.scratch(dir, ((long) width * height + 7) / 8);
        this.bits = bitsFile.segment();
        if (riverWidth <= 0) {
            return;
        }
        try {
            find(data, lakes, climb, riverWidth, snap, excludeUpTo, logger, label, dir);
        } catch (IOException | RuntimeException | Error e) {
            bitsFile.close();
            throw e;
        }
    }

    private void find(HeightMapData data, LakeMask lakes, int climb, int riverWidth, int snap, int excludeUpTo,
                      Logger logger, String label, File dir) throws IOException {
        long total = (long) width * height;
        int[] ends = new int[4]; // rivers that end in a lake, in the sea, in another river, or stuck
        try (MappedFile visitedFile = MappedFile.scratch(dir, (total + 7) / 8)) {
            MemorySegment visited = visitedFile.segment();
            long[] queue = new long[1024];
            long bytes = (total + 7) / 8;
            for (long byteIndex = 0; byteIndex < bytes; byteIndex++) {
                if (lakes.byteAt(byteIndex) == 0) {
                    continue;
                }
                for (int bit = 0; bit < 8; bit++) {
                    long index = byteIndex * 8 + bit;
                    if (index >= total || !lakes.at((int) (index % width), (int) (index / width))
                            || isSet(visited, index)) {
                        continue;
                    }
                    // A lake: its columns by a flood fill, and the lowest column around it, the outlet.
                    int level = data.valueAt((int) (index % width), (int) (index / width));
                    long outlet = -1;
                    int outletHeight = Integer.MAX_VALUE;
                    int head = 0;
                    int tail = 0;
                    Set<Long> sourceLake = new HashSet<>(); // the columns of this lake, left out of the river's snap
                    queue[tail++] = index;
                    set(visited, index);
                    sourceLake.add(index);
                    while (head < tail) {
                        long cell = queue[head++];
                        int column = (int) (cell % width);
                        int row = (int) (cell / width);
                        for (int d = 0; d < 8; d += 2) {
                            int c = column + DX[d];
                            int r = row + DZ[d];
                            if (c < 0 || c >= width || r < 0 || r >= height) {
                                continue;
                            }
                            long next = (long) r * width + c;
                            if (lakes.at(c, r)) {
                                if (!isSet(visited, next)) {
                                    set(visited, next);
                                    sourceLake.add(next);
                                    if (tail == queue.length) {
                                        queue = Arrays.copyOf(queue, tail * 2);
                                    }
                                    queue[tail++] = next;
                                }
                            } else if (data.valueAt(c, r) < outletHeight) {
                                outletHeight = data.valueAt(c, r);
                                outlet = next;
                            }
                        }
                        if (head > 1 << 20 && head > tail / 2) { // keep the queue short
                            System.arraycopy(queue, head, queue, 0, tail - head);
                            tail -= head;
                            head = 0;
                        }
                    }
                    if (outlet >= 0 && outletHeight <= level + climb) {
                        int end = trace(data, lakes, outlet, level, climb, snap, excludeUpTo, sourceLake);
                        if (end >= 0) {
                            ends[end]++;
                        }
                    }
                }
            }
        }

        long columns = stamp(data, riverWidth, excludeUpTo);
        logger.info(label + " : " + rivers.size() + " rivière(s) depuis les lacs (" + ends[0] + " finissent dans un lac, "
                + ends[1] + " à la mer, " + ends[2] + " dans une autre rivière, " + ends[3] + " bloquées), "
                + cells.size + " colonnes de tracé, "
                + columns + " colonnes de rivière");
    }

    /**
     * Follows the relief from the outlet of a lake of this level. At each step, if a lake other than the one it left
     * ({@code sourceLake}) lies within {@code snap} blocks, the river is carried to its nearest column in a straight line
     * and ends there (a confluence with the lake).
     *
     * @return how the river ended: 0 in a lake, 1 in the sea, 2 in another river, 3 stuck; -1 if it is too short to keep
     */
    private int trace(HeightMapData data, LakeMask lakes, long outlet, int lakeLevel, int climb, int snap, int excludeUpTo,
                      Set<Long> sourceLake) {
        if (onRiver.get(outlet) >= 0) {
            return -1;
        }
        Longs path = new Longs();
        path.add(outlet);
        int level = Math.min(lakeLevel, valueAt(data, outlet));
        long joined = -1;
        int end = 3;
        int dx = 0;
        int dz = 0;
        long current = outlet;
        walk:
        while (path.size < MAX_LENGTH) {
            long[] leg = lower(data, lakes, current, level, dx, dz);
            if (leg == null) {
                leg = spill(data, lakes, current, level, climb);
            }
            if (leg == null) {
                break;
            }
            for (long cell : leg) {
                int h = valueAt(data, cell);
                if (onRiver.get(cell) >= 0) {
                    joined = cell;
                    end = 2;
                    break walk;
                }
                path.add(cell);
                if (lakes.at((int) (cell % width), (int) (cell / width))) {
                    end = 0;
                    break walk;
                }
                if (h <= excludeUpTo) {
                    end = 1;
                    break walk;
                }
                long lakeCell = snap > 0 ? nearestLake(lakes, cell, snap, sourceLake) : -1;
                if (lakeCell >= 0) {
                    bridge(path, cell, lakeCell);
                    end = 0;
                    break walk;
                }
            }
            long last = leg[leg.length - 1];
            long before = leg.length > 1 ? leg[leg.length - 2] : current;
            dx = (int) (last % width) - (int) (before % width);
            dz = (int) (last / width) - (int) (before / width);
            current = last;
            level = valueAt(data, last);
        }
        if (path.size < 2 && joined < 0) {
            return -1;
        }
        int start = cells.size;
        for (int i = 0; i < path.size; i++) {
            onRiver.putIfAbsent(path.values[i], cells.size);
            cells.add(path.values[i]);
        }
        rivers.add(new int[] {start, path.size, joined >= 0 ? onRiver.get(joined) : -1});
        joinedCell.add(new long[] {joined});
        return end;
    }

    /**
     * The lake column closest (by straight-line distance) to {@code from} within {@code snap} blocks, or -1 if none.
     * The column {@code from} itself is already known not to be a lake, and the columns of {@code sourceLake} (the lake
     * the river left) are skipped so it does not snap straight back to its own source.
     */
    private long nearestLake(LakeMask lakes, long from, int snap, Set<Long> sourceLake) {
        int column = (int) (from % width);
        int row = (int) (from / width);
        long best = -1;
        int bestDistance = snap * snap + 1;
        for (int dz = -snap; dz <= snap; dz++) {
            int r = row + dz;
            if (r < 0 || r >= height) {
                continue;
            }
            for (int dx = -snap; dx <= snap; dx++) {
                int c = column + dx;
                if (c < 0 || c >= width) {
                    continue;
                }
                int distance = dx * dx + dz * dz;
                long candidate = (long) r * width + c;
                if (distance < bestDistance && lakes.at(c, r) && !sourceLake.contains(candidate)) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Adds the columns of the straight line from just after {@code from} up to {@code to} (included) to {@code path}. */
    private void bridge(Longs path, long from, long to) {
        int x0 = (int) (from % width);
        int z0 = (int) (from / width);
        int x1 = (int) (to % width);
        int z1 = (int) (to / width);
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        for (int i = 1; i <= steps; i++) {
            int c = x0 + (int) Math.round((double) (x1 - x0) * i / steps);
            int r = z0 + (int) Math.round((double) (z1 - z0) * i / steps);
            path.add((long) r * width + c);
        }
    }

    private int valueAt(HeightMapData data, long cell) {
        return data.valueAt((int) (cell % width), (int) (cell / width));
    }

    /**
     * The column next to {@code from} that is lower than {@code level}: the lowest one, the one that goes on in the
     * direction (dx, dz) among the equal ones. Null when there is none.
     */
    private long[] lower(HeightMapData data, LakeMask lakes, long from, int level, int dx, int dz) {
        int column = (int) (from % width);
        int row = (int) (from / width);
        long best = -1;
        int bestHeight = 0;
        int bestDot = 0;
        for (int d = 0; d < 8; d++) {
            int c = column + DX[d];
            int r = row + DZ[d];
            if (c < 0 || c >= width || r < 0 || r >= height) {
                continue;
            }
            int h = data.valueAt(c, r);
            if (h >= level) {
                continue;
            }
            int dot = DX[d] * dx + DZ[d] * dz;
            if (best < 0 || h < bestHeight || h == bestHeight && dot > bestDot) {
                best = (long) r * width + c;
                bestHeight = h;
                bestDot = dot;
            }
        }
        return best < 0 ? null : new long[] {best};
    }

    /**
     * The way out of a hollow: from {@code from}, the lowest columns reachable first (as water that rises), over columns at
     * most {@code climb} higher than {@code level} and never a lake that is not lower, up to the first column lower
     * than {@code level}. Returns the columns from the one after {@code from} to that one; null if there is no way out.
     */
    private long[] spill(HeightMapData data, LakeMask lakes, long from, int level, int climb) {
        PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        HashMap<Long, Long> parent = new HashMap<>();
        parent.put(from, -1L);
        heap.add(new long[] {((long) valueAt(data, from) << 32), from, 0});
        int expanded = 0;
        while (!heap.isEmpty() && expanded++ < MAX_EXPAND) {
            long[] top = heap.poll();
            long cell = top[1];
            int column = (int) (cell % width);
            int row = (int) (cell / width);
            if (cell != from && data.valueAt(column, row) < level) {
                ArrayList<Long> reverse = new ArrayList<>();
                for (long c = cell; c != from; c = parent.get(c)) {
                    reverse.add(c);
                }
                long[] leg = new long[reverse.size()];
                for (int i = 0; i < leg.length; i++) {
                    leg[i] = reverse.get(leg.length - 1 - i);
                }
                return leg;
            }
            for (int d = 0; d < 8; d++) {
                int c = column + DX[d];
                int r = row + DZ[d];
                if (c < 0 || c >= width || r < 0 || r >= height) {
                    continue;
                }
                long next = (long) r * width + c;
                if (parent.containsKey(next)) {
                    continue;
                }
                int h = data.valueAt(c, r);
                if (h > level + climb || h >= level && lakes.at(c, r)) {
                    continue;
                }
                parent.put(next, cell);
                heap.add(new long[] {((long) h << 32) + top[2] + 1, next, top[2] + 1});
            }
        }
        return null;
    }

    /**
     * Puts the rivers in the mask: each path smoothed (a moving average), the width at each point from the sources upstream
     * (a river that ends in another one adds its own to it from there). The rivers are done from the last found to the
     * first, as one ends in an earlier one. Returns the number of columns.
     */
    private long stamp(HeightMapData data, int riverWidth, int excludeUpTo) {
        int count = rivers.size();
        int[] startOf = new int[count];
        for (int i = 0; i < count; i++) {
            startOf[i] = rivers.get(i)[0];
        }
        List<List<int[]>> inflows = new ArrayList<>(); // for each river: {index in the path, sources}
        for (int i = 0; i < count; i++) {
            inflows.add(new ArrayList<>());
        }
        long columns = 0;
        for (int i = count - 1; i >= 0; i--) {
            int[] river = rivers.get(i);
            int length = river[1];
            long joinedColumn = joinedCell.get(i)[0];
            int points = length + (joinedColumn >= 0 ? 1 : 0);
            double[] xs = new double[points];
            double[] zs = new double[points];
            for (int k = 0; k < points; k++) {
                long cell = k < length ? cells.values[river[0] + k] : joinedColumn;
                xs[k] = cell % width;
                zs[k] = cell / width;
            }
            int[] sources = new int[points + 1];
            for (int[] inflow : inflows.get(i)) {
                sources[inflow[0]] += inflow[1];
            }
            int flow = 1;
            for (int k = 0; k < points; k++) {
                flow += sources[k];
                double radius = Math.max(0.5, riverWidth * Math.sqrt(flow) / 2);
                int span = Math.min(SMOOTH, Math.min(k, points - 1 - k));
                double x = 0;
                double z = 0;
                for (int j = k - span; j <= k + span; j++) {
                    x += xs[j];
                    z += zs[j];
                }
                x /= 2 * span + 1;
                z /= 2 * span + 1;
                columns += disc(data, x, z, radius, excludeUpTo);
            }
            if (river[2] >= 0) {
                int owner = Arrays.binarySearch(startOf, river[2]);
                if (owner < 0) {
                    owner = -owner - 2; // the river whose start is just before that position
                }
                inflows.get(owner).add(new int[] {river[2] - startOf[owner], flow});
            }
        }
        return columns;
    }

    /** Sets the columns within {@code radius} of (x, z) that are above the sea; returns how many were not set. */
    private long disc(HeightMapData data, double x, double z, double radius, int excludeUpTo) {
        long added = 0;
        int c0 = Math.max(0, (int) Math.floor(x - radius));
        int c1 = Math.min(width - 1, (int) Math.ceil(x + radius));
        int r0 = Math.max(0, (int) Math.floor(z - radius));
        int r1 = Math.min(height - 1, (int) Math.ceil(z + radius));
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                double ddx = c - x;
                double ddz = r - z;
                if (ddx * ddx + ddz * ddz > radius * radius || data.valueAt(c, r) <= excludeUpTo) {
                    continue;
                }
                long index = (long) r * width + c;
                byte old = bits.get(MappedFile.BYTE, index >> 3);
                byte updated = (byte) (old | 1 << (index & 7));
                if (updated != old) {
                    bits.set(MappedFile.BYTE, index >> 3, updated);
                    added++;
                }
            }
        }
        return added;
    }

    private static boolean isSet(MemorySegment segment, long index) {
        return (segment.get(MappedFile.BYTE, index >> 3) >> (index & 7) & 1) != 0;
    }

    private static void set(MemorySegment segment, long index) {
        segment.set(MappedFile.BYTE, index >> 3, (byte) (segment.get(MappedFile.BYTE, index >> 3) | 1 << (index & 7)));
    }

    /** Whether the column at world (x, z) is a river; the map repeats outside its bounds. */
    boolean contains(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        long index = (long) row * width + column;
        return isSet(bits, index);
    }

    /** Frees the scratch file of the mask. */
    @Override
    public void close() {
        bitsFile.close();
    }
}
