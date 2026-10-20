package fr.basile.isohypse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The maximum height of the overworld, which the plugin changes with a datapack written at the launch of the server
 * (see {@link IsohypseBootstrap}): the vanilla dimension type and noise settings of the overworld, with a bigger
 * height. The bottom of the world stays at {@link #MIN_Y}.
 */
final class WorldHeight {

    /** The lowest Y of the overworld (not changed). */
    static final int MIN_Y = -64;
    /** The vanilla maximum height of the overworld: nothing to do when it is asked. */
    static final int VANILLA_MAX_HEIGHT = 320;
    /** The smallest maximum height accepted. */
    static final int LOWEST_MAX_HEIGHT = 128;
    /** The biggest one: the game wants {@code min_y + height} to be 2032 at most. */
    static final int HIGHEST_MAX_HEIGHT = 2032;

    private static final String DIMENSION_TYPE = "data/minecraft/dimension_type/overworld.json";
    private static final String NOISE_SETTINGS = "data/minecraft/worldgen/noise_settings/overworld.json";

    private WorldHeight() {
    }

    /**
     * The closest maximum height the game accepts to the one asked: a multiple of 16 (the height of a section) between
     * {@link #LOWEST_MAX_HEIGHT} and {@link #HIGHEST_MAX_HEIGHT}.
     */
    static int round(int requested) {
        long rounded = Math.round(requested / 16.0) * 16;
        return (int) Math.clamp(rounded, LOWEST_MAX_HEIGHT, HIGHEST_MAX_HEIGHT);
    }

    /**
     * Writes the datapack (the folder {@code pack}) that gives the overworld the maximum height {@code maxHeight}: the
     * vanilla overworld dimension type and noise settings, read from the server itself, with the height changed. The
     * files are rewritten at every launch.
     *
     * @return null if it worked, else why it did not (the server then keeps the vanilla height)
     */
    static String writePack(Path pack, int maxHeight) {
        int height = maxHeight - MIN_Y;
        try {
            String dimensionType = vanilla(DIMENSION_TYPE);
            String noiseSettings = vanilla(NOISE_SETTINGS);
            String version = vanilla("version.json");
            if (dimensionType == null || noiseSettings == null || version == null) {
                return "the vanilla data of the overworld cannot be found in the server";
            }
            Matcher format = Pattern.compile("\"data_major\"\\s*:\\s*(\\d+)").matcher(version);
            if (!format.find()) {
                return "the data pack format of the server cannot be read";
            }
            String dimension = withNumber(withNumber(dimensionType, "height", height), "logical_height", height);
            String noise = withNumber(noiseSettings, "height", height);
            if (dimension == null || noise == null) {
                return "the height of the overworld is not where expected in the vanilla data";
            }
            Path data = pack.resolve("data").resolve("minecraft");
            Files.createDirectories(data.resolve("dimension_type"));
            Files.createDirectories(data.resolve("worldgen").resolve("noise_settings"));
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Isohypse: height of the overworld\","
                    + "\"min_format\":" + format.group(1) + ",\"max_format\":" + format.group(1) + "}}");
            Files.writeString(data.resolve("dimension_type").resolve("overworld.json"), dimension);
            Files.writeString(data.resolve("worldgen").resolve("noise_settings").resolve("overworld.json"), noise);
            return null;
        } catch (IOException e) {
            return "writing the data pack failed: " + e;
        }
    }

    /** A file of the server (a resource of its jar), or null if there is none. */
    private static String vanilla(String path) throws IOException {
        try (InputStream in = WorldHeight.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The JSON with the number of the key replaced; null unless the key is found exactly once. */
    private static String withNumber(String json, String key, int value) {
        Pattern pattern = Pattern.compile("(\"" + key + "\"\\s*:\\s*)\\d+");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || matcher.find()) {
            return null;
        }
        return pattern.matcher(json).replaceFirst("$1" + value);
    }
}
