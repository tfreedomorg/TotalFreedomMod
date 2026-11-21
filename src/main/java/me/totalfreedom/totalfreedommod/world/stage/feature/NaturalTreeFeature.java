package me.totalfreedom.totalfreedommod.world.stage.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.block.Biome;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.profile.FeatureDetail;

/**
 * Grows whichever tree the placement site's actual biome would naturally produce, weighted to
 * approximate the vanilla mix for that biome; nine spruce to one tall spruce in a taiga, mostly oak
 * with a scattering of birch in a forest, and so on. "Approximate" is doing real work in that
 * sentence though: the table below is hand-tuned against Bukkit's {@link TreeType} set, not pulled
 * from Mojang's actual configured feature system, and nobody's checked it species-for-species
 * against a specific game version.
 * <p>
 * Reads the biome from the region rather than the feature spec's own biome filter, since a spec can
 * span several biomes with different mixes.
 * <p>
 * A biome missing from the table has no natural tree cover at all (desert, ocean, badlands, the
 * nether, the end) and is silently skipped rather than defaulted to oak.
 */
public final class NaturalTreeFeature implements Feature<FeatureDetail.NaturalTree>
{
    private record Weighted(TreeType type, int weight)
    {
    }

    /**
     * One mix per distinct species combination, not per biome. Plenty of biomes share the exact same
     * mix (three kinds of savanna all want plain acacia), so the table says so once instead of
     * repeating it.
     */
    private static final Map<Biome, List<Weighted>> MIXES = buildMixes();

    private static Map<Biome, List<Weighted>> buildMixes()
    {
        final Map<Biome, List<Weighted>> mixes = new HashMap<>();

        mix(mixes, List.of(new Weighted(TreeType.TREE, 4), new Weighted(TreeType.BIRCH, 1)),
            Biome.FOREST);
        mix(mixes, List.of(new Weighted(TreeType.TREE, 1)),
            Biome.PLAINS, Biome.SUNFLOWER_PLAINS, Biome.FLOWER_FOREST);
        mix(mixes, List.of(new Weighted(TreeType.BIRCH, 1)),
            Biome.BIRCH_FOREST);
        mix(mixes, List.of(new Weighted(TreeType.TALL_BIRCH, 1)),
            Biome.OLD_GROWTH_BIRCH_FOREST);
        mix(mixes, List.of(new Weighted(TreeType.DARK_OAK, 4), new Weighted(TreeType.TREE, 1)),
            Biome.DARK_FOREST);
        mix(mixes, List.of(new Weighted(TreeType.TREE, 3), new Weighted(TreeType.REDWOOD, 1)),
            Biome.WINDSWEPT_FOREST);
        mix(mixes, List.of(new Weighted(TreeType.REDWOOD, 9), new Weighted(TreeType.TALL_REDWOOD, 1)),
            Biome.TAIGA);
        mix(mixes, List.of(new Weighted(TreeType.TALL_REDWOOD, 3), new Weighted(TreeType.MEGA_REDWOOD, 1)),
            Biome.OLD_GROWTH_PINE_TAIGA);
        mix(mixes, List.of(new Weighted(TreeType.MEGA_REDWOOD, 3), new Weighted(TreeType.TALL_REDWOOD, 1)),
            Biome.OLD_GROWTH_SPRUCE_TAIGA);
        mix(mixes, List.of(new Weighted(TreeType.REDWOOD, 1)),
            Biome.SNOWY_TAIGA, Biome.SNOWY_PLAINS);
        mix(mixes, List.of(new Weighted(TreeType.JUNGLE, 4), new Weighted(TreeType.SMALL_JUNGLE, 1)),
            Biome.JUNGLE);
        mix(mixes, List.of(new Weighted(TreeType.SMALL_JUNGLE, 3), new Weighted(TreeType.JUNGLE, 1)),
            Biome.SPARSE_JUNGLE);
        mix(mixes, List.of(new Weighted(TreeType.JUNGLE, 1)),
            Biome.BAMBOO_JUNGLE);
        mix(mixes, List.of(new Weighted(TreeType.ACACIA, 1)),
            Biome.SAVANNA, Biome.SAVANNA_PLATEAU, Biome.WINDSWEPT_SAVANNA);
        mix(mixes, List.of(new Weighted(TreeType.SWAMP, 1)),
            Biome.SWAMP);

        return Map.copyOf(mixes);
    }

    private static void mix(final Map<Biome, List<Weighted>> mixes, final List<Weighted> mix, final Biome... biomes)
    {
        Stream.of(biomes).forEach(biome -> mixes.put(biome, mix));
    }

    @Override
    public void place(final ChunkContext context,
                      final LimitedRegion region,
                      final FeatureDetail.NaturalTree detail,
                      final int x,
                      final int y,
                      final int z)
    {
        final Biome biome = region.getBiome(x, y, z);
        final List<Weighted> mix = MIXES.get(biome);

        if (mix == null)
            return;

        final Random random = context.getRandom();
        final TreeType type = pick(mix, random);

        region.generateTree(new Location(null, x, y, z), random, type);
    }

    /** Rolls one weighted pick out of a biome's mix. */
    private static TreeType pick(final List<Weighted> mix, final Random random)
    {
        final int total = mix.stream().mapToInt(Weighted::weight).sum();
        int roll = random.nextInt(total);

        for (final Weighted candidate : mix)
        {
            if (roll < candidate.weight())
                return candidate.type();

            roll -= candidate.weight();
        }

        // Unreachable: weights sum to total, so the loop above always returns first.
        return mix.get(0).type();
    }
}
