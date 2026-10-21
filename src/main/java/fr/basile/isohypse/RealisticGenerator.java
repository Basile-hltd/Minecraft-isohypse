package fr.basile.isohypse;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>Everything comes from {@code plugins/Isohypse/world.bin}, made when the world is created (see
 * {@link IsohypsePlugin}) and read from the disk by {@link WorldData}: the height of every column and the biomes (of the
 * surface and of the caves). The ground is filled with stone, with water up to the vanilla sea level (63). It replaces
 * the vanilla noise stage only: the other vanilla stages (surface rules, carvers, decorations, mobs) run on top of it;
 * there are no structures yet.
 */
public final class RealisticGenerator extends ChunkGenerator {

    /** Vanilla sea level: the low parts of the terrain are filled with water up to this Y. */
    private static final int SEA_LEVEL = HeightScale.SEA_LEVEL;
    /** Under a sea, the caves only start this many blocks under the lowest point of the sea floor of the map. */
    private static final int CARVER_SEA_MARGIN = 8;

    private final IsohypsePlugin plugin;

    RealisticGenerator(IsohypsePlugin plugin) {
        this.plugin = plugin;
    }

    /** The world data, made when the world was created. */
    private WorldData data() {
        WorldData data = plugin.worldData();
        if (data == null) {
            throw new IllegalStateException("Realistic: world.bin is not ready");
        }
        return data;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int minY = chunkData.getMinHeight();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
            }
        }
    }

    /** Biome at (x, y, z), from the layers of {@code world.bin}. */
    Biome biomeAt(int x, int y, int z) {
        Biome biome = data().biomeAt(x, y, z);
        return biome != null ? biome : Biome.PLAINS; // a biome that this version of the game does not have
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new HeightBiomeProvider(this);
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return data().heightAt(x, z) + 1;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    /**
     * The vanilla caves and ravines have just been carved in {@code chunkData}: under the seas, only what is below the
     * lowest point of the sea floor minus {@link #CARVER_SEA_MARGIN} is kept. Above it, in a column covered by water,
     * every carved block (air, water or lava where there was stone) is stone again, and the water above the sea floor
     * is whole again, so the sea floor is not broken into. On the land the caves are as the carvers made them.
     */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int limit = Math.max(chunkData.getMinHeight(), data.lowest() - CARVER_SEA_MARGIN);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                if (top >= SEA_LEVEL) {
                    continue; // land
                }
                for (int y = limit + 1; y <= top; y++) {
                    Material material = chunkData.getType(localX, y, localZ);
                    if (material.isAir() || material == Material.WATER || material == Material.LAVA) {
                        chunkData.setBlock(localX, y, localZ, Material.STONE);
                    }
                }
                for (int y = top + 1; y <= SEA_LEVEL; y++) {
                    if (chunkData.getType(localX, y, localZ).isAir()) {
                        chunkData.setBlock(localX, y, localZ, Material.WATER);
                    }
                }
            }
        }
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
