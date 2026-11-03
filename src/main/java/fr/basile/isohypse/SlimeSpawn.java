package fr.basile.isohypse;

import org.bukkit.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Moves the window of heights where slimes spawn on the surface of a swamp. The game only lets them spawn between Y 50 and
 * 70, which is around the sea level (63): in a world whose land is all above it, a swamp never gets any. Paper keeps the
 * window in its world configuration ({@code entities.spawning.slime-spawn-height.surface-biome}), which the Bukkit API
 * does not reach; it is set here by reflection on the configuration of the loaded world, which the game reads at each
 * spawn, so there is nothing to write in the files of the server. If Paper's classes are not as expected, nothing is
 * changed and a warning says so.
 */
final class SlimeSpawn {

    private SlimeSpawn() {
    }

    /**
     * @param min the lowest Y where slimes spawn in a swamp
     * @param max the highest Y, or 0 for the top of the world
     */
    static void apply(World world, double min, double max, Logger logger) {
        double top = max > 0 ? max : world.getMaxHeight();
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Method paperConfig = level.getClass().getMethod("paperConfig");
            Object config = paperConfig.invoke(level);
            Object window = config;
            for (String name : new String[] {"entities", "spawning", "slimeSpawnHeight", "surfaceBiome"}) {
                window = field(window, name).get(window);
            }
            field(window, "minimum").setDouble(window, min);
            field(window, "maximum").setDouble(window, top);
            logger.info("Realistic : les slimes des marais apparaissent entre y=" + min + " et y=" + top);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Realistic: the slime spawn height of the swamps could not be changed (Paper's configuration is not"
                    + " as expected: " + e + "); it stays at Paper's setting");
        }
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException notHere) {
                // look in the parent class
            }
        }
        throw new NoSuchFieldException(name + " in " + owner.getClass());
    }
}
