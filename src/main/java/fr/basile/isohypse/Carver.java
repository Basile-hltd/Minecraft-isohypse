package fr.basile.isohypse;

/**
 * Shapes the ground of the lakes and the rivers: a lake gets a bed sloping down from its shore, a river a channel under
 * its water level with banks lowered around it. This is the solid top of a column once the water bodies are dug, and how
 * much water of a lake or a river is over it. The result depends on the column and its neighbours alone (within a fixed
 * reach), so it can be computed for any area, and is baked into the layers {@code height} and {@code water} of
 * {@code world.bin} at build time ({@link HeightScale}) so the generator only reads it. All coordinates are world coordinates (the origin is the middle of the map).
 */
final class Carver {

    /** Blocks under the height of a lake (its water level) where its bed is, away from the shore, before the noise. */
    private static final int LAKE_DEPTH = 6;
    /** Blocks of depth gained per block from the shore: the bed goes down in a slope, not in a wall. */
    private static final double LAKE_SLOPE = 0.5;
    /** The distance from the shore, in blocks, from which the bed is at its deepest. */
    private static final int LAKE_RADIUS = (int) Math.ceil(LAKE_DEPTH / LAKE_SLOPE);
    /** How far the noise moves the bed, up or down, in blocks, where the lake is at its deepest (none at the shore). */
    private static final double LAKE_NOISE = 1.0;
    /** The size, in blocks, of the cells of the first octave of the noise of the bed (the second is a third of it). */
    private static final int LAKE_NOISE_CELL = 12;
    /** The depth, in blocks, of the channel of a river under its water level (less at the banks). */
    private static final int RIVER_DEPTH = 3;
    /** How far from a river, in blocks, the ground is lowered to its banks (fading out, so there is no wall at the end). */
    private static final int RIVER_REACH = 16;
    /** The water level of a river is the lowest river column within this radius, so it is flat across the river. */
    private static final int RIVER_LEVEL_RADIUS = 2;
    /** How far the noise moves the bed of a river, up or down, in blocks, where it is deepest. */
    private static final double RIVER_NOISE = 1.0;
    /** Marks a column that the rivers leave as it is. */
    private static final int NO_RIVER = Integer.MIN_VALUE;

    private Carver() {
    }

    /**
     * The water is this many blocks under the level of a lake or a river (the natural height of a lake, the level of a
     * river), so a lake or a river is not full to the brim of its bed.
     */
    private static final int WATER_DROP = 1;

    /**
     * The carving of an area of columns, indexed as in {@link #carveTile}: {@code tops} the solid top Y of each column,
     * {@code water} the blocks of water of a lake or a river over it (0 for none).
     */
    record Carved(int[] tops, int[] water) {}

    /**
     * The carving of every column of the area of {@code width} x {@code height} columns from (x0, z0), indexed by
     * {@code (x - x0) * height + (z - z0)}. The solid top is the bed of a lake (its water level less its depth), the
     * channel or lowered bank of a river, else the natural height. The water of a lake or a river fills its bed up to
     * {@link #WATER_DROP} blocks under its level; the sea is not here.
     */
    static Carved carveTile(Relief data, long seed, int x0, int z0, int width, int height) {
        int[] lakeDepths = lakeDepths(data, seed, x0, z0, width, height);
        River rivers = riverTops(data, seed, x0, z0, width, height);
        int[] out = new int[width * height];
        int[] water = new int[width * height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                int index = i * height + j;
                int top = data.heightAt(x0 + i, z0 + j);
                int level = NO_RIVER; // the level of the lake or the river of the column, if any
                if (lakeDepths != null && lakeDepths[index] >= 0) {
                    out[index] = top - lakeDepths[index]; // a lake: the bed, under its water level (the natural height)
                    if (rivers != null) {
                        // A river that goes into the lake (RiverMask) cuts its channel through the shore of the lake.
                        out[index] = Math.min(out[index], rivers.top(index, top));
                    }
                    level = top;
                } else if (rivers != null) {
                    out[index] = rivers.top(index, top); // the channel of a river, its banks, or the natural height
                    if (rivers.isRiver(index)) {
                        level = rivers.levels()[index];
                    }
                } else {
                    out[index] = top;
                }
                if (level != NO_RIVER) {
                    water[index] = Math.clamp(level - WATER_DROP - out[index], 0, 255);
                }
            }
        }
        return new Carved(out, water);
    }

    /**
     * The depth of the water of the lake columns of the area of {@code width} x {@code height} columns from (x0, z0), by
     * {@code (x - x0) * height + (z - z0)}: -1 for a column that is not a lake, else the blocks under the water level
     * where its bed is. The depth grows by {@link #LAKE_SLOPE} per block from the shore (the nearest column that is not a
     * lake, within {@link #LAKE_RADIUS} blocks) up to {@link #LAKE_DEPTH}, so the bed goes down in a slope; a noise then
     * moves the bed, by {@link #LAKE_NOISE} at most where it is deepest and not at all at the shore, so that it is not
     * flat. Null when there is no lake in the area.
     */
    static int[] lakeDepths(Relief data, long seed, int x0, int z0, int width, int height) {
        int around = 2 * LAKE_RADIUS;
        int stride = height + around;
        boolean[] lake = new boolean[(width + around) * stride]; // the area and LAKE_RADIUS columns around it
        boolean any = false;
        for (int i = 0; i < width + around; i++) {
            for (int j = 0; j < stride; j++) {
                boolean is = data.lakeAt(x0 - LAKE_RADIUS + i, z0 - LAKE_RADIUS + j);
                lake[i * stride + j] = is;
                any |= is && i >= LAKE_RADIUS && i < LAKE_RADIUS + width && j >= LAKE_RADIUS && j < LAKE_RADIUS + height;
            }
        }
        if (!any) {
            return null;
        }
        int[] depths = new int[width * height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                if (!lake[(i + LAKE_RADIUS) * stride + j + LAKE_RADIUS]) {
                    depths[i * height + j] = -1;
                    continue;
                }
                int nearest = Integer.MAX_VALUE; // squared distance to the nearest column that is not a lake
                for (int di = -LAKE_RADIUS; di <= LAKE_RADIUS; di++) {
                    for (int dj = -LAKE_RADIUS; dj <= LAKE_RADIUS; dj++) {
                        int distance = di * di + dj * dj;
                        if (distance < nearest && !lake[(i + LAKE_RADIUS + di) * stride + j + LAKE_RADIUS + dj]) {
                            nearest = distance;
                        }
                    }
                }
                double base = nearest == Integer.MAX_VALUE ? LAKE_DEPTH
                        : Math.min(LAKE_DEPTH, Math.floor(Math.sqrt(nearest) * LAKE_SLOPE));
                double noise = bedNoise(seed, x0 + i, z0 + j) * LAKE_NOISE * base / LAKE_DEPTH;
                depths[i * height + j] = Math.max(0, (int) Math.round(base + noise));
            }
        }
        return depths;
    }

    /**
     * The Y of the top block of the columns of the area of {@code width} x {@code height} columns from (x0, z0), by
     * {@code (x - x0) * height + (z - z0)}, once the rivers have cut their channel: {@link #NO_RIVER} for a column that
     * they leave as it is, null when there is none in the area. The water level of a river column is the lowest river
     * column around it (flat across the river, following the course down); its bed is under it, from 1 block at the
     * banks of the river up to {@link #RIVER_DEPTH} in the middle, moved by a noise. The ground next to the river is
     * lowered to a bank that rises by 1 block per block from the water level, the lowering fading out to nothing
     * {@link #RIVER_REACH} blocks from the river. The ground is only ever lowered.
     */
    static River riverTops(Relief data, long seed, int x0, int z0, int width, int height) {
        int margin = RIVER_REACH + RIVER_LEVEL_RADIUS;
        int stride = height + 2 * margin;
        int size = (width + 2 * margin) * stride;
        int[] river = new int[size]; // the height of the river columns of the area and the margin around it, else NO_RIVER
        int count = 0;
        for (int i = 0; i < width + 2 * margin; i++) {
            for (int j = 0; j < stride; j++) {
                boolean is = data.riverAt(x0 - margin + i, z0 - margin + j);
                river[i * stride + j] = is ? data.heightAt(x0 - margin + i, z0 - margin + j) : NO_RIVER;
                count += is ? 1 : 0;
            }
        }
        if (count == 0) {
            return null;
        }
        int[] cells = new int[count]; // the index of each river column
        count = 0;
        for (int index = 0; index < size; index++) {
            if (river[index] != NO_RIVER) {
                cells[count++] = index;
            }
        }
        int[] tops = new int[width * height];
        int[] levels = new int[width * height];
        boolean any = false;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                int top = data.heightAt(x0 + i, z0 + j);
                int self = (i + margin) * stride + j + margin;
                boolean is = river[self] != NO_RIVER;
                int result = is ? channel(river, stride, self, top, seed, x0 + i, z0 + j)
                        : bank(river, stride, cells, self, top);
                tops[i * height + j] = result == top ? NO_RIVER : result;
                levels[i * height + j] = is ? waterLevel(river, stride, self) : NO_RIVER;
                any |= is || result != top;
            }
        }
        return any ? new River(tops, levels) : null;
    }

    /**
     * What the rivers do to an area of columns (indexed as in {@link #riverTops}): {@code tops} the Y of the top block of
     * a column they change, else {@link #NO_RIVER}; {@code levels} the Y of the water of a river column, else
     * {@link #NO_RIVER}.
     */
    record River(int[] tops, int[] levels) {

        /** The top block of the column {@code index}, {@code natural} when the rivers leave it as it is. */
        int top(int index, int natural) {
            return tops[index] == NO_RIVER ? natural : tops[index];
        }

        boolean isRiver(int index) {
            return levels[index] != NO_RIVER;
        }
    }

    /** The water level of a river column: the lowest river column within {@link #RIVER_LEVEL_RADIUS} of {@code index}. */
    private static int waterLevel(int[] river, int stride, int index) {
        int level = Integer.MAX_VALUE;
        for (int di = -RIVER_LEVEL_RADIUS; di <= RIVER_LEVEL_RADIUS; di++) {
            for (int dj = -RIVER_LEVEL_RADIUS; dj <= RIVER_LEVEL_RADIUS; dj++) {
                int other = index + di * stride + dj;
                if (other >= 0 && other < river.length && river[other] != NO_RIVER) {
                    level = Math.min(level, river[other]);
                }
            }
        }
        return level;
    }

    /** The top of a river column: its bed, {@code 1} to {@link #RIVER_DEPTH} blocks under the water level. */
    private static int channel(int[] river, int stride, int self, int top, long seed, int x, int z) {
        int level = waterLevel(river, stride, self);
        int nearest = Integer.MAX_VALUE; // squared distance to the nearest column that is not a river, within the depth
        for (int di = -RIVER_DEPTH; di <= RIVER_DEPTH; di++) {
            for (int dj = -RIVER_DEPTH; dj <= RIVER_DEPTH; dj++) {
                int other = self + di * stride + dj;
                if (di * di + dj * dj < nearest && other >= 0 && other < river.length && river[other] == NO_RIVER) {
                    nearest = di * di + dj * dj;
                }
            }
        }
        double base = nearest == Integer.MAX_VALUE ? RIVER_DEPTH : Math.min(RIVER_DEPTH, Math.floor(Math.sqrt(nearest)));
        double noise = bedNoise(seed, x, z) * RIVER_NOISE * base / RIVER_DEPTH;
        int depth = Math.max(0, (int) Math.round(base + noise));
        return Math.min(top, level - depth);
    }

    /** The top of a column next to a river: the natural ground, lowered to the bank of the nearest river column. */
    private static int bank(int[] river, int stride, int[] cells, int self, int top) {
        int nearest = Integer.MAX_VALUE;
        int from = -1;
        int si = self / stride;
        int sj = self % stride;
        for (int cell : cells) {
            int di = cell / stride - si;
            int dj = cell % stride - sj;
            int distance = di * di + dj * dj;
            if (distance < nearest) {
                nearest = distance;
                from = cell;
            }
        }
        if (nearest > RIVER_REACH * RIVER_REACH) {
            return top;
        }
        double distance = Math.sqrt(nearest);
        int cap = waterLevel(river, stride, from) + (int) Math.floor(distance) - 1;
        double fade = Math.clamp((RIVER_REACH - distance) / (RIVER_REACH - 2), 0, 1);
        return top - (int) Math.round(Math.max(0, top - cap) * fade);
    }

    /** Smooth noise in [-1, 1] at the column (x, z): two octaves of value noise, from the seed. */
    private static double bedNoise(long seed, int x, int z) {
        return (valueNoise(seed, x, z, LAKE_NOISE_CELL) * 2 + valueNoise(seed + 1, x, z, LAKE_NOISE_CELL / 3)) / 3;
    }

    private static double valueNoise(long seed, int x, int z, int cell) {
        int cx = Math.floorDiv(x, cell);
        int cz = Math.floorDiv(z, cell);
        double fx = (double) Math.floorMod(x, cell) / cell;
        double fz = (double) Math.floorMod(z, cell) / cell;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double top = lerp(corner(seed, cx, cz), corner(seed, cx + 1, cz), fx);
        double bottom = lerp(corner(seed, cx, cz + 1), corner(seed, cx + 1, cz + 1), fx);
        return lerp(top, bottom, fz);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** A pseudo-random value in [-1, 1] for a corner of the grid of the noise. */
    private static double corner(long seed, int cx, int cz) {
        long h = seed ^ cx * 0x9E3779B97F4A7C15L ^ cz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h & 0xFFFF) / 32767.5 - 1;
    }
}
