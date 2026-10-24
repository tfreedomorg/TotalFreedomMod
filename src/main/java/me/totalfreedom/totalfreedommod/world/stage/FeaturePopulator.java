package me.totalfreedom.totalfreedommod.world.stage;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.bukkit.block.Biome;
import org.bukkit.generator.LimitedRegion;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Populator;
import me.totalfreedom.totalfreedommod.world.profile.Anchor;
import me.totalfreedom.totalfreedommod.world.profile.BiomeDefinition;
import me.totalfreedom.totalfreedommod.world.profile.BiomeTarget;
import me.totalfreedom.totalfreedommod.world.profile.FeatureSpec;
import me.totalfreedom.totalfreedommod.world.profile.Palette;
import me.totalfreedom.totalfreedommod.world.stage.feature.FeatureRegistry;

/**
 * Rolls each feature in the profile against the chunk and hands off the hits. This only decides
 * what gets placed and where; the features do the placing.
 * <p>
 * A {@link BiomeTarget.Logical} band with its own {@code features()} list wholly replaces this.specs
 * for any column that resolves to it, the same "replace, don't merge" contract {@link RuleDesigner}
 * applies to surface rules. So this rolls this.specs once, then rolls every such band's own list once
 * more, and each roll's attempts only land on columns whose resolved band actually owns that list.
 */
public final class FeaturePopulator implements Populator
{
    private final List<FeatureSpec> specs;
    private final FeatureRegistry registry;

    public FeaturePopulator(final List<FeatureSpec> specs, final FeatureRegistry registry)
    {
        this.specs = specs;
        this.registry = registry;
    }

    @Override
    public void populate(final ChunkContext context, final LimitedRegion data)
    {
        final Random random = context.getRandom();
        final Palette palette = context.getProfile().palette();

        this.specs.forEach(spec -> this.roll(context, data, random, spec, Optional.empty()));

        palette.biomes()
               .stream()
               .map(Palette.BiomeBand::target)
               .filter(BiomeTarget.Logical.class::isInstance)
               .map(BiomeTarget.Logical.class::cast)
               .map(BiomeTarget.Logical::definition)
               .distinct()
               .forEach(definition -> definition.features()
                                                .ifPresent(overrides -> overrides.forEach(spec -> this.roll(context, 
                                                                                                            data, 
                                                                                                            random, 
                                                                                                            spec, 
                                                                                                            Optional.of(definition)))));
    }

    private void roll(final ChunkContext context,
                      final LimitedRegion data,
                      final Random random,
                      final FeatureSpec spec,
                      final Optional<BiomeDefinition> owner)
    {
        final Palette palette = context.getProfile().palette();

        for (int attempt = 0; attempt < spec.rarity(); attempt++)
        {
            final int localX = random.nextInt(16);
            final int localZ = random.nextInt(16);
            final int worldX = context.worldX(localX);
            final int worldZ = context.worldZ(localZ);
            final Optional<Palette.BiomeBand> band = palette.resolveBand(worldX, worldZ);

            if (!owner.equals(overrideOwner(band)))
                continue;

            final Biome biome = band.map(built -> built.target().display()).orElse(palette.fallback());

            if (!spec.appliesTo(biome))
                continue;

            final int y = spec.detail().anchor() == Anchor.SURFACE
                ? context.columnTop(localX, localZ) + 1
                : spec.minY() + random.nextInt(spec.maxY() - spec.minY() + 1);

            if (y < spec.minY() || y > spec.maxY())
                continue;

            this.registry.place(context, data, spec.detail(), worldX, y, worldZ);
        }
    }

    /** The Logical band owning this column's feature list, if its band is one and it has its own list. */
    private static Optional<BiomeDefinition> overrideOwner(final Optional<Palette.BiomeBand> band)
    {
        return band.map(Palette.BiomeBand::target)
                   .filter(BiomeTarget.Logical.class::isInstance)
                   .map(BiomeTarget.Logical.class::cast)
                   .map(BiomeTarget.Logical::definition)
                   .filter(definition -> definition.features().isPresent());
    }
}
