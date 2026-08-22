package me.totalfreedom.totalfreedommod.world.stage;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.bukkit.generator.ChunkGenerator;

import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Generator;
import me.totalfreedom.totalfreedommod.world.noise.NoiseField;
import me.totalfreedom.totalfreedommod.world.profile.Bounds;
import me.totalfreedom.totalfreedommod.world.profile.Materials;

/**
 * 3D mode. Samples on a grid in all three directions and interpolates between the samples.
 * Turns out, this was a lot of math.
 * <p>
 * Roughly fifty times the samples of heightmap mode, so only use it if a world actually needs
 * those shapes.
 * <p>
 * Raw noise has no notion of "up" on its own, so a block is solid where {@code noise - falloff(y) >
 * 0}. falloff climbs from -1 at the world's own floor to +1 at its own ceiling, centred on sea level
 * (or the bounds' vertical midpoint where there is none), which is what keeps the ground roughly
 * where the profile expects instead of scattering solid blocks across the world's full height. Scaled
 * to the bounds themselves rather than a fixed number of blocks, so a shallow nether and a tall
 * overworld each get a transition that actually reaches their own floor and ceiling instead of
 * saturating partway through. That falloff is a property of the world's own bounds, not of any one
 * region, so it is applied once after blending rather than per region.
 * <p>
 * {@link #regions}, when present, lets different parts of the world sample a different {@link NoiseField}
 * instead of one density field everywhere. Every column samples {@code regions.selector()} once,
 * two-dimensionally, so a region's border does not wobble with height, and blends every region within
 * {@code blendWidth} of that value: full weight throughout the region's own range, fading linearly to
 * zero over blendWidth beyond either edge. Whatever weight no region claims falls to this generator's
 * own density field, so every position resolves to a normalisable blend even where no listed region
 * reaches.
 * <p>
 * {@link #warp} offsets a field's sample coordinates by a second sample of that same field, taken
 * {@link #WARP_PROBE_OFFSET} blocks away on each axis so the offset doesn't just echo the position
 * it's displacing. Scaled by the field's own wavelength (1 / frequency), so a coarse field warps by
 * more blocks than a fine one for the same warp value instead of the two reading differently for the
 * same number. Applied per contribution, each with its own warp, before blending or falloff see any
 * of it.
 */
public final class DensityGenerator implements Generator
{
    private static final int HORIZONTAL_STEP = 4;
    private static final int HORIZONTAL_NODES = 16 / HORIZONTAL_STEP + 1;
    private static final int VERTICAL_STEP = 8;

    /** How far away, on each axis, warp's offset probes sample the same field they're displacing. */
    private static final int WARP_PROBE_OFFSET = 1013;

    private final NoiseField density;
    private final double warp;
    private final Bounds bounds;
    private final Materials materials;
    private final Optional<RegionSet> regions;

    public DensityGenerator(final NoiseField density,
                            final double warp,
                            final Bounds bounds,
                            final Materials materials,
                            final Optional<RegionSet> regions)
    {
        this.density = density;
        this.warp = warp;
        this.bounds = bounds;
        this.materials = materials;
        this.regions = regions;
    }

    @Override
    public void generateBase(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        final int originX = context.worldX(0);
        final int originZ = context.worldZ(0);
        final int[] nodeY = this.verticalNodes();
        final double[][][] grid = this.sampleGrid(originX, originZ, nodeY);
        final Optional<Integer> seaLevel = this.bounds.seaLevel();

        IntStream.range(0, 256).forEach(index -> this.writeColumn(data, grid, nodeY, index & 0xF, index >> 4, seaLevel));
    }

    @Override
    public int surfaceHeight(final int worldX, final int worldZ)
    {
        final List<Contribution> contributions = this.contributions(worldX, worldZ);

        return IntStream.iterate(this.bounds.maxY() - 1, y -> y >= this.bounds.minY(), y -> y - 1)
                        .filter(y -> this.isSolid(blend(contributions, worldX, y, worldZ), y))
                        .findFirst()
                        .orElse(this.bounds.minY());
    }

    /**
     * One profile region, already built: a sampled noise field, not the raw settings
     * {@link me.totalfreedom.totalfreedommod.world.profile.Shape.DensityLayer} carries.
     */
    public record BuiltRegion(String name, double min, double max, NoiseField noise, double warp)
    {
    }

    /** See {@link HeightmapGenerator.RegionSet}; the same role-string requirement applies here too. */
    public record RegionSet(NoiseField selector, double blendWidth, List<BuiltRegion> regions)
    {
    }

    /** One noise field's share of a blended sample, and the warp to apply before sampling it. Purely internal; never leaves this class. */
    private record Contribution(NoiseField noise, double warp, double weight)
    {
    }

    /** The y each vertical node sits at: every {@link #VERTICAL_STEP} blocks from minY, plus maxY itself. */
    private int[] verticalNodes()
    {
        final int minY = this.bounds.minY();
        final int maxY = this.bounds.maxY();
        final IntStream steps = IntStream.iterate(minY, 
                                                  y -> y < maxY, 
                                                  y -> y + VERTICAL_STEP);

        return IntStream.concat(steps, IntStream.of(maxY))
                        .toArray();
    }

    /** One blended noise sample per grid node. {@code grid[x][y][z]}, all three indexed by node, not by block. */
    private double[][][] sampleGrid(final int originX, final int originZ, final int[] nodeY)
    {
        final double[][][] grid = new double[HORIZONTAL_NODES][nodeY.length][HORIZONTAL_NODES];

        IntStream.range(0, HORIZONTAL_NODES).forEach(x ->
            IntStream.range(0, HORIZONTAL_NODES).forEach(z ->
                this.fillColumn(grid, 
                                nodeY, 
                                x, 
                                z, 
                                originX + x * HORIZONTAL_STEP, 
                                originZ + z * HORIZONTAL_STEP)));

        return grid;
    }

    /** Resolves this horizontal node's region blend once, then reuses it for every vertical node above it. */
    private void fillColumn(final double[][][] grid, final int[] nodeY, final int nodeX, final int nodeZ, final int worldX, final int worldZ)
    {
        final List<Contribution> contributions = this.contributions(worldX, worldZ);

        IntStream.range(0, nodeY.length).forEach(y -> grid[nodeX][y][nodeZ] = blend(contributions, worldX, nodeY[y], worldZ));
    }

    /**
     * This position's density sources and how much each one counts. Just this generator's own field
     * when there are no regions; otherwise every region within blendWidth of the selector's value,
     * plus this generator's own field for whatever weight none of them claim.
     */
    private List<Contribution> contributions(final int worldX, final int worldZ)
    {
        if (this.regions.isEmpty())
            return List.of(new Contribution(this.density, this.warp, 1.0D));

        final RegionSet regionSet = this.regions.get();
        final double selector = regionSet.selector().sample(worldX, worldZ);
        final double blendWidth = regionSet.blendWidth();

        final List<Contribution> matched = regionSet.regions()
                                                     .stream()
                                                     .map(region -> new Contribution(region.noise(), region.warp(), edgeWeight(region, selector, blendWidth)))
                                                     .filter(contribution -> contribution.weight() > 0.0D)
                                                     .toList();

        final double covered = matched.stream().mapToDouble(Contribution::weight).sum();
        final double fallbackWeight = Math.max(0.0D, 1.0D - covered);

        if (fallbackWeight <= 0.0D)
            return matched;

        return Stream.concat(matched.stream(), Stream.of(new Contribution(this.density, this.warp, fallbackWeight))).toList();
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

    /** Every contribution's noise, warped then sampled at the same position and combined by weighted average. */
    private static double blend(final List<Contribution> contributions, final int worldX, final int y, final int worldZ)
    {
        final double totalWeight = contributions.stream().mapToDouble(Contribution::weight).sum();
        final double weightedSum = contributions.stream()
                                                 .mapToDouble(contribution -> contribution.weight()
                                                     * warpedSample(contribution.noise(), contribution.warp(), worldX, y, worldZ))
                                                 .sum();

        return weightedSum / totalWeight;
    }

    /**
     * Displaces (worldX, worldZ) before the real sample, using two more samples of the same field to
     * decide by how much. That's what turns smooth noise contours into the twisted, drippy shapes
     * density mode is for; without it every region reads as plain rounded blobs. The probes sample
     * {@link #WARP_PROBE_OFFSET} blocks away on each axis so they don't just echo the position they're
     * displacing, and the result is scaled by the field's own wavelength (1 / frequency) so warp reads
     * the same whether the field is coarse or fine.
     */
    private static double warpedSample(final NoiseField noise, final double warp, final int worldX, final int y, final int worldZ)
    {
        if (warp == 0.0D)
            return noise.sample(worldX, y, worldZ);

        final double wavelength = 1.0D / noise.getProfile().frequency();
        final double offsetX = noise.sample(worldX + WARP_PROBE_OFFSET, y, worldZ) * warp * wavelength;
        final double offsetZ = noise.sample(worldX, y, worldZ + WARP_PROBE_OFFSET) * warp * wavelength;

        return noise.sample(worldX + (int) Math.round(offsetX), y, worldZ + (int) Math.round(offsetZ));
    }

    private void writeColumn(final ChunkGenerator.ChunkData data,
                             final double[][][] grid,
                             final int[] nodeY,
                             final int localX,
                             final int localZ,
                             final Optional<Integer> seaLevel)
    {
        IntStream.range(this.bounds.minY(), this.bounds.maxY())
                 .forEach(y -> this.writeBlock(data,
                                               grid,
                                               nodeY,
                                               localX,
                                               localZ,
                                               y,
                                               seaLevel));
    }

    private void writeBlock(final ChunkGenerator.ChunkData data,
                            final double[][][] grid,
                            final int[] nodeY,
                            final int localX,
                            final int localZ,
                            final int y,
                            final Optional<Integer> seaLevel)
    {
        final double noise = this.interpolate(grid, nodeY, localX, y, localZ);

        if (this.isSolid(noise, y))
            data.setBlock(localX, y, localZ, this.materials.defaultBlock());

        else if (seaLevel.isPresent() && y <= seaLevel.get())
            data.setBlock(localX, y, localZ, this.materials.fluidBlock());
    }

    /** Trilinear interpolation of the sampled grid at an arbitrary block position. */
    private double interpolate(final double[][][] grid,
                               final int[] nodeY,
                               final int localX,
                               final int y,
                               final int localZ)
    {
        final int x = Math.min(localX / HORIZONTAL_STEP, HORIZONTAL_NODES - 2);
        final int z = Math.min(localZ / HORIZONTAL_STEP, HORIZONTAL_NODES - 2);
        final int j = Math.min((y - this.bounds.minY()) / VERTICAL_STEP, nodeY.length - 2);

        final double tx = (localX - x * HORIZONTAL_STEP) / (double) HORIZONTAL_STEP;
        final double tz = (localZ - z * HORIZONTAL_STEP) / (double) HORIZONTAL_STEP;
        final double ty = (y - nodeY[j]) / (double) (nodeY[j + 1] - nodeY[j]);

        final double lower = bilerp(grid, x, z, j, tx, tz);
        final double upper = bilerp(grid, x, z, j + 1, tx, tz);

        return FUtil.lerp(ty, lower, upper);
    }

    private static double bilerp(final double[][][] grid,
                                 final int x,
                                 final int z,
                                 final int node,
                                 final double tx,
                                 final double tz)
    {
        final double near = FUtil.lerp(tx, grid[x][node][z], grid[x + 1][node][z]);
        final double far = FUtil.lerp(tx, grid[x][node][z + 1], grid[x + 1][node][z + 1]);

        return FUtil.lerp(tz, near, far);
    }

    /** True below the profile's ground, false above it; the noise decides which side of that split a position lands on. */
    private boolean isSolid(final double noise, final int y)
    {
        return noise - this.falloff(y) > 0.0D;
    }

    /**
     * The transition spans from centre down to {@link Bounds#minY()} on the low side and from centre
     * up to {@link Bounds#maxY()} on the high side, each scaled independently, so falloff only
     * actually saturates to -1/+1 at the world's own floor and ceiling rather than a fixed number of
     * blocks away from centre regardless of how tall the world is. A world whose centre sits far off
     * to one side (a shallow nether with sea level near its floor, say) gets a short transition on
     * that side and a long one on the other, instead of the short side clipping solid ground off
     * early and the long side leaving most of its height permanently void.
     */
    private double falloff(final int y)
    {
        final double centre = this.bounds.seaLevel()
                                         .map(Integer::doubleValue)
                                         .orElse((this.bounds.minY() + this.bounds.maxY()) / 2.0D);

        final double span = Math.max(1.0D, y < centre ? centre - this.bounds.minY() : this.bounds.maxY() - centre);
        final double slope = (y - centre) / span;

        return Math.max(-1.0D, Math.min(1.0D, slope));
    }
}
