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
 * Fetches the real altitudes of the area around a GPS point (see {@link #fetchSource}); {@link HeightScale} scales them.
 *
 * <p>Sources: swisstopo swissALTI3D (Switzerland, 0.5 m), AWS Terrain Tiles / Terrarium (whole world, ~30 m)
 * where swisstopo has no data. North is up (negative z).
 */
public final class HeightMapGenerator {

    private static final double M_PER_DEG_LAT = 111320.0;
    /** The real altitudes of the area (before scaling), kept next to the height map so that only the scaling is redone. */
    public static final String SOURCE_FILE = "heightmap_source.bin";
    static final int SOURCE_MAGIC = 0x49534F48;
    /** Most samples per side of the real area: a bigger area is sampled every few metres instead of every metre. */
    private static final int MAX_SAMPLES = 4096;
    /** swissALTI3D (0.5 m, then 2 m tiles) is only used up to this sampling step (m); beyond, Terrarium is lighter. */
    private static final int MAX_SWISS_STEP = 4;
    /** The altitudes (m) outside this range are artefacts of the sources (a corrupt pixel of a tile...): brought back to its bounds. */
    private static final double MIN_ALTITUDE = -500;
    private static final double MAX_ALTITUDE = 9000;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Logger logger;
    private final Path cache;

    public HeightMapGenerator(Logger logger, Path cacheDir) {
        this.logger = logger;
        this.cache = cacheDir;
    }

    /** Metres between two samples of the real altitudes of an area of {@code size} metres. */
    static int stepFor(int size) {
        return Math.max(1, (int) Math.ceil((double) size / MAX_SAMPLES));
    }

    /** Samples per side of the real altitudes of an area of {@code size} metres. */
    static int samplesFor(int size) {
        return (int) Math.ceil((double) size / stepFor(size));
    }

    /**
     * Makes sure {@code sourceFile} ({@code heightmap_source.bin}, the real altitudes before scaling) exists for this
     * area. If it already holds this area, nothing is downloaded. Otherwise (no file, or the file of another area,
     * which is deleted) the tiles needed for the area are downloaded, the altitudes sampled and written in it, then
     * the tile cache is deleted, as the file holds everything that is needed from now on. Nothing else is done: no
     * scaling, no height map.
     *
     * @param size side in metres of the real area (1 sample per metre, or per few metres beyond {@link #MAX_SAMPLES}
     *             samples per side)
     */
    public void fetchSource(double lat, double lon, int size, File sourceFile) throws Exception {
        if (sourceFile.exists()) {
            double fileLat;
            double fileLon;
            int fileSize;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(sourceFile)))) {
                if (in.readInt() != SOURCE_MAGIC) {
                    throw new IOException("Fichier d'altitudes réelles invalide : " + sourceFile);
                }
                fileLat = in.readDouble();
                fileLon = in.readDouble();
                fileSize = in.readInt();
            }
            if (fileLat == lat && fileLon == lon && fileSize == size) {
                logger.info(sourceFile.getAbsolutePath() + " : altitudes réelles déjà présentes (zone de " + size
                        + " m, latitude " + lat + ", longitude " + lon + "), rien n'est téléchargé");
                return;
            }
            logger.info(sourceFile.getAbsolutePath() + " : c'est une autre zone (" + fileSize + " m, latitude " + fileLat
                    + ", longitude " + fileLon + "), supprimé, la zone demandée est téléchargée");
            Files.delete(sourceFile.toPath());
        }
        if (size <= 0) {
            throw new IllegalArgumentException("La taille doit être > 0");
        }
        if (Math.abs(lat) > 85) {
            throw new IllegalArgumentException("Latitude hors de la couverture (±85°)");
        }
        Source source = fetch(lat, lon, size);
        writeSource(sourceFile, source);
        logger.info(sourceFile.getAbsolutePath() + " : altitudes réelles enregistrées (zone de " + size + " m, "
                + String.format(Locale.ROOT, "%.1f à %.1f m", source.min, source.max) + ")");
        if (source.fallback > 0) {
            logger.warning("Attention : " + source.fallback
                    + " pixels hors couverture swisstopo, remplacés par Terrarium.");
        }
        deleteCache();
    }

    /** Deletes the cache of downloaded tiles. */
    private void deleteCache() throws IOException {
        if (!Files.exists(cache)) {
            return;
        }
        try (var paths = Files.walk(cache)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
        logger.info(cache.toAbsolutePath() + " : tuiles supprimées, tout est dans le fichier d'altitudes réelles");
    }

    /** The real altitudes (m) of the area: n x n samples, one every r metres, centred on (lat, lon). */
    private record Source(double lat, double lon, int size, int r, int n, float[] altitude, double min, double max,
                          int fallback) {
    }

    /** Step 1: fetches the real altitudes, one sample every r metres (r = 1 unless the area is very big). */
    private Source fetch(double lat, double lon, int size) throws Exception {
        int r = stepFor(size);
        int n = samplesFor(size);
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
        altitude = clean(altitude, n, lat, lon, r);
        min = Double.MAX_VALUE;
        max = -Double.MAX_VALUE;
        for (float v : altitude) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return new Source(lat, lon, size, r, n, altitude, min, max, fallback);
    }

    /**
     * Removes the artefacts of the sources: the altitudes outside [{@link #MIN_ALTITUDE}, {@link #MAX_ALTITUDE}] are brought
     * back to these bounds (their raw extremes are logged, with where they are), then a median of 3 x 3 samples removes the
     * isolated points without blurring the relief.
     */
    private float[] clean(float[] altitude, int n, double lat, double lon, int r) {
        int lowest = 0;
        int highest = 0;
        float lowestRaw = altitude[0];
        float highestRaw = altitude[0];
        long clamped = 0;
        for (int i = 0; i < altitude.length; i++) {
            if (altitude[i] < lowestRaw) {
                lowest = i;
                lowestRaw = altitude[i];
            }
            if (altitude[i] > highestRaw) {
                highest = i;
                highestRaw = altitude[i];
            }
            if (altitude[i] < MIN_ALTITUDE || altitude[i] > MAX_ALTITUDE) {
                altitude[i] = (float) Math.clamp(altitude[i], MIN_ALTITUDE, MAX_ALTITUDE);
                clamped++;
            }
        }
        if (clamped > 0) {
            logger.warning(clamped + " points d'altitude hors de [" + MIN_ALTITUDE + " ; " + MAX_ALTITUDE + "] m ramenés aux bornes");
        }
        double cosLat = Math.cos(Math.toRadians(lat));
        for (int index : new int[]{lowest, highest}) {
            double east = (index % n - n / 2.0 + 0.5) * r;
            double north = -(index / n - n / 2.0 + 0.5) * r;
            logger.info(String.format(Locale.ROOT, "altitude brute %s : %.1f m en latitude %.5f, longitude %.5f",
                    index == lowest ? "la plus basse" : "la plus haute", index == lowest ? lowestRaw : highestRaw,
                    lat + north / M_PER_DEG_LAT, lon + east / (M_PER_DEG_LAT * cosLat)));
        }
        float[] median = new float[altitude.length];
        java.util.stream.IntStream.range(0, n).parallel().forEach(row -> {
            float[] window = new float[9];
            for (int column = 0; column < n; column++) {
                int k = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        window[k++] = altitude[Math.clamp(row + dz, 0, n - 1) * n + Math.clamp(column + dx, 0, n - 1)];
                    }
                }
                java.util.Arrays.sort(window);
                median[row * n + column] = window[4];
            }
        });
        return median;
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
