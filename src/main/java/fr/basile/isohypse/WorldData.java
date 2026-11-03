package fr.basile.isohypse;

import org.bukkit.block.Biome;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the plugin asks of {@code world.bin} when the game wants a height or a biome: the file ({@link WorldFile}) is
 * memory-mapped, so it stays on the disk and nothing of the size of the map is in the Java heap; a value is read
 * where it is. Outside the map there is nothing: it is the void ({@link #contains}); a read there gives the value of the
 * nearest column of the map, except for the biome, which is the void's.
 *
 * <p>A column (x, z) of the world is the column {@code x + width / 2} of the map (north, row 0, towards negative z), and
 * the cell of a biome layer is one per 4 x 4 blocks; the width and the height are multiples of 32, so the cells and the
 * chunks of the world line up with those of the file.
 */
final class WorldData {

    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final MemorySegment file;
    private final int width;
    private final int height;
    private final WorldFile.Layer heights;
    private final WorldFile.Layer zones;
    /** The index of the zone "lake" in the layer of the zones, -1 if the map has none. */
    private final int lakeZone;
    /** The index of the zone "river" in the layer of the zones, -1 if the map has none. */
    private final int riverZone;
    private final WorldFile.Layer surface;
    private final WorldFile.Layer caves;
    private final WorldFile.Layer deepCaves;
    private final WorldFile.Layer water;
    /** The biome of each index of the layers (the list of settings.json), null for "none" or a biome this version lacks. */
    private final Biome[] biomes;
    private final int lowest;
    private final int seaLevel;

    private WorldData(MemorySegment file, WorldFile.Directory directory, Biome[] biomes, int lowest, int seaLevel,
                      int lakeZone, int riverZone) {
        this.file = file;
        this.width = directory.width();
        this.height = directory.height();
        this.heights = directory.layer(WorldFile.HEIGHT);
        this.zones = directory.layer(WorldFile.TEMP);
        this.lakeZone = lakeZone;
        this.riverZone = riverZone;
        this.surface = directory.layer(WorldFile.BIOME);
        this.caves = directory.layer(WorldFile.CAVE_BIOME);
        this.deepCaves = directory.layer(WorldFile.DEEP_CAVE_BIOME);
        this.water = directory.layer(WorldFile.WATER);
        this.biomes = biomes;
        this.lowest = lowest;
        this.seaLevel = seaLevel;
    }

    /** Opens {@code world.bin} of the plugin folder, with the list of biomes and the lowest Y of {@code settings.json}. */
    static WorldData open(File dir, int seaLevel) throws IOException {
        File worldFile = new File(dir, HeightScale.WORLD_FILE);
        WorldFile.Directory directory = WorldFile.read(worldFile);
        String settings = Files.readString(new File(dir, HeightScale.SETTINGS_FILE).toPath(), StandardCharsets.UTF_8);

        Matcher list = Pattern.compile("\"biomes\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(settings);
        if (!list.find()) {
            throw new IOException("settings.json: no list of biomes");
        }
        List<Biome> found = new ArrayList<>();
        Matcher name = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
        while (name.find()) {
            found.add(name.group(1).equals("none") ? null : BiomeLayout.biome(name.group(1)));
        }
        Matcher low = Pattern.compile("\"lowest_y\"\\s*:\\s*(-?\\d+)").matcher(settings);
        if (!low.find()) {
            throw new IOException("settings.json: no lowest_y");
        }

        // The zones are listed in the order of their index in the layer: the lakes are the zone named "lake".
        int lakeZone = -1;
        int riverZone = -1;
        Matcher zoneList = Pattern.compile("\"temp_zones\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(settings);
        if (zoneList.find()) {
            Matcher zoneName = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(zoneList.group(1));
            for (int index = 0; zoneName.find(); index++) {
                if (zoneName.group(1).equals("lake")) {
                    lakeZone = index;
                } else if (zoneName.group(1).equals("river")) {
                    riverZone = index;
                }
            }
        }

        MemorySegment segment;
        try (RandomAccessFile raf = new RandomAccessFile(worldFile, "r"); FileChannel channel = raf.getChannel()) {
            // The mapping stays for the life of the server: the file is only replaced when the server starts.
            segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
        }
        return new WorldData(segment, directory, found.toArray(new Biome[0]), Integer.parseInt(low.group(1)), seaLevel,
                lakeZone, riverZone);
    }

    /** Whether the column (x, z) is on the map; everything outside it is the void. */
    boolean contains(int x, int z) {
        int column = x + width / 2;
        int row = z + height / 2;
        return column >= 0 && column < width && row >= 0 && row < height;
    }

    /** Whether the chunk is on the map (its sides are on the limits of the map, whose size is a multiple of 32). */
    boolean containsChunk(int chunkX, int chunkZ) {
        return contains(chunkX * 16, chunkZ * 16);
    }

    /** Whether the column (x, z) is on the outermost ring of the map, where the barrier is. */
    boolean onBorder(int x, int z) {
        int column = x + width / 2;
        int row = z + height / 2;
        return contains(x, z) && (column == 0 || column == width - 1 || row == 0 || row == height - 1);
    }

    /** The column of the map nearest to the world column {@code x}. */
    private long columnOf(int x) {
        return Math.clamp(x + (long) width / 2, 0, width - 1);
    }

    /** The row of the map nearest to the world row {@code z}. */
    private long rowOf(int z) {
        return Math.clamp(z + (long) height / 2, 0, height - 1);
    }

    /** Y of the top block of the column (x, z). */
    int heightAt(int x, int z) {
        return file.get(SHORT, heights.offset() + 2 * (rowOf(z) * width + columnOf(x)));
    }

    /** The blocks of water of a lake or a river over the top block of the column (x, z), 0 for none. */
    int waterAt(int x, int z) {
        if (!contains(x, z)) {
            return 0;
        }
        return file.get(ValueLayout.JAVA_BYTE, water.offset() + rowOf(z) * width + columnOf(x)) & 0xFF;
    }

    /** Whether the column (x, z) is a lake (the zone "lake" of the intermediate map): a flat, whose height is the water level. */
    boolean lakeAt(int x, int z) {
        if (lakeZone < 0 || !contains(x, z)) {
            return false;
        }
        return (file.get(ValueLayout.JAVA_BYTE, zones.offset() + rowOf(z) * zones.columns() + columnOf(x)) & 0xFF) == lakeZone;
    }

    /** Whether the column (x, z) is a river (the zone "river" of the intermediate map; a lake is over a river). */
    boolean riverAt(int x, int z) {
        if (riverZone < 0 || !contains(x, z)) {
            return false;
        }
        return (file.get(ValueLayout.JAVA_BYTE, zones.offset() + rowOf(z) * zones.columns() + columnOf(x)) & 0xFF) == riverZone;
    }

    /** Y of the lowest column of the map. */
    int lowest() {
        return lowest;
    }

    /**
     * The biome at the block (x, y, z): the biome of a cave when the block is deep enough under the ground and above
     * the sea level margin, else the surface one. The same rules as {@link BiomeLayout#at(int, int, int, int)}, from the
     * layers instead of the noises.
     */
    Biome biomeAt(int x, int y, int z) {
        if (!contains(x, z)) {
            return Biome.THE_VOID;
        }
        if (y < heightAt(x, z) - BiomeLayout.CAVE_DEPTH && y < seaLevel - BiomeLayout.CAVE_SEA_MARGIN) {
            Biome cave = biome(y < BiomeLayout.DEEP_DARK_Y ? deepCaves : caves, x, z);
            if (cave != null) {
                return cave;
            }
        }
        return biome(surface, x, z);
    }

    private Biome biome(WorldFile.Layer layer, int x, int z) {
        long column = columnOf(x) / layer.step();
        long row = rowOf(z) / layer.step();
        int index = file.get(ValueLayout.JAVA_BYTE, layer.offset() + row * layer.columns() + column) & 0xFF;
        return index < biomes.length ? biomes[index] : null;
    }
}
