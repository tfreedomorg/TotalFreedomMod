package me.totalfreedom.totalfreedommod.world.stage;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Generator;
import me.totalfreedom.totalfreedommod.world.noise.NoiseField;
import me.totalfreedom.totalfreedommod.world.profile.Bounds;
import me.totalfreedom.totalfreedommod.world.profile.Materials;
import me.totalfreedom.totalfreedommod.world.profile.Spline;

/**
 * The default mode. 2D noise through a spline, with rivers pulling height toward sea level. No
 * overhangs, and it covers most of what a custom survival world wants.
 * <p>
 * {@link #heightAt} is sampled exactly, once per column, and is the one place height is decided:
 * both {@link #generateBase} and {@link #surfaceHeight} call it directly rather than each keeping
 * their own approximation of the other. Interpolating a coarser grid would be cheaper, but
 * {@link Generator#surfaceHeight} must agree exactly with what {@link #generateBase} writes, since
 * {@link ChunkContext#terrainHeight}, cave-mouth blending, and spawn selection all trust it, and a
 * heightmap column has no overhangs to make an approximation unavoidable the way density mode's does.
 * <p>
 * terrain/spline/warp are the fallback used wherever regions is empty or its selector matches no
 * listed region, exactly as they always have been. Every listed region blends in over
 * {@code regions.blendWidth()} the same way {@link DensityGenerator} blends its own regions, just
 * with a spline standing in for a falloff.
 * <p>
 * A river's threshold sets how wide, in normalised noise either side of zero, its channel reads;
 * depth is how many blocks of bedBlock line the channel floor at full strength, tapering to none at
 * the channel's edge.
 */
public final class HeightmapGenerator implements Generator
{
    /** How far away, on each axis, warp's offset probes sample the same field they're displacing. */
    private static final int WARP_PROBE_OFFSET = 1013;

    private final NoiseField terrain;
    private final Optional<River> river;
    private final Spline spline;
    private final Bounds bounds;
    private final Materials materials;
    private final double warp;
    private final Optional<RegionSet> regions;

    public HeightmapGenerator(final NoiseField terrain,
                              final Optional<River> river,
                              final Spline spline,
                              final Bounds bounds,
                              final Materials materials,
                              final double warp,
                              final Optional<RegionSet> regions)
    {
        this.terrain = terrain;
        this.river = river;
        this.spline = spline;
        this.bounds = bounds;
        this.materials = materials;
        this.warp = warp;
        this.regions = regions;
    }

    @Override
    public void generateBase(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        final Optional<Integer> seaLevel = this.bounds.seaLevel();

        IntStream.range(0, 256).forEach(index ->
        {
            final int localX = index & 0xF;
            final int localZ = index >> 4;

            this.writeColumn(data, localX, localZ, context.worldX(localX), context.worldZ(localZ), seaLevel);
        });
    }

    @Override
    public int surfaceHeight(final int worldX, final int worldZ)
    {
        return this.clamp((int) Math.round(this.heightAt(worldX, worldZ)));
    }

    /**
     * One profile region, already built: a sampled noise field and a ready spline, not the raw
     * settings {@link me.totalfreedom.totalfreedommod.world.profile.Shape.Region} carries.
     */
    public record BuiltRegion(String name,
                              double min,
                              double max,
                              NoiseField noise,
                              Spline spline,
                              double warp)
    {
    }

    /**
     * The selector noise plus its built regions and blend width. {@code ProfileChunkGenerator.wire()}
     * builds this from a profile's {@link me.totalfreedom.totalfreedommod.world.profile.Shape.Regions}
     * by calling {@code NoiseField.of} once per region plus once for the selector itself, each with
     * its own role string (e.g. {@code "terrain-region-<name>"}, {@code "terrain-selector"}) so no two
     * fields in one profile collapse onto the same random stream.
     */
    public record RegionSet(NoiseField selector, double blendWidth, List<BuiltRegion> regions)
    {
    }

    /**
     * A river's own noise plus how it behaves. threshold sets the channel's half-width in normalised
     * noise; depth is how many blocks of bedBlock line the channel floor at full strength.
     */
    public record River(NoiseField noise, double threshold, int depth, BlockData bedBlock)
    {
    }

    /** One noise field's share of a blended height, and the spline and warp to apply before blending. */
    private record Contribution(NoiseField noise, Spline spline, double warp, double weight)
    {
    }

    /** The blended, river-adjusted height at one world position; the one place height is actually decided. */
    private double heightAt(final int worldX, final int worldZ)
    {
        final List<Contribution> contributions = this.contributions(worldX, worldZ);
        final double totalWeight = contributions.stream()
                                                .mapToDouble(Contribution::weight)
                                                .sum();

        final double weightedSum = contributions.stream()
                                                .mapToDouble(contribution -> contribution.weight() * contribution.spline()
                                                                                                                 .apply(warpedSample(contribution.noise(), 
                                                                                                                                     contribution.warp(), 
                                                                                                                                     worldX, 
                                                                                                                                     worldZ)))
                                                .sum();

        final double height = weightedSum / totalWeight;

        return this.river.map(river -> this.pullTowardRiver(river, height, worldX, worldZ))
                                                .orElse(height);
    }

    /**
     * This position's height sources and how much each one counts. Just this generator's own
     * terrain/spline/warp when there are no regions; otherwise every region within blendWidth of the
     * selector's value, plus this generator's own fields for whatever weight none of them claim.
     */
    private List<Contribution> contributions(final int worldX, final int worldZ)
    {
        if (this.regions.isEmpty())
            return List.of(new Contribution(this.terrain, this.spline, this.warp, 1.0D));

        final RegionSet regionSet = this.regions.get();
        final double selector = regionSet.selector().sample(worldX, worldZ);
        final double blendWidth = regionSet.blendWidth();

        final List<Contribution> matched = regionSet.regions()
                                                    .stream()
                                                    .map(region -> new Contribution(region.noise(), 
                                                                                    region.spline(), 
                                                                                    region.warp(), 
                                                                                    edgeWeight(region, selector, blendWidth)))
                                                    .filter(contribution -> contribution.weight() > 0.0D)
                                                    .toList();

        final double covered = matched.stream().mapToDouble(Contribution::weight).sum();
        final double fallbackWeight = Math.max(0.0D, 1.0D - covered);

        if (fallbackWeight <= 0.0D)
            return matched;

        return Stream.concat(matched.stream(), Stream.of(new Contribution(this.terrain, 
                                                                          this.spline, 
                                                                          this.warp, 
                                                                          fallbackWeight)))
                                                     .toList();
    }

    /**
     * 1 throughout the region's own range, fading linearly to 0 over blendWidth beyond either edge,
     * 0 past that. The one place a region's border actually gets decided.
     */
    private static double edgeWeight(final BuiltRegion region, final double selector, final double blendWidth)
    {
        final double min = region.min();
        final double max = region.max();

        if (selector < min - blendWidth || selector > max + blendWidth)
            return 0.0D;

        if (selector < min)
            return (selector - (min - blendWidth)) / blendWidth;

        if (selector > max)
            return ((max + blendWidth) - selector) / blendWidth;

        return 1.0D;
    }

    /**
     * Displaces (worldX, worldZ) before the real sample, using two more samples of the same field to
     * decide by how much, scaled by the field's own wavelength so warp reads the same regardless of
     * how coarse or fine the field is. See {@link DensityGenerator#warpedSample} for the 3D version.
     */
    private static double warpedSample(final NoiseField noise, final double warp, final int worldX, final int worldZ)
    {
        if (warp == 0.0D)
            return noise.sample(worldX, worldZ);

        final double wavelength = 1.0D / noise.getProfile().frequency();
        final double offsetX = noise.sample(worldX + WARP_PROBE_OFFSET, worldZ) * warp * wavelength;
        final double offsetZ = noise.sample(worldX, worldZ + WARP_PROBE_OFFSET) * warp * wavelength;

        return noise.sample(worldX + (int) Math.round(offsetX), worldZ + (int) Math.round(offsetZ));
    }

    /**
     * Pulls height toward sea level as the river's own noise sample nears zero, full strength at zero
     * and fading out over {@code river.threshold()}. Falls back to the bounds' vertical midpoint where
     * the profile has no sea, so a river still carves a channel rather than doing nothing.
     */
    private double pullTowardRiver(final River river, final double height, final int worldX, final int worldZ)
    {
        final double strength = riverStrength(river, worldX, worldZ);
        final double riverLevel = this.bounds.seaLevel()
                                             .map(Integer::doubleValue)
                                             .orElse((this.bounds.minY() + this.bounds.maxY()) / 2.0D);

        return FUtil.lerp(strength, height, riverLevel);
    }

    /** 1 at the channel's centre (noise sample of zero), fading linearly to 0 at threshold either side. */
    private static double riverStrength(final River river, final int worldX, final int worldZ)
    {
        final double sample = river.noise().sample(worldX, worldZ);

        return Math.max(0.0D, 1.0D - Math.abs(sample) / river.threshold());
    }

    /** How many blocks of bedBlock this column's river channel gets, tapering to 0 at the channel's edge. */
    private int riverBedThickness(final int worldX, final int worldZ)
    {
        return this.river.map(river -> (int) Math.round(riverStrength(river, worldX, worldZ) * river.depth()))
                         .orElse(0);
    }

    private void writeColumn(final ChunkGenerator.ChunkData data,
                             final int localX,
                             final int localZ,
                             final int worldX,
                             final int worldZ,
                             final Optional<Integer> seaLevel)
    {
        final int minY = this.bounds.minY();
        final int height = this.clamp((int) Math.round(this.heightAt(worldX, worldZ)));
        final int bedThickness = this.riverBedThickness(worldX, worldZ);

        if (bedThickness > 0)
        {
            final int bedTop = Math.max(minY, height - bedThickness + 1);

            data.setRegion(localX, minY, localZ, localX + 1, bedTop, localZ + 1, this.materials.defaultBlock());
            data.setRegion(localX, bedTop, localZ, localX + 1, height + 1, localZ + 1, this.river.get().bedBlock());
        }
        else
        {
            data.setRegion(localX, minY, localZ, localX + 1, height + 1, localZ + 1, this.materials.defaultBlock());
        }

        if (seaLevel.isPresent() && seaLevel.get() > height)
            data.setRegion(localX,
                           height + 1,
                           localZ,
                           localX + 1,
                           seaLevel.get() + 1,
                           localZ + 1,
                           this.materials.fluidBlock());
    }

    /** {@link Bounds#maxY()} is one past the world's own top row, so the tallest column clamps to maxY - 1. */
    private int clamp(final int height)
    {
        return Math.max(this.bounds.minY(), Math.min(this.bounds.maxY() - 1, height));
    }
}
