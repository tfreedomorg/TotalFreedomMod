package me.totalfreedom.totalfreedommod.world.profile;

import java.util.List;
import java.util.Optional;

import org.bukkit.block.data.BlockData;

import me.totalfreedom.totalfreedommod.world.noise.NoiseProfile;

/**
 * How a world's terrain gets formed. One variant per generator, and each carries exactly the
 * settings that generator reads.
 * <p>
 * This is what stops a profile saying flat and then setting terrain noise, or saying heightmap with
 * no terrain at all. Those states cannot be typed, so nothing has to check for them.
 * <p>
 * Pattern match it once when wiring up the chunk generator to pick the stages. Nothing per chunk
 * and nothing per block should ever look at it again.
 */
public sealed interface Shape
{
    /** Fixed layers, no noise. */
    record Flat(LayerStack layers) implements Shape
    {
    }

    /**
     * 2D height through a spline. No overhangs.
     * <p>
     * regions, when present, lets different parts of the world use different terrain instead of one
     * spline everywhere; see {@link Regions}.
     */
    record Heightmap(Terrain terrain,
                     Optional<River> river,
                     Optional<Caves> caves,
                     Optional<Regions<Terrain>> regions) implements Shape
    {
    }

    /**
     * 3D density. Overhangs and floating islands, at roughly fifty times the samples.
     * <p>
     * regions, when present, lets different parts of the world use different density noise instead
     * of one field everywhere; see {@link Regions}.
     */
    record Density(NoiseProfile noise,
                   double warp,
                   Optional<Caves> caves,
                   Optional<Regions<DensityLayer>> regions) implements Shape
    {
    }

    /** warp offsets the sample coordinates by a second noise. */
    record Terrain(NoiseProfile noise, Spline spline, double warp)
    {
    }

    /** A density mode region's own noise. warp offsets the sample coordinates, same as {@link Terrain}. */
    record DensityLayer(NoiseProfile noise, double warp)
    {
    }

    /** Pulls height toward sea level where the noise is near zero. */
    record River(NoiseProfile noise, double threshold, int depth, BlockData bedBlock)
    {
    }

    /**
     * floodLevel is the y below which a carved out block fills with water instead of air.
     * <p>
     * Keep minY above the bedrock layer, since carving runs after bedrock is written.
     *
     * @throws IllegalArgumentException if minY is above maxY
     */
    record Caves(NoiseProfile noise, double threshold, int minY, int maxY, int floodLevel)
    {
        public Caves
        {
            if (minY > maxY)
                throw new IllegalArgumentException("minY (" + minY + ") must not be above maxY (" + maxY + ")");
        }
    }

    /**
     * One named region: the slice of the selector noise it claims, and the terrain it uses there.
     * <p>
     * Unlike {@link Palette.BiomeBand} or {@link SurfaceRule}, this is not first-match-wins: every
     * region whose range the selector's value falls within, or comes within the enclosing
     * {@link Regions#blendWidth} of, contributes, weighted by how close. That is what turns a shared
     * border into a gradient instead of a seam, so two regions are expected to overlap only within
     * that blend margin; {@link ProfileParser} rejects a profile whose regions overlap outright, since
     * two full-strength regions simply averaging there would be a surprise, not a border. name only
     * has to be unique within the enclosing list; it exists so an admin authoring a profile can tell
     * regions apart in an error message, not for anything to reference.
     *
     * @throws IllegalArgumentException if min is above max
     */
    record Region<T>(String name, double min, double max, T terrain)
    {
        public Region
        {
            if (min > max)
                throw new IllegalArgumentException("min (" + min + ") must not be above max (" + max + ")");
        }

        public boolean matches(final double value)
        {
            return value >= this.min && value <= this.max;
        }
    }

    /**
     * A coarse selector noise plus the ordered regions it can pick between, for worlds that want
     * different terrain in different places rather than one spline or one density field everywhere.
     * <p>
     * blendWidth is how far either side of a region boundary, in the selector's own -1 to 1 units,
     * two neighbouring regions' terrain gets blended together, so borders read as a gradient rather
     * than a hard seam.
     * <p>
     * Wherever the selector's value matches no listed region, the enclosing {@link Heightmap} or
     * {@link Density}'s own terrain field applies instead. Same fallback idiom as a palette's biome
     * bands plus its fallback biome; there is no separate "implicit region" to reason about.
     *
     * @throws IllegalArgumentException if blendWidth is negative
     */
    record Regions<T>(NoiseProfile selector, double blendWidth, List<Region<T>> regions)
    {
        public Regions
        {
            if (blendWidth < 0.0D)
                throw new IllegalArgumentException("blendWidth (" + blendWidth + ") must not be negative");

            regions = List.copyOf(regions);
        }
    }
}
