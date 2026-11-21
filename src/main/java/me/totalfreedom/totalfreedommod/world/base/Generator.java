package me.totalfreedom.totalfreedommod.world.base;

import org.bukkit.generator.ChunkGenerator;

/**
 * Primary world designer; generates the base shape of a chunk out of stone, air, and water, and
 * handles all terrain shaping including rivers. Everything after this either changes what blocks
 * are made of or takes blocks away.
 * <p>
 * Reads its settings from a .json file in {@link org.bukkit.plugin.Plugin#getDataFolder}/worlds,
 * named after the world. One implementation per mode (flat, heightmap, density), picked when the
 * profile compiles.
 */
public interface Generator
{
    /**
     * Writes the chunk. Runs under generateNoise.
     * <p>
     * ChunkData takes local x/z (0-15) and absolute y. Use setRegion for runs of the same block up
     * a column, and sample noise on a grid and interpolate between the samples; a chunk can hold
     * hundreds of thousands of block positions depending on the world's own bounds, so sampling
     * every one of them is not an option.
     * <p>
     * Off the main thread. Only the context's WorldInfo is safe to touch, never World or entities.
     */
    void generateBase(ChunkContext context, ChunkGenerator.ChunkData data);

    /**
     * Terrain height at a world position, before carving. Pure, no chunk access, and no context: the
     * spawn finder calls this at world creation, before any chunk exists to build one from.
     * <p>
     * Backs getBaseHeight, the spawn finder, and the context's own column heights, which is why an
     * implementation must not read those back through a context. Must agree with what
     * {@link #generateBase} writes or spawn lands in mid-air.
     */
    int surfaceHeight(int worldX, int worldZ);
}
