package fr.basile.isohypse;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Biomes of the Realistic style: the altitude of the column in the height map and the depth under its surface decide
 * (see {@link BiomeLayout}): a surface biome, or a cave biome deep enough. Thread safe since the layout and the height map are read-only.
 */
final class HeightBiomeProvider extends BiomeProvider {

    private final RealisticGenerator generator;

    HeightBiomeProvider(RealisticGenerator generator) {
        this.generator = generator;
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return generator.biomeAt(worldInfo, x, y, z);
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return BiomeLayout.allBiomes();
    }
}
