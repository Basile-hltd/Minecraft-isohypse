package fr.basile.isohypse;

import org.bukkit.generator.ChunkGenerator;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>For now it only keeps the vanilla "noise" stage (terrain shape: stone, water, noise caves, aquifers); surface rules, carvers, decorations, structures and mobs are skipped. The -specific generation will be written here.
 *
 * <p>No generateXxx method is overridden, so the vanilla code still does the work for every
 * stage whose shouldGenerateXxx returns true.
 */
public final class RealisticGenerator extends ChunkGenerator {

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
