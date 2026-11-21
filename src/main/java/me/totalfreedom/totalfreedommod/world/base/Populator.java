package me.totalfreedom.totalfreedommod.world.base;

import org.bukkit.generator.LimitedRegion;

/**
 * Places features into a finished chunk. Trees, flowers, ores, structures.
 * <p>
 * Last in the chain, and the only stage that runs as a BlockPopulator, so it gets a
 * {@link LimitedRegion} instead of chunk data. The region covers the chunk plus a margin, so a tree
 * on a border can put its canopy in the next chunk.
 * <p>
 * Driven by the feature list in the world's profile.
 */
public interface Populator
{
    /**
     * Places this chunk's features.
     * <p>
     * Use the context's random for every roll so placement is reproducible. Bounds check writes
     * with isInRegion, and only originate features from inside the target chunk; originating from
     * the margin doubles them along borders, since neighbours populate independently.
     * <p>
     * Off the main thread. No chunk loading, no entity spawning through World.
     */
    void populate(ChunkContext context, LimitedRegion data);
}
