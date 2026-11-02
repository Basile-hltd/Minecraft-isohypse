package fr.basile.isohypse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The datapack of the Realistic style, written at the launch of the server (see {@link IsohypseBootstrap}): the maximum
 * height of the overworld (the vanilla dimension type and noise settings of the overworld, with a bigger height; the
 * bottom of the world stays at {@link #MIN_Y}), and the caves of the game up in the mountains ({@link #HIGH_CAVES_FROM}).
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
    private static final String HIGH_CAVES = "data/minecraft/worldgen/carver/cave_extra_underground.json";
    /**
     * The carvers of the game start their caves under Y 180 (the canyons under 67): the caves of its mountains come from
     * its noise, which this generator does not use, so the mountains were solid. The carver {@code
     * cave_extra_underground} (in every biome of the surface, for more caves under Y 47, where the caverns are now) is
     * made to start its caves from this Y up to {@link #HIGH_CAVES_BELOW_TOP} blocks under the top of the world.
     */
    static final int HIGH_CAVES_FROM = 180;
    static final int HIGH_CAVES_BELOW_TOP = 16;

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
     * Writes the datapack (the folder {@code pack}) of the Realistic style for a maximum height of {@code maxHeight}: the
     * vanilla overworld dimension type and noise settings with that height (none for the vanilla height, the files of an
     * earlier launch are then removed), and the carver of the high caves. The files are read from the server itself and
     * rewritten at every launch.
     *
     * @return null if it worked, else why it did not (the server then keeps the vanilla height and caves)
     */
    static String writePack(Path pack, int maxHeight) {
        int height = maxHeight - MIN_Y;
        try {
            String version = vanilla("version.json");
            if (version == null) {
                return "the version of the data of the server cannot be found";
            }
            Matcher format = Pattern.compile("\"data_major\"\\s*:\\s*(\\d+)").matcher(version);
            if (!format.find()) {
                return "the data pack format of the server cannot be read";
            }
            String dimension = null;
            String noise = null;
            if (maxHeight != VANILLA_MAX_HEIGHT) {
                String dimensionType = vanilla(DIMENSION_TYPE);
                String noiseSettings = vanilla(NOISE_SETTINGS);
                if (dimensionType == null || noiseSettings == null) {
                    return "the vanilla data of the overworld cannot be found in the server";
                }
                dimension = withNumber(withNumber(dimensionType, "height", height), "logical_height", height);
                noise = withNumber(noiseSettings, "height", height);
                if (dimension == null || noise == null) {
                    return "the height of the overworld is not where expected in the vanilla data";
                }
            }
            String caves = highCaves(maxHeight);
            Files.createDirectories(pack);
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Isohypse: Realistic overworld\","
                    + "\"min_format\":" + format.group(1) + ",\"max_format\":" + format.group(1) + "}}");
            write(pack.resolve(DIMENSION_TYPE), dimension);
            write(pack.resolve(NOISE_SETTINGS), noise);
            write(pack.resolve(HIGH_CAVES), caves);
            return null;
        } catch (IOException e) {
            return "writing the data pack failed: " + e;
        }
    }

    /**
     * The carver {@code cave_extra_underground} of the game starting its caves from {@link #HIGH_CAVES_FROM} up to
     * {@link #HIGH_CAVES_BELOW_TOP} blocks under the top of a world of this maximum height; null (the vanilla one) if the
     * world is not that high or the vanilla file is not as expected.
     */
    private static String highCaves(int maxHeight) throws IOException {
        int top = maxHeight - 1 - HIGH_CAVES_BELOW_TOP;
        String carver = vanilla(HIGH_CAVES);
        if (carver == null || top <= HIGH_CAVES_FROM) {
            return null;
        }
        carver = withAnchor(carver, "min_inclusive", "above_bottom", HIGH_CAVES_FROM);
        return carver == null ? null : withAnchor(carver, "max_inclusive", "absolute", top);
    }

    /** Writes {@code text} to {@code file}, or deletes the file when it is null. */
    private static void write(Path file, String text) throws IOException {
        if (text == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /**
     * The JSON with the vertical anchor {@code "key": {"kind": n}} replaced by {@code "key": {"absolute": value}}; null
     * unless it is found exactly once.
     */
    private static String withAnchor(String json, String key, String kind, int value) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{\\s*\"" + kind + "\"\\s*:\\s*-?\\d+\\s*}");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find() || matcher.find()) {
            return null;
        }
        return pattern.matcher(json).replaceFirst(Matcher.quoteReplacement("\"" + key + "\": {\"absolute\": " + value + "}"));
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
