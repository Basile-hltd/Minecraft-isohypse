package fr.basile.isohypse;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

public final class IsohypsePlugin extends JavaPlugin {

    private Optional<WorldStyle> worldStyle = Optional.empty();

    @Override
    public void onLoad() {
        // The generator is requested while the world is created, before onEnable runs.
        worldStyle = detectWorldStyle();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Isohypse " + getPluginMeta().getVersion() + " enabled");
        logRealisticCenter();
    }

    /** Prints the real-world point (latitude, longitude) that the Realistic style puts at x=0, z=0. */
    private void logRealisticCenter() {
        double latitude = getConfig().getDouble("realistic.latitude");
        double longitude = getConfig().getDouble("realistic.longitude");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            getLogger().warning("Realistic: invalid center (latitude " + latitude + ", longitude " + longitude
                    + "), expected latitude in [-90, 90] and longitude in [-180, 180]");
            return;
        }
        getLogger().info("Realistic: world center (0, 0) = latitude " + latitude + ", longitude " + longitude);
    }

    /** Called by Bukkit for worlds whose bukkit.yml entry says "generator: Isohypse". */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, @Nullable String id) {
        return worldStyle.map(style -> {
            getLogger().info("World '" + worldName + "': " + style.levelType() + " generator");
            return (ChunkGenerator) new StyleRouterGenerator(style, this);
        }).orElse(null);
    }

    /** Reads level-type from server.properties (server root) and reports whether it is an Isohypse style. */
    private Optional<WorldStyle> detectWorldStyle() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("server.properties"))) {
            props.load(reader);
        } catch (IOException e) {
            getLogger().warning("Cannot read server.properties: " + e.getMessage());
            return Optional.empty();
        }
        String levelType = props.getProperty("level-type", "minecraft:normal");
        Optional<WorldStyle> style = WorldStyle.fromLevelType(levelType);
        if (style.isPresent()) {
            getLogger().info("level-type '" + levelType + "' recognized: " + style.get().levelType()
                    + " - modified terrain generation will be applied");
        } else {
            getLogger().info("level-type '" + levelType + "' is not handled by Isohypse: generation unchanged");
        }
        return style;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
            sender.sendMessage("isohypse " + getPluginMeta().getVersion());
            return true;
        }
        return false; // shows the "usage" from plugin.yml
    }
}
