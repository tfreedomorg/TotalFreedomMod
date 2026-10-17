package me.totalfreedom.totalfreedommod.world.profile;

import java.util.Set;

import org.bukkit.block.Biome;

/**
 * Which biomes a surface rule or feature applies to.
 * <p>
 * Replaces a null biome meaning "any", so nothing downstream has to remember which way round that
 * was. {@link Any} says so out loud.
 */
public sealed interface BiomeFilter
{
    record Any() implements BiomeFilter
    {
        @Override
        public boolean matches(final Biome biome)
        {
            return true;
        }
    }

    /** @throws IllegalArgumentException if the set is empty, which would match nothing at all */
    record OneOf(Set<Biome> biomes) implements BiomeFilter
    {
        public OneOf
        {
            if (biomes.isEmpty())
                throw new IllegalArgumentException("biomes must not be empty");

            biomes = Set.copyOf(biomes);
        }

        @Override
        public boolean matches(final Biome biome)
        {
            return this.biomes.contains(biome);
        }
    }

    boolean matches(Biome biome);

    static BiomeFilter any()
    {
        return new Any();
    }

    static BiomeFilter of(final Set<Biome> biomes)
    {
        return new OneOf(biomes);
    }
}
