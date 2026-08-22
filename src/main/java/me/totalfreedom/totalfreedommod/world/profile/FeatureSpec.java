package me.totalfreedom.totalfreedommod.world.profile;

import org.bukkit.block.Biome;

/**
 * One feature entry. rarity is attempts per chunk, and minY and maxY bound where it may appear.
 * <p>
 * What gets placed, and every setting particular to it, lives in the detail.
 */
public record FeatureSpec(int rarity,
                          int minY,
                          int maxY,
                          BiomeFilter biomes,
                          FeatureDetail detail)
{
    /** @throws IllegalArgumentException if rarity is below one, or minY is above maxY */
    public FeatureSpec
    {
        if (rarity < 1)
            throw new IllegalArgumentException("rarity (" + rarity + ") must be at least one");

        if (minY > maxY)
            throw new IllegalArgumentException("minY (" + minY + ") must not be above maxY (" + maxY + ")");
    }

    public boolean appliesTo(final Biome biome)
    {
        return this.biomes.matches(biome);
    }
}
