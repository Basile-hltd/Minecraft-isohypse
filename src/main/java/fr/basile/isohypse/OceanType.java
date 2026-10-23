package fr.basile.isohypse;

import org.bukkit.block.Biome;

import java.util.List;
import java.util.function.Supplier;

/**
 * Every ocean biome of the game, sorted in three types: the zones of the intermediate map ({@link TempLayout}) are
 * one per type, and each will one day get its biomes from here.
 */
enum OceanType {

    /** The cold seas, with the ice. */
    COLD("ocean_cold", () -> List.of(Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN)),
    /** The ordinary ocean. */
    NORMAL("ocean_normal", () -> List.of(Biome.OCEAN, Biome.DEEP_OCEAN)),
    /** The warm seas. */
    WARM("ocean_warm", () -> List.of(Biome.LUKEWARM_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.WARM_OCEAN));

    private final String id;
    /** The biomes are looked up on the first use, so that the type can be used without a server (the biomes need it). */
    private final Supplier<List<Biome>> supplier;
    private List<Biome> biomes;

    OceanType(String id, Supplier<List<Biome>> supplier) {
        this.id = id;
        this.supplier = supplier;
    }

    /** The name of the type. */
    String id() {
        return id;
    }

    /** The biomes of the type, shallow ones first. */
    synchronized List<Biome> biomes() {
        if (biomes == null) {
            biomes = supplier.get();
        }
        return biomes;
    }
}
