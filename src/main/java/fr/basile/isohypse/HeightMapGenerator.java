package fr.basile.isohypse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a height map (16-bit PNG, 1 pixel = 1 block, value = Y level in blocks) of the area around a GPS point,
 * which becomes the centre of the map, plus the readable biome map (map.png).
 *
 * <p>The real altitudes of a {@code size} x {@code size} metre area (1 pixel = 1 m) are fetched, then the whole map
 * is scaled by one factor, the same in x, z and height, so that the lowest point lands on {@code bottomY} and the
 * highest on {@code topY}: the final map is smaller than {@code size} blocks when the relief is higher than the
 * world, bigger when it is lower. Ported from tools/GenerateHeightMap.java of the generation-height-map branch.
 *
 * <p>Sources: swisstopo swissALTI3D (Switzerland, 0.5 m), AWS Terrain Tiles / Terrarium (whole world, ~30 m)
 * where swisstopo has no data. North is up (negative z).
 */
public final class HeightMapGenerator {

    private static final double M_PER_DEG_LAT = 111320.0;
    /** The real altitudes of the area (before scaling), kept next to the height map so that only the scaling is redone. */
    /** The readable map: biomes in colour, plus a glossary. */
    public static final String MAP_FILE = "map.png";
    /** The cave biomes, at two depths. */
    public static final String CAVE_MAP_FILE = "cave_map.png";
    /** The intermediate map: zones from the altitude alone, no biome. */
    public static final String TEMP_MAP_FILE = "temp_map.png";
    /** The readable altitude only: land in grey, sea in blue. */
    public static final String HUMAN_FILE = "heightmap_human.png";
    /** The altitude in grey with a dot for each structure of the game (see {@link StructureMap}). */
    public static final String STRUCTURE_FILE = "structure.png";
    public static final String SOURCE_FILE = "heightmap_source.bin";
    private static final int SOURCE_MAGIC = 0x49534F48;
    /** Most samples per side of the real area: a bigger area is sampled every few metres instead of every metre. */
    private static final int MAX_SAMPLES = 4096;
    /** swissALTI3D (0.5 m, then 2 m tiles) is only used up to this sampling step (m); beyond, Terrarium is lighter. */
    private static final int MAX_SWISS_STEP = 4;
    /** Largest side (in blocks) of the final map: a bigger one keeps its scale and is cut at its edges, around its centre. */
    private static final int MAX_SIDE = 10000;
    /** The share of the map that is sea (a column under S - 3): the scale is adjusted until it is between these two. */
    private static final double SEA_SHARE_MIN = 0.12;
    private static final double SEA_SHARE_MAX = 0.22;
    /** The share aimed at (the middle of the window) when the scale has to be adjusted. */
    private static final double SEA_SHARE_TARGET = 0.17;
    /** The most tries of the search for the altitude of the shore. */
    private static final int SEA_FIT_TRIES = 40;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Logger logger;
    private final Path cache;
    private final int seaLevel;
    private final long seed;
    private final boolean tiling;
    private final int blendWidth;

    /**
     * @param seaLevel Y of the sea, where the beaches start in the readable preview
     * @param seed world seed, which the biome noises of the readable preview are drawn from
     * @param tiling whether the map is surrounded by rotated copies (see {@link HeightMapData})
     * @param blendWidth half width of the band smoothed along the seams of the 3x3 block (see {@link HeightMapData})
     */
    public HeightMapGenerator(Logger logger, Path cacheDir, int seaLevel, long seed, boolean tiling, int blendWidth) {
        this.logger = logger;
        this.cache = cacheDir;
        this.seaLevel = seaLevel;
        this.seed = seed;
        this.tiling = tiling;
        this.blendWidth = blendWidth;
    }

    /**
     * Writes {@code out} (16-bit height map, value = Y level) and the {@code map.png} biome map.
     *
     * <p>The real altitudes come from {@code heightmap_source.bin} next to {@code out} when it exists (only the
     * scaling is then done, nothing is downloaded), otherwise they are fetched and that file is written first.
     *
     * @param size    side in metres of the real area fetched before scaling (1 sample per metre, or per few
     *                metres beyond {@link #MAX_SAMPLES} samples per side)
     * @param bottomY world height given to the lowest point of the area
     * @param topY    world height given to the highest point of the area
     */
    public void generate(double lat, double lon, int size, File out, int bottomY, int topY) throws Exception {
        if (size <= 0) {
            throw new IllegalArgumentException("La taille doit être > 0");
        }
        if (Math.abs(lat) > 85) {
            throw new IllegalArgumentException("Latitude hors de la couverture (±85°)");
        }
        File dir = out.getAbsoluteFile().getParentFile();
        File sourceFile = new File(dir, SOURCE_FILE);
        Source source;
        if (sourceFile.exists()) {
            source = readSource(sourceFile);
            logger.info(sourceFile.getAbsolutePath() + " : altitudes réelles déjà présentes (zone de " + source.size
                    + " m, latitude " + source.lat + ", longitude " + source.lon + "), seule la mise à l'échelle est faite");
        } else {
            source = fetch(lat, lon, size);
            writeSource(sourceFile, source);
            logger.info(sourceFile.getAbsolutePath() + " : altitudes réelles enregistrées");
        }
        scaleAndWrite(source, out, bottomY, topY);
    }

    /** The real altitudes (m) of the area: n x n samples, one every r metres, centred on (lat, lon). */
    private record Source(double lat, double lon, int size, int r, int n, float[] altitude, double min, double max,
                          int fallback) {
    }

    /** Step 1: fetches the real altitudes, one sample every r metres (r = 1 unless the area is very big). */
    private Source fetch(double lat, double lon, int size) throws Exception {
        int r = Math.max(1, (int) Math.ceil((double) size / MAX_SAMPLES));
        int n = (int) Math.ceil((double) size / r);
        double areaM = (double) n * r;
        if (r > 1) {
            logger.info("Zone de " + size + " m : un point d'altitude tous les " + r + " m (" + n + "x" + n + " points)");
        }
        double cosLat = Math.cos(Math.toRadians(lat));
        Swiss swiss = r <= MAX_SWISS_STEP ? new Swiss(lat, lon, areaM / 2.0, r <= 1 ? 0.5 : 2.0) : null;
        Terrarium terrarium = new Terrarium(lat, r);
        float[] altitude = new float[n * n];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int fallback = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double east = (i - n / 2.0 + 0.5) * r;
                double north = -(j - n / 2.0 + 0.5) * r;
                double elevation = swiss == null ? Double.NaN : swiss.elevation(east, north);
                if (Double.isNaN(elevation)) {
                    if (swiss != null) {
                        fallback++;
                    }
                    elevation = terrarium.elevation(lat + north / M_PER_DEG_LAT,
                            lon + east / (M_PER_DEG_LAT * cosLat));
                }
                min = Math.min(min, elevation);
                max = Math.max(max, elevation);
                altitude[j * n + i] = (float) elevation;
            }
        }
        return new Source(lat, lon, size, r, n, altitude, min, max, fallback);
    }

    private static void writeSource(File file, Source source) throws IOException {
        Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(SOURCE_MAGIC);
            out.writeDouble(source.lat);
            out.writeDouble(source.lon);
            out.writeInt(source.size);
            out.writeInt(source.r);
            out.writeInt(source.n);
            for (float v : source.altitude) {
                out.writeFloat(v);
            }
        }
    }

    private static Source readSource(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != SOURCE_MAGIC) {
                throw new IOException("Fichier d'altitudes réelles invalide : " + file);
            }
            double lat = in.readDouble();
            double lon = in.readDouble();
            int size = in.readInt();
            int r = in.readInt();
            int n = in.readInt();
            float[] altitude = new float[n * n];
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int i = 0; i < altitude.length; i++) {
                altitude[i] = in.readFloat();
                min = Math.min(min, altitude[i]);
                max = Math.max(max, altitude[i]);
            }
            return new Source(lat, lon, size, r, n, altitude, min, max, 0);
        }
    }

    /**
     * A scale of the map: the altitude {@code anchor} (in the unit of the data) is put at the shore (S - 3), the
     * highest point at the ceiling, with one factor {@code scale} (blocks per unit) in x, z and height; and the share
     * of the final map that is sea with it.
     */
    private record SeaFit(double anchor, double scale, double share) {}

    /**
     * Looks for the scale that makes the sea a share of the map between {@link #SEA_SHARE_MIN} and
     * {@link #SEA_SHARE_MAX} (17 % aimed at). The scale first tried is the one that fills the whole range of heights (the
     * lowest point at {@code bottomY}, the highest at {@code topY}); if the sea is out of the range, the altitude that
     * lies at the shore is moved by bisection (higher for more sea, lower for less), every time with the highest point
     * at the ceiling, until the share is right, or the tries run out and the best one is kept.
     *
     * <p>The share is counted on the part of the data that the final map keeps: a map over {@link #MAX_SIDE} blocks is cut
     * at its edges, which depends on the scale.
     *
     * @param data the altitudes of a grid of {@code width x height} samples, {@code metersPerSample} metres apart
     * @param initial the altitude put at the shore by the scale that fills the range
     */
    private SeaFit fitSea(float[] data, int width, int height, double metersPerSample, double min, double max, int topY,
                          double initial) {
        int shore = TempLayout.beachStart(seaLevel);
        if (max - min < 1) { // flat: nothing to fit
            return new SeaFit(initial, 1, seaShare(data, width, height, metersPerSample, initial, 1));
        }
        SeaFit best = tried(data, width, height, metersPerSample, max, shore, topY, initial);
        if (best.share >= SEA_SHARE_MIN && best.share <= SEA_SHARE_MAX) {
            return best;
        }
        double low = min;
        double high = max - 0.001 * (max - min);
        for (int i = 0; i < SEA_FIT_TRIES; i++) {
            double anchor = (low + high) / 2;
            SeaFit fit = tried(data, width, height, metersPerSample, max, shore, topY, anchor);
            if (Math.abs(fit.share - SEA_SHARE_TARGET) < Math.abs(best.share - SEA_SHARE_TARGET)) {
                best = fit;
            }
            if (fit.share >= SEA_SHARE_MIN && fit.share <= SEA_SHARE_MAX) {
                return fit;
            }
            if (fit.share < SEA_SHARE_TARGET) {
                low = anchor; // not enough sea: a higher altitude at the shore
            } else {
                high = anchor;
            }
        }
        return best;
    }

    /** The scale that puts {@code anchor} at the shore and {@code max} at the ceiling, and its share of sea. */
    private SeaFit tried(float[] data, int width, int height, double metersPerSample, double max, int shore, int topY,
                         double anchor) {
        double scale = (topY - shore) / (max - anchor); // blocks per metre
        return new SeaFit(anchor, scale, seaShare(data, width, height, metersPerSample, anchor, scale));
    }

    /**
     * The share of the final map that is sea (an altitude lower than {@code anchor}, which is at the shore), counted
     * on the samples of the part of the grid that the map keeps, for a scale of {@code scale} blocks per metre.
     */
    private static double seaShare(float[] data, int width, int height, double metersPerSample, double anchor, double scale) {
        int fullWidth = Math.max(1, (int) Math.round(width * metersPerSample * scale));
        int fullHeight = Math.max(1, (int) Math.round(height * metersPerSample * scale));
        int keptWidth = (int) Math.max(1, Math.round((double) width * Math.min(fullWidth, MAX_SIDE) / fullWidth));
        int keptHeight = (int) Math.max(1, Math.round((double) height * Math.min(fullHeight, MAX_SIDE) / fullHeight));
        int fromX = (width - keptWidth) / 2;
        int fromZ = (height - keptHeight) / 2;
        long sea = java.util.stream.IntStream.range(fromZ, fromZ + keptHeight).parallel().mapToLong(row -> {
            long count = 0;
            for (int column = fromX; column < fromX + keptWidth; column++) {
                if (data[row * width + column] < anchor) {
                    count++;
                }
            }
            return count;
        }).sum();
        return (double) sea / ((long) keptWidth * keptHeight);
    }

    /** Step 2: one scale for x, z and height, so that [min, max] fits [bottomY, topY]; writes the PNGs. */
    private void scaleAndWrite(Source source, File out, int bottomY, int topY) throws IOException {
        float[] altitude = source.altitude;
        int n = source.n;
        double min = source.min;
        double max = source.max;
        double areaM = (double) n * source.r;
        int shore = TempLayout.beachStart(seaLevel);
        double fillScale = max - min < 1 ? 1 : (topY - bottomY) / (max - min); // blocks per metre that fill the range
        SeaFit fit = fitSea(altitude, n, n, source.r, min, max, topY, min + (shore - bottomY) / fillScale);
        double scale = fit.scale; // blocks per metre, the same in x, z and height
        int fullSize = Math.max(1, (int) Math.round(areaM * scale));
        int outSize = Math.min(fullSize, MAX_SIDE); // a bigger map keeps its scale and is cut at the edges
        int offset = (fullSize - outSize) / 2;
        double step = (double) n / fullSize; // samples per block along x and z

        BufferedImage map = new BufferedImage(outSize, outSize, BufferedImage.TYPE_USHORT_GRAY);
        var raster = map.getRaster();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                double meters = sample(altitude, n, n, (i + offset + 0.5) * step, (j + offset + 0.5) * step, step, step);
                long y = Math.round(shore + (meters - fit.anchor) * scale);
                int value = (int) Math.clamp(y, 0, 65535);
                lowest = Math.min(lowest, value);
                highest = Math.max(highest, value);
                raster.setSample(i, j, 0, value);
            }
        }

        if (!ImageIO.write(map, "png", out)) {
            throw new IllegalStateException("Écriture PNG impossible");
        }
        logger.info(String.format(Locale.ROOT, "%s : altitudes %.1f à %.1f m, échelle 1 m = %.3f bloc (x, z et hauteur), "
                        + "zone %dx%d m, carte %dx%d blocs, hauteurs %d à %d blocs (prévu %d à %d)",
                out.getAbsolutePath(), min, max, scale, (int) areaM, (int) areaM, outSize, outSize, lowest, highest,
                bottomY, topY));
        logger.info(String.format(Locale.ROOT, "Mer : %.1f %% de la carte (sous y=%d), l'altitude %.1f m est mise au bord de la mer",
                100 * fit.share, shore, fit.anchor));
        if (fullSize > outSize) {
            logger.warning("Attention : la carte ferait " + fullSize + " blocs de côté, elle est rognée à " + outSize
                    + " (les bords sont coupés, l'échelle est gardée), les hauteurs extrêmes peuvent être coupées.");
        }

        writeMap(map, out);

        if (source.fallback > 0) {
            logger.warning("Attention : " + source.fallback
                    + " pixels hors couverture swisstopo, remplacés par Terrarium.");
        }
    }

    /**
     * Value at the source position (x, z) in pixels (centre of the block), for a block that covers {@code stepX} x
     * {@code stepZ} source pixels of a {@code width} x {@code height} grid: area average when the block is bigger than
     * a pixel, bilinear interpolation otherwise.
     */
    private static double sample(float[] values, int width, int height, double x, double z, double stepX, double stepZ) {
        if (stepX > 1 || stepZ > 1) {
            double x0 = Math.max(x - stepX / 2, 0);
            double x1 = Math.min(x + stepX / 2, width);
            double z0 = Math.max(z - stepZ / 2, 0);
            double z1 = Math.min(z + stepZ / 2, height);
            double sum = 0;
            double weight = 0;
            for (int pz = (int) z0; pz < Math.ceil(z1); pz++) {
                double wz = Math.min(pz + 1, z1) - Math.max(pz, z0);
                for (int px = (int) x0; px < Math.ceil(x1); px++) {
                    double w = wz * (Math.min(px + 1, x1) - Math.max(px, x0));
                    sum += w * values[pz * width + px];
                    weight += w;
                }
            }
            return sum / weight;
        }
        double gx = Math.clamp(x - 0.5, 0, width - 1);
        double gz = Math.clamp(z - 0.5, 0, height - 1);
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        int x1 = Math.min(x0 + 1, width - 1);
        int z1 = Math.min(z0 + 1, height - 1);
        double fx = gx - x0;
        double fz = gz - z0;
        double top = values[z0 * width + x0] * (1 - fx) + values[z0 * width + x1] * fx;
        double bottom = values[z1 * width + x0] * (1 - fx) + values[z1 * width + x1] * fx;
        return top * (1 - fz) + bottom * fz;
    }

    /**
     * Writes {@code heightmap_human.png} next to {@code out}: the altitude only, no biome, for the whole 3x3 block. Land in grey, contrast
     * stretched between the sea level and the highest point; the sea (columns lower than the sea level) in blue, from
     * light (shallow) to dark (deep).
     */
    private void writeHuman(BufferedImage heightMap, File out, HeightMapData data) throws IOException {
        var raster = heightMap.getRaster();
        int width = heightMap.getWidth();
        int height = heightMap.getHeight();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int v = raster.getSample(i, j, 0);
                lowest = Math.min(lowest, v);
                highest = Math.max(highest, v);
            }
        }
        int landLow = Math.min(Math.max(lowest, seaLevel), highest); // grey scale starts at the sea level
        int maxDepth = Math.max(1, seaLevel - lowest);
        // The whole 3x3 block (the map and its eight rotated copies), sampled every few blocks if it is huge.
        int tiles = data.tilesPerSide();
        int step = 1; // one pixel per block
        BufferedImage preview = new BufferedImage(tiles * width / step, tiles * height / step, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < preview.getHeight(); j++) {
            for (int i = 0; i < preview.getWidth(); i++) {
                int v = data.heightAt(i * step - (tiles / 2) * width - width / 2, j * step - (tiles / 2) * height - height / 2);
                int rgb;
                if (v < seaLevel) {
                    double depth = (double) (seaLevel - v) / maxDepth; // 0 shallow, 1 deepest
                    rgb = (lerp(130, 10, depth) << 16) | (lerp(200, 40, depth) << 8) | lerp(255, 120, depth);
                } else {
                    int grey = Math.round(255f * (v - landLow) / Math.max(1, highest - landLow));
                    rgb = (grey << 16) | (grey << 8) | grey;
                }
                preview.setRGB(i, j, rgb);
            }
        }
        File human = new File(out.getAbsoluteFile().getParentFile(), HUMAN_FILE);
        ImageIO.write(preview, "png", human);
        logger.info(human.getAbsolutePath() + " : terre en gris, contraste étiré de " + landLow + " (noir) à " + highest
                + " (blanc) ; mer en bleu sous le niveau " + seaLevel + ", de clair à foncé jusqu'à "
                + (lowest < seaLevel ? seaLevel - lowest + " blocs de profondeur" : "aucune zone immergée"));
    }

    private static int lerp(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    /**
     * Writes {@code map.png} next to {@code out}: the biome of every column of the 3x3 block (the height map and its
     * eight rotated copies) with a glossary on the side (see {@link MapRenderer}).
     */
    private void writeMap(BufferedImage heightMap, File out) throws IOException {
        HeightMapData data = HeightMapData.from(heightMap.getRaster(), tiling, blendWidth, seed);
        writeHuman(heightMap, out, data);
        File map = new File(out.getAbsoluteFile().getParentFile(), MAP_FILE);
        TempLayout temp = TempLayout.build(data, seaLevel); // the oceans of the biome map follow its zones
        BiomeLayout layout = new BiomeLayout(seaLevel, seed, temp);
        try {
            StructureMap.write(data, layout, seaLevel, seed, new File(out.getAbsoluteFile().getParentFile(), STRUCTURE_FILE), logger);
        } catch (IOException | RuntimeException e) { // a preview: never worth stopping the world for
            logger.warning("structure.png impossible : " + e);
        }
        ImageIO.write(MapRenderer.surface(data, layout, logger), "png", map);
        File caveMap = new File(out.getAbsoluteFile().getParentFile(), CAVE_MAP_FILE);
        ImageIO.write(MapRenderer.caves(data, layout, logger), "png", caveMap);
        logger.info(caveMap.getAbsolutePath() + " : carte des biomes souterrains, à deux profondeurs");
        File tempMap = new File(out.getAbsoluteFile().getParentFile(), TEMP_MAP_FILE);
        ImageIO.write(MapRenderer.temp(data, temp, logger), "png", tempMap);
        logger.info(tempMap.getAbsolutePath() + " : carte intermédiaire, zones selon l'altitude seule");
        logger.info(map.getAbsolutePath() + " : carte des biomes (carte d'origine et ses huit copies) avec glossaire, "
                + "niveau de la mer " + seaLevel + ", hauteur max " + data.highest());
    }

    /**
     * Checks an existing height map against the wanted heights and rescales it if needed, then rewrites
     * {@code map.png}. The exact real altitudes ({@code heightmap_source.bin}) are used when present; otherwise the
     * map itself is rescaled (same factor in x, z and height), which is less precise on the heights.
     */
    public void adjust(File out, int bottomY, int topY) throws Exception {
        File sourceFile = new File(out.getAbsoluteFile().getParentFile(), SOURCE_FILE);
        if (sourceFile.exists()) {
            Source source = readSource(sourceFile);
            logger.info(sourceFile.getAbsolutePath() + " : altitudes réelles présentes, la height map est refaite à l'échelle");
            scaleAndWrite(source, out, bottomY, topY);
            return;
        }
        BufferedImage image = ImageIO.read(out);
        if (image == null) {
            throw new IOException("Height map illisible : " + out);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        float[] values = new float[width * height];
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int v = image.getRaster().getSample(i, j, 0);
                values[j * width + i] = v;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        int shore = TempLayout.beachStart(seaLevel);
        double current = seaShare(values, width, height, 1, shore, 1);
        if (hi == topY && current >= SEA_SHARE_MIN && current <= SEA_SHARE_MAX) {
            logger.info(String.format(Locale.ROOT, "%s : hauteurs %d à %d, mer %.1f %%, l'échelle est déjà bonne",
                    out.getAbsolutePath(), lo, hi, 100 * current));
            writeMap(image, out);
            return;
        }
        if (Math.max(width, height) >= MAX_SIDE) {
            // A map this big was cut at its edges, which may have taken the extreme heights: its scale cannot be checked.
            logger.info(out.getAbsolutePath() + " : carte rognée à " + MAX_SIDE + " blocs, hauteurs " + lo + " à " + hi
                    + " (voulu " + bottomY + " à " + topY + "), l'échelle n'est pas vérifiée (supprimer la height map pour la refaire)");
            writeMap(image, out);
            return;
        }
        double fillScale = hi - lo < 1 ? 1 : (topY - bottomY) / (double) (hi - lo);
        // The data are heights already: the scale that fills the range is the one tried first, unless the map already
        // reaches the ceiling, in which case the first try is the map as it is (the shore at S - 3).
        double initial = hi == topY ? shore : lo + (shore - bottomY) / fillScale;
        SeaFit fit = fitSea(values, width, height, 1, lo, hi, topY, initial);
        double scale = fit.scale;
        int fullWidth = Math.max(1, (int) Math.round(width * scale));
        int fullHeight = Math.max(1, (int) Math.round(height * scale));
        int outWidth = Math.min(fullWidth, MAX_SIDE); // a bigger map keeps its scale and is cut at the edges
        int outHeight = Math.min(fullHeight, MAX_SIDE);
        int offsetX = (fullWidth - outWidth) / 2;
        int offsetZ = (fullHeight - outHeight) / 2;
        double stepX = (double) width / fullWidth;
        double stepZ = (double) height / fullHeight;
        BufferedImage map = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_USHORT_GRAY);
        var raster = map.getRaster();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int j = 0; j < outHeight; j++) {
            for (int i = 0; i < outWidth; i++) {
                double y = sample(values, width, height, (i + offsetX + 0.5) * stepX, (j + offsetZ + 0.5) * stepZ, stepX, stepZ);
                int value = (int) Math.clamp(Math.round(shore + (y - fit.anchor) * scale), 0, 65535);
                lowest = Math.min(lowest, value);
                highest = Math.max(highest, value);
                raster.setSample(i, j, 0, value);
            }
        }
        if (!ImageIO.write(map, "png", out)) {
            throw new IllegalStateException("Écriture PNG impossible");
        }
        logger.info(String.format(Locale.ROOT, "%s : hauteurs %d à %d, voulu %d à %d, carte %dx%d remise à l'échelle "
                        + "x%.3f (x, z et hauteur) en %dx%d blocs, hauteurs %d à %d",
                out.getAbsolutePath(), lo, hi, bottomY, topY, width, height, scale, outWidth, outHeight, lowest, highest));
        logger.info(String.format(Locale.ROOT, "Mer : %.1f %% de la carte (sous y=%d), la hauteur %.1f est mise au bord de la mer",
                100 * fit.share, shore, fit.anchor));
        writeMap(map, out);
        if (fullWidth > outWidth || fullHeight > outHeight) {
            logger.warning("Attention : la carte ferait " + fullWidth + "x" + fullHeight + " blocs, elle est rognée à "
                    + outWidth + "x" + outHeight + " (les bords sont coupés, l'échelle est gardée).");
        }
    }

    private Path download(String url, String name) throws Exception {
        Path file = cache.resolve(name);
        if (!Files.exists(file)) {
            Files.createDirectories(cache);
            logger.info("Téléchargement " + url);
            Path part = cache.resolve(name + ".part");
            HttpResponse<Path> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) {
                Files.deleteIfExists(part);
                throw new IllegalStateException(url + " : HTTP " + response.statusCode());
            }
            Files.move(part, file);
        }
        return file;
    }

    // ---------------------------------------------------------------------------------------
    // swisstopo swissALTI3D: 1 km GeoTIFF tiles in LV95 (EPSG:2056), float32 altitude.
    // ---------------------------------------------------------------------------------------
    private final class Swiss {
        final double centerE;
        final double centerN;
        final double res;
        final int pixelsPerTile;
        final Map<String, String> urls = new HashMap<>();      // "kx-ky" -> GeoTIFF URL
        final Map<String, Raster> tiles = new HashMap<>();

        Swiss(double lat, double lon, double half, double res) throws Exception {
            this.res = res;
            this.pixelsPerTile = (int) (1000 / res);
            double[] c = toLv95(lat, lon);
            centerE = c[0];
            centerN = c[1];

            // Tiles found through the STAC API: WGS84 bbox of the map with a margin.
            double pad = half * 1.05 + 50;
            double cosLat = Math.cos(Math.toRadians(lat));
            double dLat = pad / M_PER_DEG_LAT;
            double dLon = pad / (M_PER_DEG_LAT * cosLat);
            String next = String.format(Locale.ROOT,
                    "https://data.geo.admin.ch/api/stac/v1/collections/ch.swisstopo.swissalti3d/items"
                            + "?bbox=%f,%f,%f,%f&limit=100", lon - dLon, lat - dLat, lon + dLon, lat + dLat);
            Map<String, Integer> years = new HashMap<>();
            Pattern id = Pattern.compile("swissalti3d_(\\d{4})_(\\d+-\\d+)");
            Pattern asset = Pattern.compile("\"href\"\\s*:\\s*\"([^\"]*_" + (res == 0.5 ? "0\\.5" : "2")
                    + "_2056_\\d+\\.tif)\"");
            while (next != null) {
                String body = HTTP.send(HttpRequest.newBuilder(URI.create(next)).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                // Split by feature: each feature "id" comes before its assets.
                for (String feature : body.split("\"type\"\\s*:\\s*\"Feature\"")) {
                    Matcher m = id.matcher(feature);
                    Matcher a = asset.matcher(feature);
                    if (m.find() && a.find()) {
                        int year = Integer.parseInt(m.group(1));
                        String key = m.group(2);
                        if (year > years.getOrDefault(key, 0)) {
                            years.put(key, year);
                            urls.put(key, a.group(1));
                        }
                    }
                }
                Matcher n = Pattern.compile("\"rel\"\\s*:\\s*\"next\"[^}]*?\"href\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                Matcher n2 = Pattern.compile("\"href\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"rel\"\\s*:\\s*\"next\"").matcher(body);
                next = n.find() ? n.group(1) : n2.find() ? n2.group(1) : null;
            }
            logger.info("swisstopo swissALTI3D : " + urls.size() + " dalle(s) de 1 km, résolution " + res + " m");
        }

        /** Altitude (m) at the (east, north) offset in metres from the centre, or NaN outside the coverage. */
        double elevation(double east, double north) throws Exception {
            double gx = (centerE + east) / res - 0.5;
            double gy = (centerN + north) / res - 0.5;
            int x0 = (int) Math.floor(gx);
            int y0 = (int) Math.floor(gy);
            double fx = gx - x0;
            double fy = gy - y0;
            double a = pixel(x0, y0);
            double b = pixel(x0 + 1, y0);
            double c = pixel(x0, y0 + 1);
            double d = pixel(x0 + 1, y0 + 1);
            if (Double.isNaN(a + b + c + d)) {
                // Next to a missing tile: take the nearest pixel if there is one.
                return pixel((int) Math.round(gx), (int) Math.round(gy));
            }
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
        }

        /** Global pixel (gx eastwards, gy northwards); NaN if absent or without data. */
        double pixel(int gx, int gy) throws Exception {
            int kx = Math.floorDiv(gx, pixelsPerTile);
            int ky = Math.floorDiv(gy, pixelsPerTile);
            Raster tile = tile(kx, ky);
            if (tile == null) {
                return Double.NaN;
            }
            int col = gx - kx * pixelsPerTile;
            int row = pixelsPerTile - 1 - (gy - ky * pixelsPerTile); // row 0 is the north edge
            float v = tile.getSampleFloat(col, row, 0);
            return v < -1000 || Float.isNaN(v) ? Double.NaN : v;
        }

        /** Tile (kx, ky) = (E / 1000, N / 1000) in LV95, as in the file names (2494-1113). */
        Raster tile(int kx, int ky) throws Exception {
            String key = kx + "-" + ky;
            if (tiles.containsKey(key)) {
                return tiles.get(key);
            }
            String url = urls.get(key);
            Raster raster = null;
            if (url != null) {
                Path file = download(url, url.substring(url.lastIndexOf('/') + 1));
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) {
                    throw new IllegalStateException("GeoTIFF illisible : " + file);
                }
                raster = image.getRaster();
            }
            tiles.put(key, raster);
            return raster;
        }

        /** WGS84 -> LV95 (approximate swisstopo formulas, ~1 m precision). */
        double[] toLv95(double lat, double lon) {
            double p = (lat * 3600 - 169028.66) / 10000;
            double l = (lon * 3600 - 26782.5) / 10000;
            double e = 2600072.37 + 211455.93 * l - 10938.51 * l * p - 0.36 * l * p * p - 44.54 * l * l * l;
            double n = 1200147.07 + 308807.95 * p + 3745.25 * l * l + 76.63 * p * p
                    - 194.56 * l * l * p + 119.79 * p * p * p;
            return new double[]{e, n};
        }
    }

    // ---------------------------------------------------------------------------------------
    // AWS Terrain Tiles (Terrarium): whole world, altitude encoded in the RGB channels.
    // ---------------------------------------------------------------------------------------
    private final class Terrarium {
        static final int MAX_ZOOM = 15;
        static final double EARTH = 6378137.0;
        final int zoom;
        final Map<String, BufferedImage> tiles = new HashMap<>();

        Terrarium(double lat, double step) {
            double cosLat = Math.cos(Math.toRadians(lat));
            int z = (int) Math.min(MAX_ZOOM, Math.ceil(Math.log(2 * Math.PI * EARTH * cosLat / 256.0 / step) / Math.log(2)));
            zoom = Math.max(z, 0);
        }

        /** Altitude (m), bilinearly interpolated between the tile pixels. */
        double elevation(double lat, double lon) throws Exception {
            double n = (1 << zoom) * 256.0;
            double px = (lon + 180.0) / 360.0 * n - 0.5;
            double sin = Math.sin(Math.toRadians(lat));
            double py = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * n - 0.5;
            int x0 = (int) Math.floor(px);
            int y0 = (int) Math.floor(py);
            double fx = px - x0;
            double fy = py - y0;
            double a = pixel(x0, y0);
            double b = pixel(x0 + 1, y0);
            double c = pixel(x0, y0 + 1);
            double d = pixel(x0 + 1, y0 + 1);
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
        }

        double pixel(int gx, int gy) throws Exception {
            int max = (1 << zoom) * 256 - 1;
            gx = Math.floorMod(gx, max + 1);
            gy = Math.clamp(gy, 0, max);
            BufferedImage tile = tile(gx / 256, gy / 256);
            int rgb = tile.getRGB(gx % 256, gy % 256);
            return ((rgb >> 16) & 0xFF) * 256.0 + ((rgb >> 8) & 0xFF) + (rgb & 0xFF) / 256.0 - 32768.0;
        }

        BufferedImage tile(int x, int y) throws Exception {
            String key = zoom + "/" + x + "/" + y;
            BufferedImage tile = tiles.get(key);
            if (tile == null) {
                Path file = download("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/" + key + ".png",
                        "terrarium_" + zoom + "_" + x + "_" + y + ".png");
                tile = ImageIO.read(file.toFile());
                tiles.put(key, tile);
            }
            return tile;
        }
    }
}
