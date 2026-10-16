package fr.basile.isohypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class IsohypsePlugin extends JavaPlugin {

    private HeightMap heightMap;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getConfig().getBoolean("chunksmith.auto-start", true)) {
            // Après le chargement des mondes et de Chunksmith.
            Bukkit.getScheduler().runTaskLater(this, this::startChunksmith, 100L);
        }
        getLogger().info("Isohypse " + getPluginMeta().getVersion() + " activé");
    }

    // Utilisé via bukkit.yml : worlds.<monde>.generator: Isohypse
    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new IsohypseGenerator(heightMap());
    }

    // Pré-génère toute la height map (centrée sur 0,0) avec Chunksmith, via la console.
    private void startChunksmith() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Chunksmith")) {
            getLogger().warning("Chunksmith absent : pas de pré-génération automatique.");
            return;
        }
        World world = Bukkit.getWorlds().get(0);
        boolean chunks = getConfig().getString("chunksmith.radius-unit", "blocks").equalsIgnoreCase("chunks");
        int div = chunks ? 32 : 2; // demi-largeur
        HeightMap map = heightMap();
        String radius = (map.widthBlocks() / div) + " " + (map.heightBlocks() / div);
        for (String cmd : new String[]{"cs world " + world.getName(), "cs center 0 0", "cs shape rectangle",
                "cs radius " + radius, "cs start"}) {
            getLogger().info("> " + cmd);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    // plugins/Isohypse/heightmap.png (16 bits) ou heightmap.bmp (8 bits) ; à défaut, 16x16 chunks plats.
    private HeightMap heightMap() {
        if (heightMap == null) heightMap = loadHeightMap();
        return heightMap;
    }

    private HeightMap loadHeightMap() {
        File file = new File(getDataFolder(), "heightmap.png");
        if (!file.isFile()) file = new File(getDataFolder(), "heightmap.bmp");
        if (file.isFile()) {
            try {
                HeightMap map = HeightMap.load(file);
                getLogger().info("Height map chargée : " + map.widthBlocks() + "x" + map.heightBlocks());
                return map;
            } catch (IOException e) {
                getLogger().severe("Height map illisible (" + e.getMessage() + "), carte plate utilisée");
            }
        } else {
            getLogger().warning("Aucune height map (" + file.getPath() + "), carte plate utilisée");
        }
        return HeightMap.flat(16);
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
