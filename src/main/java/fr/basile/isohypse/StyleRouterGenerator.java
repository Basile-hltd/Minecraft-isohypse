package fr.basile.isohypse;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * The generator registered by the plugin: it does not generate anything itself, it forwards every
 * call to the generator of the {@link WorldStyle} read from level-type.
 *
 * <p>Both forms of each shouldGenerateXxx are forwarded (the vanilla server calls the one with
 * chunk coordinates, which by default falls back to the no-argument one).
 */
public final class StyleRouterGenerator extends ChunkGenerator {

    private final ChunkGenerator delegate;

    public StyleRouterGenerator(WorldStyle style, IsohypsePlugin plugin) {
        this.delegate = switch (style) {
            case VANILLA_PLUS -> new VanillaPlusGenerator();
            case REALISTIC -> new RealisticGenerator(plugin);
            case FAIRY -> new FairyGenerator();
        };
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        delegate.generateNoise(worldInfo, random, chunkX, chunkZ, chunkData);
    }

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        delegate.generateSurface(worldInfo, random, chunkX, chunkZ, chunkData);
    }

    @Override
    public void generateBedrock(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        delegate.generateBedrock(worldInfo, random, chunkX, chunkZ, chunkData);
    }

    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        delegate.generateCaves(worldInfo, random, chunkX, chunkZ, chunkData);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return delegate.getDefaultBiomeProvider(worldInfo);
    }

    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return delegate.getBaseHeight(worldInfo, random, x, z, heightMap);
    }

    @Override
    public boolean canSpawn(World world, int x, int z) {
        return delegate.canSpawn(world, x, z);
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return delegate.getDefaultPopulators(world);
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return delegate.getFixedSpawnLocation(world, random);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return delegate.shouldGenerateNoise();
    }

    @Override
    public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return delegate.shouldGenerateNoise(worldInfo, random, chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateSurface() {
        return delegate.shouldGenerateSurface();
    }

    @Override
    public boolean shouldGenerateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return delegate.shouldGenerateSurface(worldInfo, random, chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateCaves() {
        return delegate.shouldGenerateCaves();
    }

    @Override
    public boolean shouldGenerateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return delegate.shouldGenerateCaves(worldInfo, random, chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return delegate.shouldGenerateDecorations();
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return delegate.shouldGenerateDecorations(worldInfo, random, chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateMobs() {
        return delegate.shouldGenerateMobs();
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return delegate.shouldGenerateMobs(worldInfo, random, chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateStructures() {
        return delegate.shouldGenerateStructures();
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
        return delegate.shouldGenerateStructures(worldInfo, random, chunkX, chunkZ);
    }
}
