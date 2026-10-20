package fr.basile.isohypse;

import java.util.List;

/**
 * The intermediate map: seven zones derived from the height map alone, with no link to the Minecraft biomes (yet).
 * With S the sea level, and L the highest point of the height map:
 * <ul>
 * <li>the sea, the columns lower than S - 3, in the three {@link OceanType}s, that only depend on the distance to the
 * edge of the sea (not on the depth): ocean_warm along the shore, ocean_normal beyond it, ocean_cold in the open. The two
 * distances are searched so that the three zones cover the same share (a third) of the sea columns. The distance is
 * first filtered by a very wide low-pass filter ({@link HeightSmoothing}, about {@link #OCEAN_BLUR} blocks), so the
 * borders are smooth;</li>
 * <li>beach: S - 3 to S + 5, but more than {@link #BEACH_INLAND_DISTANCE} blocks from the nearest sea it is warm
 * (decided on the raw heights, before any filtering);</li>
 * <li>the land above, from S + 5 to L, cut in three so that warm, normal and cold cover {@link #WARM_SHARE},
 * {@link #NORMAL_SHARE} and the rest of the land columns of the height map (the limits are searched), told apart on
 * a narrower low-pass filtering of the land ({@link #LAND_BLUR} blocks).</li>
 * </ul>
 */
final class TempLayout {

    /** A zone, the colour that stands for it, and the Y range it covers (for the glossary). */
    record Zone(String name, int rgb, String range) {}

    /** A beach column farther than this from the nearest sea is warm land. */
    static final int BEACH_INLAND_DISTANCE = 20;
    /** About the standard deviation, in blocks, of the low-pass filter of the distance to the shore in the sea... */
    static final int OCEAN_BLUR = 150;
    /** ...and of the land (narrower). */
    static final int LAND_BLUR = 50;

    /** The share of the land columns that is warm... */
    static final double WARM_SHARE = 0.35;
    /** ...and normal; the rest of the land is cold. */
    static final double NORMAL_SHARE = 0.45;

    /** The columns lower than this Y are the ocean (the beach starts here), for a sea level. */
    static int beachStart(int seaLevel) {
        return seaLevel - 3;
    }

    /** The land starts from this Y (the beach ends here), for a sea level. */
    static int landStart(int seaLevel) {
        return seaLevel + 5;
    }

    /**
     * The layout of a height map: the filtered distances and the limits between the zones are computed here, which
     * takes a few seconds on a big map.
     */
    static TempLayout build(HeightMapData data, int seaLevel) {
        int beachStart = beachStart(seaLevel);
        int landStart = landStart(seaLevel);
        // The sea zones: the distance of every sea column to the edge of the sea (what is not sea), filtered widely.
        ShoreDistance shore = new ShoreDistance(data, v -> v >= beachStart, 0);
        HeightSmoothing seaSmoothing = new HeightSmoothing(data, shore::quarterBlocks,
                (column, row) -> data.valueAt(column, row) < beachStart, OCEAN_BLUR);
        ShoreDistance seaDistance = new ShoreDistance(data, v -> v < beachStart, BEACH_INLAND_DISTANCE);
        // The land, and the beach columns too far from the sea (they are warm land, decided before the filtering).
        HeightSmoothing landSmoothing = new HeightSmoothing(data, data::valueAt, (column, row) -> {
            int v = data.valueAt(column, row);
            return v >= landStart || v >= beachStart && seaDistance.isFar(column - data.width() / 2, row - data.height() / 2);
        }, LAND_BLUR);
        return new TempLayout(seaLevel, data.highest(), seaSmoothing, landSmoothing, seaDistance);
    }

    private final Zone deepSea;
    private final Zone ocean;
    private final Zone warmOcean;
    private final Zone beach;
    private final Zone warm;
    private final Zone normal;
    private final Zone cold;

    private final ShoreDistance seaDistance;
    private final HeightSmoothing seaSmoothing;
    private final HeightSmoothing landSmoothing;
    /** The limits between the sea zones, in quarters of a block from the shore. */
    private final int warmOceanLimit;
    private final int oceanLimit;
    private final int beachStart;
    private final int landStart;
    private final int warmTop;
    private final int normalTop;

    /**
     * @param seaSmoothing the filtered distance to the shore of the sea columns, in quarters of a block
     * @param landSmoothing the filtered land, which the land zones are told apart on
     * @param seaDistance which columns are far enough from the nearest sea, to turn the inland beach to warm land
     */
    TempLayout(int seaLevel, int highest, HeightSmoothing seaSmoothing, HeightSmoothing landSmoothing,
               ShoreDistance seaDistance) {
        this.seaDistance = seaDistance;
        this.seaSmoothing = seaSmoothing;
        this.landSmoothing = landSmoothing;
        this.beachStart = beachStart(seaLevel);
        this.landStart = landStart(seaLevel);
        int[] sea = seaSmoothing.histogram();
        this.warmOceanLimit = heightAtShare(sea, 0, 1.0 / 3);
        this.oceanLimit = heightAtShare(sea, 0, 2.0 / 3);
        int[] land = landSmoothing.histogram();
        this.warmTop = heightAtShare(land, landStart, WARM_SHARE);
        this.normalTop = heightAtShare(land, landStart, WARM_SHARE + NORMAL_SHARE);
        int near = Math.round(warmOceanLimit / 4f);
        int far = Math.round(oceanLimit / 4f);
        this.deepSea = new Zone(OceanType.COLD.id(), 0x14347a, "à plus de " + far + " du bord");
        this.ocean = new Zone(OceanType.NORMAL.id(), 0x1f4fa3, "à " + near + " à " + far + " du bord");
        this.warmOcean = new Zone(OceanType.WARM.id(), 0x3fc0d0, "à moins de " + near + " du bord");
        this.beach = new Zone("beach", 0xf0e0a0, beachStart + " à " + landStart);
        this.warm = new Zone("warm", 0xe8a23c, landStart + " à " + warmTop);
        this.normal = new Zone("normal", 0x6aa84f, warmTop + " à " + normalTop);
        this.cold = new Zone("cold", 0xdde6ee, normalTop + " à " + highest);
    }

    /**
     * The value under which the given share of the columns (those from the value {@code start} up) lie, or as close to
     * it as the values allow: the limit between two zones.
     *
     * @param heights how many columns have each (filtered) value
     */
    private static int heightAtShare(int[] heights, int landStart, double share) {
        long total = 0;
        for (int y = landStart; y < heights.length; y++) {
            total += heights[y];
        }
        double target = share * total;
        long below = 0;
        for (int y = landStart; y < heights.length; y++) {
            long with = below + heights[y];
            if (with >= target) {
                return Math.abs(below - target) <= Math.abs(with - target) ? y : y + 1;
            }
            below = with;
        }
        return Math.max(landStart, heights.length);
    }

    /**
     * The type of ocean of the sea column at world (x, z), told apart on the filtered distance to the shore. Only
     * meaningful for a column of the sea, lower than S - 3.
     */
    OceanType oceanTypeAt(int x, int z) {
        int level = seaSmoothing.at(x, z);
        return level < warmOceanLimit ? OceanType.WARM : level < oceanLimit ? OceanType.NORMAL : OceanType.COLD;
    }

    /** The zone of the column at world (x, z) whose top block is at Y {@code top}. */
    Zone at(int x, int z, int top) {
        if (top < beachStart) { // the sea
            return switch (oceanTypeAt(x, z)) {
                case WARM -> warmOcean;
                case NORMAL -> ocean;
                case COLD -> deepSea;
            };
        }
        return switch (landTypeAt(x, z, top)) {
            case BEACH -> beach;
            case WARM -> warm;
            case NORMAL -> normal;
            case COLD -> cold;
        };
    }

    /**
     * The type of land of the column at world (x, z) whose top block is at Y {@code top}: the beach, or one of the
     * three land zones (a beach too far from the sea is warm; the land zones are told apart on the filtered land).
     * Only meaningful for a column that is not in the sea, from S - 3 up.
     */
    LandType landTypeAt(int x, int z, int top) {
        if (top < landStart) {
            return seaDistance.isFar(x, z) ? LandType.WARM : LandType.BEACH;
        }
        int level = landSmoothing.at(x, z);
        return level < warmTop ? LandType.WARM : level < normalTop ? LandType.NORMAL : LandType.COLD;
    }

    /** Every zone, bottom to top. */
    List<Zone> all() {
        return List.of(deepSea, ocean, warmOcean, beach, warm, normal, cold);
    }
}
