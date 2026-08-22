package me.totalfreedom.totalfreedommod.world.stage.feature;

import org.bukkit.Location;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * Grows one exact tree species, regardless of the biome it lands in. Use {@link NaturalTreeFeature}
 * instead for a biome-appropriate mix.
 * <p>
 * Hands off to LimitedRegion#generateTree, which knows every vanilla tree shape and handles the
 * canopy crossing a chunk border.
 */
public final class TreeFeature implements Feature<FeatureDetail.Tree>
{
    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.Tree detail,
                      final int x,
                      final int y,
                      final int z)
    {
        region.generateTree(new Location(null, x, y, z), context.getRandom(), detail.type());
    }
}
