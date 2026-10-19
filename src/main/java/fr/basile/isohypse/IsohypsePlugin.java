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
        getLogger().info("Isohypse " + getPluginMeta().getVersion() + " enabled");
    }

    /** Called by Bukkit for worlds whose bukkit.yml entry says "generator: Isohypse". */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, @Nullable String id) {
        return worldStyle.map(style -> {
            getLogger().info("World '" + worldName + "': " + style.levelType() + " generator");
            return (ChunkGenerator) new StyleRouterGenerator(style);
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
