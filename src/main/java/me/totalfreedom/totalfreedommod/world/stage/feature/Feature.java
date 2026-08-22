package me.totalfreedom.totalfreedommod.world.stage.feature;

import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * Places one kind of thing into a finished chunk.
 * <p>
 * Typed to its own detail, so a tree gets a Tree and never has to check what it was handed. The
 * populator's switch over the sealed detail is what makes that safe.
 * <p>
 * Shared and run concurrently, so keep implementations immutable and take every roll from the
 * context's random. The origin is always inside the target chunk, but the overhang may not be, so
 * bounds check writes with isInRegion.
 *
 * @param <D> the detail variant this feature places
 */
public interface Feature<D extends FeatureDetail>
{
    void place(ChunkContext context, LimitedRegion region, D detail, int x, int y, int z);
}
