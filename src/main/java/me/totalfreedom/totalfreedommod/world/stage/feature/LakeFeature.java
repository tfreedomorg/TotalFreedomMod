package me.totalfreedom.totalfreedommod.world.stage.feature;

import org.bukkit.Material;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * A dip filled with fluid. Detail radius is how wide the bowl is.
 * <p>
 * The only feature that removes blocks as well as placing them, so it clears the bowl before it
 * fills it. Everything else here is additive and can be reasoned about one block at a time; this
 * one destroys terrain the designer already finished, and it will happily eat a hillside if it
 * lands in one.
 * <p>
 * Shaped as a squashed sphere, wider than it is deep, because a round hole reads as a crater. Only
 * the lower half is filled; the upper half is cleared to air, which is what gives the water a bank
 * instead of a lid.
 * <p>
 * Skips the world's own bedrock block wherever it finds it, floor or roof, rather than clearing
 * straight through it. A lake spec placed near either edge of the world's bounds still carves its
 * bowl; it just cannot open a hole into the void the way an unguarded clear would.
 */
public final class LakeFeature implements Feature<FeatureDetail.Lake>
{
    /** How much flatter the bowl is than it is wide. */
    private static final double SQUASH = 2.0D;

    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.Lake detail,
                      final int x,
                      final int y,
                      final int z)
    {
        final int radius = detail.radius();
        final int depth = Math.max(1, (int) Math.round(radius / SQUASH));
        final Material bedrock = context.getProfile().palette().materials().bedrockBlock().getMaterial();

        for (int offsetX = -radius; offsetX <= radius; offsetX++)
        {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++)
            {
                for (int offsetY = -depth; offsetY <= depth; offsetY++)
                {
                    final double reachX = offsetX / (double) radius;
                    final double reachY = offsetY / (double) depth;
                    final double reachZ = offsetZ / (double) radius;

                    if (reachX * reachX + reachY * reachY + reachZ * reachZ > 1.0D)
                        continue;

                    final int blockX = x + offsetX;
                    final int blockY = y + offsetY;
                    final int blockZ = z + offsetZ;

                    if (!region.isInRegion(blockX, blockY, blockZ))
                        continue;

                    if (region.getType(blockX, blockY, blockZ) == bedrock)
                        continue;

                    // Fluid in the bottom half, air above it. Filling the whole bowl would seal the
                    // lake over and leave a block of water floating at head height.
                    if (offsetY <= 0)
                        region.setBlockData(blockX, blockY, blockZ, detail.fluid());
                    else
                        region.setType(blockX, blockY, blockZ, Material.AIR);
                }
            }
        }
    }
}
