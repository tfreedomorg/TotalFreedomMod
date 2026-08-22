package me.totalfreedom.totalfreedommod.world.adapter;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.*;

import me.totalfreedom.totalfreedommod.world.GenerationProfile;
import me.totalfreedom.totalfreedommod.world.base.*;
import me.totalfreedom.totalfreedommod.world.noise.NoiseField;
import me.totalfreedom.totalfreedommod.world.profile.BedrockMode;
import me.totalfreedom.totalfreedommod.world.profile.Materials;
import me.totalfreedom.totalfreedommod.world.profile.Shape;
import me.totalfreedom.totalfreedommod.world.stage.*;
import me.totalfreedom.totalfreedommod.world.stage.feature.FeatureRegistry;

/**
 * Bridges Bukkit's callbacks to the profile's stages.
 * <p>
 * The only class that knows what order Bukkit runs things in, and the only one that turns a
 * {@link Shape} into actual stage objects. 
 * <p>
 * Builds a fresh ChunkContext in each callback, since nothing carries over between them.
 * <p>
 * Reads the carver's y range once before looping, leaves bedrock alone, and
 * fills cleared blocks with water instead of air below the depth the profile sets.
 * <p>
 * The shouldGenerate methods come straight from the profile's vanilla flags.
 */
public final class ProfileChunkGenerator extends ChunkGenerator
{
    private final GenerationProfile profile;
    private final Stages stages;
    private final Optional<Integer> caveFloodLevel;

    public ProfileChunkGenerator(final GenerationProfile profile)
    {
        this.profile = profile;
        this.stages = wire(profile);
        this.caveFloodLevel = caveFloodLevel(profile.shape());
    }

    /** The wired terrain generator, for callers that need heights or spawn candidates outside a chunk callback. */
    public Generator generator()
    {
        return this.stages.generator();
    }

    @Override
    public void generateNoise(final WorldInfo worldInfo,
                              final Random random,
                              final int chunkX,
                              final int chunkZ,
                              final ChunkData chunkData)
    {
        final ChunkContext context = ChunkContext.of(this.profile, 
                                                     this.stages, 
                                                     worldInfo, 
                                                     random, 
                                                     chunkX, 
                                                     chunkZ);

        this.stages.generator().generateBase(context, chunkData);
    }

    @Override
    public void generateSurface(final WorldInfo worldInfo,
                                final Random random,
                                final int chunkX,
                                final int chunkZ,
                                final ChunkData chunkData)
    {
        final ChunkContext context = ChunkContext.of(this.profile, 
                                                     this.stages, 
                                                     worldInfo, 
                                                     random, 
                                                     chunkX, 
                                                     chunkZ);

        this.stages.designer().surface(context, chunkData);
    }

    @Override
    public void generateBedrock(final WorldInfo worldInfo,
                                final Random random,
                                final int chunkX,
                                final int chunkZ,
                                final ChunkData chunkData)
    {
        final ChunkContext context = ChunkContext.of(this.profile, 
                                                     this.stages, 
                                                     worldInfo, 
                                                     random, 
                                                     chunkX, 
                                                     chunkZ);

        this.stages.designer().bedrock(context, chunkData);
    }

    /** Walks the carver's y range and clears whatever it flags. No carver means no work. */
    @Override
    public void generateCaves(final WorldInfo worldInfo,
                              final Random random,
                              final int chunkX,
                              final int chunkZ,
                              final ChunkData chunkData)
    {
        final Optional<Carver> carver = this.stages.carver();

        if (carver.isEmpty())
            return;

        final ChunkContext context = ChunkContext.of(this.profile,
                                                     this.stages,
                                                     worldInfo,
                                                     random,
                                                     chunkX,
                                                     chunkZ);

        final BlockData air = Material.CAVE_AIR.createBlockData();
        final BlockData fluid = this.profile.palette().materials().fluidBlock();
        final int floodLevel = this.caveFloodLevel.orElse(Integer.MIN_VALUE);
        final int carveMinY = Math.max(carver.get().minY(), this.bedrockFloorGuard());
        final int carveMaxY = Math.min(carver.get().maxY(), this.bedrockRoofGuard());

        IntStream.range(0, 256)
                 .forEach(index -> this.carveColumn(context,
                                                    chunkData,
                                                    carver.get(),
                                                    carveMinY,
                                                    carveMaxY,
                                                    index & 0xF,
                                                    index >> 4,
                                                    air,
                                                    fluid,
                                                    floodLevel));
    }

    /** One past the bedrock floor {@link RuleDesigner}/{@link LayerDesigner} write at bounds.minY(), or no guard at all if there is none. */
    private int bedrockFloorGuard()
    {
        return this.profile.palette().materials().bedrock() == BedrockMode.NONE
                                                            ? Integer.MIN_VALUE
                                                            : this.profile.bounds().minY() + 1;
    }

    /** One below the bedrock roof a FLOOR_AND_ROOF profile writes at bounds.maxY() - 1, or no guard for any other mode. */
    private int bedrockRoofGuard()
    {
        return this.profile.palette().materials().bedrock() == BedrockMode.FLOOR_AND_ROOF
                                                            ? this.profile.bounds().maxY() - 2
                                                            : Integer.MAX_VALUE;
    }

    @Override
    public int getBaseHeight(final WorldInfo worldInfo,
                             final Random random,
                             final int x,
                             final int z,
                             final HeightMap heightMap)
    {
        return this.stages.generator().surfaceHeight(x, z);
    }

    @Override
    public Location getFixedSpawnLocation(final World world, final Random random)
    {
        return new SpawnFinder(this.profile, this.stages.generator()).findSpawn(world);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(final WorldInfo worldInfo)
    {
        return new ProfileBiomeProvider(this.profile);
    }

    /** Returns the block populator wrapping the profile's populator. */
    @Override
    public List<BlockPopulator> getDefaultPopulators(final World world)
    {
        return List.of(new ProfileBlockPopulator(this.profile, this.stages));
    }

    @Override
    public boolean shouldGenerateSurface()
    {
        return this.profile.world().vanilla().surface();
    }

    @Override
    public boolean shouldGenerateCaves()
    {
        return this.profile.world().vanilla().caves();
    }

    @Override
    public boolean shouldGenerateDecorations()
    {
        return this.profile.world().vanilla().decorations();
    }

    @Override
    public boolean shouldGenerateMobs()
    {
        return this.profile.world().vanilla().mobs();
    }

    @Override
    public boolean shouldGenerateStructures()
    {
        return this.profile.world().vanilla().structures();
    }

    /** One column of the cave loop: clear whatever the carver flags, water below the flood level. */
    private void carveColumn(final ChunkContext context,
                             final ChunkData data,
                             final Carver carver,
                             final int carveMinY,
                             final int carveMaxY,
                             final int localX,
                             final int localZ,
                             final BlockData air,
                             final BlockData fluid,
                             final int floodLevel)
    {
        final int worldX = context.worldX(localX);
        final int worldZ = context.worldZ(localZ);

        IntStream.rangeClosed(carveMinY, carveMaxY)
                 .filter(y -> carver.isCarved(context, worldX, y, worldZ))
                 .forEach(y -> data.setBlock(localX, y, localZ, y < floodLevel ? fluid : air));
    }

    /** Picks the stages for a profile's shape. The one place that switch is written. */
    private static Stages wire(final GenerationProfile profile)
    {
        final long seed = profile.world().seed().orElse((long) profile.name().hashCode());
        final Materials materials = profile.palette().materials();
        final Populator populator = new FeaturePopulator(profile.features(), new FeatureRegistry());

        return switch (profile.shape())
        {
            case Shape.Flat flat -> new Stages(new FlatGenerator(flat.layers(), profile.bounds()),
                                               new LayerDesigner(flat.layers(), materials),
                                               Optional.empty(),
                                               populator);

            case Shape.Heightmap heightmap -> new Stages(new HeightmapGenerator(NoiseField.of(heightmap.terrain().noise(),
                                                                                              seed,
                                                                                              "terrain"),
                                                                                heightmap.river().map(river -> new HeightmapGenerator.River(NoiseField.of(river.noise(),
                                                                                                                                                          seed,
                                                                                                                                                          "river"),
                                                                                                                                             river.threshold(),
                                                                                                                                             river.depth(),
                                                                                                                                             river.bedBlock())),
                                                                                heightmap.terrain().spline(),
                                                                                profile.bounds(),
                                                                                materials,
                                                                                heightmap.terrain().warp(),
                                                                                wireHeightmapRegions(heightmap.regions(), seed)),
                                                         new RuleDesigner(profile.palette().surface(), materials),
                                                         wireCaves(heightmap.caves(), seed),
                                                         populator);

            case Shape.Density density -> new Stages(new DensityGenerator(NoiseField.of(density.noise(),
                                                                                        seed,
                                                                                        "density"),
                                                                          density.warp(),
                                                                          profile.bounds(),
                                                                          materials,
                                                                          wireDensityRegions(density.regions(), seed)),
                                                     new RuleDesigner(profile.palette().surface(), materials),
                                                     wireCaves(density.caves(), seed),
                                                     populator);
        };
    }

    private static Optional<Carver> wireCaves(final Optional<Shape.Caves> caves, final long seed)
    {
        return caves.map(c -> new NoiseCarver(NoiseField.of(c.noise(), 
                                                            seed, 
                                                            "caves"), 
                                              c.threshold(), 
                                              c.minY(), 
                                              c.maxY()));
    }

    /** 
     * The Optional a profile's own {@link Shape.Caves#floodLevel} lives in, whatever mode the shape is. 
     * Flat is intentionally unused because 
     */
    private static Optional<Integer> caveFloodLevel(final Shape shape)
    {
        return switch (shape)
        {
            case Shape.Heightmap heightmap -> heightmap.caves().map(Shape.Caves::floodLevel);
            case Shape.Density density -> density.caves().map(Shape.Caves::floodLevel);
            case Shape.Flat flat -> Optional.empty();
        };
    }

    private static Optional<HeightmapGenerator.RegionSet> wireHeightmapRegions(final Optional<Shape.Regions<Shape.Terrain>> regions,
                                                                                final long seed)
    {
        return regions.map(built -> new HeightmapGenerator.RegionSet(NoiseField.of(built.selector(), 
                                                                                   seed, 
                                                                                   "terrain-selector"),
                                                                     built.blendWidth(),
                                                                     built.regions()
                                                                          .stream()
                                                                          .map(region -> wireHeightmapRegion(region, seed))
                                                                          .toList()));
    }

    private static HeightmapGenerator.BuiltRegion wireHeightmapRegion(final Shape.Region<Shape.Terrain> region, final long seed)
    {
        return new HeightmapGenerator.BuiltRegion(region.name(),
                                                  region.min(),
                                                  region.max(),
                                                  NoiseField.of(region.terrain().noise(), 
                                                                seed, 
                                                                "terrain-region-" + region.name()),
                                                  region.terrain().spline(),
                                                  region.terrain().warp());
    }

    private static Optional<DensityGenerator.RegionSet> wireDensityRegions(final Optional<Shape.Regions<Shape.DensityLayer>> regions,
                                                                            final long seed)
    {
        return regions.map(built -> new DensityGenerator.RegionSet(NoiseField.of(built.selector(), seed, "density-selector"),
                                                                   built.blendWidth(),
                                                                   built.regions()
                                                                        .stream()
                                                                        .map(region -> wireDensityRegion(region, seed))
                                                                        .toList()));
    }

    private static DensityGenerator.BuiltRegion wireDensityRegion(final Shape.Region<Shape.DensityLayer> region, final long seed)
    {
        return new DensityGenerator.BuiltRegion(region.name(),
                                                region.min(),
                                                region.max(),
                                                NoiseField.of(region.terrain().noise(), 
                                                              seed, 
                                                              "density-region-" + region.name()),
                                                region.terrain().warp());
    }
}
