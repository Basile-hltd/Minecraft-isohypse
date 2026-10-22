package fr.basile.isohypse;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.HeightMap;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Chunk generator for the "Realistic" level-type, selected by {@link StyleRouterGenerator}.
 *
 * <p>Everything comes from {@code plugins/Isohypse/world.bin}, made when the world is created (see
 * {@link IsohypsePlugin}) and read from the disk by {@link WorldData}: the height of every column and the biomes (of the
 * surface and of the caves). The ground is filled with stone, with water up to the vanilla sea level (63). It replaces
 * the vanilla noise stage only: the other vanilla stages (surface rules, carvers, decorations, structures, mobs) run on
 * top of it. The structures are the game's own: where they start comes from the heights ({@link #getBaseHeight}) and the
 * biomes ({@link #biomeAt}) given here.
 */
public final class RealisticGenerator extends ChunkGenerator {

    /** Vanilla sea level: the low parts of the terrain are filled with water up to this Y. */
    private static final int SEA_LEVEL = HeightScale.SEA_LEVEL;
    /** Under a sea, the caves only start this many blocks under the lowest point of the sea floor of the map. */
    private static final int CARVER_SEA_MARGIN = 8;

    /** The most blocks of margin or of blend (config), so that the structures asked for are within the chunks the game has ready. */
    private static final int MAX_BLEND = 48;
    private static final int MAX_MARGIN = 16;
    private static final int MAX_CORNER_RADIUS = 32;

    private final IsohypsePlugin plugin;
    /** Blocks around each piece of a flattened structure that are flat too (config {@code structures.margin}). */
    private final int margin;
    /** Blocks over which the flat fades into the natural ground (config {@code structures.blend_distance}). */
    private final int blend;
    /** Radius in blocks of the rounded corners of each flat (config {@code structures.corner_radius}). */
    private final int cornerRadius;
    /** The structures already logged (their name and box), so that each is told once and not for every chunk. */
    private final Set<String> logged = ConcurrentHashMap.newKeySet();
    private volatile boolean lookupFailed;
    private volatile boolean lookupWorked;

    RealisticGenerator(IsohypsePlugin plugin) {
        this.plugin = plugin;
        this.margin = Math.clamp(plugin.getConfig().getInt("structures.margin", 1), 0, MAX_MARGIN);
        this.blend = Math.clamp(plugin.getConfig().getInt("structures.blend_distance", 12), 0, MAX_BLEND);
        this.cornerRadius = Math.clamp(plugin.getConfig().getInt("structures.corner_radius", 4), 0, MAX_CORNER_RADIUS);
    }

    /** The part of a flattened structure (the box of one of its pieces): the ground there is at {@code level}. */
    private record Flat(int minX, int minZ, int maxX, int maxZ, int level, int radius) {

        /**
         * How much of the flat is in the ground at (x, z): 1 on the flat, then falling to 0 over {@code blend} blocks (a
         * smoothstep of the distance to the flat), so that the flat fades into the natural ground without an edge.
         */
        double weight(int x, int z, int blend) {
            // The box with rounded corners: the distance to the box shrunk by the radius, less the radius.
            double dx = Math.max(Math.max(minX + radius - x, x - (maxX - radius)), 0);
            double dz = Math.max(Math.max(minZ + radius - z, z - (maxZ - radius)), 0);
            double distance = Math.sqrt(dx * dx + dz * dz) - radius;
            if (distance <= 0) {
                return 1;
            }
            if (distance >= blend) {
                return 0;
            }
            double t = distance / blend;
            return 1 - t * t * (3 - 2 * t);
        }
    }

    /** Whether the ground under this structure is made flat: the small ones, which need it. */
    private static boolean flattened(String name) {
        return name.equals("igloo") || name.equals("pillager_outpost") || name.startsWith("abandoned_camp");
    }

    /**
     * The structures that touch the chunk or are near enough to be blended into it (those of the chunks around, as far as
     * the margin and the blend reach), asked to the game itself: when the noise of a chunk is made, the starts of the
     * structures (their pieces, hence their boxes) are already known, they are only placed later. The ground of each is
     * flat at the height {@link #getBaseHeight} gave the game where it measured the start (the middle of the chunk for the
     * igloo, the middle of the first piece for a jigsaw structure), so that every chunk of the same structure agrees and the structure is on the ground the game measured.
     * Nothing is flattened (and a warning is logged once) if the game does not answer.
     */
    private List<Flat> flats(WorldInfo worldInfo, WorldData data, int chunkX, int chunkZ) {
        if (lookupFailed || !(worldInfo instanceof World world)) {
            return List.of();
        }
        try {
            int radius = Math.ceilDiv(margin + blend, 16);
            Map<String, GeneratedStructure> structures = new LinkedHashMap<>();
            Map<String, String> names = new HashMap<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (GeneratedStructure structure : world.getStructures(chunkX + dx, chunkZ + dz)) {
                        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE)
                                .getKey(structure.getStructure());
                        String name = key == null ? "" : key.getKey();
                        if (flattened(name)) {
                            String id = name + structure.getBoundingBox();
                            if (structures.putIfAbsent(id, structure) == null) {
                                names.put(id, name);
                            }
                        }
                    }
                }
            }
            if (!lookupWorked) {
                lookupWorked = true;
                plugin.getLogger().info("Structures: World#getStructures answers while the noise is made (chunk " + chunkX
                        + ", " + chunkZ + ": " + structures.size() + " flattened structure(s) within " + radius + " chunk(s))");
            }
            List<Flat> flats = new ArrayList<>();
            for (Map.Entry<String, GeneratedStructure> entry : structures.entrySet()) {
                GeneratedStructure structure = entry.getValue();
                String name = names.get(entry.getKey());
                List<StructurePiece> pieces = List.copyOf(structure.getPieces());
                BoundingBox first = pieces.isEmpty() ? structure.getBoundingBox() : pieces.get(0).getBoundingBox();
                // The igloo is put on the middle of its chunk; a jigsaw structure (the outpost, the camps) on the ground at the
                // middle of its first piece, wherever that is: measured there, not at the middle of the chunk.
                int startX = name.equals("igloo") ? Math.floorDiv((int) Math.floor(first.getCenterX()), 16) * 16 + 8
                        : ((int) Math.floor(first.getMinX()) + (int) Math.ceil(first.getMaxX()) - 1) / 2;
                int startZ = name.equals("igloo") ? Math.floorDiv((int) Math.floor(first.getCenterZ()), 16) * 16 + 8
                        : ((int) Math.floor(first.getMinZ()) + (int) Math.ceil(first.getMaxZ()) - 1) / 2;
                int terrain = Math.max(data.heightAt(startX, startZ), SEA_LEVEL);
                // The game puts the first piece of a jigsaw structure with its bottom on the ground it measured: that Y is read
                // from the piece itself rather than worked out again.
                int level = name.equals("igloo") || pieces.isEmpty() ? terrain : (int) Math.floor(first.getMinY());
                for (StructurePiece piece : pieces) {
                    // The plates of an outpost follow the ground under their own middle (terrain_matching): each is flat at
                    // its own bottom, which is the ground the game found there; the cages and tents on a plate stand on it.
                    // The camps are rigid, whole at the level of their first piece.
                    int pieceLevel = name.equals("pillager_outpost") ? (int) Math.floor(piece.getBoundingBox().getMinY()) : level;
                    flats.add(flat(piece.getBoundingBox(), pieceLevel));
                }
                if (pieces.isEmpty()) {
                    flats.add(flat(structure.getBoundingBox(), level));
                }
                if (logged.add(name + structure.getBoundingBox())) {
                    StringBuilder boxes = new StringBuilder();
                    for (StructurePiece piece : pieces) {
                        BoundingBox b = piece.getBoundingBox();
                        boxes.append(" [x ").append((int) b.getMinX()).append("..").append((int) b.getMaxX()).append(" y ")
                                .append((int) b.getMinY()).append("..").append((int) b.getMaxY()).append(" z ")
                                .append((int) b.getMinZ()).append("..").append((int) b.getMaxZ()).append(']');
                    }
                    plugin.getLogger().info("Structures: " + name + " start at " + startX + ", " + startZ + ": ground flat at y="
                            + level + " (terrain there " + terrain + ", first piece minY " + (int) first.getMinY() + ") pieces"
                            + boxes);
                }
            }
            return flats;
        } catch (RuntimeException | LinkageError e) {
            lookupFailed = true;
            plugin.getLogger().log(Level.WARNING, "Structures: World#getStructures fails while the noise is made, "
                    + "no ground is flattened", e);
            return List.of();
        }
    }

    private Flat flat(BoundingBox box, int level) {
        Flat square = new Flat((int) Math.floor(box.getMinX()) - margin, (int) Math.floor(box.getMinZ()) - margin,
                (int) Math.ceil(box.getMaxX()) - 1 + margin, (int) Math.ceil(box.getMaxZ()) - 1 + margin, level, 0);
        // A radius cannot be more than half the smallest side of the piece.
        int radius = Math.min(cornerRadius, Math.min(square.maxX() - square.minX() + 1, square.maxZ() - square.minZ() + 1) / 2);
        return new Flat(square.minX(), square.minZ(), square.maxX(), square.maxZ(), level, radius);
    }

    /** The world data, made when the world was created. */
    private WorldData data() {
        WorldData data = plugin.worldData();
        if (data == null) {
            throw new IllegalStateException("Realistic: world.bin is not ready");
        }
        return data;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int minY = chunkData.getMinHeight();
        List<Flat> flats = flats(worldInfo, data, chunkX, chunkZ);
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = chunkX * 16 + localX;
                int z = chunkZ * 16 + localZ;
                int top = data.heightAt(x, z);
                // The flat that weighs the most in this column (where pieces overlap, the lowest ground) is blended with the
                // natural ground by its weight.
                double weight = 0;
                int flatLevel = 0;
                for (Flat flat : flats) {
                    double w = flat.weight(x, z, blend);
                    if (w > weight || w == weight && w > 0 && flat.level() < flatLevel) {
                        weight = w;
                        flatLevel = flat.level();
                    }
                }
                if (weight > 0) {
                    top = (int) Math.round(flatLevel * weight + top * (1 - weight));
                }
                chunkData.setRegion(localX, minY, localZ, localX + 1, top + 1, localZ + 1, Material.STONE);
                if (top < SEA_LEVEL) {
                    chunkData.setRegion(localX, top + 1, localZ, localX + 1, SEA_LEVEL + 1, localZ + 1, Material.WATER);
                }
            }
        }
    }

    /** Biome at (x, y, z), from the layers of {@code world.bin}. */
    Biome biomeAt(int x, int y, int z) {
        Biome biome = data().biomeAt(x, y, z);
        return biome != null ? biome : Biome.PLAINS; // a biome that this version of the game does not have
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new HeightBiomeProvider(this);
    }

    /**
     * The first free Y above the column. The ocean floor maps (what a structure under the sea is placed on) stop at the
     * seabed; the others (the surface of the world, what stops a movement) count the water, so above a sea they are at
     * the sea level. The game asks for it to place the structures.
     */
    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        int top = data().heightAt(x, z);
        boolean seabed = heightMap == HeightMap.OCEAN_FLOOR || heightMap == HeightMap.OCEAN_FLOOR_WG;
        return (seabed ? top : Math.max(top, SEA_LEVEL)) + 1;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return true;
    }

    /**
     * The vanilla caves and ravines have just been carved in {@code chunkData}: under the seas, only what is below the
     * lowest point of the sea floor minus {@link #CARVER_SEA_MARGIN} is kept. Above it, in a column covered by water,
     * every carved block (air, water or lava where there was stone) is stone again, and the water above the sea floor
     * is whole again, so the sea floor is not broken into, and the water the carvers left above the sea level is
     * removed. On the land the caves are as the carvers made them (the water of the underground lakes stays).
     */
    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        WorldData data = data();
        int limit = Math.max(chunkData.getMinHeight(), data.lowest() - CARVER_SEA_MARGIN);
        int maxY = chunkData.getMaxHeight();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int top = data.heightAt(chunkX * 16 + localX, chunkZ * 16 + localZ);
                if (top >= SEA_LEVEL) {
                    continue; // land
                }
                for (int y = limit + 1; y <= top; y++) {
                    Material material = chunkData.getType(localX, y, localZ);
                    if (material.isAir() || material == Material.WATER || material == Material.LAVA) {
                        chunkData.setBlock(localX, y, localZ, Material.STONE);
                    }
                }
                for (int y = top + 1; y <= SEA_LEVEL; y++) {
                    if (chunkData.getType(localX, y, localZ).isAir()) {
                        chunkData.setBlock(localX, y, localZ, Material.WATER);
                    }
                }
                // the carvers' aquifers can also leave water above the sea (a ravine cut through the surface): none is wanted
                for (int y = SEA_LEVEL + 1; y < maxY; y++) {
                    if (chunkData.getType(localX, y, localZ) == Material.WATER) {
                        chunkData.setBlock(localX, y, localZ, Material.AIR);
                    }
                }
            }
        }
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return true;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    /** The ruined portals of the game come out buried (README): ours are placed on the ground instead. */
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(plugin.ruinedPortals());
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }
}
