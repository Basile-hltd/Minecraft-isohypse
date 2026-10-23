package fr.basile.isohypse;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Biomes of the Realistic style: the biome of the surface, or of a cave deep enough, read from the layers of
 * {@code world.bin} (see {@link WorldData}). Thread safe since the file is only read.
 */
final class HeightBiomeProvider extends BiomeProvider {

    private final RealisticGenerator generator;

    HeightBiomeProvider(RealisticGenerator generator) {
        this.generator = generator;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return generator.biomeAt(x, y, z);
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return BiomeLayout.allBiomes();
    }
}
