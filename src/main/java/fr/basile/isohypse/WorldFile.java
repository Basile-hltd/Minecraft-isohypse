package fr.basile.isohypse;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The format of {@code world.bin}, the file that holds everything computed from the real altitudes (and of
 * {@code heightmap.bin}, its input, which has only the layer {@code height}), read by the plugin
 * when the game asks for a height, a biome or a structure, and by {@code tools/map_viewer.py}, which is why the format is
 * as simple as can be: a directory, then the layers one after another, each of them read on its own straight from the
 * disk (memory-mapped, never loaded whole). All numbers are big-endian.
 *
 * <pre>
 * int  magic, int version, int width, int height (in blocks), int number of layers
 * per layer: byte length of the name, the name (ASCII), byte type (1 = unsigned byte, 2 = signed short),
 *            int step (blocks per cell, 1 = one value per block), int columns, int rows, long offset in the file
 * the layers, row by row, north (row 0) towards negative z, column 0 towards negative x; the middle of the map is
 * the world origin (x=0, z=0)
 * </pre>
 *
 * <p>The layers: {@code height} (the Y of the top block of every column), {@code temp} (the index of the zone of
 * {@link TempLayout} of every column) and {@code biome}, {@code cave_biome}, {@code deep_cave_biome} (the index of the
 * biome at the surface and in the caves at two depths, one cell per 4 x 4 blocks). The names of the zones and the list of
 * the biomes are in {@code settings.json}.
 */
final class WorldFile {

    static final int MAGIC = 0x49534F57;
    static final int VERSION = 1;
    static final int UINT8 = 1;
    static final int INT16 = 2;

    static final String HEIGHT = "height";
    static final String TEMP = "temp";
    /** The surface biome, and the cave biome at two depths (see {@link HeightScale#CAVE_Y}): indexes in the list of biomes of settings.json. */
    static final String BIOME = "biome";
    static final String CAVE_BIOME = "cave_biome";
    static final String DEEP_CAVE_BIOME = "deep_cave_biome";
    /** Blocks per cell of the biome layers: the game asks for a biome of a 4 x 4 x 4 cell. */
    static final int BIOME_STEP = 4;

    private WorldFile() {
    }

    /** What a layer is: its name, the type of its values and its step in blocks. */
    record Spec(String name, int type, int step) {}

    /** A layer in the file. */
    record Layer(String name, int type, int step, int columns, int rows, long offset) {

        int bytesPerValue() {
            return type == INT16 ? 2 : 1;
        }

        long bytes() {
            return (long) columns * rows * bytesPerValue();
        }
    }

    record Directory(int width, int height, List<Layer> layers) {

        Layer layer(String name) {
            return layers.stream().filter(layer -> layer.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No layer " + name + " in world.bin"));
        }
    }

    /** The directory of a world of this size with these layers, in this order, with their offsets in the file. */
    static Directory plan(int width, int height, List<Spec> specs) {
        long offset = 4L * 5;
        for (Spec spec : specs) {
            offset += 1 + spec.name().length() + 1 + 4 + 4 + 4 + 8;
        }
        List<Layer> layers = new ArrayList<>();
        for (Spec spec : specs) {
            Layer layer = new Layer(spec.name(), spec.type(), spec.step(), (width + spec.step() - 1) / spec.step(),
                    (height + spec.step() - 1) / spec.step(), offset);
            layers.add(layer);
            offset += layer.bytes();
        }
        return new Directory(width, height, List.copyOf(layers));
    }

    /** The directory of {@code heightmap.bin}: the layer {@code height} alone. */
    static Directory planHeights(int width, int height) {
        return plan(width, height, List.of(new Spec(HEIGHT, INT16, 1)));
    }

    /**
     * The layers of {@code world.bin}, for a world of this size. The biome layers have one cell per {@link #BIOME_STEP}
     * blocks, the cell (c, r) being the biome of the block x = c * 4 - width / 2, z = r * 4 - height / 2 (the width and
     * the height are multiples of 32, so these are the very blocks the game asks for).
     */
    static Directory planWorld(int width, int height) {
        return plan(width, height, List.of(new Spec(HEIGHT, INT16, 1), new Spec(TEMP, UINT8, 1),
                new Spec(BIOME, UINT8, BIOME_STEP), new Spec(CAVE_BIOME, UINT8, BIOME_STEP),
                new Spec(DEEP_CAVE_BIOME, UINT8, BIOME_STEP)));
    }

    static void writeHeader(DataOutputStream out, Directory directory) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(directory.width());
        out.writeInt(directory.height());
        out.writeInt(directory.layers().size());
        for (Layer layer : directory.layers()) {
            byte[] name = layer.name().getBytes(StandardCharsets.US_ASCII);
            out.writeByte(name.length);
            out.write(name);
            out.writeByte(layer.type());
            out.writeInt(layer.step());
            out.writeInt(layer.columns());
            out.writeInt(layer.rows());
            out.writeLong(layer.offset());
        }
    }

    static Directory read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Ce n'est pas un world.bin : " + file);
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("world.bin de la version " + version + ", celle du plugin est la " + VERSION + " : " + file);
            }
            int width = in.readInt();
            int height = in.readInt();
            int count = in.readInt();
            List<Layer> layers = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                int type = in.readUnsignedByte();
                int step = in.readInt();
                int columns = in.readInt();
                int rows = in.readInt();
                long offset = in.readLong();
                layers.add(new Layer(new String(name, StandardCharsets.US_ASCII), type, step, columns, rows, offset));
            }
            return new Directory(width, height, List.copyOf(layers));
        }
    }
}
