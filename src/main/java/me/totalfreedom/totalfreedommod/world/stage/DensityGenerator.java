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
import me.totalfreedom.totalfreedommod.world.profile.Shape;

/**
 * 3D mode. Samples on a grid in all three directions and interpolates between the samples.
 * Turns out, this was a lot of math.
 * <p>
 * Roughly fifty times the samples of heightmap mode, so only use it if a world actually needs
 * those shapes.
 * <p>
 * Raw noise has no notion of "up" on its own, so a block is solid where {@code noise - falloff(y) >
 * 0}. The profile's {@link Shape.Falloff} picks the curve:
 * <ul>
 * <li>RAMP climbs from -1 at the world's floor to +1 at its ceiling, which is a mass of ground
 * below the centre with loose pieces above it.</li>
 * <li>BAND is 0 at the centre and rises to +1 either side of it, so only a layer around the centre
 * is ever solid and everything above and below is void (end islands).</li>
 * <li>HOLLOW is the inverse of BAND, -1 at the floor and at the ceiling and +1 at the centre, so the
 * world is solid top and bottom with open space between them (nether).</li>
 * </ul>
 * The centre is the profile's own, else sea level, else the bounds' vertical midpoint. Every curve is
 * scaled to the bounds themselves rather than a fixed number of blocks, so a shallow nether and a tall
 * overworld each get a transition that actually reaches their own floor and ceiling instead of
 * saturating partway through. That falloff is a property of the world's own bounds, not of any one
 * region, so it is applied once after blending rather than per region.
 * <p>
 * {@link #surfaceHeight} interpolates the very same sampled grid {@link #generateBase} writes from,
 * not a fresh exact sample per block, so the two always agree on where the ground is. Everything
 * that places something "on the surface" trusts that. In HOLLOW mode the surface is the top of the
 * mass below the centre, never the ceiling.
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
    private final Shape.Falloff.Mode falloffMode;
    private final double centre;
    private final double halfSpan;
    private final int[] nodeY;
    private final Optional<RegionSet> regions;

    public DensityGenerator(final NoiseField density,
                            final double warp,
                            final Bounds bounds,
                            final Materials materials,
                            final Shape.Falloff falloff,
                            final Optional<RegionSet> regions)
    {
        this.density = density;
        this.warp = warp;
        this.bounds = bounds;
        this.materials = materials;
        this.falloffMode = falloff.mode();
        this.centre = falloff.centre()
                             .or(bounds::seaLevel)
                             .map(Integer::doubleValue)
                             .orElse((bounds.minY() + bounds.maxY()) / 2.0D);
        this.halfSpan = Math.max(1.0D, Math.min(this.centre - bounds.minY(), bounds.maxY() - this.centre));
        this.nodeY = this.verticalNodes();
        this.regions = regions;
    }

    @Override
    public void generateBase(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        final double[][][] grid = this.sampleGrid(context.worldX(0), context.worldZ(0));
        final Optional<Integer> seaLevel = this.bounds.seaLevel();

        IntStream.range(0, 256).forEach(index -> this.writeColumn(data, grid, index & 0xF, index >> 4, seaLevel));
    }

    /**
     * Interpolates the four grid columns around this position, which is exactly what
     * {@link #generateBase} reads from, then walks down to the first solid block. The grid sits on
     * multiples of {@link #HORIZONTAL_STEP} in world coordinates, which is the same alignment every
     * chunk's own grid has, since a chunk origin is a multiple of 16.
     */
    @Override
    public int surfaceHeight(final int worldX, final int worldZ)
    {
        final int cellX = Math.floorDiv(worldX, HORIZONTAL_STEP);
        final int cellZ = Math.floorDiv(worldZ, HORIZONTAL_STEP);
        final double[][][] corners = new double[2][this.nodeY.length][2];

        IntStream.range(0, 2).forEach(offsetX ->
            IntStream.range(0, 2).forEach(offsetZ ->
                this.fillColumn(corners,
                                offsetX,
                                offsetZ,
                                (cellX + offsetX) * HORIZONTAL_STEP,
                                (cellZ + offsetZ) * HORIZONTAL_STEP)));

        final double fractionX = (worldX - cellX * HORIZONTAL_STEP) / (double) HORIZONTAL_STEP;
        final double fractionZ = (worldZ - cellZ * HORIZONTAL_STEP) / (double) HORIZONTAL_STEP;

        return IntStream.iterate(this.surfaceScanStart(), y -> y >= this.bounds.minY(), y -> y - 1)
                        .filter(y -> this.isSolid(this.interpolate(corners, 0, 0, fractionX, fractionZ, y), y))
                        .findFirst()
                        .orElse(this.bounds.minY());
    }

    /** The top row, except in a hollow world, where scanning down from the roof would find the ceiling instead of the ground. */
    private int surfaceScanStart()
    {
        final int top = this.bounds.maxY() - 1;

        return this.falloffMode == Shape.Falloff.Mode.HOLLOW ? Math.min(top, (int) Math.round(this.centre)) : top;
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
    private double[][][] sampleGrid(final int originX, final int originZ)
    {
        final double[][][] grid = new double[HORIZONTAL_NODES][this.nodeY.length][HORIZONTAL_NODES];

        IntStream.range(0, HORIZONTAL_NODES).forEach(x ->
            IntStream.range(0, HORIZONTAL_NODES).forEach(z ->
                this.fillColumn(grid,
                                x,
                                z,
                                originX + x * HORIZONTAL_STEP,
                                originZ + z * HORIZONTAL_STEP)));

        return grid;
    }

    /** Resolves this horizontal node's region blend once, then reuses it for every vertical node above it. */
    private void fillColumn(final double[][][] grid, final int nodeX, final int nodeZ, final int worldX, final int worldZ)
    {
        final List<Contribution> contributions = this.contributions(worldX, worldZ);

        IntStream.range(0, this.nodeY.length).forEach(y -> grid[nodeX][y][nodeZ] = blend(contributions, worldX, this.nodeY[y], worldZ));
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
                             final int localX,
                             final int localZ,
                             final Optional<Integer> seaLevel)
    {
        final int cellX = Math.min(localX / HORIZONTAL_STEP, HORIZONTAL_NODES - 2);
        final int cellZ = Math.min(localZ / HORIZONTAL_STEP, HORIZONTAL_NODES - 2);
        final double fractionX = (localX - cellX * HORIZONTAL_STEP) / (double) HORIZONTAL_STEP;
        final double fractionZ = (localZ - cellZ * HORIZONTAL_STEP) / (double) HORIZONTAL_STEP;

        IntStream.range(this.bounds.minY(), this.bounds.maxY())
                 .forEach(y ->
                 {
                     if (this.isSolid(this.interpolate(grid, cellX, cellZ, fractionX, fractionZ, y), y))
                         data.setBlock(localX, y, localZ, this.materials.defaultBlock());

                     else if (seaLevel.isPresent() && y <= seaLevel.get())
                         data.setBlock(localX, y, localZ, this.materials.fluidBlock());
                 });
    }

    /** Trilinear interpolation of the sampled grid at one block height, within the cell whose lower corner is (cellX, cellZ). */
    private double interpolate(final double[][][] grid,
                               final int cellX,
                               final int cellZ,
                               final double fractionX,
                               final double fractionZ,
                               final int y)
    {
        final int j = Math.min((y - this.bounds.minY()) / VERTICAL_STEP, this.nodeY.length - 2);
        final double fractionY = (y - this.nodeY[j]) / (double) (this.nodeY[j + 1] - this.nodeY[j]);

        final double lower = bilerp(grid, cellX, cellZ, j, fractionX, fractionZ);
        final double upper = bilerp(grid, cellX, cellZ, j + 1, fractionX, fractionZ);

        return FUtil.lerp(fractionY, lower, upper);
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
     * RAMP spans from the centre down to {@link Bounds#minY()} on the low side and from the centre up
     * to {@link Bounds#maxY()} on the high side, each scaled independently, so it only saturates to
     * -1/+1 at the world's own floor and ceiling rather than a fixed number of blocks away from the
     * centre regardless of how tall the world is. A centre far off to one side gets a short transition
     * there and a long one on the other, instead of the short side clipping solid ground off early
     * and the long side leaving most of its height permanently void.
     * <p>
     * BAND and HOLLOW are symmetric about the centre, scaled to whichever of the floor or ceiling is
     * nearer, so their layer or their open space has the same thickness either side.
     */
    private double falloff(final int y)
    {
        return switch (this.falloffMode)
        {
            case RAMP -> this.unitClamp((y - this.centre) / this.rampSpan(y), -1.0D);
            case BAND -> this.unitClamp(Math.abs(y - this.centre) / this.halfSpan, 0.0D);
            case HOLLOW -> 1.0D - 2.0D * this.unitClamp(Math.abs(y - this.centre) / this.halfSpan, 0.0D);
        };
    }

    private double rampSpan(final int y)
    {
        return Math.max(1.0D, y < this.centre ? this.centre - this.bounds.minY() : this.bounds.maxY() - this.centre);
    }

    private double unitClamp(final double value, final double lowest)
    {
        return Math.max(lowest, Math.min(1.0D, value));
    }
}
