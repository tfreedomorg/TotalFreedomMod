package me.totalfreedom.totalfreedommod.world.profile;

import java.util.List;
import java.util.Optional;

import org.bukkit.block.Biome;

/**
 * A biome TFM controls entirely, with no datapack.
 * <p>
 * display is the real vanilla biome the client renders: fog, sky colour, ambient sound, mob tables.
 * Everything else about a Logical biome is TFM's own to decide.
 * <p>
 * surface and features, when present, wholly replace the world's own lists for any column that
 * resolves to this definition; they do not merge with them. A reader of one definition's file can
 * answer "what spawns here" from that file alone, and merging would also risk placing a world-level
 * feature twice onto a column whose display happens to match its filter.
 * Absent means the column falls through to the world's plainly-filtered surface and features, the
 * same as a {@link BiomeTarget.Vanilla} band.
 * <p>
 * No identity field. A definition's identity is the filename it was loaded from, the same idiom
 * {@link ProfileLoader} already uses for a world's own profile.
 */
public record BiomeDefinition(Biome display,
                              Optional<List<SurfaceRule>> surface,
                              Optional<List<FeatureSpec>> features)
{
}
