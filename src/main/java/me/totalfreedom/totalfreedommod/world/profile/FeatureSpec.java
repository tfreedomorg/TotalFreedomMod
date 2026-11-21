package me.totalfreedom.totalfreedommod.world.profile;

import org.bukkit.block.Biome;

/**
 * One feature entry. rarity is attempts per chunk, and minY and maxY bound where it may appear.
 * chance is the odds that any one attempt actually goes ahead, 0 to 1, which is how something
 * rarer than once a chunk (a lake, say) is written.
 * <p>
 * What gets placed, and every setting particular to it, lives in the detail.
 */
public record FeatureSpec(int rarity,
                          double chance,
                          int minY,
                          int maxY,
                          BiomeFilter biomes,
                          FeatureDetail detail)
{
    /** @throws IllegalArgumentException if rarity is below one, chance is not within 0 to 1, or minY is above maxY */
    public FeatureSpec
    {
        if (rarity < 1)
            throw new IllegalArgumentException("rarity (" + rarity + ") must be at least one");

        if (!(chance > 0.0D && chance <= 1.0D))
            throw new IllegalArgumentException("chance (" + chance + ") must be above 0 and at most 1");

        if (minY > maxY)
            throw new IllegalArgumentException("minY (" + minY + ") must not be above maxY (" + maxY + ")");
    }

    public boolean appliesTo(final Biome biome)
    {
        return this.biomes.matches(biome);
    }
}
