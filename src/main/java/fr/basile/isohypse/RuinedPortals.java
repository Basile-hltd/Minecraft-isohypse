package fr.basile.isohypse;

import org.bukkit.NamespacedKey;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * The ruined portals of a Realistic world. The game finds the Y of a ruined portal in a column of the vanilla terrain, not
 * of ours (see the README), so its own portals come out buried. {@link IsohypsePlugin#onStructureSpawn} cancels them and asks
 * {@link #request} for a portal at the same place; this populator then puts a template of the game
 * ({@code ruined_portal/portal_N}, {@code ruined_portal/giant_portal_N}) on our ground, when the chunk of its centre is
 * populated.
 *
 * <p>Only the placement on the ground is reproduced (the buried and mountain placements of the game are not), and not the
 * processors of the game (moss, rot, blackstone, vines): the template is placed as it is, with a random rotation and
 * mirror like the game's.
 */
final class RuinedPortals extends BlockPopulator {

    private static final float GIANT_PROBABILITY = 0.05f;
    private static final int NORMAL_COUNT = 10;
    private static final int GIANT_COUNT = 3;

    private final IsohypsePlugin plugin;
    private final Map<NamespacedKey, Structure> templates = new ConcurrentHashMap<>();
    /** The centres (x, z) of the portals to place, by the chunk that holds the centre. */
    private final Map<Long, List<int[]>> pending = new ConcurrentHashMap<>();
    private volatile boolean ready;

    RuinedPortals(IsohypsePlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether the templates are loaded: if not, the portals of the game are left alone. */
    boolean ready() {
        return ready;
    }

    /** Loads the templates of the game; on the main thread. */
    void load() {
        try {
            StructureManager manager = plugin.getServer().getStructureManager();
            for (int i = 1; i <= NORMAL_COUNT; i++) {
                load(manager, "ruined_portal/portal_" + i);
            }
            for (int i = 1; i <= GIANT_COUNT; i++) {
                load(manager, "ruined_portal/giant_portal_" + i);
            }
            ready = true;
        } catch (RuntimeException | IOException e) {
            plugin.getLogger().log(Level.WARNING, "Portails en ruine : modèles introuvables, ceux du jeu sont gardés", e);
        }
    }

    private void load(StructureManager manager, String name) throws IOException {
        NamespacedKey key = NamespacedKey.minecraft(name);
        Structure template = manager.loadStructure(key);
        if (template == null) {
            throw new IOException("modèle " + key + " introuvable");
        }
        templates.put(key, template);
    }

    /** Asks for a portal centred on (x, z), placed when the chunk of that point is populated. */
    void request(int x, int z) {
        pending.computeIfAbsent(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)), k -> new CopyOnWriteArrayList<>())
                .add(new int[]{x, z});
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        List<int[]> centres = pending.remove(key(chunkX, chunkZ));
        if (centres == null) {
            return;
        }
        for (int[] centre : centres) {
            place(random, region, centre[0], centre[1]);
        }
    }

    private void place(Random random, LimitedRegion region, int centreX, int centreZ) {
        String name = random.nextFloat() < GIANT_PROBABILITY
                ? "ruined_portal/giant_portal_" + (1 + random.nextInt(GIANT_COUNT))
                : "ruined_portal/portal_" + (1 + random.nextInt(NORMAL_COUNT));
        Structure template = templates.get(NamespacedKey.minecraft(name));
        Mirror mirror = random.nextFloat() < 0.5f ? Mirror.NONE : Mirror.FRONT_BACK;
        StructureRotation[] rotations = StructureRotation.values();
        StructureRotation rotation = rotations[random.nextInt(rotations.length)];

        // The box of the placed template: its corners after the mirror and the rotation around the origin
        BlockVector size = template.getSize();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[]{0, size.getBlockX() - 1}) {
            for (int z : new int[]{0, size.getBlockZ() - 1}) {
                int[] t = transform(mirror == Mirror.FRONT_BACK ? -x : x, z, rotation);
                minX = Math.min(minX, t[0]);
                maxX = Math.max(maxX, t[0]);
                minZ = Math.min(minZ, t[1]);
                maxZ = Math.max(maxZ, t[1]);
            }
        }
        int boxMinX = centreX - (maxX - minX + 1) / 2;
        int boxMinZ = centreZ - (maxZ - minZ + 1) / 2;
        int boxMaxX = boxMinX + (maxX - minX);
        int boxMaxZ = boxMinZ + (maxZ - minZ);

        // On the ground: the lowest of the corners and the centre, so that no corner floats
        WorldData data = plugin.worldData();
        int ground = Math.min(data.heightAt(centreX, centreZ), Math.min(
                Math.min(data.heightAt(boxMinX, boxMinZ), data.heightAt(boxMaxX, boxMinZ)),
                Math.min(data.heightAt(boxMinX, boxMaxZ), data.heightAt(boxMaxX, boxMaxZ))));
        int y = ground + 1;

        if (!region.isInRegion(boxMinX, y, boxMinZ) || !region.isInRegion(boxMaxX, y + size.getBlockY(), boxMaxZ)) {
            plugin.getLogger().warning("Portail en ruine (" + name + ") en (" + centreX + ", " + centreZ
                    + ") : hors de la zone de génération du chunk, non placé");
            return;
        }
        BlockVector origin = new BlockVector(boxMinX - minX, y, boxMinZ - minZ);
        template.place(region, origin, false, rotation, mirror, -1, 1.0f, random);
        plugin.getLogger().info("Portail en ruine " + name + " (" + rotation + ", " + mirror + ") posé en (" + boxMinX + ", "
                + y + ", " + boxMinZ + ") sur un sol à y=" + ground);
    }

    /** The position (x, z) of a template block after the rotation around the origin, like the game does it. */
    private static int[] transform(int x, int z, StructureRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }
}
