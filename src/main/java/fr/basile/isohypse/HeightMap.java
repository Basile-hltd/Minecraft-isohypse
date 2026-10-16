package fr.basile.isohypse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Carte de décalage : 1 pixel = 1 colonne de blocs. La carte est centrée sur l'origine : le
 * pixel (px, py) correspond au bloc (x = px - largeur/2, z = py - hauteur/2) ; sa valeur (échantillon brut de l'image : 0-255 en 8 bits, 0-65535 en 16 bits) est la hauteur en blocs.
 */
public final class HeightMap {

    private final int width;
    private final int height;
    private final short[] data; // null = carte plate (aucun décalage)

    private HeightMap(int width, int height, short[] data) {
        this.width = width;
        this.height = height;
        this.data = data;
    }

    /** Carte sans décalage, de {@code chunks} x {@code chunks} chunks. */
    public static HeightMap flat(int chunks) {
        return new HeightMap(chunks * 16, chunks * 16, null);
    }

    public static HeightMap load(File file) throws IOException {
        BufferedImage image = ImageIO.read(file);
        if (image == null) throw new IOException("Format d'image non reconnu : " + file.getName());
        int w = image.getWidth();
        int h = image.getHeight();
        if (w % 16 != 0 || h % 16 != 0) {
            throw new IOException("Les dimensions doivent être des multiples de 16 (image " + w + "x" + h + ")");
        }
        short[] data = new short[w * h];
        var raster = image.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                data[y * w + x] = (short) raster.getSample(x, y, 0);
            }
        }
        return new HeightMap(w, h, data);
    }

    /** Vrai si au moins une colonne du chunk est couverte par la carte. */
    public boolean containsChunk(int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        return x0 + 15 >= -width / 2 && x0 < width - width / 2
                && z0 + 15 >= -height / 2 && z0 < height - height / 2;
    }

    /** Valeur brute (hauteur en blocs) de la colonne de blocs (x, z), ou -1 hors de la carte. */
    public int shiftAt(int blockX, int blockZ) {
        int px = blockX + width / 2;
        int pz = blockZ + height / 2;
        if (px < 0 || pz < 0 || px >= width || pz >= height) return -1;
        return data == null ? 0 : data[pz * width + px] & 0xFFFF;
    }

    public int widthBlocks() {
        return width;
    }

    public int heightBlocks() {
        return height;
    }
}
