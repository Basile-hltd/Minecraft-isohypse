package fr.basile.isohypse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The data behind the maps, for {@code tools/map_viewer.py}: one 8-bit picture per layer (the surface biome, and the
 * cave biomes at the two depths of {@code cave_map.png}), where the value of a pixel is the index of its biome in the
 * list of that layer in {@code map_data.json}, which also holds the size of the map, the sea level and the place of
 * every structure, in blocks from the centre of the map: the pixel (i, j) of the pictures is the column
 * x = i - width / 2, z = j - height / 2.
 */
final class MapExport {

    static final String BIOMES_FILE = "biomes.png";
    static final String DATA_FILE = "map_data.json";

    private MapExport() {
    }

    /** What a layer shows at a column: a biome entry, null for none. */
    private interface Sampler {
        BiomeLayout.Entry at(int x, int z, int top);
    }

    static void write(File dir, HeightMapData data, BiomeLayout layout, int seaLevel, List<String> names,
                      List<Integer> colors, List<List<StructureMap.Dot>> found, Logger logger) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"width\": ").append(data.width()).append(",\n  \"height\": ").append(data.height())
                .append(",\n  \"sea_level\": ").append(seaLevel).append(",\n  \"layers\": [");
        layer(json, dir, data, "Surface", BIOMES_FILE, layout::at, true);
        for (int y : MapRenderer.CAVE_MAP_Y) {
            String file = "biomes_cave_" + (y < 0 ? "m" + -y : String.valueOf(y)) + ".png";
            layer(json, dir, data, "Grottes y=" + y, file, (x, z, top) -> layout.caveAt(x, y, z, top), false);
        }
        json.append("\n  ],\n  \"structures\": [");
        for (int s = 0; s < names.size(); s++) {
            json.append(s == 0 ? "\n" : ",\n").append("    {\"name\": \"").append(names.get(s)).append("\", \"rgb\": ")
                    .append(colors.get(s)).append(", \"points\": [");
            List<StructureMap.Dot> dots = found.get(s);
            for (int d = 0; d < dots.size(); d++) {
                json.append(d == 0 ? "" : ",").append('[').append(dots.get(d).x()).append(',').append(dots.get(d).z()).append(']');
            }
            json.append("]}");
        }
        json.append("\n  ]\n}\n");
        Files.writeString(new File(dir, DATA_FILE).toPath(), json, StandardCharsets.UTF_8);
        logger.info(new File(dir, DATA_FILE).getAbsolutePath() + " et les biomes*.png : données de tools/map_viewer.py "
                + "(structures, biome de surface et biomes de grotte de chaque colonne)");
    }

    /**
     * Writes the index picture of a layer and its entry in the json. Index 0 is "aucun", for a column with no biome in
     * this layer (a cave without a biome of its own).
     */
    private static void layer(StringBuilder json, File dir, HeightMapData data, String title, String file,
                              Sampler sampler, boolean first) throws IOException {
        int width = data.width();
        int height = data.height();
        int halfWidth = width / 2;
        int halfHeight = height / 2;
        Map<String, Integer> indexes = new LinkedHashMap<>();
        List<Integer> biomeColors = new ArrayList<>();
        indexes.put("aucun", 0);
        biomeColors.add(0x8c8c8c);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = image.getRaster();
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int x = i - halfWidth;
                int z = j - halfHeight;
                BiomeLayout.Entry entry = sampler.at(x, z, data.heightAt(x, z));
                int index = 0;
                if (entry != null && entry.biome() != null) {
                    String name = entry.biome().key().value();
                    Integer known = indexes.get(name);
                    if (known == null) {
                        known = indexes.size();
                        indexes.put(name, known);
                        biomeColors.add(entry.rgb());
                    }
                    index = known;
                }
                raster.setSample(i, j, 0, index);
            }
        }
        ImageIO.write(image, "png", new File(dir, file));
        json.append(first ? "\n" : ",\n").append("    {\"name\": \"").append(title).append("\", \"file\": \"")
                .append(file).append("\", \"biomes\": [");
        int n = 0;
        for (String name : indexes.keySet()) {
            json.append(n == 0 ? "" : ", ").append("{\"name\": \"").append(name).append("\", \"rgb\": ")
                    .append(biomeColors.get(n)).append('}');
            n++;
        }
        json.append("]}");
    }
}
