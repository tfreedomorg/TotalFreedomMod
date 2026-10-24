package me.totalfreedom.totalfreedommod.world.base;

import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;

import org.bukkit.generator.WorldInfo;

import me.totalfreedom.totalfreedommod.util.Lazy;
import me.totalfreedom.totalfreedommod.world.GenerationProfile;

/**
 * Per-chunk state for one generation stage. The stages themselves are shared and run concurrently,
 * so everything that varies per chunk lives here instead.
 * <p>
 * Build a fresh one in each callback, use it on that thread, throw it away. The stages are separate
 * Bukkit calls and the populator phase may not even run on the generating thread, so nothing can be
 * handed from one stage to the next.
 * <p>
 * Both column heights are worked out on first use and cached: terrain height comes from
 * {@link Generator#surfaceHeight}, column top from walking that down while
 * {@link Carver#isCarved} keeps saying true. A full pass over the columns is 256 samples.
 */
public final class ChunkContext
{
    private final GenerationProfile profile;
    private final Stages stages;
    private final WorldInfo info;
    private final int chunkX;
    private final int chunkZ;
    private final Random random;
    private final Lazy<int[]> terrainHeights;
    private final Lazy<int[]> columnTops;

    private ChunkContext(final GenerationProfile profile,
                         final Stages stages,
                         final WorldInfo info,
                         final Random random,
                         final int chunkX,
                         final int chunkZ)
    {
        this.profile = profile;
        this.stages = stages;
        this.info = info;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.random = random;
        this.terrainHeights = new Lazy<>(this::computeTerrainHeights);
        this.columnTops = new Lazy<>(this::computeColumnTops);
    }

    /** The random comes from Bukkit already seeded per chunk, so decoration is reproducible. */
    public static ChunkContext of(final GenerationProfile profile,
                                  final Stages stages,
                                  final WorldInfo info,
                                  final Random random,
                                  final int chunkX,
                                  final int chunkZ)
    {
        return new ChunkContext(profile, stages, info, random, chunkX, chunkZ);
    }

    /** Terrain height for a local column, before carving. */
    public int terrainHeight(final int localX, final int localZ)
    {
        return this.terrainHeights.get()[index(localX, localZ)];
    }

    /**
     * Highest solid block for a local column, after carving. Differs from the terrain height
     * wherever a cave broke the surface, so this is the one the {@link Designer} wants.
     * <p>
     * Starts at {@link #terrainHeight} and walks down while the carver keeps saying that y is
     * carved. That only works if terrain height really is the highest solid block in the column,
     * which holds for heightmap generation but is just an approximation for density, where an
     * overhang sitting above the nominal surface would slip past this entirely.
     */
    public int columnTop(final int localX, final int localZ)
    {
        return this.columnTops.get()[index(localX, localZ)];
    }

    /** Local x (0-15) to absolute. */
    public int worldX(final int localX)
    {
        return (this.chunkX << 4) + localX;
    }

    /** Local z (0-15) to absolute. */
    public int worldZ(final int localZ)
    {
        return (this.chunkZ << 4) + localZ;
    }

    public GenerationProfile getProfile()
    {
        return this.profile;
    }

    public Stages getStages()
    {
        return this.stages;
    }

    public WorldInfo getInfo()
    {
        return this.info;
    }

    public Random getRandom()
    {
        return this.random;
    }

    public int getChunkX()
    {
        return this.chunkX;
    }

    public int getChunkZ()
    {
        return this.chunkZ;
    }

    /** One full pass over the chunk's 256 columns, sampling {@link Generator#surfaceHeight} once each. */
    private int[] computeTerrainHeights()
    {
        final int[] heights = new int[256];
        final Generator generator = this.stages.generator();

        IntStream.range(0, 256)
                 .forEach(i -> heights[i] = generator.surfaceHeight(this.worldX(i & 0xF), this.worldZ(i >> 4)));

        return heights;
    }

    /** One full pass over the chunk's 256 columns, walking each down through the carver. */
    private int[] computeColumnTops()
    {
        final int[] tops = new int[256];
        final Optional<Carver> carver = this.stages.carver();

        IntStream.range(0, 256)
                 .forEach(i -> tops[i] = this.computeColumnTop(i & 0xF, i >> 4, carver));

        return tops;
    }

    private int computeColumnTop(final int localX, final int localZ, final Optional<Carver> carver)
    {
        int y = this.terrainHeight(localX, localZ);

        if (carver.isEmpty())
            return y;

        final Carver actualCarver = carver.get();
        final int worldX = this.worldX(localX);
        final int worldZ = this.worldZ(localZ);

        while (y >= actualCarver.minY() && y <= actualCarver.maxY() && actualCarver.isCarved(this, worldX, y, worldZ))
            y--;

        return y;
    }

    private static int index(final int localX, final int localZ)
    {
        return localZ << 4 | localX;
    }
}
