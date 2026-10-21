package fr.basile.isohypse;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * The scaling step: turns {@code heightmap_source.bin} (the real altitudes in metres, see
 * {@link HeightMapGenerator#fetchSource}) into {@code heightmap.bin}, the Y of the top block of every column of the
 * world, one 16-bit value per block, and writes {@code settings.json} next to it with every parameter used and their
 * values. The map is never in memory: the source is memory-mapped, the result is written band by band.
 *
 * <p>One factor {@code s} (blocks per metre) is used in x, z and height. Two unknowns, {@code s} and the altitude
 * {@code a} (metres) that lies at the sea level S (the columns lower than S are under water):
 * <ul>
 * <li>the highest point reaches the ceiling: {@code S + (max - a) * s = topY}, which gives {@code s} from
 * {@code a};</li>
 * <li>the sea (what is lower than {@code a}) covers a share of the map between the minimum share of the settings and
 * the maximum share of the settings. As the map is not cut, this share is the share of the samples lower than {@code a}, whatever
 * {@code s}: {@code a} is a quantile of the altitudes, read in a histogram.</li>
 * </ul>
 * The first try is the scale that fills the whole range (the lowest point at {@code bottomY}, the highest at
 * {@code topY}); it is kept if the sea share is acceptable, otherwise {@code a} is the quantile of the aimed share
 * (the aimed share of the settings).
 *
 * <p>Once {@code heightmap.bin} is written, the source file is deleted: it is made again (downloaded again) when the
 * settings change, which {@link #upToDate} tells by comparing {@code settings.json} with the parameters asked.
 */
final class HeightScale {

    static final String HEIGHTMAP_FILE = "heightmap.bin";
    static final String SETTINGS_FILE = "settings.json";

    /** Version of the format of the files and of this algorithm: part of the settings, so a change redoes the map. */
    private static final int FORMAT = 2;
    static final int HEIGHTMAP_MAGIC = 0x49534F4D;
    /** Bytes before the altitudes of heightmap_source.bin: magic, latitude, longitude, size, step, side. */
    private static final int SOURCE_HEADER = 4 + 8 + 8 + 4 + 4 + 4;

    static final int SEA_LEVEL = 63;
    /** No column is lower than this Y (a deeper sea floor is flattened). */
    static final int LOWEST_COLUMN = 0;
    /** Width in metres of a bin of the histogram of the altitudes. */
    private static final double BIN = 0.1;
    /** Rows computed at once (in parallel) before being written. */
    private static final int BAND = 256;

    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private HeightScale() {
    }

    /** The parameters that make {@code heightmap.bin}, for an area and a maximum height, as written in the settings. */
    private static String inputs(double lat, double lon, int size, int maxHeight, TerrainSettings settings) {
        int topY = maxHeight - 1;
        return inputs(lat, lon, size, HeightMapGenerator.stepFor(size), HeightMapGenerator.samplesFor(size), maxHeight, topY,
                SEA_LEVEL - settings.oceanDepth(), settings);
    }

    /**
     * Writes the parameters of the intermediate map in the {@code temp} block of {@code settings.json} (they do not
     * make {@code heightmap.bin}, so changing them redoes nothing else).
     */
    static void updateTemp(File dir, TerrainSettings settings) throws IOException {
        File file = new File(dir, SETTINGS_FILE);
        if (!file.exists()) {
            return;
        }
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("  \"temp\": \\{[^}]*\\}").matcher(text);
        String updated = block.find() ? block.replaceFirst(Matcher.quoteReplacement(settings.tempJson()))
                : text.replace("  \"results\":", settings.tempJson() + ",\n  \"results\":");
        if (!updated.equals(text)) {
            Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8);
        }
    }

    /** Whether {@code heightmap.bin} was made with exactly these parameters (and so needs nothing to be done). */
    static boolean upToDate(File dir, double lat, double lon, int size, int maxHeight, TerrainSettings settings)
            throws IOException {
        File settingsFile = new File(dir, SETTINGS_FILE);
        return new File(dir, HEIGHTMAP_FILE).exists() && settingsFile.exists()
                && Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8).contains(inputs(lat, lon, size, maxHeight, settings));
    }

    private static float at(MemorySegment altitude, long index) {
        return altitude.get(FLOAT, 4 * index);
    }

    /**
     * Scales {@code heightmap_source.bin} into {@code heightmap.bin} and {@code settings.json}, then deletes the source.
     *
     * @param dir the plugin folder, which holds the source and receives the two files
     * @param maxHeight the maximum height of the world (the highest column is {@code maxHeight - 1})
     */
    static void scale(File dir, double lat, double lon, int size, int maxHeight, TerrainSettings settings, Logger logger)
            throws IOException {
        File sourceFile = new File(dir, HeightMapGenerator.SOURCE_FILE);
        File heightmap = new File(dir, HEIGHTMAP_FILE);
        File settingsFile = new File(dir, SETTINGS_FILE);
        int r = HeightMapGenerator.stepFor(size);
        int n = HeightMapGenerator.samplesFor(size);
        int topY = maxHeight - 1;
        int bottomY = SEA_LEVEL - settings.oceanDepth();
        int shore = SEA_LEVEL; // the pivot of the scale: the sea is what is lower than the sea level (the beach is the business of TempLayout)
        String inputs = inputs(lat, lon, size, maxHeight, settings);
        Files.deleteIfExists(settingsFile.toPath()); // an interrupted run must not leave settings that match nothing

        try (DataInputStream in = new DataInputStream(new FileInputStream(sourceFile))) {
            if (in.readInt() != HeightMapGenerator.SOURCE_MAGIC) {
                throw new IOException("Fichier d'altitudes réelles invalide : " + sourceFile);
            }
            in.readDouble();
            in.readDouble();
            in.readInt();
            if (in.readInt() != r || in.readInt() != n) {
                throw new IOException("Le fichier d'altitudes réelles n'a pas le pas ni la taille de la zone demandée : " + sourceFile);
            }
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        double anchor;
        double scale;
        double seaShare;
        int[] extremes;
        int side;
        // A shared arena, closed before the source is deleted (a mapped file cannot be deleted on Windows).
        try (Arena arena = Arena.ofShared(); RandomAccessFile raf = new RandomAccessFile(sourceFile, "r");
             FileChannel channel = raf.getChannel()) {
            MemorySegment altitude = channel.map(FileChannel.MapMode.READ_ONLY, SOURCE_HEADER, 4L * n * n, arena);
            long count = (long) n * n;
            for (long i = 0; i < count; i++) {
                float v = at(altitude, i);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            long[] histogram = new long[(int) ((max - min) / BIN) + 1];
            for (long i = 0; i < count; i++) {
                histogram[(int) ((at(altitude, i) - min) / BIN)]++;
            }

            if (max - min < 1) { // flat: nothing to fit
                anchor = min + (shore - bottomY);
            } else {
                double fill = (topY - bottomY) / (max - min); // blocks per metre that fill the range
                anchor = min + (shore - bottomY) / fill;
                double share = share(histogram, min, count, anchor);
                if (share < settings.seaShareMin() || share > settings.seaShareMax()) {
                    anchor = quantile(histogram, min, count, settings.seaShareTarget());
                }
            }
            scale = max - min < 1 ? 1 : (topY - shore) / (max - anchor); // blocks per metre
            seaShare = share(histogram, min, count, anchor);
            side = Math.max(1, (int) Math.round((double) n * r * scale));
            extremes = write(altitude, n, side, (double) n / side, shore, anchor, scale, topY, heightmap);
        }
        Files.delete(sourceFile.toPath());

        String json = "{\n" + inputs + ",\n" + settings.tempJson() + ",\n  \"results\": {\n"
                + line("min_altitude_m", min, true) + line("max_altitude_m", max, true)
                + line("anchor_altitude_m", anchor, true) + line("scale_blocks_per_m", scale, true)
                + line("sea_share", seaShare, true) + line("width", side, true) + line("height", side, true)
                + line("lowest_y", extremes[0], true) + line("highest_y", extremes[1], false)
                + "  }\n}\n";
        Files.writeString(settingsFile.toPath(), json, StandardCharsets.UTF_8); // last: a stopped run leaves no valid settings
        logger.info(String.format(Locale.ROOT, "%s : altitudes %.1f à %.1f m, échelle 1 m = %.3f bloc (x, z et hauteur), "
                        + "carte %dx%d blocs (pas rognée), hauteurs %d à %d (prévu %d à %d), mer %.1f %% de la carte "
                        + "(sous y=%d, l'altitude %.1f m est au bord de la mer)",
                heightmap.getAbsolutePath(), min, max, scale, side, side, extremes[0], extremes[1], bottomY, topY,
                100 * seaShare, shore, anchor));
        logger.info(settingsFile.getAbsolutePath() + " : paramètres utilisés, " + HeightMapGenerator.SOURCE_FILE + " supprimé");
    }

    /** The parameters that make {@code heightmap.bin}, as the json written (and compared) in {@code settings.json}. */
    private static String inputs(double lat, double lon, int size, int step, int samples, int maxHeight, int topY,
                                 int bottomY, TerrainSettings settings) {
        return "  \"format\": " + FORMAT + ",\n"
                + "  \"source\": {\n"
                + line("latitude", lat, true) + line("longitude", lon, true) + line("size_m", size, true)
                + line("step_m", step, true) + line("samples_per_side", samples, false)
                + "  },\n  \"world\": {\n"
                + line("max_height", maxHeight, true) + line("top_y", topY, true) + line("sea_level", SEA_LEVEL, true)
                + line("ocean_depth", settings.oceanDepth(), true)
                + line("bottom_y", bottomY, true) + line("lowest_column", LOWEST_COLUMN, false)
                + "  },\n  \"sea_share\": {\n"
                + line("min", settings.seaShareMin(), true) + line("target", settings.seaShareTarget(), true)
                + line("max", settings.seaShareMax(), false) + "  }";
    }

    private static String line(String key, Number value, boolean comma) {
        String text = value instanceof Double d ? String.format(Locale.ROOT, "%s", d) : value.toString();
        return "    \"" + key + "\": " + text + (comma ? "," : "") + "\n";
    }

    /** The share of the samples lower than {@code altitude}, from the histogram. */
    private static double share(long[] histogram, double min, long total, double altitude) {
        double position = (altitude - min) / BIN;
        int bin = (int) Math.floor(position);
        if (bin < 0) {
            return 0;
        }
        if (bin >= histogram.length) {
            return 1;
        }
        long below = 0;
        for (int i = 0; i < bin; i++) {
            below += histogram[i];
        }
        return (below + histogram[bin] * (position - bin)) / total;
    }

    /** The altitude under which the given share of the samples lies, from the histogram. */
    private static double quantile(long[] histogram, double min, long total, double share) {
        double wanted = share * total;
        long below = 0;
        for (int i = 0; i < histogram.length; i++) {
            if (below + histogram[i] >= wanted && histogram[i] > 0) {
                return min + (i + (wanted - below) / histogram[i]) * BIN;
            }
            below += histogram[i];
        }
        return min + histogram.length * BIN;
    }

    /**
     * Writes {@code heightmap.bin}: magic, width, height (ints), then the Y of the top block of every column, one big-endian
     * short each, row by row, north (row 0) towards negative z. The file is written aside and moved in place. Returns the
     * lowest and the highest Y.
     */
    private static int[] write(MemorySegment altitude, int n, int side, double step, int shore, double anchor, double scale,
                               int topY, File out) throws IOException {
        File part = new File(out.getPath() + ".part");
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(part), 1 << 16))) {
            stream.writeInt(HEIGHTMAP_MAGIC);
            stream.writeInt(side);
            stream.writeInt(side);
            for (int from = 0; from < side; from += BAND) {
                int rows = Math.min(BAND, side - from);
                int first = from;
                short[] band = new short[rows * side];
                IntStream.range(0, rows).parallel().forEach(row -> {
                    int j = first + row;
                    for (int i = 0; i < side; i++) {
                        double meters = sample(altitude, n, (i + 0.5) * step, (j + 0.5) * step, step);
                        long y = Math.round(shore + (meters - anchor) * scale);
                        band[row * side + i] = (short) Math.clamp(y, LOWEST_COLUMN, topY);
                    }
                });
                for (short y : band) {
                    stream.writeShort(y);
                    lowest = Math.min(lowest, y);
                    highest = Math.max(highest, y);
                }
            }
        }
        Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new int[]{lowest, highest};
    }

    /**
     * Altitude at the source position (x, z), in samples (the centre of the block), for a block that covers
     * {@code stepSamples} samples: the average over the area when it is bigger than a sample, bilinear interpolation
     * otherwise.
     */
    private static double sample(MemorySegment altitude, int n, double x, double z, double stepSamples) {
        if (stepSamples > 1) {
            double x0 = Math.max(x - stepSamples / 2, 0);
            double x1 = Math.min(x + stepSamples / 2, n);
            double z0 = Math.max(z - stepSamples / 2, 0);
            double z1 = Math.min(z + stepSamples / 2, n);
            double sum = 0;
            double weight = 0;
            for (int pz = (int) z0; pz < Math.ceil(z1); pz++) {
                double wz = Math.min(pz + 1, z1) - Math.max(pz, z0);
                for (int px = (int) x0; px < Math.ceil(x1); px++) {
                    double w = wz * (Math.min(px + 1, x1) - Math.max(px, x0));
                    sum += w * at(altitude, (long) pz * n + px);
                    weight += w;
                }
            }
            return sum / weight;
        }
        double gx = Math.clamp(x - 0.5, 0, n - 1);
        double gz = Math.clamp(z - 0.5, 0, n - 1);
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        int x1 = Math.min(x0 + 1, n - 1);
        int z1 = Math.min(z0 + 1, n - 1);
        double fx = gx - x0;
        double fz = gz - z0;
        double top = at(altitude, (long) z0 * n + x0) * (1 - fx) + at(altitude, (long) z0 * n + x1) * fx;
        double bottom = at(altitude, (long) z1 * n + x0) * (1 - fx) + at(altitude, (long) z1 * n + x1) * fx;
        return top * (1 - fz) + bottom * fz;
    }
}
