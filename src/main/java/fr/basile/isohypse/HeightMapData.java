package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.stream.IntStream;

/**
 * The heights of {@code heightmap.bin} (or of a {@code world.bin}), memory-mapped and never loaded: value = Y level of
 * the top block, the middle of the map being the world origin (x=0, z=0), north (row 0) towards negative z. The map
 * repeats endlessly outside, as it is. Closed when the build is done.
 */
final class HeightMapData implements AutoCloseable {

    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final int width;
    private final int height;
    private final MappedFile mapped;
    private final MemorySegment values;
    private final int highest;

    private HeightMapData(int width, int height, MappedFile mapped) {
        this.width = width;
        this.height = height;
        this.mapped = mapped;
        this.values = mapped.segment();
        this.highest = IntStream.range(0, height).parallel().map(row -> {
            int max = Integer.MIN_VALUE;
            for (int column = 0; column < width; column++) {
                max = Math.max(max, valueAt(column, row));
            }
            return max;
        }).max().orElse(Integer.MIN_VALUE);
    }

    /** The height of the pixel (column, row) of the map itself. */
    int valueAt(int column, int row) {
        return values.get(SHORT, 2L * ((long) row * width + column));
    }

    /** Y of the highest column. */
    int highest() {
        return highest;
    }

    /** The layer {@code height} of a {@code world.bin} (see {@link WorldFile}); the map repeats endlessly outside, as it is. */
    static HeightMapData fromWorld(File file) throws IOException {
        WorldFile.Directory directory = WorldFile.read(file);
        WorldFile.Layer layer = directory.layer(WorldFile.HEIGHT);
        if (layer.offset() + layer.bytes() > file.length()) {
            throw new IOException("world.bin trop court : " + file);
        }
        return new HeightMapData(layer.columns(), layer.rows(), MappedFile.readOnly(file, layer.offset(), layer.bytes()));
    }

    /** Y level of the top block at world column (x, z). */
    int heightAt(int x, int z) {
        return valueAt(Math.floorMod(x + width / 2, width), Math.floorMod(z + height / 2, height));
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    @Override
    public void close() {
        mapped.close();
    }
}
