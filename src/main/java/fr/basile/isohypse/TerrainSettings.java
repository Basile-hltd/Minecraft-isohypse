package fr.basile.isohypse;

import java.util.Locale;

/**
 * The parameters of the computation of the terrain that the config ({@code config.yml}, section {@code realistic}) lets
 * change: the scaling of the real altitudes ({@link HeightScale}) and the intermediate map ({@link TempLayout}, which
 * alone knows the beach). They
 * are written in {@code settings.json}. This class knows nothing of the game.
 *
 * @param oceanDepth blocks under the sea level where the flat sea (or the lowest point of an area without sea) is put
 * @param beachDepth blocks under the sea level where the beach starts (the sea zones end)
 * @param beachHeight blocks above the sea level where the beach ends (the land starts)
 * @param beachInlandDistance a beach column farther than this (blocks) from the nearest sea is warm land
 * @param oceanBlur about the standard deviation (blocks) of the low-pass filter of the distance to the shore, in the sea...
 * @param landBlur ...and of the land (narrower)
 * @param warmOceanShare the share of the sea columns that is ocean_warm...
 * @param normalOceanShare ...and ocean_normal; the rest of the sea is ocean_cold
 * @param warmLandShare the share of the land columns that is warm...
 * @param normalLandShare ...and normal; the rest of the land is cold
 */
record TerrainSettings(int oceanDepth, int beachDepth, int beachHeight, int beachInlandDistance, int oceanBlur, int landBlur, double warmOceanShare,
                       double normalOceanShare, double warmLandShare, double normalLandShare) {

    static final TerrainSettings DEFAULTS = new TerrainSettings(30, 3, 5, 20, 150, 50,
            1.0 / 3, 1.0 / 3, 0.35, 0.45);

    TerrainSettings {
        require(oceanDepth >= 0, "ocean_depth must be 0 or more");
        require(beachDepth >= 0 && beachHeight >= 0, "beach_depth and beach_height must be 0 or more");
        require(beachInlandDistance >= 0 && oceanBlur >= 0 && landBlur >= 0,
                "beach_inland_distance, ocean_blur and land_blur must be 0 or more");
        require(warmOceanShare >= 0 && normalOceanShare >= 0 && warmOceanShare + normalOceanShare <= 1,
                "warm_ocean_share and normal_ocean_share: 0 or more, 1 at most together");
        require(warmLandShare >= 0 && normalLandShare >= 0 && warmLandShare + normalLandShare <= 1,
                "warm_land_share and normal_land_share: 0 or more, 1 at most together");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException("Invalid terrain setting: " + message);
        }
    }

    /** The columns lower than this Y are the ocean (the beach starts here), for a sea level. */
    int beachStart(int seaLevel) {
        return seaLevel - beachDepth;
    }

    /** The land starts from this Y (the beach ends here), for a sea level. */
    int landStart(int seaLevel) {
        return seaLevel + beachHeight;
    }

    /** The {@code temp} block of {@code settings.json}: the parameters of the intermediate map. */
    String tempJson() {
        return "  \"temp\": {\n"
                + line("beach_depth", beachDepth) + line("beach_height", beachHeight)
                + line("beach_inland_distance", beachInlandDistance) + line("ocean_blur", oceanBlur)
                + line("land_blur", landBlur) + line("warm_ocean_share", warmOceanShare)
                + line("normal_ocean_share", normalOceanShare) + line("warm_land_share", warmLandShare)
                + lineLast("normal_land_share", normalLandShare) + "  }";
    }

    static String line(String key, Number value) {
        return "    \"" + key + "\": " + format(value) + ",\n";
    }

    static String lineLast(String key, Number value) {
        return "    \"" + key + "\": " + format(value) + "\n";
    }

    private static String format(Number value) {
        return value instanceof Double d ? String.format(Locale.ROOT, "%s", d) : value.toString();
    }
}
