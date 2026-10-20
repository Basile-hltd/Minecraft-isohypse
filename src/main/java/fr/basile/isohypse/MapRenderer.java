package fr.basile.isohypse;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.BiomeTagKeys;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Draws the readable maps written next to the height map, one pixel per block coloured with its biome (see
 * {@link BiomeLayout}), and on the right a glossary:
 * <ul>
 * <li>{@link #surface}: the surface biomes present in the map with their colour, the underground ones, then, in red,
 * every other overworld biome Paper knows (the registry of the running server);</li>
 * <li>{@link #caves}: two maps side by side, the cave biomes met at two depths, and in red the cave biomes missing
 * from both.</li>
 * </ul>
 */
final class MapRenderer {

    /** Width of the glossary panel, in units of text size (the longest row has to fit). */
    private static final int LEGEND_WIDTH = 440;
    private static final Color PANEL = Color.WHITE;
    private static final Color TEXT = Color.BLACK;
    private static final Color ABSENT = new Color(0xd02020);

    /** The cave map shows the cave biomes at these two Y: one above the deep dark, one under its upper limit. */
    private static final int[] CAVE_MAP_Y = {0, -40};

    /** What the cave map shows where a cave has no biome of its own (the surface one applies): grey, no biome. */
    private static final BiomeLayout.Entry NO_CAVE_BIOME = new BiomeLayout.Entry(null, 0x8c8c8c);

    private MapRenderer() {
    }

    /** What a pixel shows, from its world position and the Y of the top block of its column. */
    private interface Sampler<T> {
        T at(int x, int z, int top);
    }

    /** One map of the picture, with an optional caption written in its corner. */
    private record Panel<T>(String caption, Sampler<T> sampler) {}

    /** A glossary section: a title in a colour, and rows of a name with an optional colour swatch (-1 for none). */
    private record Section(String title, Color color, List<Row> rows) {}

    private record Row(String name, int rgb) {}

    /** The painted picture and the scale (pixels per unit of text size) it was drawn with. */
    private record Drawn(BufferedImage image, int unit) {}

    /**
     * The surface map of the whole block (a single map, or the middle map and its eight copies around it, the original
     * outlined in black).
     */
    static BufferedImage surface(HeightMapData data, BiomeLayout layout, Logger logger) {
        List<Biome> known = knownBiomes(logger);
        List<BiomeLayout.Entry> entries = BiomeLayout.allEntries();
        List<BiomeLayout.Entry> caves = BiomeLayout.caveEntries();
        // One row per biome (present, underground or absent), so the glossary size does not depend on the map.
        Set<Biome> every = new HashSet<>(known);
        entries.forEach(entry -> every.add(entry.biome()));
        caves.forEach(entry -> every.add(entry.biome()));

        Map<Biome, BiomeLayout.Entry> present = new LinkedHashMap<>();
        Drawn drawn = draw(data, List.of(new Panel<BiomeLayout.Entry>(null, (x, z, top) -> layout.at(x, z, top))),
                5 + every.size(), BiomeLayout.Entry::rgb, entry -> present.put(entry.biome(), entry), logger);

        List<Row> inMap = new ArrayList<>();
        for (BiomeLayout.Entry entry : entries) {
            if (present.containsKey(entry.biome())) {
                inMap.add(new Row(name(entry.biome()), entry.rgb()));
            }
        }
        List<Row> underground = caves.stream().map(cave -> new Row(name(cave.biome()), cave.rgb())).toList();
        List<Row> absent = new ArrayList<>();
        for (Biome biome : known) {
            if (!present.containsKey(biome) && caves.stream().noneMatch(cave -> cave.biome().equals(biome))) {
                absent.add(new Row(name(biome), -1));
            }
        }
        glossary(drawn, List.of(new Section("Biomes de la carte", TEXT, inMap),
                new Section("Souterrains (pas sur la carte)", TEXT, underground),
                new Section("Absents de la carte", ABSENT, absent)), logger);
        return drawn.image();
    }

    /**
     * The intermediate map: the seven zones of {@link TempLayout}, from the altitude alone, with the Y range of each
     * in the glossary.
     */
    static BufferedImage temp(HeightMapData data, TempLayout layout, Logger logger) {
        List<TempLayout.Zone> zones = layout.all();
        int oceanBiomes = 0;
        for (OceanType type : OceanType.values()) {
            oceanBiomes += type.biomes().size();
        }
        int landBiomes = 0;
        for (LandType type : LandType.values()) {
            landBiomes += type.biomes().size();
        }
        Drawn drawn = draw(data, List.of(new Panel<TempLayout.Zone>(null, (x, z, top) -> layout.at(x, z, top))),
                6 + zones.size() + OceanType.values().length + oceanBiomes + LandType.values().length + landBiomes, TempLayout.Zone::rgb, zone -> { }, logger);
        List<Row> rows = zones.stream().map(zone -> new Row(zone.name() + " : " + zone.range(), zone.rgb())).toList();
        List<Row> oceans = new ArrayList<>();
        for (OceanType type : OceanType.values()) {
            oceans.add(new Row(type.id() + " :", -1));
            type.biomes().forEach(biome -> oceans.add(new Row("    " + name(biome), -1)));
        }
        List<Row> lands = new ArrayList<>();
        for (LandType type : LandType.values()) {
            lands.add(new Row(type.id() + " :", -1));
            type.weighted().forEach(weighted -> lands.add(new Row("    " + name(weighted.biome()) + " "
                    + Math.round(type.percent(weighted.weight())) + " %", -1)));
        }
        glossary(drawn, List.of(new Section("Zones de la carte", TEXT, rows),
                new Section("Océans du jeu par type", TEXT, oceans),
                new Section("Biomes terrestres par type", TEXT, lands)), logger);
        return drawn.image();
    }

    /** The cave map: the cave biomes at {@link #CAVE_MAP_Y}, one map per depth, side by side. */
    static BufferedImage caves(HeightMapData data, BiomeLayout layout, Logger logger) {
        List<BiomeLayout.Entry> caves = BiomeLayout.caveEntries();
        List<Panel<BiomeLayout.Entry>> panels = new ArrayList<>();
        for (int y : CAVE_MAP_Y) {
            panels.add(new Panel<>("Y = " + y, (x, z, top) -> {
                BiomeLayout.Entry cave = layout.caveAt(x, y, z, top);
                return cave != null ? cave : NO_CAVE_BIOME;
            }));
        }
        Map<Biome, BiomeLayout.Entry> present = new LinkedHashMap<>();
        Drawn drawn = draw(data, panels, 5 + caves.size(), BiomeLayout.Entry::rgb, entry -> present.put(entry.biome(), entry), logger);

        List<Row> inMap = new ArrayList<>();
        List<Row> absent = new ArrayList<>();
        for (BiomeLayout.Entry cave : caves) {
            if (present.containsKey(cave.biome())) {
                inMap.add(new Row(name(cave.biome()), cave.rgb()));
            } else {
                absent.add(new Row(name(cave.biome()), -1));
            }
        }
        if (present.containsKey(null)) {
            inMap.add(new Row("aucun (le biome de la surface)", NO_CAVE_BIOME.rgb()));
        }
        glossary(drawn, List.of(new Section("Biomes souterrains de la carte", TEXT, inMap),
                new Section("Absents de la carte", ABSENT, absent)), logger);
        return drawn.image();
    }

    /**
     * Paints the panels left to right, then leaves room on the right for a glossary of {@code rows} rows.
     *
     * @param rgb the colour of what a pixel shows
     * @param seen called with what every pixel shows (to know which of them are present)
     */
    private static <T> Drawn draw(HeightMapData data, List<Panel<T>> panels, int rows,
                                  java.util.function.ToIntFunction<T> rgb, java.util.function.Consumer<T> seen,
                                  Logger logger) {
        System.setProperty("java.awt.headless", "true");
        int tileWidth = data.width();
        int tileHeight = data.height();
        int tiles = data.tilesPerSide();
        int count = panels.size();
        int step = 1; // blocks per pixel: one pixel per block, whatever the size of the picture
        int width = tiles * tileWidth;
        int height = tiles * tileHeight;

        int unit = Math.max(1, height / 800);
        int rowHeight = 22 * unit;
        int gap = 6 * unit;
        int legendWidth = LEGEND_WIDTH * unit;
        int axisLeft = 70 * unit;   // room on the left for the sizes in blocks of the vertical axis
        int axisBottom = 30 * unit; // and under the maps for the horizontal one
        int mapsWidth = count * width + (count - 1) * gap;
        int imageHeight = Math.max(height + axisBottom, rows * rowHeight + 2 * rowHeight);

        BufferedImage image = new BufferedImage(axisLeft + mapsWidth + legendWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D background = image.createGraphics();
        background.setColor(PANEL);
        background.fillRect(0, 0, image.getWidth(), image.getHeight());
        background.dispose();

        for (int p = 0; p < count; p++) {
            int left = axisLeft + p * (width + gap);
            Sampler<T> sampler = panels.get(p).sampler();
            for (int j = 0; j < height; j++) {
                for (int i = 0; i < width; i++) {
                    // The original map is the middle one, centred on x=0, z=0 like HeightMapData.
                    int x = i * step - (tiles / 2) * tileWidth - tileWidth / 2;
                    int z = j * step - (tiles / 2) * tileHeight - tileHeight / 2;
                    T shown = sampler.at(x, z, data.heightAt(x, z));
                    image.setRGB(left + i, j, rgb.applyAsInt(shown));
                    seen.accept(shown);
                }
            }
            try {
                decorate(image, left, tileWidth / step, tileHeight / step, tiles > 1, panels.get(p).caption(), unit);
                axes(image, left, width, height, tiles * tileWidth, tiles * tileHeight, p == 0 ? axisLeft : 0, unit);
            } catch (RuntimeException | LinkageError e) { // e.g. a headless Linux without fonts
                logger.warning("Carte : légende impossible à dessiner (" + e + ")");
            }
        }
        return new Drawn(image, unit);
    }

    /** Outlines the original map in black when it is surrounded by copies, and writes the caption of the panel. */
    private static void decorate(BufferedImage image, int left, int tileWidth, int tileHeight, boolean outline,
                                 String caption, int unit) {
        Graphics2D g = image.createGraphics();
        try {
            if (outline) {
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(unit));
                g.drawRect(left + tileWidth, tileHeight, tileWidth, tileHeight);
            }
            if (caption != null) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20 * unit));
                FontMetrics metrics = g.getFontMetrics();
                int pad = 6 * unit;
                g.setColor(PANEL);
                g.fillRect(left + pad, pad, metrics.stringWidth(caption) + 2 * pad, metrics.getHeight() + pad);
                g.setColor(TEXT);
                g.drawString(caption, left + 2 * pad, pad + metrics.getAscent() + pad / 2);
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * Rulers in blocks: ticks and numbers under the map for its width (0 at its left edge), and, for the first map,
     * on its left for its height (0 at its bottom edge), the last number being the whole size.
     *
     * @param originX the x of the left edge of the map in the image
     * @param blocksWide the width of the map in blocks, {@code blocksHigh} its height
     * @param leftRoom the width of the margin on the left of the map where the vertical ruler goes, 0 for none
     */
    private static void axes(BufferedImage image, int originX, int width, int height, int blocksWide, int blocksHigh,
                             int leftRoom, int unit) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13 * unit));
            g.setStroke(new BasicStroke(Math.max(1, unit)));
            FontMetrics metrics = g.getFontMetrics();
            int tick = 5 * unit;
            g.setColor(TEXT);
            for (int block : ticks(blocksWide)) {
                int x = originX + (int) ((long) block * width / blocksWide);
                g.drawLine(x, height, x, height + tick);
                String label = String.valueOf(block);
                int labelX = Math.min(x - metrics.stringWidth(label) / 2, originX + width - metrics.stringWidth(label));
                g.drawString(label, Math.max(originX, labelX), height + tick + metrics.getAscent());
            }
            if (leftRoom > 0) {
                for (int block : ticks(blocksHigh)) {
                    int y = height - (int) ((long) block * height / blocksHigh);
                    g.drawLine(originX - tick, y, originX, y);
                    String label = String.valueOf(block);
                    int labelY = Math.max(metrics.getAscent(), Math.min(height, y + metrics.getAscent() / 2));
                    g.drawString(label, originX - tick - 3 * unit - metrics.stringWidth(label), labelY);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Round numbers of blocks from 0 to the size (included): about eight steps of 1, 2 or 5 times a power of ten. */
    private static List<Integer> ticks(int size) {
        double rough = size / 8.0;
        double power = Math.pow(10, Math.floor(Math.log10(rough)));
        double step = power;
        for (double factor : new double[]{1, 2, 5, 10}) {
            step = factor * power;
            if (step >= rough) {
                break;
            }
        }
        List<Integer> ticks = new ArrayList<>();
        for (double block = 0; block < size; block += step) {
            if (block == 0 || size - block >= step / 2) { // no number crowding the total
                ticks.add((int) Math.round(block));
            }
        }
        ticks.add(size);
        return ticks;
    }

    /** Writes the sections in the white panel on the right of the image. */
    private static void glossary(Drawn drawn, List<Section> sections, Logger logger) {
        try {
            BufferedImage image = drawn.image();
            int unit = drawn.unit();
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 15 * unit);
                int rowHeight = 22 * unit;
                int margin = 10 * unit;
                int swatch = 16 * unit;
                int x = image.getWidth() - LEGEND_WIDTH * unit + margin;
                int y = rowHeight;
                for (Section section : sections) {
                    g.setFont(font.deriveFont(Font.BOLD));
                    g.setColor(section.color());
                    g.drawString(section.title(), x, y);
                    y += rowHeight;
                    g.setFont(font);
                    for (Row row : section.rows()) {
                        int textX = x;
                        if (row.rgb() >= 0) {
                            g.setColor(new Color(row.rgb()));
                            g.fillRect(x, y - swatch + 2 * unit, swatch, swatch);
                            g.setColor(Color.DARK_GRAY);
                            g.drawRect(x, y - swatch + 2 * unit, swatch, swatch);
                            textX = x + swatch + margin;
                        }
                        g.setColor(row.rgb() >= 0 ? TEXT : section.color());
                        g.drawString(row.name(), textX, y);
                        y += rowHeight;
                    }
                    y += rowHeight / 2;
                }
            } finally {
                g.dispose();
            }
        } catch (RuntimeException | LinkageError e) { // e.g. a headless Linux without fonts
            logger.warning("Carte : glossaire impossible à dessiner (" + e + "), carte seule");
        }
    }

    /**
     * Every overworld biome of the running server's registry (the Nether and the End ones are left out), sorted by
     * name; empty if the registry is unavailable.
     */
    private static List<Biome> knownBiomes(Logger logger) {
        List<Biome> all = new ArrayList<>();
        try {
            Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            all.addAll(registry.getTagValues(BiomeTagKeys.IS_OVERWORLD));
        } catch (RuntimeException | LinkageError e) {
            logger.warning("Carte : liste des biomes de Paper indisponible (" + e + "), pas de liste des absents");
        }
        all.sort((a, b) -> name(a).compareTo(name(b)));
        return all;
    }

    private static String name(Biome biome) {
        return biome.key().value();
    }
}
