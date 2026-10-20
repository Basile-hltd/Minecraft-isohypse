package fr.basile.isohypse;

import org.bukkit.block.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * The land biomes of the game, sorted by the land zone of the intermediate map ({@link TempLayout}) they belong to:
 * the beach, warm, normal and cold. A biome can be in several types (a plains is warm and normal, a taiga is normal
 * and cold...), with a different weight in each. The weight is the relative chance of the biome in the draw of the
 * type: 80 and 20 make a biome four times as likely as the other, whatever the number of biomes; without a number
 * the weight is 1. The weights follow the frequency of the biomes in the game (an estimate). The rivers, the mushroom fields and the eroded badlands are left out.
 */
enum LandType {

    /** The shore. */
    BEACH("beach", biomes(b(Biome.BEACH, 80), b(Biome.STONY_SHORE, 20))),
    /** The hot and humid or dry lowlands. */
    WARM("land_warm", biomes(b(Biome.DESERT, 23), b(Biome.SAVANNA, 18), b(Biome.JUNGLE, 11), b(Biome.SPARSE_JUNGLE, 9),
            b(Biome.BADLANDS, 5), b(Biome.PLAINS, 7), b(Biome.SWAMP, 5), b(Biome.SAVANNA_PLATEAU, 4),
            b(Biome.WOODED_BADLANDS, 3), b(Biome.BAMBOO_JUNGLE, 3), b(Biome.WINDSWEPT_SAVANNA, 3),
            b(Biome.MANGROVE_SWAMP, 3), b(Biome.SUNFLOWER_PLAINS, 1), b(Biome.FLOWER_FOREST, 1))),
    /** The temperate biomes: the plains and the forests, and where they meet the mountains. dappled_forest only
     * exists in recent versions. */
    NORMAL("land_normal", biomes(b(Biome.FOREST, 21), b(Biome.PLAINS, 20), b(Biome.TAIGA, 10), b(Biome.BIRCH_FOREST, 9),
            b(Biome.DARK_FOREST, 8), b(BiomeLayout.biome("dappled_forest"), 8), b(Biome.SWAMP, 2), b(Biome.MEADOW, 4),
            b(Biome.FLOWER_FOREST, 3), b(Biome.OLD_GROWTH_PINE_TAIGA, 3), b(Biome.SUNFLOWER_PLAINS, 2),
            b(Biome.OLD_GROWTH_BIRCH_FOREST, 2), b(Biome.CHERRY_GROVE, 2), b(Biome.OLD_GROWTH_SPRUCE_TAIGA, 2),
            b(Biome.PALE_GARDEN, 1))),
    /** The snow and the mountains. */
    COLD("land_cold", biomes(b(Biome.SNOWY_PLAINS, 18), b(Biome.SNOWY_TAIGA, 12), b(Biome.WINDSWEPT_HILLS, 10),
            b(Biome.SNOWY_SLOPES, 10), b(Biome.TAIGA, 9), b(Biome.JAGGED_PEAKS, 6), b(Biome.GROVE, 6),
            b(Biome.WINDSWEPT_FOREST, 5), b(Biome.FROZEN_PEAKS, 5), b(Biome.WINDSWEPT_GRAVELLY_HILLS, 4),
            b(Biome.OLD_GROWTH_PINE_TAIGA, 3), b(Biome.OLD_GROWTH_SPRUCE_TAIGA, 3), b(Biome.STONY_PEAKS, 3),
            b(Biome.MEADOW, 3), b(Biome.ICE_SPIKES, 1)));

    /** A biome and its weight in a type. */
    record Weighted(Biome biome, double weight) {}

    private final String id;
    private final List<Weighted> weighted;
    private final double totalWeight;

    LandType(String id, List<Weighted> weighted) {
        this.id = id;
        this.weighted = weighted;
        this.totalWeight = weighted.stream().mapToDouble(Weighted::weight).sum();
    }

    /** A biome with the weight 1; null (a biome that this version of the game does not have) gives nothing. */
    private static Weighted b(Biome biome) {
        return b(biome, 1);
    }

    private static Weighted b(Biome biome, double weight) {
        return biome == null ? null : new Weighted(biome, weight);
    }

    private static List<Weighted> biomes(Weighted... biomes) {
        List<Weighted> list = new ArrayList<>();
        for (Weighted biome : biomes) {
            if (biome != null) {
                list.add(biome);
            }
        }
        return List.copyOf(list);
    }

    /** The name of the type. */
    String id() {
        return id;
    }

    /** The biomes of the type with their weights. */
    List<Weighted> weighted() {
        return weighted;
    }

    /** The biomes of the type. */
    List<Biome> biomes() {
        return weighted.stream().map(Weighted::biome).toList();
    }

    /** The chance of a weight in this type, in percent. */
    double percent(double weight) {
        return 100 * weight / totalWeight;
    }
}
