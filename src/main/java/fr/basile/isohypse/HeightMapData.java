package fr.basile.isohypse;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * The heights of {@code world.bin}, read in memory: value = Y level of the top block, the middle of the map being the
 * world origin (x=0, z=0), north (row 0) towards negative z. The map repeats endlessly outside, as it is.
 */
final class HeightMapData {

    private final int width;
    private final int height;
    private final int[] values;
    private final int highest;

    private HeightMapData(int width, int height, int[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
        int max = Integer.MIN_VALUE;
        for (int v : values) {
            max = Math.max(max, v);
        }
        this.highest = max;
    }

    /** The height of the pixel (column, row) of the map itself. */
    int valueAt(int column, int row) {
        return values[row * width + column];
    }

    /** Y of the highest column. */
    int highest() {
        return highest;
    }

    /** The layer {@code height} of a {@code world.bin} (see {@link WorldFile}); the map repeats endlessly outside, as it is. */
    static HeightMapData fromWorld(File file) throws IOException {
        WorldFile.Directory directory = WorldFile.read(file);
        WorldFile.Layer layer = directory.layer(WorldFile.HEIGHT);
        int width = layer.columns();
        int height = layer.rows();
        int[] values = new int[width * height];
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20);
            long position = layer.offset();
            int index = 0;
            while (index < values.length) {
                buffer.clear();
                if (channel.read(buffer, position) <= 0) {
                    throw new IOException("world.bin trop court : " + file);
                }
                buffer.flip();
                int pairs = buffer.remaining() / 2;
                for (int i = 0; i < pairs && index < values.length; i++) {
                    values[index++] = buffer.getShort();
                }
                position += 2L * pairs;
            }
        }
        return new HeightMapData(width, height, values);
    }

    /** Y level of the top block at world column (x, z). */
    int heightAt(int x, int z) {
        return values[Math.floorMod(z + height / 2, height) * width + Math.floorMod(x + width / 2, width)];
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }
}
