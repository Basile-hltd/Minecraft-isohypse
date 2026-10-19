package fr.basile.isohypse;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class IsohypsePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Isohypse " + getPluginMeta().getVersion() + " enabled");
        detectWorldStyle();
    }

    /** Reads level-type from server.properties (server root) and reports whether it is an Isohypse style. */
    private void detectWorldStyle() {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("server.properties"))) {
            props.load(reader);
        } catch (IOException e) {
            getLogger().warning("Cannot read server.properties: " + e.getMessage());
            return;
        }
        String levelType = props.getProperty("level-type", "minecraft:normal");
        WorldStyle.fromLevelType(levelType).ifPresentOrElse(
                style -> getLogger().info("level-type '" + levelType + "' recognized: " + style.levelType()
                        + " - modified terrain generation will be applied"),
                () -> getLogger().info("level-type '" + levelType + "' is not handled by Isohypse: generation unchanged"));
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
