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
 * where it is. The map repeats endlessly outside, as it is.
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
    private final WorldFile.Layer surface;
    private final WorldFile.Layer caves;
    private final WorldFile.Layer deepCaves;
    /** The biome of each index of the layers (the list of settings.json), null for "none" or a biome this version lacks. */
    private final Biome[] biomes;
    private final int lowest;
    private final int seaLevel;

    private WorldData(MemorySegment file, WorldFile.Directory directory, Biome[] biomes, int lowest, int seaLevel) {
        this.file = file;
        this.width = directory.width();
        this.height = directory.height();
        this.heights = directory.layer(WorldFile.HEIGHT);
        this.surface = directory.layer(WorldFile.BIOME);
        this.caves = directory.layer(WorldFile.CAVE_BIOME);
        this.deepCaves = directory.layer(WorldFile.DEEP_CAVE_BIOME);
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

        MemorySegment segment;
        try (RandomAccessFile raf = new RandomAccessFile(worldFile, "r"); FileChannel channel = raf.getChannel()) {
            // The mapping stays for the life of the server: the file is only replaced when the server starts.
            segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
        }
        return new WorldData(segment, directory, found.toArray(new Biome[0]), Integer.parseInt(low.group(1)), seaLevel);
    }

    /** Y of the top block of the column (x, z). */
    int heightAt(int x, int z) {
        long column = Math.floorMod(x + width / 2, width);
        long row = Math.floorMod(z + height / 2, height);
        return file.get(SHORT, heights.offset() + 2 * (row * width + column));
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
        if (y < heightAt(x, z) - BiomeLayout.CAVE_DEPTH && y < seaLevel - BiomeLayout.CAVE_SEA_MARGIN) {
            Biome cave = biome(y < BiomeLayout.DEEP_DARK_Y ? deepCaves : caves, x, z);
            if (cave != null) {
                return cave;
            }
        }
        return biome(surface, x, z);
    }

    private Biome biome(WorldFile.Layer layer, int x, int z) {
        long column = Math.floorMod(x + width / 2, width) / layer.step();
        long row = Math.floorMod(z + height / 2, height) / layer.step();
        int index = file.get(ValueLayout.JAVA_BYTE, layer.offset() + row * layer.columns() + column) & 0xFF;
        return index < biomes.length ? biomes[index] : null;
    }
}
