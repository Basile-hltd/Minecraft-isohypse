package fr.basile.isohypse;

/**
 * The height map the carving ({@link Carver}) reads: the natural Y of the top block of a column and whether the column is
 * a lake or a river, all in world coordinates (x, z; the origin is the middle of the map). Implemented over the finished
 * {@code world.bin} by {@link WorldData}, and over the raw heights and the masks while {@code world.bin} is built.
 */
interface Relief {

    int heightAt(int x, int z);

    boolean lakeAt(int x, int z);

    boolean riverAt(int x, int z);

    /** The Y of the top block of the column if it is on a mushroom island, else {@link IslandMask#NO_TOP}. */
    int islandTopAt(int x, int z);

    /** The water level of the river at the column, which never rises along the river; only for a river column. */
    int riverLevelAt(int x, int z);
}
