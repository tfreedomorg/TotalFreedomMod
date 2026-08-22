package me.totalfreedom.totalfreedommod.world.adapter;

import java.util.Random;

import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import me.totalfreedom.totalfreedommod.world.GenerationProfile;
import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Stages;

/**
 * Runs the profile's populator as a Bukkit block populator.
 * <p>
 * Builds its own ChunkContext, since this may not run on the thread that generated the chunk.
 */
public final class ProfileBlockPopulator extends BlockPopulator
{
    private final GenerationProfile profile;
    private final Stages stages;

    public ProfileBlockPopulator(final GenerationProfile profile, final Stages stages)
    {
        this.profile = profile;
        this.stages = stages;
    }

    @Override
    public void populate(final WorldInfo worldInfo,
                         final Random random,
                         final int chunkX,
                         final int chunkZ,
                         final LimitedRegion limitedRegion)
    {
        final ChunkContext context = ChunkContext.of(this.profile, this.stages, worldInfo, random, chunkX, chunkZ);

        this.stages.populator().populate(context, limitedRegion);
    }
}
