package me.totalfreedom.totalfreedommod.world.stage.feature;

import java.util.Random;

import org.bukkit.Material;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * A vein of ore buried in the filler block. Detail size is how many blocks the vein is worth.
 * <p>
 * Laid along a short line with a swollen middle rather than as a ball, which is what gives a vein
 * its stretched, slightly lumpy shape. The line is randomly angled on the horizontal, so veins do
 * not all run the same way.
 * <p>
 * Only replaces blocks matching {@code palette.materials().defaultBlock()}, nothing else. That is
 * why an ore entry works in the nether without being told about netherrack: the generator writes
 * the filler there instead of stone. Don't read too much into "filler" though; it's not the same as
 * "untouched terrain". Whatever a surface rule, a river, or an earlier feature already swapped out no
 * longer matches, so ore skips it same as anything else that isn't filler. Ends up leaning away from
 * ground generation has already touched, which is usually what you'd want anyway.
 */
public final class OreFeature implements Feature<FeatureDetail.Ore>
{
    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.Ore detail,
                      final int x,
                      final int y,
                      final int z)
    {
        final Random random = context.getRandom();
        final Material filler = context.getProfile().palette().materials().defaultBlock().getMaterial();
        final int size = detail.size();

        // Both ends of the line, angled anywhere on the horizontal and drifting a little vertically.
        final double angle = random.nextDouble() * Math.PI;
        final double reach = size / 8.0D;

        final double fromX = x + Math.sin(angle) * reach;
        final double toX = x - Math.sin(angle) * reach;
        final double fromZ = z + Math.cos(angle) * reach;
        final double toZ = z - Math.cos(angle) * reach;
        final double fromY = y + random.nextInt(3) - 1;
        final double toY = y + random.nextInt(3) - 1;

        for (int step = 0; step < size; step++)
        {
            final double progress = step / (double) size;

            final double centreX = FUtil.lerp(progress, fromX, toX);
            final double centreY = FUtil.lerp(progress, fromY, toY);
            final double centreZ = FUtil.lerp(progress, fromZ, toZ);

            // Swells to its widest halfway along and tapers back at both ends, so the vein has
            // pointed tips instead of blunt ones. The +1 keeps the thinnest step at least a block.
            final double swell = random.nextDouble() * size / 16.0D;
            final double radius = ((Math.sin(Math.PI * progress) + 1.0D) * swell + 1.0D) / 2.0D;

            blob(region, detail, filler, centreX, centreY, centreZ, radius);
        }
    }

    /** Fills one sphere of the vein, skipping anything outside the region or not made of filler. */
    private static void blob(final LimitedRegion region,
                             final FeatureDetail.Ore detail,
                             final Material filler,
                             final double centreX,
                             final double centreY,
                             final double centreZ,
                             final double radius)
    {
        final int minX = (int) Math.floor(centreX - radius);
        final int maxX = (int) Math.floor(centreX + radius);
        final int minY = (int) Math.floor(centreY - radius);
        final int maxY = (int) Math.floor(centreY + radius);
        final int minZ = (int) Math.floor(centreZ - radius);
        final int maxZ = (int) Math.floor(centreZ + radius);

        for (int blockX = minX; blockX <= maxX; blockX++)
        {
            final double offsetX = (blockX + 0.5D - centreX) / radius;

            if (offsetX * offsetX >= 1.0D)
                continue;

            for (int blockY = minY; blockY <= maxY; blockY++)
            {
                final double offsetY = (blockY + 0.5D - centreY) / radius;

                if (offsetX * offsetX + offsetY * offsetY >= 1.0D)
                    continue;

                for (int blockZ = minZ; blockZ <= maxZ; blockZ++)
                {
                    final double offsetZ = (blockZ + 0.5D - centreZ) / radius;

                    if (offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ >= 1.0D)
                        continue;

                    if (!region.isInRegion(blockX, blockY, blockZ))
                        continue;

                    if (region.getType(blockX, blockY, blockZ) != filler)
                        continue;

                    region.setBlockData(blockX, blockY, blockZ, detail.block());
                }
            }
        }
    }
}
