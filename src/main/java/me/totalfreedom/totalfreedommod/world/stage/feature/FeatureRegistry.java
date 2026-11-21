package me.totalfreedom.totalfreedommod.world.stage.feature;

import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * Dispatches a feature detail to the {@link Feature} that knows how to place it.
 * <p>
 * One instance per chunk generator, shared across every chunk. The switch over the sealed
 * {@link FeatureDetail} is exhaustive, so a new variant will not compile until this knows how to
 * place it too.
 */
public final class FeatureRegistry
{
    private final OreFeature ore = new OreFeature();
    private final PatchFeature patch = new PatchFeature();
    private final LakeFeature lake = new LakeFeature();
    private final BoulderFeature boulder = new BoulderFeature();
    private final TreeFeature tree = new TreeFeature();
    private final NaturalTreeFeature naturalTree = new NaturalTreeFeature();

    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail detail,
                      final int x,
                      final int y,
                      final int z)
    {
        switch (detail)
        {
            case FeatureDetail.Ore d -> this.ore.place(context, region, d, x, y, z);
            case FeatureDetail.Patch d -> this.patch.place(context, region, d, x, y, z);
            case FeatureDetail.Lake d -> this.lake.place(context, region, d, x, y, z);
            case FeatureDetail.Boulder d -> this.boulder.place(context, region, d, x, y, z);
            case FeatureDetail.Tree d -> this.tree.place(context, region, d, x, y, z);
            case FeatureDetail.NaturalTree d -> this.naturalTree.place(context, region, d, x, y, z);
        }
    }
}
