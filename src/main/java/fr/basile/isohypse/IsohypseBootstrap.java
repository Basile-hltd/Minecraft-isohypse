package fr.basile.isohypse;

import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Runs when the server starts, long before the worlds are loaded, which is the only moment when a datapack can still
 * change the overworld. For the Realistic style only (Vanilla+ and Fairy keep the vanilla overworld), it writes the
 * datapack of {@link WorldHeight} in the plugin folder and tells the server to use it: the caves of the game up in the
 * mountains, and the height {@code world.max_height} of {@code plugins/Isohypse/config.yml}, rounded to the closest
 * height the game accepts ({@link WorldHeight#round}); a maximum height of {@value WorldHeight#VANILLA_MAX_HEIGHT} (the
 * default) leaves the height as it is.
 *
 * <p>The height only applies to a world that does not exist yet; the caves, to the chunks not generated yet.
 */
public final class IsohypseBootstrap implements PluginBootstrap {

    private static final String PACK_ID = "world_height";

    @Override
    public void bootstrap(BootstrapContext context) {
        Path folder = context.getDataDirectory();
        int requested = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile())
                .getInt("world.max_height", WorldHeight.VANILLA_MAX_HEIGHT);
        int maxHeight = WorldHeight.round(requested);
        if (maxHeight != requested) {
            context.getLogger().info("Isohypse: world.max_height " + requested + " rounded to " + maxHeight
                    + ", the closest height the game accepts (a multiple of 16 from " + WorldHeight.LOWEST_MAX_HEIGHT
                    + " to " + WorldHeight.HIGHEST_MAX_HEIGHT + ")");
        }
        Optional<WorldStyle> style = WorldStyle.fromLevelType(serverProperties().getProperty("level-type"));
        if (style.orElse(null) != WorldStyle.REALISTIC) {
            if (maxHeight != WorldHeight.VANILLA_MAX_HEIGHT) {
                context.getLogger().info("Isohypse: world.max_height " + maxHeight + " is only for the Realistic style, the "
                        + "overworld keeps the vanilla height " + WorldHeight.VANILLA_MAX_HEIGHT);
            }
            return;
        }

        Path pack = folder.resolve("datapack");
        String problem = WorldHeight.writePack(pack, maxHeight);
        if (problem != null) {
            context.getLogger().error("Isohypse: the maximum height stays " + WorldHeight.VANILLA_MAX_HEIGHT
                    + " and the caves vanilla, " + problem);
            return;
        }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                event.registrar().discoverPack(pack, PACK_ID, configurer -> configurer
                        .autoEnableOnServerStart(true)
                        .position(true, Datapack.Position.TOP));
            } catch (IOException e) {
                context.getLogger().error("Isohypse: the datapack of the height cannot be loaded", e);
            }
        });
        context.getLogger().info("Isohypse: caves of the game also from Y " + WorldHeight.HIGH_CAVES_FROM + " up to "
                + WorldHeight.HIGH_CAVES_BELOW_TOP + " blocks under the top of the world");
        if (maxHeight == WorldHeight.VANILLA_MAX_HEIGHT) {
            return;
        }
        context.getLogger().info("Isohypse: maximum height of the overworld " + maxHeight
                + " (only for a world that does not exist yet)");
        if (Files.exists(Path.of(levelName(), "level.dat"))) {
            context.getLogger().warn("Isohypse: the world '" + levelName() + "' already exists: change nothing to its height "
                    + "after its creation, delete it to get the new maximum height");
        }
    }

    /** The name of the main world folder, from server.properties. */
    private static String levelName() {
        return serverProperties().getProperty("level-name", "world");
    }

    /** server.properties, at the root of the server; empty if it cannot be read. */
    private static Properties serverProperties() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("server.properties"))) {
            props.load(reader);
        } catch (IOException e) {
            // no properties: the defaults apply
        }
        return props;
    }
}
