package fr.basile.isohypse;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Command line tool for {@code tools/map_viewer.py}: the intermediate map (the zones of {@link TempLayout}) of the
 * height map of a plugin folder, computed by the very code the plugin uses, so that there is one source of truth.
 *
 * <pre>java -cp isohypse.jar fr.basile.isohypse.TempMapTool &lt;plugin folder&gt;</pre>
 *
 * <p>It reads {@code heightmap.bin} and {@code settings.json} (the parameters of the computation are the {@link TerrainSettings} written in it) and writes {@code temp_map.bin} (magic, width, height,
 * then the index of the zone of every column, one byte, row by row like the height map) and {@code temp_map.json} (the
 * zones in the order of the indexes: name, colour and range). It needs no server.
 */
public final class TempMapTool {

    static final int MAGIC = 0x49534F54;
    static final String MAP_FILE = "temp_map.bin";
    static final String ZONES_FILE = "temp_map.json";
    private static final int BAND = 256;

    private TempMapTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: TempMapTool <plugin folder>");
            System.exit(2);
        }
        File dir = new File(args[0]);
        String settings = Files.readString(new File(dir, HeightScale.SETTINGS_FILE).toPath(), StandardCharsets.UTF_8);
        Matcher sea = Pattern.compile("\"sea_level\"\\s*:\\s*(\\d+)").matcher(settings);
        if (!sea.find()) {
            throw new IOException("sea_level not found in settings.json");
        }
        int seaLevel = Integer.parseInt(sea.group(1));

        System.out.println("reading heightmap.bin");
        HeightMapData data = HeightMapData.fromBin(new File(dir, HeightScale.HEIGHTMAP_FILE));
        System.out.println("computing the zones (" + data.width() + " x " + data.height() + " blocks)");
        TempLayout layout = TempLayout.build(data, seaLevel, TerrainSettings.fromJson(settings));
        List<TempLayout.Zone> zones = layout.all();

        System.out.println("writing " + MAP_FILE);
        File out = new File(dir, MAP_FILE);
        File part = new File(dir, MAP_FILE + ".part");
        int width = data.width();
        int height = data.height();
        try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(part), 1 << 16))) {
            stream.writeInt(MAGIC);
            stream.writeInt(width);
            stream.writeInt(height);
            for (int from = 0; from < height; from += BAND) {
                int rows = Math.min(BAND, height - from);
                int first = from;
                byte[] band = new byte[rows * width];
                IntStream.range(0, rows).parallel().forEach(row -> {
                    int z = first + row - height / 2;
                    for (int column = 0; column < width; column++) {
                        int x = column - width / 2;
                        band[row * width + column] = (byte) zones.indexOf(layout.at(x, z, data.heightAt(x, z)));
                    }
                });
                stream.write(band);
            }
        }
        Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);

        StringBuilder json = new StringBuilder("{\n  \"zones\": [");
        for (int i = 0; i < zones.size(); i++) {
            TempLayout.Zone zone = zones.get(i);
            json.append(i == 0 ? "\n" : ",\n").append("    {\"name\": \"").append(zone.name()).append("\", \"rgb\": ")
                    .append(zone.rgb()).append(", \"range\": \"").append(zone.range()).append("\"}");
        }
        json.append("\n  ]\n}\n");
        Files.writeString(new File(dir, ZONES_FILE).toPath(), json, StandardCharsets.UTF_8); // last: it tells the map is complete
        System.out.println("done");
    }
}
