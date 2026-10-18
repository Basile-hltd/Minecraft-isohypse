import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Génère une height map (PNG 16 bits, 1 pixel = 1 bloc, valeur = hauteur en blocs) à partir de
 * l'altitude réelle autour d'un point GPS, qui devient le centre de la carte.
 *
 * Sources :
 *  - swisstopo swissALTI3D (Suisse, 0,5 m ou 2 m selon l'échelle, par défaut)
 *  - AWS Terrain Tiles / Terrarium (monde entier, ~30 m réels), utilisé hors de Suisse
 *    ou avec --source terrarium. OpenStreetMap ne fournit pas d'altitude.
 *
 * Usage : java tools/GenerateHeightMap.java LAT LON TAILLE [options]
 *   TAILLE                  côté de la carte finale en blocs, multiple de 16 (256 = 16x16 chunks)
 *   --meters-per-block M    mètres réels par bloc (défaut 1) ; la zone couverte fait TAILLE x M mètres
 *                           de côté, et c'est aussi la taille de la carte de base (1 pixel = 1 m)
 *   --base B                altitude (m) retirée de toutes les valeurs (défaut 0)
 *   --source S              swisstopo (défaut, avec repli terrarium) ou terrarium
 *   --out FICHIER           sortie (défaut heightmap.png) ; écrit aussi FICHIER_human.png,
 *                           aperçu 8 bits au contraste étiré (à regarder, pas à utiliser), et
 *                           FICHIER_base.png, la carte de base avant échelle (si M != 1)
 *
 * Deux étapes : (1) carte de base à 1 m par pixel, valeur = altitude en mètres - base, bornée à
 * 0-4000 ; (2) échelle appliquée à cette carte : réduction par moyenne des pixels, hauteurs divisées
 * par M pour conserver les proportions (à 2 m par bloc, 4000 m -> 2000). Même zone, moins de blocs.
 * Biomes : biome.png (valeur 1 à 5) et biome_human.png (couleurs), dans le dossier de --out, d'après
 * l'altitude réelle de la carte de base puis réduits par vote majoritaire. Limites par défaut (m) :
 * 1 forêt < 2200 <= 2 arbustes < 2500 <= 3 caillou < 3000 <= 4 neige < 3300 <= 5 glacier
 * (--biome-limits A,B,C,D pour les changer).
 * Nord en haut (z négatif).
 */
public class GenerateHeightMap {
    static final double M_PER_DEG_LAT = 111320.0;
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    static final Path CACHE = Path.of("tmp", "dem-cache");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage : java tools/GenerateHeightMap.java LAT LON TAILLE "
                    + "[--meters-per-block M] [--base B] [--source swisstopo|terrarium] [--out FICHIER]");
            System.exit(1);
        }
        double lat = parse(args[0]);
        double lon = parse(args[1]);
        int outSize = Integer.parseInt(args[2]);
        double scale = 1.0;
        double base = 0;
        String source = "swisstopo";
        File out = new File("heightmap.png");
        double[] biomeLimits = BIOME_LIMITS.clone();
        for (int i = 3; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Valeur manquante pour " + args[i]);
            switch (args[i]) {
                case "--meters-per-block" -> scale = parse(args[i + 1]);
                case "--base" -> base = parse(args[i + 1]);
                case "--source" -> source = args[i + 1];
                case "--out" -> out = new File(args[i + 1]);
                case "--biome-limits" -> {
                    String[] parts = args[i + 1].split(",");
                    if (parts.length != 4) throw new IllegalArgumentException("--biome-limits attend 4 altitudes : A,B,C,D");
                    for (int k = 0; k < 4; k++) biomeLimits[k] = parse(parts[k]);
                }
                default -> throw new IllegalArgumentException("Option inconnue : " + args[i]);
            }
        }
        if (outSize % 16 != 0 || outSize <= 0) throw new IllegalArgumentException("La taille doit être un multiple de 16");
        if (!(scale > 0)) throw new IllegalArgumentException("--meters-per-block doit être > 0");
        int size = (int) Math.round(outSize * scale); // côté de la carte de base, en mètres
        if (Math.abs(lat) > 85) throw new IllegalArgumentException("Latitude hors de la couverture (±85°)");

        // Étape 1 : carte de base, toujours à 1 m par pixel, valeur = altitude (m) - base, bornée à 0-4000.
        double cosLat = Math.cos(Math.toRadians(lat));
        Swiss swiss = null;
        if (source.equals("swisstopo")) {
            swiss = new Swiss(lat, lon, size / 2.0, 1.0);
        } else if (!source.equals("terrarium")) {
            throw new IllegalArgumentException("Source inconnue : " + source);
        }
        Terrarium terrarium = new Terrarium(lat, 1.0);

        BufferedImage baseMap = new BufferedImage(size, size, BufferedImage.TYPE_USHORT_GRAY);
        var baseRaster = baseMap.getRaster();
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int clamped = 0, fallback = 0;
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double east = i - size / 2.0 + 0.5;
                double north = -(j - size / 2.0 + 0.5);
                double elevation = swiss == null ? Double.NaN : swiss.elevation(east, north);
                if (Double.isNaN(elevation)) {
                    if (swiss != null) fallback++;
                    elevation = terrarium.elevation(lat + north / M_PER_DEG_LAT,
                            lon + east / (M_PER_DEG_LAT * cosLat));
                }
                min = Math.min(min, elevation);
                max = Math.max(max, elevation);
                long value = Math.round(elevation - base);
                if (value < 0 || value > 4000) clamped++;
                baseRaster.setSample(i, j, 0, Math.clamp(value, 0, 4000));
            }
        }

        // Étape 2 : l'échelle est appliquée à la carte de base (réduction par moyenne des pixels couverts,
        // hauteurs divisées par l'échelle). Échelle 1 : la carte finale est la carte de base.
        String name = out.getName();
        String stem = name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
        File dir = out.getAbsoluteFile().getParentFile();
        BufferedImage result = baseMap;
        if (scale != 1.0) {
            File baseFile = new File(dir, stem + "_base.png");
            if (!ImageIO.write(baseMap, "png", baseFile)) throw new IllegalStateException("Écriture PNG impossible");
            System.out.println(baseFile.getAbsolutePath() + " : carte de base " + size + "x" + size + " (1 m par pixel)");
            result = downscale(baseMap, outSize, scale);
        }
        var raster = result.getRaster();
        if (!ImageIO.write(result, "png", out)) throw new IllegalStateException("Écriture PNG impossible");
        System.out.printf("%s : altitudes %.1f à %.1f m (base %.1f), échelle 1 bloc = %.2f m, carte %dx%d blocs, hauteurs %.0f à %.0f blocs%n",
                out.getAbsolutePath(), min, max, base, scale, outSize, outSize, (min - base) / scale, (max - base) / scale);

        // Version lisible : même carte en 8 bits, contraste étiré entre la valeur min et max réelles.
        File human = new File(dir, stem + "_human.png");
        int lo = Integer.MAX_VALUE, hi = 0;
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                int v = raster.getSample(i, j, 0);
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        BufferedImage preview = new BufferedImage(outSize, outSize, BufferedImage.TYPE_BYTE_GRAY);
        var previewRaster = preview.getRaster();
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                previewRaster.setSample(i, j, 0, Math.round(255f * (raster.getSample(i, j, 0) - lo) / Math.max(1, hi - lo)));
            }
        }
        ImageIO.write(preview, "png", human);
        System.out.println(human.getAbsolutePath() + " : contraste étiré de " + lo + " (noir) à " + hi + " (blanc)");

        // Biomes : calculés sur la carte de base (altitude réelle = valeur + base), puis réduits à la fin
        // par vote majoritaire (une moyenne n'a pas de sens pour des catégories).
        var baseIn = baseMap.getRaster();
        BufferedImage biomeBase = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        var biomeBaseRaster = biomeBase.getRaster();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                biomeBaseRaster.setSample(i, j, 0, biomeAt(baseIn.getSample(i, j, 0) + base, biomeLimits));
            }
        }
        BufferedImage biomes = scale != 1.0 ? downscaleBiomes(biomeBase, outSize, scale) : biomeBase;
        var biomeRaster = biomes.getRaster();
        File biomeFile = new File(dir, "biome.png");
        File biomeHuman = new File(dir, "biome_human.png");
        ImageIO.write(biomes, "png", biomeFile);
        BufferedImage biomeColors = new BufferedImage(outSize, outSize, BufferedImage.TYPE_INT_RGB);
        int[] counts = new int[BIOME_COLORS.length + 1];
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                int b = biomeRaster.getSample(i, j, 0);
                counts[b]++;
                biomeColors.setRGB(i, j, BIOME_COLORS[b - 1]);
            }
        }
        ImageIO.write(biomeColors, "png", biomeHuman);
        System.out.println(biomeFile.getAbsolutePath() + " : biomes 1 à 5 (valeur du pixel), " + outSize + "x" + outSize);
        System.out.println(biomeHuman.getAbsolutePath() + " : aperçu en couleurs");
        for (int b = 1; b <= BIOME_COLORS.length; b++) {
            System.out.printf("  %d %-22s %5.1f %%%n", b, BIOME_NAMES[b - 1], 100.0 * counts[b] / ((double) outSize * outSize));
        }
        if (fallback > 0) {
            System.out.println("Attention : " + fallback + " pixels hors couverture swisstopo, remplacés par Terrarium.");
        }
        if (clamped > 0) {
            System.out.println("Attention : " + clamped + " pixels hors de 0-4000 ont été bornés (voir --base).");
        }
    }

    // Biomes selon l'altitude réelle (m), valeurs moyennes approximatives pour la Suisse ; modifiables avec
    // --biome-limits A,B,C,D : 1 sous A, 2 de A à B, 3 de B à C, 4 de C à D, 5 au-dessus de D.
    static final double[] BIOME_LIMITS = {2200, 2500, 3000, 3300};
    static final String[] BIOME_NAMES = {"forêt", "arbustes / prairie alpine", "caillou", "neige", "glacier"};
    static final int[] BIOME_COLORS = {0x2E8B3A, 0xB5D36A, 0x8A8A8A, 0xFFFFFF, 0x9FD8F0};

    static int biomeAt(double altitude, double[] limits) {
        int b = 1;
        while (b <= limits.length && altitude >= limits[b - 1]) b++;
        return b;
    }

    /** Réduction d'une carte de biomes : biome majoritaire (pondéré par la surface) de chaque carré. */
    static BufferedImage downscaleBiomes(BufferedImage biomes, int outSize, double scale) {
        int size = biomes.getWidth();
        var in = biomes.getRaster();
        BufferedImage result = new BufferedImage(outSize, outSize, BufferedImage.TYPE_BYTE_GRAY);
        var out = result.getRaster();
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                double x0 = i * scale, x1 = Math.min((i + 1) * scale, size);
                double z0 = j * scale, z1 = Math.min((j + 1) * scale, size);
                double[] votes = new double[BIOME_COLORS.length + 1];
                for (int pz = (int) z0; pz < Math.ceil(z1); pz++) {
                    double wz = Math.min(pz + 1, z1) - Math.max(pz, z0);
                    for (int px = (int) x0; px < Math.ceil(x1); px++) {
                        votes[in.getSample(px, pz, 0)] += wz * (Math.min(px + 1, x1) - Math.max(px, x0));
                    }
                }
                int best = 1;
                for (int b = 2; b < votes.length; b++) if (votes[b] > votes[best]) best = b;
                out.setSample(i, j, 0, best);
            }
        }
        return result;
    }

    /**
     * Réduit la carte de base à {@code outSize} x {@code outSize} : chaque pixel de sortie est la moyenne
     * (pondérée par la surface couverte) d'un carré de {@code scale} x {@code scale} pixels de la base,
     * divisée par {@code scale}.
     */
    static BufferedImage downscale(BufferedImage base, int outSize, double scale) {
        int size = base.getWidth();
        var in = base.getRaster();
        BufferedImage result = new BufferedImage(outSize, outSize, BufferedImage.TYPE_USHORT_GRAY);
        var out = result.getRaster();
        for (int j = 0; j < outSize; j++) {
            for (int i = 0; i < outSize; i++) {
                double x0 = i * scale, x1 = Math.min((i + 1) * scale, size);
                double z0 = j * scale, z1 = Math.min((j + 1) * scale, size);
                double sum = 0, weight = 0;
                for (int pz = (int) z0; pz < Math.ceil(z1); pz++) {
                    double wz = Math.min(pz + 1, z1) - Math.max(pz, z0);
                    for (int px = (int) x0; px < Math.ceil(x1); px++) {
                        double w = wz * (Math.min(px + 1, x1) - Math.max(px, x0));
                        sum += w * in.getSample(px, pz, 0);
                        weight += w;
                    }
                }
                out.setSample(i, j, 0, Math.clamp(Math.round(sum / weight / scale), 0, 4000));
            }
        }
        return result;
    }

    /** Accepte "46.168441," (virgule finale copiée depuis un exemple) et la virgule décimale. */
    static double parse(String s) {
        return Double.parseDouble(s.replaceAll(",+$", "").replace(',', '.'));
    }

    static Path download(String url, String name) throws Exception {
        Path file = CACHE.resolve(name);
        if (!Files.exists(file)) {
            Files.createDirectories(CACHE);
            System.out.println("Téléchargement " + url);
            Path part = CACHE.resolve(name + ".part");
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
    // swisstopo swissALTI3D : dalles GeoTIFF de 1 km en LV95 (EPSG:2056), altitude en float32.
    // ---------------------------------------------------------------------------------------
    static final class Swiss {
        final double centerE, centerN, res;
        final int pixelsPerTile;
        final Map<String, String> urls = new HashMap<>();      // "kx-ky" -> URL du GeoTIFF
        final Map<String, Raster> tiles = new HashMap<>();

        Swiss(double lat, double lon, double half, double scale) throws Exception {
            double[] c = toLv95(lat, lon);
            centerE = c[0];
            centerN = c[1];
            res = scale <= 1.0 ? 0.5 : 2.0;
            pixelsPerTile = (int) (1000 / res);

            // Dalles recherchées via l'API STAC : bbox WGS84 de la carte avec une marge.
            double pad = half * 1.05 + 50;
            double cosLat = Math.cos(Math.toRadians(lat));
            double dLat = pad / M_PER_DEG_LAT, dLon = pad / (M_PER_DEG_LAT * cosLat);
            String next = String.format(java.util.Locale.ROOT,
                    "https://data.geo.admin.ch/api/stac/v1/collections/ch.swisstopo.swissalti3d/items"
                            + "?bbox=%f,%f,%f,%f&limit=100", lon - dLon, lat - dLat, lon + dLon, lat + dLat);
            Map<String, Integer> years = new HashMap<>();
            Pattern id = Pattern.compile("swissalti3d_(\\d{4})_(\\d+-\\d+)");
            Pattern asset = Pattern.compile("\"href\"\\s*:\\s*\"([^\"]*_" + (res == 0.5 ? "0\\.5" : "2")
                    + "_2056_\\d+\\.tif)\"");
            while (next != null) {
                String body = HTTP.send(HttpRequest.newBuilder(URI.create(next)).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                // Découpe par entité : chaque "id" de feature précède ses assets.
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
            System.out.println("swisstopo swissALTI3D : " + urls.size() + " dalle(s) de 1 km, résolution " + res + " m");
        }

        /** Altitude (m) au décalage (est, nord) en mètres du centre, ou NaN si hors couverture. */
        double elevation(double east, double north) throws Exception {
            double gx = (centerE + east) / res - 0.5;
            double gy = (centerN + north) / res - 0.5;
            int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy);
            double fx = gx - x0, fy = gy - y0;
            double a = pixel(x0, y0), b = pixel(x0 + 1, y0), c = pixel(x0, y0 + 1), d = pixel(x0 + 1, y0 + 1);
            if (Double.isNaN(a + b + c + d)) {
                // Au bord d'une dalle manquante : prend le pixel le plus proche s'il existe.
                return pixel((int) Math.round(gx), (int) Math.round(gy));
            }
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
        }

        /** Pixel global (gx vers l'est, gy vers le nord) ; NaN si absent ou sans donnée. */
        double pixel(int gx, int gy) throws Exception {
            int kx = Math.floorDiv(gx, pixelsPerTile), ky = Math.floorDiv(gy, pixelsPerTile);
            Raster tile = tile(kx, ky);
            if (tile == null) return Double.NaN;
            int col = gx - kx * pixelsPerTile;
            int row = pixelsPerTile - 1 - (gy - ky * pixelsPerTile); // la ligne 0 est au nord
            float v = tile.getSampleFloat(col, row, 0);
            return v < -1000 || Float.isNaN(v) ? Double.NaN : v;
        }

        /** Dalle (kx, ky) = (E / 1000, N / 1000) en LV95, comme dans le nom des fichiers (2494-1113). */
        Raster tile(int kx, int ky) throws Exception {
            String key = kx + "-" + ky;
            if (tiles.containsKey(key)) return tiles.get(key);
            String url = urls.get(key);
            Raster raster = null;
            if (url != null) {
                Path file = download(url, url.substring(url.lastIndexOf('/') + 1));
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) throw new IllegalStateException("GeoTIFF illisible : " + file);
                raster = image.getRaster();
            }
            tiles.put(key, raster);
            return raster;
        }

        /** WGS84 -> LV95 (formules approchées de swisstopo, précision ~1 m). */
        static double[] toLv95(double lat, double lon) {
            double p = (lat * 3600 - 169028.66) / 10000;
            double l = (lon * 3600 - 26782.5) / 10000;
            double e = 2600072.37 + 211455.93 * l - 10938.51 * l * p - 0.36 * l * p * p - 44.54 * l * l * l;
            double n = 1200147.07 + 308807.95 * p + 3745.25 * l * l + 76.63 * p * p
                    - 194.56 * l * l * p + 119.79 * p * p * p;
            return new double[]{e, n};
        }
    }

    // ---------------------------------------------------------------------------------------
    // AWS Terrain Tiles (Terrarium) : monde entier, altitude encodée dans les canaux RVB.
    // ---------------------------------------------------------------------------------------
    static final class Terrarium {
        static final int MAX_ZOOM = 15;
        static final double EARTH = 6378137.0;
        final int zoom;
        final Map<String, BufferedImage> tiles = new HashMap<>();

        Terrarium(double lat, double scale) {
            double cosLat = Math.cos(Math.toRadians(lat));
            int z = (int) Math.min(MAX_ZOOM,
                    Math.ceil(Math.log(2 * Math.PI * EARTH * cosLat / 256.0 / scale) / Math.log(2)));
            zoom = Math.max(z, 0);
        }

        /** Altitude (m), interpolée bilinéairement entre les pixels des tuiles. */
        double elevation(double lat, double lon) throws Exception {
            double n = (1 << zoom) * 256.0;
            double px = (lon + 180.0) / 360.0 * n - 0.5;
            double sin = Math.sin(Math.toRadians(lat));
            double py = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * n - 0.5;
            int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
            double fx = px - x0, fy = py - y0;
            double a = pixel(x0, y0), b = pixel(x0 + 1, y0), c = pixel(x0, y0 + 1), d = pixel(x0 + 1, y0 + 1);
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
