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
 * the vanilla noise stage only: the other vanilla stages (surface rules, carvers, decorations, structures, mobs) run on
 * top of it. The structures are the game's own: where they start comes from the heights ({@link #getBaseHeight}) and the
 * biomes ({@link #biomeAt}) given here.
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
        int highestTop = Integer.MIN_VALUE;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                highestTop = Math.max(highestTop, top);
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
            }
        }
        logWaterAboveSea("noise", chunkX, chunkZ, chunkData, highestTop);
    }

    /** Debug: logs the water that {@code chunkData} holds above the sea level (there should be none) after a stage. */
    private void logWaterAboveSea(String stage, int chunkX, int chunkZ, ChunkData chunkData, int highestTop) {
        WorldData data = data();
        int count = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        StringBuilder columns = new StringBuilder();
        int shown = 0;
        int maxY = Math.min(chunkData.getMaxHeight() - 1, Math.max(highestTop, SEA_LEVEL) + 8);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int first = Integer.MAX_VALUE;
                int last = Integer.MIN_VALUE;
                for (int y = SEA_LEVEL + 1; y <= maxY; y++) {
                    if (chunkData.getType(localX, y, localZ) == Material.WATER) {
                        count++;
                        first = Math.min(first, y);
                        last = Math.max(last, y);
                    }
                }
                if (last == Integer.MIN_VALUE) {
                    continue;
                }
                lowest = Math.min(lowest, first);
                highest = Math.max(highest, last);
                if (shown++ < 5) {
                    int x = chunkX * 16 + localX;
                    int z = chunkZ * 16 + localZ;
                    columns.append(" [x=").append(x).append(" z=").append(z).append(" eau y=").append(first).append("..")
                            .append(last).append(" top=").append(data.heightAt(x, z)).append(" biome=")
                            .append(biomeAt(x, last, z).key().value()).append(']');
                }
            }
        }
        if (count > 0) {
            plugin.getLogger().warning("[eau] APRÈS " + stage + " chunk (" + chunkX + ", " + chunkZ + ") : " + count
                    + " blocs d'eau au-dessus du niveau de la mer (" + SEA_LEVEL + "), y " + lowest + " à " + highest + " dans "
                    + shown + " colonnes ; exemples :" + columns);
        }
    }

    private int highestTop(int chunkX, int chunkZ) {
        WorldData data = data();
        int highest = SEA_LEVEL;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                highest = Math.max(highest, data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ));
            }
        }
        return highest;
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

    /**
     * The first free Y above the column. The ocean floor maps (what a structure under the sea is placed on) stop at the
     * seabed; the others (the surface of the world, what stops a movement) count the water, so above a sea they are at
     * the sea level. The game asks for it to place the structures.
     */
    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        int top = data().heightAt(x, z);
        boolean seabed = heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG;
        return (seabed ? top : Math.max(top, SEA_LEVEL)) + 1;
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
     * is whole again, so the sea floor is not broken into, and the water the carvers left above the sea level is
     * removed. On the land the caves are as the carvers made them (the water of the underground lakes stays).
     */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int limit = Math.max(chunkData.getMinHeight(), data.lowest() - CARVER_SEA_MARGIN);
        logWaterAboveSea("carvers vanilla", chunkX, chunkZ, chunkData, highestTop(chunkX, chunkZ));
        int maxY = chunkData.getMaxHeight();
        int removed = 0;
        int restoredWater = 0;
        int restoredStone = 0;
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
                        restoredStone++;
                    }
                }
                for (int y = top + 1; y <= SEA_LEVEL; y++) {
                    if (chunkData.getType(localX, y, localZ).isAir()) {
                        chunkData.setBlock(localX, y, localZ, Material.WATER);
                        restoredWater++;
                    }
                }
                // the carvers' aquifers can also leave water above the sea (a ravine cut through the surface): none is wanted
                for (int y = SEA_LEVEL + 1; y < maxY; y++) {
                    if (chunkData.getType(localX, y, localZ) == Material.WATER) {
                        chunkData.setBlock(localX, y, localZ, Material.AIR);
                        removed++;
                    }
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("[eau] caves chunk (" + chunkX + ", " + chunkZ + ") : eau au-dessus de la mer retirée sur la terre "
                    + removed + " blocs (eau remise sous la mer " + restoredWater + ", creusé remis en pierre "
                    + restoredStone + ")");
        }
        logWaterAboveSea("generateCaves (notre nettoyage)", chunkX, chunkZ, chunkData, highestTop(chunkX, chunkZ));
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
        return true;
    }
}
