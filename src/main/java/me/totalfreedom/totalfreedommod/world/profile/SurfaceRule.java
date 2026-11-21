package me.totalfreedom.totalfreedommod.world.profile;

import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

/**
 * One surface rule: which biomes it covers, how far down it reaches, and what to put there.
 * <p>
 * The designer walks its rules in order and takes the first match, so put the specific ones first.
 */
public record SurfaceRule(BiomeFilter biomes, Depth depth, BlockData block)
{
    public boolean matches(final Biome biome, final int depth)
    {
        return depth().contains(depth) && biomes.matches(biome);        
    }
}
