package me.totalfreedom.totalfreedommod.world.stage;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.ChunkGenerator;

import me.totalfreedom.totalfreedommod.world.base.Carver;
import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Designer;
import me.totalfreedom.totalfreedommod.world.base.Generator;
import me.totalfreedom.totalfreedommod.world.profile.BedrockMode;
import me.totalfreedom.totalfreedommod.world.profile.BiomeTarget;
import me.totalfreedom.totalfreedommod.world.profile.Materials;
import me.totalfreedom.totalfreedommod.world.profile.Palette;
import me.totalfreedom.totalfreedommod.world.profile.SurfaceRule;

/**
 * Applies the profile's surface rules. Walks each column down from the context's column top with a
 * depth counter that resets on every gap, so cave floors and, for density terrain, natural overhangs
 * each get their own treatment rather than being buried under a depth count that assumed solid rock.
 * <p>
 * A column's biome comes from {@code palette().resolveBand()}, not just its display biome, since a
 * {@link BiomeTarget.Logical} band's own {@code surface()} list, when it has one, wholly replaces
 * this.rules for that column rather than adding to it. Everything else falls through to this.rules.
 * <p>
 * A gap is either of two things, checked together at every y: the block {@link Generator#generateBase}
 * already wrote there is not solid (covers density terrain's own overhangs and cavities, and any
 * heightmap/density approximation slipping a block either side of where {@link ChunkContext#columnTop}
 * expected), or the carver says the position will end up carved once {@code generateCaves} runs, which
 * hasn't happened yet at this point in the callback order so isCarved is asked rather than the not-yet-
 * carved chunk data.
 * <p>
 * Only filler is ever rewritten, meaning blocks that are still the profile's {@code defaultBlock}.
 * Anything generation deliberately placed as something else, a river's bed gravel for one, is already
 * where it should be and is left alone; it still counts toward the depth, so the rules resume below it.
 * <p>
 * {@code dressCaveFloors} says whether a floor exposed by a carved gap gets surface rules. A world
 * with an open sky (heightmap) has one real surface, so once the walk down has passed through a
 * carved gap everything below is underground and stays bare stone, rather than every cave in the
 * world floored with grass. A world with no sky to speak of (density, a nether or an end) has no such
 * distinction, so there every floor is a surface.
 */
public final class RuleDesigner implements Designer
{
    private final List<SurfaceRule> rules;
    private final Materials materials;
    private final boolean dressCaveFloors;

    public RuleDesigner(final List<SurfaceRule> rules, final Materials materials, final boolean dressCaveFloors)
    {
        this.rules = rules;
        this.materials = materials;
        this.dressCaveFloors = dressCaveFloors;
    }

    @Override
    public void surface(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        IntStream.range(0, 256)
                 .forEach(index -> this.surfaceColumn(context, data, index & 0xF, index >> 4));
    }

    @Override
    public void bedrock(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        if (this.materials.bedrock() == BedrockMode.NONE)
            return;

        final int minY = context.getProfile().bounds().minY();
        final int maxY = context.getProfile().bounds().maxY();

        data.setRegion(0, minY, 0, 16, minY + 1, 16, this.materials.bedrockBlock());

        if (this.materials.bedrock() == BedrockMode.FLOOR_AND_ROOF)
            data.setRegion(0, maxY - 1, 0, 16, maxY, 16, this.materials.bedrockBlock());
    }

    private void surfaceColumn(final ChunkContext context, final ChunkGenerator.ChunkData data, final int localX, final int localZ)
    {
        final int worldX = context.worldX(localX);
        final int worldZ = context.worldZ(localZ);

        final Palette palette = context.getProfile().palette();
        final Optional<Palette.BiomeBand> band = palette.resolveBand(worldX, worldZ);
        final Biome biome = band.map(built -> built.target().display())
                                .orElse(palette.fallback());

        final List<SurfaceRule> rules = this.rulesFor(band);
        final Optional<Carver> carver = context.getStages().carver();
        final int minY = context.getProfile().bounds().minY();
        final Material filler = this.materials.defaultBlock().getMaterial();
        final int[] depth = { 0 };
        final boolean[] underground = { false };

        IntStream.iterate(context.columnTop(localX, localZ), y -> y >= minY, y -> y - 1)
                 .forEach(y ->
                 {
                     final boolean carved = isCarved(context, carver, worldX, y, worldZ);

                     if (!isSolid(data, this.materials, localX, y, localZ) || carved)
                     {
                         depth[0] = 0;
                         underground[0] |= carved && !this.dressCaveFloors;
                         return;
                     }

                     final int currentDepth = depth[0];

                     depth[0]++;

                     if (underground[0] || data.getType(localX, y, localZ) != filler)
                         return;

                     rules.stream()
                          .filter(rule -> rule.matches(biome, currentDepth))
                          .findFirst()
                          .ifPresent(rule -> data.setBlock(localX, y, localZ, rule.block()));
                 });
    }

    /** A Logical band's own surface list when it has one, otherwise this designer's world-level rules. */
    private List<SurfaceRule> rulesFor(final Optional<Palette.BiomeBand> band)
    {
        return band.map(Palette.BiomeBand::target)
                   .filter(BiomeTarget.Logical.class::isInstance)
                   .map(BiomeTarget.Logical.class::cast)
                   .flatMap(logical -> logical.definition().surface())
                   .orElse(this.rules);
    }

    /**
     * Whatever {@link Generator#generateBase} actually wrote there, not what a terrain-height
     * approximation assumed. Air (of any of Bukkit's three flavours) and the profile's own fluid both
     * count as not solid, the latter so a lake or aquifer bed resets depth and gets dressed too instead
     * of staying buried under whatever depth count the column had before the water started.
     */
    private static boolean isSolid(final ChunkGenerator.ChunkData data, final Materials materials, final int localX, final int y, final int localZ)
    {
        final Material type = data.getType(localX, y, localZ);

        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR)
            return false;

        return type != materials.fluidBlock().getMaterial();
    }

    private static boolean isCarved(final ChunkContext context, final Optional<Carver> carver, final int worldX, final int y, final int worldZ)
    {
        return carver.isPresent()
               && y >= carver.get().minY()
               && y <= carver.get().maxY()
               && carver.get().isCarved(context, worldX, y, worldZ);
    }
}
