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
 * which becomes the centre of the map, plus a readable 8-bit preview (_human.png).
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
    public static final String SOURCE_FILE = "heightmap_source.bin";
    private static final int SOURCE_MAGIC = 0x49534F48;
    /** Most samples per side of the real area: a bigger area is sampled every few metres instead of every metre. */
    private static final int MAX_SAMPLES = 4096;
    /** swissALTI3D (0.5 m, then 2 m tiles) is only used up to this sampling step (m); beyond, Terrarium is lighter. */
    private static final int MAX_SWISS_STEP = 4;
    /** Largest side (in blocks) of the final map, so that a nearly flat area is not magnified without limit. */
    private static final int MAX_SIDE = 8192;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Logger logger;
    private final Path cache;
    private final int seaLevel;

    /** @param seaLevel Y of the sea, shown in blue (darker when deeper) in the readable preview */
    public HeightMapGenerator(Logger logger, Path cacheDir, int seaLevel) {
        this.logger = logger;
        this.cache = cacheDir;
        this.seaLevel = seaLevel;
    }

    /**
     * Writes {@code out} (16-bit height map, value = Y level) and its {@code _human.png} preview.
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

    /** Step 2: one scale for x, z and height, so that [min, max] fits [bottomY, topY]; writes the PNGs. */
    private void scaleAndWrite(Source source, File out, int bottomY, int topY) throws IOException {
        float[] altitude = source.altitude;
        int n = source.n;
        double min = source.min;
        double max = source.max;
        double areaM = (double) n * source.r;
        double scale = max - min < 1 ? 1 : (topY - bottomY) / (max - min); // blocks per metre
        boolean capped = areaM * scale > MAX_SIDE;
        if (capped) {
            scale = MAX_SIDE / areaM;
        }
        int outSize = Math.max(1, (int) Math.round(areaM * scale));
        double step = (double) n / outSize; // samples per block along x and z

        BufferedImage map = new BufferedImage(outSize, outSize, BufferedImage.TYPE_USHORT_GRAY);
        var raster = map.getRaster();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                double meters = sample(altitude, n, n, (i + 0.5) * step, (j + 0.5) * step, step, step);
                long y = Math.round(bottomY + (meters - min) * scale);
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

        writeHuman(map, out);

        if (source.fallback > 0) {
            logger.warning("Attention : " + source.fallback
                    + " pixels hors couverture swisstopo, remplacés par Terrarium.");
        }
        if (capped) {
            logger.warning("Attention : relief trop plat, la carte est limitée à " + MAX_SIDE
                    + " blocs de côté, la hauteur ne remplit pas tout le monde.");
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
     * Writes the readable preview next to {@code out}: land in grey, contrast stretched between the sea level and the
     * highest point; the sea (columns lower than the sea level) in blue, from light (shallow) to dark (deep).
     */
    private void writeHuman(BufferedImage map, File out) throws IOException {
        File dir = out.getAbsoluteFile().getParentFile();
        String name = out.getName();
        String stem = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        var raster = map.getRaster();
        int width = map.getWidth();
        int height = map.getHeight();
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
        BufferedImage preview = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int v = raster.getSample(i, j, 0);
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
        File human = new File(dir, stem + "_human.png");
        ImageIO.write(preview, "png", human);
        logger.info(human.getAbsolutePath() + " : terre en gris, contraste étiré de " + landLow + " (noir) à " + highest
                + " (blanc) ; mer en bleu sous le niveau " + seaLevel + ", de clair à foncé jusqu'à "
                + (lowest < seaLevel ? seaLevel - lowest + " blocs de profondeur" : "aucune zone immergée"));
    }

    private static int lerp(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    /**
     * Checks an existing height map against the wanted heights and rescales it if needed, then rewrites its
     * {@code _human.png}. The exact real altitudes ({@code heightmap_source.bin}) are used when present; otherwise the
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
        if (lo == bottomY && hi == topY) {
            logger.info(out.getAbsolutePath() + " : hauteurs " + lo + " à " + hi + ", l'échelle est déjà bonne");
            writeHuman(image, out);
            return;
        }
        double scale = hi - lo < 1 ? 1 : (topY - bottomY) / (double) (hi - lo);
        boolean capped = Math.max(width, height) * scale > MAX_SIDE;
        if (capped) {
            scale = (double) MAX_SIDE / Math.max(width, height);
        }
        int outWidth = Math.max(1, (int) Math.round(width * scale));
        int outHeight = Math.max(1, (int) Math.round(height * scale));
        double stepX = (double) width / outWidth;
        double stepZ = (double) height / outHeight;
        BufferedImage map = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_USHORT_GRAY);
        var raster = map.getRaster();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int j = 0; j < outHeight; j++) {
            for (int i = 0; i < outWidth; i++) {
                double y = sample(values, width, height, (i + 0.5) * stepX, (j + 0.5) * stepZ, stepX, stepZ);
                int value = (int) Math.clamp(Math.round(bottomY + (y - lo) * scale), 0, 65535);
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
        writeHuman(map, out);
        if (capped) {
            logger.warning("Attention : relief trop plat, la carte est limitée à " + MAX_SIDE
                    + " blocs de côté, la hauteur ne remplit pas tout le monde.");
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
