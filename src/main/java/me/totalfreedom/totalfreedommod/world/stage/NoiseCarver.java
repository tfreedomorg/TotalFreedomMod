package me.totalfreedom.totalfreedommod.world.stage;

import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.world.base.Carver;
import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.noise.NoiseField;

/**
 * Cuts caves and ravines wherever the noise goes past the threshold.
 * <p>
 * Tighten the threshold as you get near the context's terrain height and cave mouths blend into the
 * hillside instead of cutting a flat wall into it. The threshold ramps linearly from the configured
 * value, {@link #SURFACE_BLEND_RANGE} blocks below terrain height, up to 1.0 (never carved) right at
 * and above it, so a tunnel needs an increasingly strong noise peak to punch through as it nears the
 * surface instead of stopping dead in a flat plane the moment it crosses the terrain height.
 */
public final class NoiseCarver implements Carver
{
    private static final double SURFACE_BLEND_RANGE = 8.0D;

    private final NoiseField noise;
    private final double threshold;
    private final int minY;
    private final int maxY;

    public NoiseCarver(final NoiseField noise, final double threshold, final int minY, final int maxY)
    {
        this.noise = noise;
        this.threshold = threshold;
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    public boolean isCarved(final ChunkContext context, final int worldX, final int y, final int worldZ)
    {
        final double sample = this.noise.sample(worldX, y, worldZ);
        final int terrainHeight = context.terrainHeight(worldX & 0xF, worldZ & 0xF);
        final double proximity = Math.max(0.0D, Math.min(1.0D, (terrainHeight - y) / SURFACE_BLEND_RANGE));
        final double effectiveThreshold = FUtil.lerp(proximity, 1.0D, this.threshold);

        return sample > effectiveThreshold;
    }

    @Override
    public int minY()
    {
        return this.minY;
    }

    @Override
    public int maxY()
    {
        return this.maxY;
    }
}
