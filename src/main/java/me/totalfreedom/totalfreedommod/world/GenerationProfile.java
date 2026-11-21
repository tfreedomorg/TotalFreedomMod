package me.totalfreedom.totalfreedommod.world;

import java.util.List;

import me.totalfreedom.totalfreedommod.world.profile.Bounds;
import me.totalfreedom.totalfreedommod.world.profile.FeatureSpec;
import me.totalfreedom.totalfreedommod.world.profile.Palette;
import me.totalfreedom.totalfreedommod.world.profile.ProfileParser;
import me.totalfreedom.totalfreedommod.world.profile.Shape;
import me.totalfreedom.totalfreedommod.world.profile.WorldSettings;

/**
 * One world's profile. Pure data, built only by {@link ProfileParser}, which turns away the obvious
 * nonsense: negative radii, an octave count that would never finish, that kind of thing. So the
 * fields you'd actually crash on are safe to trust. What's not covered yet is the merely
 * questionable, like a {@link Palette.Climate#scale} of zero; nothing stops that yet but this should 
 * probably be something that we look into making much more strict.
 * <p>
 * Holds no stage objects. The chunk generator pattern matches {@link Shape} once to pick its
 * stages, which keeps this package free of any dependency on the generation code.
 */
public record GenerationProfile(String name,
                                Bounds bounds,
                                Shape shape,
                                Palette palette,
                                List<FeatureSpec> features,
                                WorldSettings world)
{
}
