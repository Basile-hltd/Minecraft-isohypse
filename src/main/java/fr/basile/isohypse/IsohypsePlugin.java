package fr.basile.isohypse;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class IsohypsePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Isohypse " + getPluginMeta().getVersion() + " activé");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
            sender.sendMessage("isohypse " + getPluginMeta().getVersion());
            return true;
        }
        return false; // affiche le "usage" du plugin.yml
    }
}
