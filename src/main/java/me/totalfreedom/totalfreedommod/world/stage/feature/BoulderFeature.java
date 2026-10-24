package me.totalfreedom.totalfreedommod.world.stage.feature;

import java.util.Random;

import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * A rough blob resting on the ground. Detail radius is how wide it is.
 * <p>
 * Built from a few overlapping spheres rather than one, which is what keeps it from reading as a
 * ball someone dropped. Each sphere is nudged off the last so the shape comes out lopsided.
 * <p>
 * Sunk one block into the ground on purpose, so it looks embedded rather than balanced.
 */
public final class BoulderFeature implements Feature<FeatureDetail.Boulder>
{
    private static final int LOBES = 3;

    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.Boulder detail,
                      final int x,
                      final int y,
                      final int z)
    {
        final Random random = context.getRandom();
        final int radius = detail.radius();

        int lobeX = x;
        int lobeY = y - 1;
        int lobeZ = z;

        for (int lobe = 0; lobe < LOBES; lobe++)
        {
            final int lobeRadius = Math.max(1, radius - lobe);

            sphere(region, detail, lobeX, lobeY, lobeZ, lobeRadius);

            lobeX += (int) Math.round((random.nextDouble() - 0.5D) * radius);
            lobeY += random.nextInt(2);
            lobeZ += (int) Math.round((random.nextDouble() - 0.5D) * radius);
        }
    }

    /**
     * Fills a sphere, overwriting whatever is already there.
     * <p>
     * Unlike an ore vein this does not check what it is replacing, because a boulder sits on the
     * surface and is meant to bury the grass under it.
     */
    private static void sphere(final LimitedRegion region,
                               final FeatureDetail.Boulder detail,
                               final int centreX,
                               final int centreY,
                               final int centreZ,
                               final int radius)
    {
        final int squared = radius * radius;

        for (int offsetX = -radius; offsetX <= radius; offsetX++)
        {
            for (int offsetY = -radius; offsetY <= radius; offsetY++)
            {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++)
                {
                    if (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ > squared)
                        continue;

                    final int blockX = centreX + offsetX;
                    final int blockY = centreY + offsetY;
                    final int blockZ = centreZ + offsetZ;

                    if (!region.isInRegion(blockX, blockY, blockZ))
                        continue;

                    region.setBlockData(blockX, blockY, blockZ, detail.block());
                }
            }
        }
    }
}
