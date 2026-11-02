package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * The intermediate map: seven zones derived from the height map alone, with no link to the Minecraft biomes (yet).
 * With S the sea level, and L the highest point of the height map:
 * <ul>
 * <li>the sea, the columns lower than S - {@code beachDepth}, in the three {@link OceanType}s, that only depend on the
 * distance to the edge of the sea (not on the depth): ocean_warm along the shore, ocean_normal beyond it, ocean_cold in
 * the open. The two distances are searched so that the three zones cover the shares {@code warmOceanShare},
 * {@code normalOceanShare} and the rest of the sea columns. The distance is first filtered by a very wide low-pass
 * filter ({@link HeightSmoothing}, about {@code oceanBlur} blocks), so the borders are smooth;</li>
 * <li>beach: S - {@code beachDepth} to S + {@code beachHeight}, but more than {@code beachInlandDistance} blocks from
 * the nearest sea it is warm (decided on the raw heights, before any filtering);</li>
 * <li>the land above, from S + {@code beachHeight} to L, cut in three so that warm, normal and cold cover
 * {@code warmLandShare}, {@code normalLandShare} and the rest of the land columns of the height map (the limits are
 * searched), told apart on a narrower low-pass filtering of the land ({@code landBlur} blocks).</li>
 * </ul>
 * All of these are the {@link TerrainSettings}.
 */
final class TempLayout implements AutoCloseable {

    /** A zone and the Y range it covers (written in settings.json). */
    record Zone(String name, String range) {}

    /** The layout of a height map with these settings. */
    static TempLayout build(HeightMapData data, int seaLevel, TerrainSettings settings, File dir, Logger logger)
            throws IOException {
        int beachStart = settings.beachStart(seaLevel);
        int landStart = settings.landStart(seaLevel);
        // The sea zones: the distance of every sea column to the edge of the sea (what is not sea), filtered widely.
        HeightSmoothing seaSmoothing;
        try (ShoreDistance shore = new ShoreDistance(data, v -> v >= beachStart, 0, dir, logger, "distance au bord de la mer")) {
            seaSmoothing = new HeightSmoothing(data, shore::quarterBlocks,
                    (column, row) -> data.valueAt(column, row) < beachStart, settings.oceanBlur(), dir, logger, "flou de la mer");
        }
        ShoreDistance seaDistance = null;
        LakeMask lakes = null;
        RiverMask rivers = null;
        try {
            seaDistance = new ShoreDistance(data, v -> v < beachStart, settings.beachInlandDistance(), dir, logger,
                    "distance à la mer des plages");
            ShoreDistance far = seaDistance;
            // The land, and the beach columns too far from the sea (they are warm land, decided before the filtering).
            HeightSmoothing landSmoothing = new HeightSmoothing(data, data::valueAt, (column, row) -> {
                int v = data.valueAt(column, row);
                return v >= landStart || v >= beachStart && far.isFar(column - data.width() / 2, row - data.height() / 2);
            }, settings.landBlur(), dir, logger, "flou de la terre");
            // The lakes come last: the flats of the height map, over the zones above, but never in what is already the sea
            // of the map (up to the sea level).
            lakes = new LakeMask(data, settings.lakeMinSize(), settings.lakeSmoothing(), seaLevel, dir, logger, "lacs");
            // The rivers leave the lakes and follow the relief down, to a lower lake, the sea or another river.
            rivers = new RiverMask(data, lakes, settings.riverClimb(), settings.riverWidth(), settings.riverLakeSnap(),
                    seaLevel, dir, logger, "rivières");
            return new TempLayout(seaLevel, data.highest(), seaSmoothing, landSmoothing, seaDistance, lakes, rivers,
                    settings);
        } catch (IOException | RuntimeException | Error e) {
            seaSmoothing.close();
            if (rivers != null) {
                rivers.close();
            }
            if (lakes != null) {
                lakes.close();
            }
            if (seaDistance != null) {
                seaDistance.close();
            }
            throw e;
        }
    }

    /** Frees the scratch files of the three maps. */
    @Override
    public void close() {
        seaDistance.close();
        seaSmoothing.close();
        landSmoothing.close();
        lakes.close();
        rivers.close();
    }

    private final Zone deepSea;
    private final Zone ocean;
    private final Zone warmOcean;
    private final Zone beach;
    private final Zone warm;
    private final Zone normal;
    private final Zone cold;
    private final Zone lake;
    private final Zone river;

    private final ShoreDistance seaDistance;
    private final HeightSmoothing seaSmoothing;
    private final HeightSmoothing landSmoothing;
    private final LakeMask lakes;
    private final RiverMask rivers;
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
     * @param lakes the flats of the height map, which are lakes whatever the zone under them
     * @param rivers the courses from the lakes down to a lower lake or the sea, over the land zones (a lake is over them)
     */
    TempLayout(int seaLevel, int highest, HeightSmoothing seaSmoothing, HeightSmoothing landSmoothing,
               ShoreDistance seaDistance, LakeMask lakes, RiverMask rivers, TerrainSettings settings) {
        this.seaDistance = seaDistance;
        this.lakes = lakes;
        this.rivers = rivers;
        this.seaSmoothing = seaSmoothing;
        this.landSmoothing = landSmoothing;
        this.beachStart = settings.beachStart(seaLevel);
        this.landStart = settings.landStart(seaLevel);
        int[] sea = seaSmoothing.histogram();
        this.warmOceanLimit = heightAtShare(sea, 0, settings.warmOceanShare());
        this.oceanLimit = heightAtShare(sea, 0, settings.warmOceanShare() + settings.normalOceanShare());
        int[] land = landSmoothing.histogram();
        this.warmTop = heightAtShare(land, landStart, settings.warmLandShare());
        this.normalTop = heightAtShare(land, landStart, settings.warmLandShare() + settings.normalLandShare());
        int near = Math.round(warmOceanLimit / 4f);
        int far = Math.round(oceanLimit / 4f);
        this.deepSea = new Zone(OceanType.COLD.id(), "à plus de " + far + " du bord");
        this.ocean = new Zone(OceanType.NORMAL.id(), "à " + near + " à " + far + " du bord");
        this.warmOcean = new Zone(OceanType.WARM.id(), "à moins de " + near + " du bord");
        this.beach = new Zone("beach", beachStart + " à " + landStart);
        this.warm = new Zone("warm", landStart + " à " + warmTop);
        this.normal = new Zone("normal", warmTop + " à " + normalTop);
        this.cold = new Zone("cold", normalTop + " à " + highest);
        this.lake = new Zone("lake", "plat d'au moins " + settings.lakeMinSize() + " x " + settings.lakeMinSize() + " blocs");
        this.river = new Zone("river", "descend d'un lac, remonte au plus de " + settings.riverClimb() + " blocs");
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
        if (lakes.contains(x, z)) {
            return lake;
        }
        if (rivers.contains(x, z)) {
            return river;
        }
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

    /** The lakes of the map (the flats), to dig their beds into the heights. */
    LakeMask lakes() {
        return lakes;
    }

    /** The rivers of the map, to dig their channels into the heights. */
    RiverMask rivers() {
        return rivers;
    }

    /** Every zone, bottom to top, then the lakes and the rivers (they come over the others, the lakes over the rivers). */
    List<Zone> all() {
        return List.of(deepSea, ocean, warmOcean, beach, warm, normal, cold, lake, river);
    }
}
