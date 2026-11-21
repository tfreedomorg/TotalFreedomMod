package me.totalfreedom.totalfreedommod.world.profile;

import java.util.Optional;

/**
 * A world's configured vertical limits and water line.
 * <p>
 * minY is the world's lowest usable row, same as {@link org.bukkit.World#getMinHeight()}. maxY is one
 * past its highest usable row, same as {@link org.bukkit.World#getMaxHeight()}, so the world's own top
 * row is {@code maxY - 1} and every stage that writes or reads the ceiling has to remember the
 * subtraction rather than treating maxY itself as a block position.
 * <p>
 * An empty seaLevel means the world has no sea at all, which is what the end and flat worlds want.
 * There is no "sea level 0 means off" rule to remember.
 */
public record Bounds(int minY, int maxY, Optional<Integer> seaLevel)
{
    /** @throws IllegalArgumentException if minY is not below maxY, or a sea level falls outside them */
    public Bounds
    {
        if (minY >= maxY)
            throw new IllegalArgumentException("minY (" + minY + ") must be below maxY (" + maxY + ")");

        if (seaLevel.isPresent() && (seaLevel.get() < minY || seaLevel.get() >= maxY))
            throw new IllegalArgumentException("seaLevel (" + seaLevel.get() + ") must fall within " + minY + " to " + (maxY - 1));
    }
}
