package fr.basile.isohypse;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;

public final class IsohypsePlugin extends JavaPlugin implements Listener {

    private Optional<WorldStyle> worldStyle = Optional.empty();
    private boolean terrainReady;
    private volatile WorldData worldData;
    private boolean worldBuilt;

    @Override
    public void onLoad() {
        // The generator is requested while the world is created, before onEnable runs.
        worldStyle = detectWorldStyle();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        registerCommand();
        getLogger().info("Isohypse " + getPluginMeta().getVersion() + " enabled");
        logRealisticCenter();
        prepareRealisticTerrain();
        // The world.bin needs the seed, known when the overworld is created (WorldInitEvent); the speed is for test servers
        // only: tools/local_run.py starts the JVM with -Disohypse.maxFlySpeed=true.
        if (worldStyle.orElse(null) == WorldStyle.REALISTIC || Boolean.getBoolean("isohypse.maxFlySpeed")) {
            getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (Boolean.getBoolean("isohypse.maxFlySpeed")) {
            event.getPlayer().setFlySpeed(1.0f); // Bukkit's maximum, also applies to spectator flight
        }
    }

    /** What the generator reads {@code world.bin} with; null until the world is created. */
    WorldData worldData() {
        return worldData;
    }

    /**
     * Realistic only: when the overworld is created (its seed is known, and nothing is generated yet), makes
     * {@code world.bin} from {@code heightmap.bin}: the zones and the biomes. The server waits for it, and stops if it fails.
     */
    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        if (!terrainReady || worldBuilt || event.getWorld().getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        worldBuilt = true;
        try {
            HeightScale.buildWorld(getDataFolder(), terrainSettings(), event.getWorld().getSeed(), getLogger());
            worldData = WorldData.open(getDataFolder(), HeightScale.SEA_LEVEL);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Realistic: world.bin cannot be made, stopping the server", e);
            getServer().shutdown();
        }
    }

    /**
     * Realistic only, at every start: unless {@code heightmap.bin} and {@code settings.json} were made with the
     * parameters of the config, makes them: downloads the tiles of the area (unless {@code heightmap_source.bin} already
     * holds it), samples the altitudes, scales them, and deletes the source and the tiles. {@code world.bin} is made
     * from the heights when the world is created ({@link #onWorldInit}). The server waits for it, and stops if it fails.
     */
    private void prepareRealisticTerrain() {
        if (worldStyle.orElse(null) != WorldStyle.REALISTIC) {
            return;
        }
        File folder = getDataFolder();
        double latitude = getConfig().getDouble("realistic.latitude");
        double longitude = getConfig().getDouble("realistic.longitude");
        int size = getConfig().getInt("realistic.size");
        int maxHeight = WorldHeight.round(getConfig().getInt("world.max_height", WorldHeight.VANILLA_MAX_HEIGHT));
        try {
            TerrainSettings settings = terrainSettings();
            if (HeightScale.upToDate(folder, latitude, longitude, size, maxHeight, settings)) {
                getLogger().info("Realistic: " + HeightScale.HEIGHTMAP_FILE + " was made with these settings ("
                        + HeightScale.SETTINGS_FILE + "), nothing to download");
            } else {
                new HeightMapGenerator(getLogger(), folder.toPath().resolve("dem-cache")).fetchSource(latitude, longitude, size,
                        new File(folder, HeightMapGenerator.SOURCE_FILE));
                HeightScale.scale(folder, latitude, longitude, size, maxHeight, settings, getLogger());
            }
            terrainReady = true; // world.bin is made when the world is created, from these heights
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Realistic: the terrain data cannot be made, stopping the server", e);
            getServer().shutdown();
        }
    }

    /** The parameters of the terrain computation from {@code realistic.scale} and {@code realistic.temp} of the config. */
    private TerrainSettings terrainSettings() {
        TerrainSettings d = TerrainSettings.DEFAULTS;
        var c = getConfig();
        return new TerrainSettings(c.getInt("realistic.scale.ocean_depth", d.oceanDepth()),
                c.getDouble("realistic.scale.sea_share_min", d.seaShareMin()),
                c.getDouble("realistic.scale.sea_share_target", d.seaShareTarget()),
                c.getDouble("realistic.scale.sea_share_max", d.seaShareMax()),
                c.getInt("realistic.temp.beach_depth", d.beachDepth()), c.getInt("realistic.temp.beach_height", d.beachHeight()),
                c.getInt("realistic.temp.beach_inland_distance", d.beachInlandDistance()),
                c.getInt("realistic.temp.ocean_blur", d.oceanBlur()), c.getInt("realistic.temp.land_blur", d.landBlur()),
                c.getDouble("realistic.temp.warm_ocean_share", d.warmOceanShare()),
                c.getDouble("realistic.temp.normal_ocean_share", d.normalOceanShare()),
                c.getDouble("realistic.temp.warm_land_share", d.warmLandShare()),
                c.getDouble("realistic.temp.normal_land_share", d.normalLandShare()));
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

    /** /isohypse version, for the operators (isohypse.admin); registered here because a paper-plugin.yml has no commands. */
    private void registerCommand() {
        try {
            getServer().getPluginManager().addPermission(new Permission("isohypse.admin",
                    "Allows Isohypse administration commands", PermissionDefault.OP));
        } catch (IllegalArgumentException alreadyThere) {
            // reloaded: the permission is already registered
        }
        Command command = new Command("isohypse", "Isohypse plugin commands", "/isohypse version", List.of()) {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
                    sender.sendMessage("isohypse " + getPluginMeta().getVersion());
                    return true;
                }
                sender.sendMessage(getUsage());
                return true;
            }
        };
        command.setPermission("isohypse.admin");
        getServer().getCommandMap().register("isohypse", command);
    }
}
