package fr.basile.isohypse;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Aucune génération vanilla : pour chaque colonne couverte par la height map, un bloc d'herbe est
 * posé à y = valeur du pixel, avec 4 blocs de terre puis 10 de pierre dessous. Tout le reste est vide.
 */
public final class IsohypseGenerator extends ChunkGenerator {

    private static final int DIRT_LAYERS = 4;
    private static final int STONE_LAYERS = 10;

    private final HeightMap heightMap;

    public IsohypseGenerator(HeightMap heightMap) {
        this.heightMap = heightMap;
    }

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                              @NotNull ChunkData chunkData) {
        if (!heightMap.containsChunk(chunkX, chunkZ)) return;

        int min = worldInfo.getMinHeight();
        int max = worldInfo.getMaxHeight() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int value = heightMap.shiftAt((chunkX << 4) + x, (chunkZ << 4) + z);
                if (value < 0) continue;
                int y = Math.clamp(value, min, max);
                chunkData.setBlock(x, y, z, Material.GRASS_BLOCK);
                for (int i = 1; i <= DIRT_LAYERS + STONE_LAYERS && y - i >= min; i++) {
                    chunkData.setBlock(x, y - i, z, i <= DIRT_LAYERS ? Material.DIRT : Material.STONE);
                }
            }
        }
    }

    @Override
    public @Nullable Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5, Math.max(heightMap.shiftAt(0, 0), 0) + 1, 0.5);
    }
}
