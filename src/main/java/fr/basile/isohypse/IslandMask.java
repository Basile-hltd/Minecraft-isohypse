package fr.basile.isohypse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.logging.Logger;
import java.util.stream.IntStream;

/**
 * The mushroom islands: wherever the open sea is wide enough, an island is raised from the sea floor. An island of
 * {@code size} blocks across is put where the sea is at least {@code size} blocks from any land in every direction
 * (so there are {@code size / 2} blocks of sea all around it). The map is cut in cells of {@code size} blocks, the
 * point of each cell farthest from the land is a candidate, and the candidates are taken from the most remote, none
 * closer than {@code spacing} blocks to one already taken. Each island is a disc whose edge and height are moved by
 * noise from the seed: the coast is irregular, the relief rises from the sea floor to a peak a few blocks over the
 * sea, with small hills. The islands are a few discs in memory (and a coarse bit map to find them fast), nothing big.
 * The map repeats endlessly, so the islands wrap around it.
 */
final class IslandMask {

    /** What {@link #topAt} gives for a column that is not on an island. */
    static final int NO_TOP = Integer.MIN_VALUE;
    /** The edge of an island is moved by up to this share of its radius by the noise. */
    private static final double COAST = 0.3;
    /** How far from the middle of an island its columns can be, in radii (the coast with the noise at its most). */
    private static final double REACH = 1 + COAST;
    /** The side, in blocks, of the cells of the coarse map that tells fast whether an island is near a column. */
    private static final int COARSE = 64;

    /** An island: its middle (a column of the map) and the seed of its noises. */
    private record Island(int column, int row, long seed) {}

    private final int width;
    private final int height;
    private final int radius;
    private final int reach;
    private final int spacing;
    private final int peak;
    private final int seaLevel;
    private final List<Island> islands = new ArrayList<>();
    /** The islands by cell of {@code spacing} blocks: an island is in the cell of its middle. */
    private final Map<Long, List<Island>> cells = new HashMap<>();
    private final int cellsAcross;
    private final int cellsDown;
    /** One bit per cell of {@link #COARSE} blocks: set when an island may be in it. */
    private final long[] coarse;
    private final int coarseAcross;
    private final int coarseDown;

    /**
     * @param quarters the distance of the column (column, row) of the map to the nearest land, in quarters of a block
     * @param size the width of an island, in blocks; 0 for no island
     * @param spacing the least distance, in blocks, between the middles of two islands
     * @param peak the height of the highest point of an island over the sea level, in blocks
     */
    IslandMask(HeightMapData data, IntBinaryOperator quarters, int size, int spacing, int peak, int seaLevel, long seed,
               Logger logger, String label) {
        this.width = data.width();
        this.height = data.height();
        this.radius = size / 2;
        this.reach = (int) Math.ceil(radius * REACH);
        this.spacing = Math.max(1, spacing);
        this.peak = peak;
        this.seaLevel = seaLevel;
        this.cellsAcross = (width + this.spacing - 1) / this.spacing;
        this.cellsDown = (height + this.spacing - 1) / this.spacing;
        this.coarseAcross = (width + COARSE - 1) / COARSE;
        this.coarseDown = (height + COARSE - 1) / COARSE;
        this.coarse = new long[(int) (((long) coarseAcross * coarseDown + 63) / 64)];
        if (size > 0) {
            place(quarters, size, seed);
            for (Island island : islands) {
                markCoarse(island);
            }
        }
        logger.info(label + " : " + islands.size() + " île(s) de " + size + " blocs, espacées d'au moins " + spacing
                + " blocs");
    }

    /** Takes the candidates, the most remote from the land first, none closer than the spacing to one taken. */
    private void place(IntBinaryOperator quarters, int size, long seed) {
        int need = 4 * size;
        int across = (width + size - 1) / size;
        int down = (height + size - 1) / size;
        // the farthest column of each cell, packed so that the larger value is the more remote: distance, column, row
        long[] best = new long[across * down];
        IntStream.range(0, down).parallel().forEach(cellRow -> {
            long[] line = new long[across];
            for (int row = cellRow * size; row < Math.min(height, (cellRow + 1) * size); row++) {
                for (int column = 0; column < width; column++) {
                    int q = quarters.applyAsInt(column, row);
                    if (q >= need) {
                        int cell = column / size;
                        line[cell] = Math.max(line[cell], ((long) q << 42) | ((long) column << 21) | row);
                    }
                }
            }
            System.arraycopy(line, 0, best, cellRow * across, across);
        });
        long[] candidates = Arrays.stream(best).filter(v -> v != 0).toArray();
        Arrays.sort(candidates);
        long mask = (1L << 21) - 1;
        for (int i = candidates.length - 1; i >= 0; i--) {
            int column = (int) (candidates[i] >> 21 & mask);
            int row = (int) (candidates[i] & mask);
            // the map does not repeat in the game: an island at the edge would show up cut, and again at the opposite edge
            if (column < reach + 1 || column >= width - reach - 1 || row < reach + 1 || row >= height - reach - 1
                    || tooClose(column, row)) {
                continue;
            }
            long mixed = seed ^ column * 0x9E3779B97F4A7C15L ^ row * 0xC2B2AE3D27D4EB4FL;
            Island island = new Island(column, row, mixed);
            islands.add(island);
            cells.computeIfAbsent(cellKey(column / spacing, row / spacing), k -> new ArrayList<>()).add(island);
        }
    }

    private boolean tooClose(int column, int row) {
        for (Island other : near(column, row)) {
            long dc = wrap(column - other.column, width);
            long dr = wrap(row - other.row, height);
            if (dc * dc + dr * dr < (long) spacing * spacing) {
                return true;
            }
        }
        return false;
    }

    private void markCoarse(Island island) {
        for (int c = Math.floorDiv(island.column - reach, COARSE); c <= Math.floorDiv(island.column + reach, COARSE); c++) {
            for (int r = Math.floorDiv(island.row - reach, COARSE); r <= Math.floorDiv(island.row + reach, COARSE); r++) {
                long bit = (long) Math.floorMod(r, coarseDown) * coarseAcross + Math.floorMod(c, coarseAcross);
                coarse[(int) (bit >> 6)] |= 1L << (bit & 63);
            }
        }
    }

    private static long cellKey(int across, int down) {
        return ((long) across << 32) | (down & 0xFFFFFFFFL);
    }

    /** The islands in the cell of the column and the eight around it (those within the spacing are among them). */
    private List<Island> near(int column, int row) {
        List<Island> found = new ArrayList<>();
        int cellColumn = column / spacing;
        int cellRow = row / spacing;
        for (int dc = -1; dc <= 1; dc++) {
            for (int dr = -1; dr <= 1; dr++) {
                List<Island> inCell = cells.get(cellKey(Math.floorMod(cellColumn + dc, cellsAcross),
                        Math.floorMod(cellRow + dr, cellsDown)));
                if (inCell != null) {
                    found.addAll(inCell);
                }
            }
        }
        return found;
    }

    /** The difference {@code d} of two positions on a map {@code size} wide, as the short way round. */
    private static int wrap(int d, int size) {
        return Math.floorMod(d + size / 2, size) - size / 2;
    }

    /**
     * How far inside its island the column is: 1 in the middle, 0 at the edge (the coast moved by noise), negative
     * outside. {@code column} and {@code row} are the offsets from the middle of the island.
     */
    private double inside(Island island, int dColumn, int dRow) {
        double u = Math.hypot(dColumn, dRow) / radius;
        int x = island.column + dColumn;
        int z = island.row + dRow;
        double cell = Math.max(8, radius / 2.0);
        double noise = (Carver.valueNoise(island.seed, x, z, (int) cell) * 2
                + Carver.valueNoise(island.seed + 1, x, z, (int) Math.max(4, radius / 5.0))) / 3;
        return 1 - u + COAST * noise;
    }

    /** The island that the column is on, or null; with its offsets from the middle in {@code offsets}. */
    private Island islandAt(int column, int row, int[] offsets) {
        if (islands.isEmpty()) {
            return null;
        }
        long bit = (long) (row / COARSE) * coarseAcross + column / COARSE;
        if ((coarse[(int) (bit >> 6)] >> (bit & 63) & 1) == 0) {
            return null;
        }
        Island found = null;
        long nearest = Long.MAX_VALUE;
        for (Island island : near(column, row)) {
            long dc = wrap(column - island.column, width);
            long dr = wrap(row - island.row, height);
            long distance = dc * dc + dr * dr;
            if (distance <= (long) reach * reach && distance < nearest) {
                nearest = distance;
                found = island;
                offsets[0] = (int) dc;
                offsets[1] = (int) dr;
            }
        }
        return found != null && inside(found, offsets[0], offsets[1]) > 0 ? found : null;
    }

    /** Whether the column at world (x, z) is on an island; the map repeats outside its bounds. */
    boolean contains(int x, int z) {
        if (islands.isEmpty()) {
            return false;
        }
        return islandAt(Math.floorMod(x + width / 2, width), Math.floorMod(z + height / 2, height), new int[2]) != null;
    }

    /**
     * The Y of the top block of the column at world (x, z) if it is on an island, else {@link #NO_TOP}; {@code floor}
     * is the Y of the top block of the sea floor there. The island rises from the floor, by an easing of how far inside
     * the column is, to a peak (itself moved by a slow noise) a few blocks over the sea, with small hills.
     */
    int topAt(int x, int z, int floor) {
        if (islands.isEmpty()) {
            return NO_TOP;
        }
        int[] offsets = new int[2];
        Island island = islandAt(Math.floorMod(x + width / 2, width), Math.floorMod(z + height / 2, height), offsets);
        if (island == null) {
            return NO_TOP;
        }
        double t = Math.clamp(inside(island, offsets[0], offsets[1]), 0, 1);
        double ease = t * t * (3 - 2 * t);
        int px = island.column + offsets[0];
        int pz = island.row + offsets[1];
        double slow = Carver.valueNoise(island.seed + 2, px, pz, Math.max(8, radius / 2));
        double summit = seaLevel + peak * (0.65 + 0.35 * slow);
        double hills = Carver.valueNoise(island.seed + 3, px, pz, 6) * 1.5 * ease;
        return Math.max(floor, (int) Math.round(floor + (summit - floor) * ease + hills));
    }

    /** Whether there is no island at all. */
    boolean isEmpty() {
        return islands.isEmpty();
    }
}
