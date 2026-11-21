package me.totalfreedom.totalfreedommod.world.profile;

/**
 * How far below the top of a column a surface rule applies. Depth 0 is the surface block itself.
 * <p>
 * Two shapes only, so there is no magic number standing in for "all the way down". A rule that
 * wants everything below a point says {@link Rest}, not {@link Range} with a made up upper bound.
 */
public sealed interface Depth
{
    /** Inclusive both ends. Use {@link #range} so from cannot end up above to. */
    record Range(int from, int to) implements Depth
    {
        @Override
        public boolean contains(final int depth)
        {
            return depth >= this.from && depth <= this.to;
        }
    }

    /** From here to the bottom of the column. */
    record Rest(int from) implements Depth
    {
        @Override
        public boolean contains(final int depth)
        {
            return depth >= this.from;
        }
    }

    boolean contains(int depth);

    /** @throws IllegalArgumentException if from is above to, or either is negative */
    static Depth range(final int from, final int to)
    {
        if (from > to)
            throw new IllegalArgumentException("from (" + from + ") must not be above to (" + to + ")");

        if (from < 0 || to < 0)
            throw new IllegalArgumentException("from and to must not be negative");

        return new Range(from, to);
    }

    static Depth at(final int depth)
    {
        return range(depth, depth);
    }

    static Depth rest(final int from)
    {
        return new Rest(from);
    }
}
