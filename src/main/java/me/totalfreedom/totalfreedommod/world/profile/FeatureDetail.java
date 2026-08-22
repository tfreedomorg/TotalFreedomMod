package me.totalfreedom.totalfreedommod.world.profile;

import org.bukkit.TreeType;
import org.bukkit.block.data.BlockData;

/**
 * What a feature actually places. One variant per kind, each carrying only the settings that kind
 * uses.
 * <p>
 * This is why there is no shared size field meaning vein length here and radius there. A boulder
 * has a radius, a patch has a spread, and a tree has neither.
 * <p>
 * Being sealed also means the populator's switch over these is checked for exhaustiveness, so
 * adding a variant will not compile until something knows how to place it.
 */
public sealed interface FeatureDetail
{
    /** A vein buried in the filler block. size is how many blocks the vein is. */
    record Ore(BlockData block, int size) implements FeatureDetail
    {
        /** How large a single vein is allowed to get, so one entry cannot become a per-chunk sink. */
        private static final int MAX_SIZE = 64;

        /** @throws IllegalArgumentException if size is below one or above {@value #MAX_SIZE} */
        public Ore
        {
            if (size < 1 || size > MAX_SIZE)
                throw new IllegalArgumentException("size (" + size + ") must fall within 1 to " + MAX_SIZE);
        }

        @Override
        public Anchor anchor()
        {
            return Anchor.RANGE;
        }
    }

    /** A scatter across the surface. spread is how far from the origin it reaches. */
    record Patch(BlockData block, int spread) implements FeatureDetail
    {
        /** How many placement attempts a single entry may make. */
        private static final int MAX_SPREAD = 256;

        /** @throws IllegalArgumentException if spread is below one or above {@value #MAX_SPREAD} */
        public Patch
        {
            if (spread < 1 || spread > MAX_SPREAD)
                throw new IllegalArgumentException("spread (" + spread + ") must fall within 1 to " + MAX_SPREAD);
        }

        @Override
        public Anchor anchor()
        {
            return Anchor.SURFACE;
        }
    }

    /** A hollowed bowl filled with fluid. */
    record Lake(BlockData fluid, int radius) implements FeatureDetail
    {
        /** How wide a bowl is allowed to get; the fill loop is cubic in this. */
        private static final int MAX_RADIUS = 32;

        /** @throws IllegalArgumentException if radius is below one or above {@value #MAX_RADIUS} */
        public Lake
        {
            if (radius < 1 || radius > MAX_RADIUS)
                throw new IllegalArgumentException("radius (" + radius + ") must fall within 1 to " + MAX_RADIUS);
        }

        @Override
        public Anchor anchor()
        {
            return Anchor.RANGE;
        }
    }

    /** A rough blob resting on the ground. */
    record Boulder(BlockData block, int radius) implements FeatureDetail
    {
        /** How wide a lobe is allowed to get; the fill loop is cubic in this. */
        private static final int MAX_RADIUS = 16;

        /** @throws IllegalArgumentException if radius is below one or above {@value #MAX_RADIUS} */
        public Boulder
        {
            if (radius < 1 || radius > MAX_RADIUS)
                throw new IllegalArgumentException("radius (" + radius + ") must fall within 1 to " + MAX_RADIUS);
        }

        @Override
        public Anchor anchor()
        {
            return Anchor.SURFACE;
        }
    }

    /**
     * A tree, named exactly. A sapling would not be enough, since spruce alone covers REDWOOD,
     * TALL_REDWOOD, and MEGA_REDWOOD.
     * <p>
     * For a vanilla-style mix, write one entry per variant and let rarity do the weighting: nine
     * REDWOOD to one MEGA_REDWOOD.
     */
    record Tree(TreeType type) implements FeatureDetail
    {
        @Override
        public Anchor anchor()
        {
            return Anchor.SURFACE;
        }
    }

    /**
     * A tree grown as its biome's vanilla mix would choose it, rather than one type named exactly.
     * Resolved against the actual biome at the placement site, not the entry's own biome filter,
     * since a wide filter can still span several distinct mixes. A biome with no natural tree cover
     * at all (desert, ocean, badlands) is simply skipped, not defaulted to oak.
     * <p>
     * Use {@link Tree} instead to force one species regardless of the biome it lands in.
     */
    record NaturalTree() implements FeatureDetail
    {
        @Override
        public Anchor anchor()
        {
            return Anchor.SURFACE;
        }
    }

    Anchor anchor();
}
