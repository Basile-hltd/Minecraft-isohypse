package fr.basile.isohypse;

import org.bukkit.block.Biome;

import java.util.List;

/**
 * Every ocean biome of the game, sorted in three types: the zones of the intermediate map ({@link TempLayout}) are
 * one per type, and each will one day get its biomes from here.
 */
enum OceanType {

    /** The cold seas, with the ice. */
    COLD("ocean_cold", List.of(Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN)),
    /** The ordinary ocean. */
    NORMAL("ocean_normal", List.of(Biome.OCEAN, Biome.DEEP_OCEAN)),
    /** The warm seas. */
    WARM("ocean_warm", List.of(Biome.LUKEWARM_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.WARM_OCEAN));

    private final String id;
    private final List<Biome> biomes;

    OceanType(String id, List<Biome> biomes) {
        this.id = id;
        this.biomes = biomes;
    }

    /** The name of the type. */
    String id() {
        return id;
    }

    /** The biomes of the type, shallow ones first. */
    List<Biome> biomes() {
        return biomes;
    }
}
