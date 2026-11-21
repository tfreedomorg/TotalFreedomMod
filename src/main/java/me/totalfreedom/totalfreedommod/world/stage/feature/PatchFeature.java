package me.totalfreedom.totalfreedommod.world.stage.feature;

import java.util.Random;

import org.bukkit.Material;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * A scatter of blocks on the surface; flowers, grass, that sort of thing. Detail spread is how many
 * placements it attempts, not how many it lands.
 * <p>
 * Attempts are scattered on a gaussian around the origin rather than uniformly, so a patch thins
 * out at its edges instead of stopping at a square border.
 * <p>
 * Each attempt finds its own ground rather than reusing the origin's height, otherwise a patch on a
 * slope would hang in the air on one side and bury itself on the other.
 */
public final class PatchFeature implements Feature<FeatureDetail.Patch>
{
    /** How far above and below the origin an attempt will look for ground. */
    private static final int SEARCH = 4;

    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.Patch detail,
                      final int x,
                      final int y,
                      final int z)
    {
        final Random random = context.getRandom();
        final double deviation = Math.max(1.0D, detail.spread() / 4.0D);

        for (int attempt = 0; attempt < detail.spread(); attempt++)
        {
            final int spotX = x + (int) Math.round(random.nextGaussian() * deviation);
            final int spotZ = z + (int) Math.round(random.nextGaussian() * deviation);

            placeOne(region, detail, spotX, y, spotZ);
        }
    }

    /** Drops a single block onto whatever ground is nearest this column, if any is in reach. */
    private static void placeOne(final LimitedRegion region,
                                 final FeatureDetail.Patch detail,
                                 final int x,
                                 final int y,
                                 final int z)
    {
        for (int spotY = y + SEARCH; spotY >= y - SEARCH; spotY--)
        {
            if (!region.isInRegion(x, spotY, z) || !region.isInRegion(x, spotY - 1, z))
                continue;

            if (region.getType(x, spotY, z) != Material.AIR)
                continue;

            if (!region.getType(x, spotY - 1, z).isSolid())
                continue;

            region.setBlockData(x, spotY, z, detail.block());
            return;
        }
    }
}
