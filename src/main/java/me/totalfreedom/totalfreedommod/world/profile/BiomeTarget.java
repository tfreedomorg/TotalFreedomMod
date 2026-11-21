package me.totalfreedom.totalfreedommod.world.profile;

import org.bukkit.block.Biome;

/**
 * What a {@link Palette.BiomeBand} actually resolves to: a plain vanilla biome, or a TFM-only one.
 * <p>
 * Every consumer that only needs to know what the client sees, mob spawning included, can call
 * {@link #display()} without caring which case it is. Only the designer and the populator need to
 * know the difference, since a {@link Logical} biome's own surface and features, if it has them,
 * replace the world's rather than adding to them.
 */
public sealed interface BiomeTarget
{
    /** Today's plain case. Whatever this names is exactly what the client sees. */
    record Vanilla(Biome biome) implements BiomeTarget
    {
        @Override
        public Biome display()
        {
            return this.biome;
        }
    }

    /** A biome TFM controls entirely. See {@link BiomeDefinition}. */
    record Logical(BiomeDefinition definition) implements BiomeTarget
    {
        @Override
        public Biome display()
        {
            return this.definition.display();
        }
    }

    /** The vanilla biome the client renders: fog, sky colour, ambient sound, mob tables. */
    Biome display();
}
