package fr.basile.isohypse;

import javax.imageio.ImageIO;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;

/**
 * A height map read from heightmap.png (see {@link HeightMapGenerator}): 1 pixel = 1 block, value = Y level of the
 * top block, the middle of the map being the world origin (x=0, z=0), north (row 0) towards negative z.
 * Outside the map the pattern repeats endlessly (modulo the map size).
 */
final class HeightMapData {

    private final int width;
    private final int height;
    private final int[] values;

    private HeightMapData(int width, int height, int[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    static HeightMapData read(File file) throws IOException {
        var image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Height map illisible : " + file);
        }
        Raster raster = image.getRaster();
        int width = raster.getWidth();
        int height = raster.getHeight();
        int[] values = new int[width * height];
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                values[j * width + i] = raster.getSample(i, j, 0);
            }
        }
        return new HeightMapData(width, height, values);
    }

    /** Y level of the top block at world column (x, z); the map repeats outside its bounds. */
    int heightAt(int x, int z) {
        int column = Math.floorMod(x + width / 2, width);
        int row = Math.floorMod(z + height / 2, height);
        return values[row * width + column];
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }
}
