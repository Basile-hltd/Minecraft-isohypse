package fr.basile.isohypse;

import org.bukkit.generator.ChunkGenerator;

/**
 * Debug generator that stops vanilla generation after the "noise" stage: only the terrain shape
 * (stone, water, noise caves, aquifers) is produced. Surface rules, carvers, decorations,
 * structures and mobs are all skipped.
 *
 * <p>No generateXxx method is overridden, so the vanilla code still does the work for every
 * stage whose shouldGenerateXxx returns true.
 */
public final class TerrainOnlyGenerator extends ChunkGenerator {

    private final WorldStyle style;

    public TerrainOnlyGenerator(WorldStyle style) {
        this.style = style;
    }

    public WorldStyle style() {
        return style;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
