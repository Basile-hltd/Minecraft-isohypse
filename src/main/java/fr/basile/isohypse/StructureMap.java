package fr.basile.isohypse;

import org.bukkit.block.Biome;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code structure.png}: the height map in grey (no sea in blue, the lowest point is black), with a coloured dot on the
 * place of every structure of the overworld, one colour per structure set, and a glossary with the number of each.
 *
 * <p>The places come from the vanilla placement rules ({@code worldgen/structure_set} of the game: grid of
 * {@code spacing} chunks with one draw per cell, drawn from the world seed and the {@code salt}), and a structure is
 * kept only if the biome of the plugin on that spot is one the structure accepts. Only the map itself is drawn, never
 * its copies around it. The dot is on the middle of the chunk the structure starts in.
 */
final class StructureMap {

    /**
     * One structure set of the game (overworld).
     *
     * @param spacing chunks between two cells of the grid
     * @param separation chunks kept free between the draws of two cells
     * @param triangular whether the draw is the mean of two (towards the middle of the cell) instead of one
     * @param reduction 0 none, 1 to 3 the {@code legacy_type_N} way to keep only {@code frequency} of the places
     * @param excludeRadius chunks around a place of {@code excludeSet} where this set has none (0 for none)
     * @param biomes the biomes (names) where one of the structures of the set can start
     */
    private record Group(String name, int rgb, int spacing, int separation, int salt, boolean triangular, int reduction,
                         float frequency, int excludeRadius, String excludeSet, String biomes) {}

    /** The sets of the game, from its data (worldgen/structure_set and the biome tags of each structure). */
    private static final List<Group> GROUPS = List.of(
        new Group("abandoned_camp", 0xe6194b, 37, 8, 91231127, false, 0, 1.0f, 0, "",
                "bamboo_jungle birch_forest cherry_grove dappled_forest flower_forest forest meadow old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga pale_garden savanna snowy_taiga sparse_jungle swamp taiga windswept_forest wooded_badlands"),
        new Group("ancient_cities", 0x3cb44b, 24, 8, 20083232, false, 0, 1.0f, 0, "",
                "deep_dark"),
        new Group("buried_treasures", 0xffe119, 1, 0, 0, false, 2, 0.01f, 0, "",
                "beach snowy_beach"),
        new Group("desert_pyramids", 0x4363d8, 32, 8, 14357617, false, 0, 1.0f, 0, "",
                "desert"),
        new Group("igloos", 0xf58231, 32, 8, 14357618, false, 0, 1.0f, 0, "",
                "snowy_plains snowy_slopes snowy_taiga"),
        new Group("jungle_temples", 0x911eb4, 32, 8, 14357619, false, 0, 1.0f, 0, "",
                "bamboo_jungle jungle"),
        new Group("mineshafts", 0x42d4f4, 1, 0, 0, false, 3, 0.004f, 0, "",
                "badlands bamboo_jungle beach birch_forest cherry_grove cold_ocean dappled_forest dark_forest deep_cold_ocean deep_frozen_ocean deep_lukewarm_ocean deep_ocean desert dripstone_caves eroded_badlands flower_forest forest frozen_ocean frozen_peaks frozen_river grove ice_spikes jagged_peaks jungle lukewarm_ocean lush_caves mangrove_swamp meadow mushroom_fields ocean old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga pale_garden plains river savanna savanna_plateau snowy_beach snowy_plains snowy_slopes snowy_taiga sparse_jungle stony_peaks stony_shore sulfur_caves sunflower_plains swamp taiga warm_ocean windswept_forest windswept_gravelly_hills windswept_hills windswept_savanna wooded_badlands"),
        new Group("ocean_monuments", 0xf032e6, 32, 5, 10387313, true, 0, 1.0f, 0, "",
                "deep_cold_ocean deep_frozen_ocean deep_lukewarm_ocean deep_ocean"),
        new Group("ocean_ruins", 0xbfef45, 20, 8, 14357621, false, 0, 1.0f, 0, "",
                "cold_ocean deep_cold_ocean deep_frozen_ocean deep_lukewarm_ocean deep_ocean frozen_ocean lukewarm_ocean ocean warm_ocean"),
        new Group("pillager_outposts", 0xfabed4, 32, 8, 165745296, false, 1, 0.2f, 10, "villages",
                "cherry_grove desert frozen_peaks grove jagged_peaks meadow plains savanna snowy_plains snowy_slopes stony_peaks taiga"),
        new Group("ruined_portals", 0x469990, 40, 15, 34222645, false, 0, 1.0f, 0, "",
                "badlands bamboo_jungle basalt_deltas beach birch_forest cherry_grove cold_ocean crimson_forest dappled_forest dark_forest deep_cold_ocean deep_frozen_ocean deep_lukewarm_ocean deep_ocean desert dripstone_caves eroded_badlands flower_forest forest frozen_ocean frozen_peaks frozen_river grove ice_spikes jagged_peaks jungle lukewarm_ocean lush_caves mangrove_swamp meadow mushroom_fields nether_wastes ocean old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga pale_garden plains river savanna savanna_plateau snowy_beach snowy_plains snowy_slopes snowy_taiga soul_sand_valley sparse_jungle stony_peaks stony_shore sulfur_caves sunflower_plains swamp taiga warm_ocean warped_forest windswept_forest windswept_gravelly_hills windswept_hills windswept_savanna wooded_badlands"),
        new Group("shipwrecks", 0xdcbeff, 24, 4, 165745295, false, 0, 1.0f, 0, "",
                "beach cold_ocean deep_cold_ocean deep_frozen_ocean deep_lukewarm_ocean deep_ocean frozen_ocean lukewarm_ocean ocean snowy_beach warm_ocean"),
        new Group("swamp_huts", 0x9a6324, 32, 8, 14357620, false, 0, 1.0f, 0, "",
                "swamp"),
        new Group("trail_ruins", 0x800000, 34, 8, 83469867, false, 0, 1.0f, 0, "",
                "jungle old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga snowy_taiga taiga"),
        new Group("trial_chambers", 0xaaffc3, 34, 12, 94251327, false, 0, 1.0f, 0, "",
                "badlands bamboo_jungle beach birch_forest cherry_grove cold_ocean dappled_forest dark_forest deep_cold_ocean deep_frozen_ocean deep_lukewarm_ocean deep_ocean desert dripstone_caves eroded_badlands flower_forest forest frozen_ocean frozen_peaks frozen_river grove ice_spikes jagged_peaks jungle lukewarm_ocean lush_caves mangrove_swamp meadow mushroom_fields ocean old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga pale_garden plains river savanna savanna_plateau snowy_beach snowy_plains snowy_slopes snowy_taiga sparse_jungle stony_peaks stony_shore sulfur_caves sunflower_plains swamp taiga warm_ocean windswept_forest windswept_gravelly_hills windswept_hills windswept_savanna wooded_badlands"),
        new Group("villages", 0x808000, 34, 8, 10387312, false, 0, 1.0f, 0, "",
                "desert meadow plains savanna snowy_plains taiga"),
        new Group("woodland_mansions", 0x000075, 80, 20, 10387319, true, 0, 1.0f, 0, "",
                "dark_forest pale_garden")
    );

    private static final int STRONGHOLD_RGB = 0xff0000;
    private static final int STRONGHOLD_COUNT = 128;
    private static final int STRONGHOLD_DISTANCE = 32;
    private static final int STRONGHOLD_SPREAD = 3;

    /** Y where the biome of an ancient city is read (it is a cave biome). */
    private static final int ANCIENT_CITY_Y = -30;

    record Dot(int x, int z, int rgb) {}

    private StructureMap() {
    }

    /** Writes {@code structure.png}. */
    static void write(HeightMapData data, BiomeLayout layout, int seaLevel, long seed, File file, Logger logger) throws IOException {
        int width = data.width();
        int height = data.height();
        int halfWidth = width / 2;
        int halfHeight = height / 2;

        List<List<Dot>> found = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (Group group : GROUPS) {
            found.add(place(group, data, layout, seed, halfWidth, halfHeight));
            names.add(group.name());
            colors.add(group.rgb());
        }
        found.add(strongholds(seed, halfWidth, halfHeight));
        names.add("strongholds");
        colors.add(STRONGHOLD_RGB);

        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int v = data.heightAt(i - halfWidth, j - halfHeight);
                lowest = Math.min(lowest, v);
                highest = Math.max(highest, v);
            }
        }
        int unit = Math.max(1, height / 800);
        int legendWidth = 360 * unit;
        BufferedImage image = new BufferedImage(width + legendWidth, height, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int v = data.heightAt(i - halfWidth, j - halfHeight);
                int grey = Math.round(255f * (v - lowest) / Math.max(1, highest - lowest));
                image.setRGB(i, j, (grey << 16) | (grey << 8) | grey);
            }
        }

        int radius = Math.max(3, width / 600);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(Math.max(1, radius / 4f)));
            for (List<Dot> dots : found) {
                for (Dot dot : dots) {
                    int cx = dot.x() + halfWidth;
                    int cy = dot.z() + halfHeight;
                    g.setColor(new Color(dot.rgb()));
                    g.fillOval(cx - radius, cy - radius, 2 * radius, 2 * radius);
                    g.setColor(Color.BLACK);
                    g.drawOval(cx - radius, cy - radius, 2 * radius, 2 * radius);
                }
            }
            g.setColor(Color.WHITE);
            g.fillRect(width, 0, legendWidth, height);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18 * unit));
            FontMetrics metrics = g.getFontMetrics();
            int rowHeight = (int) (metrics.getHeight() * 1.3);
            int y = rowHeight;
            List<Integer> order = new ArrayList<>();
            for (int s = 0; s < names.size(); s++) {
                order.add(s);
            }
            order.sort((p, q) -> found.get(q).size() - found.get(p).size()); // the most numerous on top
            for (int s : order) {
                int top = y - metrics.getAscent();
                g.setColor(new Color(colors.get(s)));
                g.fillOval(width + 10 * unit, top, metrics.getAscent(), metrics.getAscent());
                g.setColor(Color.BLACK);
                g.drawOval(width + 10 * unit, top, metrics.getAscent(), metrics.getAscent());
                g.drawString(names.get(s) + " : " + found.get(s).size(),
                        width + 10 * unit + metrics.getAscent() + 8 * unit, y);
                y += rowHeight;
            }
        } catch (RuntimeException | LinkageError e) { // e.g. a headless Linux without fonts
            logger.warning("Structures : dessin impossible (" + e + ")");
        } finally {
            g.dispose();
        }
        ImageIO.write(image, "png", file);
        MapExport.write(file.getAbsoluteFile().getParentFile(), data, layout, seaLevel, names, colors, found, logger);
        int total = found.stream().mapToInt(List::size).sum();
        logger.info(file.getAbsolutePath() + " : " + total + " structures sur la carte d'origine (hauteur en gris de "
                + lowest + " à " + highest + ", sans la mer en bleu), un point par structure, une couleur par groupe");
    }

    /** The places of a random-spread group inside the map: x and z in blocks from the centre of the map. */
    private static List<Dot> place(Group group, HeightMapData data, BiomeLayout layout, long seed, int halfWidth,
                                   int halfHeight) {
        Set<String> accepted = new HashSet<>(List.of(group.biomes().split(" ")));
        List<Dot> dots = new ArrayList<>();
        int minChunkX = Math.floorDiv(-halfWidth, 16);
        int maxChunkX = Math.floorDiv(halfWidth - 1, 16);
        int minChunkZ = Math.floorDiv(-halfHeight, 16);
        int maxChunkZ = Math.floorDiv(halfHeight - 1, 16);
        int[] chunk = new int[2];
        for (int cellX = Math.floorDiv(minChunkX, group.spacing()); cellX <= Math.floorDiv(maxChunkX, group.spacing()); cellX++) {
            for (int cellZ = Math.floorDiv(minChunkZ, group.spacing()); cellZ <= Math.floorDiv(maxChunkZ, group.spacing()); cellZ++) {
                potentialChunk(group, seed, cellX, cellZ, chunk);
                int chunkX = chunk[0];
                int chunkZ = chunk[1];
                if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                    continue;
                }
                if (!keepsFrequency(group, seed, chunkX, chunkZ)) {
                    continue;
                }
                if (group.excludeRadius() > 0 && nearOther(group, seed, chunkX, chunkZ)) {
                    continue;
                }
                int x = chunkX * 16 + 8;
                int z = chunkZ * 16 + 8;
                if (x < -halfWidth || x >= halfWidth || z < -halfHeight || z >= halfHeight) {
                    continue;
                }
                int top = data.heightAt(x, z);
                BiomeLayout.Entry entry = group.name().equals("ancient_cities")
                        ? layout.at(x, ANCIENT_CITY_Y, z, top) : layout.at(x, z, top);
                Biome biome = entry == null ? null : entry.biome();
                if (biome != null && accepted.contains(biome.key().value())) {
                    dots.add(new Dot(x, z, group.rgb()));
                }
            }
        }
        return dots;
    }

    /** The chunk where the cell (cellX, cellZ) of the grid of the group draws its structure (RandomSpreadStructurePlacement). */
    private static void potentialChunk(Group group, long seed, int cellX, int cellZ, int[] out) {
        Random random = new Random((long) cellX * 341873128712L + (long) cellZ * 132897987541L + seed + group.salt());
        int range = group.spacing() - group.separation();
        int dx;
        int dz;
        if (group.triangular()) {
            dx = (random.nextInt(range) + random.nextInt(range)) / 2;
            dz = (random.nextInt(range) + random.nextInt(range)) / 2;
        } else {
            dx = random.nextInt(range);
            dz = random.nextInt(range);
        }
        out[0] = cellX * group.spacing() + dx;
        out[1] = cellZ * group.spacing() + dz;
    }

    /** The {@code frequency} of the group: whether this chunk is one of the few kept. */
    private static boolean keepsFrequency(Group group, long seed, int chunkX, int chunkZ) {
        Random random = new Random();
        switch (group.reduction()) {
            case 1 -> {
                random.setSeed((long) ((chunkX >> 4) ^ ((chunkZ >> 4) << 4)) ^ seed);
                random.nextInt();
                return random.nextInt((int) (1.0F / group.frequency())) == 0;
            }
            case 2 -> {
                random.setSeed((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L + seed + 10387320L);
                return random.nextFloat() < group.frequency();
            }
            case 3 -> {
                random.setSeed(seed);
                long a = random.nextLong();
                long b = random.nextLong();
                random.setSeed((long) chunkX * a ^ (long) chunkZ * b ^ seed);
                return random.nextDouble() < group.frequency();
            }
            default -> {
                return true;
            }
        }
    }

    /** Whether a place of the excluded group is within the exclusion radius (in chunks) of this chunk. */
    private static boolean nearOther(Group group, long seed, int chunkX, int chunkZ) {
        Group other = GROUPS.stream().filter(g -> g.name().equals(group.excludeSet())).findFirst().orElseThrow();
        int radius = group.excludeRadius();
        int[] chunk = new int[2];
        for (int cellX = Math.floorDiv(chunkX - radius, other.spacing()); cellX <= Math.floorDiv(chunkX + radius, other.spacing()); cellX++) {
            for (int cellZ = Math.floorDiv(chunkZ - radius, other.spacing()); cellZ <= Math.floorDiv(chunkZ + radius, other.spacing()); cellZ++) {
                potentialChunk(other, seed, cellX, cellZ, chunk);
                if (Math.abs(chunk[0] - chunkX) <= radius && Math.abs(chunk[1] - chunkZ) <= radius) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The strongholds (concentric rings around the origin) that fall inside the map. The game then moves each one
     * to a preferred biome within 112 blocks; this is not done here.
     */
    private static List<Dot> strongholds(long seed, int halfWidth, int halfHeight) {
        Random random = new Random(seed);
        double angle = random.nextDouble() * Math.PI * 2;
        int ring = 0;
        int inRing = 0;
        int spread = STRONGHOLD_SPREAD;
        List<Dot> dots = new ArrayList<>();
        for (int i = 0; i < STRONGHOLD_COUNT; i++) {
            double distance = 4.0 * STRONGHOLD_DISTANCE + STRONGHOLD_DISTANCE * ring * 6.0
                    + (random.nextDouble() - 0.5) * STRONGHOLD_DISTANCE * 2.5;
            int chunkX = (int) Math.round(Math.cos(angle) * distance);
            int chunkZ = (int) Math.round(Math.sin(angle) * distance);
            int x = chunkX * 16 + 8;
            int z = chunkZ * 16 + 8;
            if (x >= -halfWidth && x < halfWidth && z >= -halfHeight && z < halfHeight) {
                dots.add(new Dot(x, z, STRONGHOLD_RGB));
            }
            angle += Math.PI * 2 / spread;
            if (++inRing == spread) {
                ring++;
                inRing = 0;
                spread += 2 * spread / (ring + 1);
                spread = Math.min(spread, STRONGHOLD_COUNT - i - 1);
                angle += random.nextDouble() * Math.PI * 2;
            }
        }
        return dots;
    }
}
