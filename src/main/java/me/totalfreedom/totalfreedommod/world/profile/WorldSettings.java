package me.totalfreedom.totalfreedommod.world.profile;

import java.util.Optional;

import org.bukkit.World;

/**
 * Settings applied to the WorldCreator at creation. Never read during chunk generation.
 * <p>
 * roExempt defaults to false and opts a world's players out of commands that sweep every online
 * player by default, e.g. {@code /ro} with no explicit player list.
 * <p>
 * weatherDisabled defaults to false and, per world, forces rain/thunder off and cancels the events
 * that would start it again. World-specific behavior like this belongs here rather than as a single
 * server-wide config.yml toggle, since whether it makes sense varies world to world.
 */
public record WorldSettings(World.Environment environment,
                            boolean generateStructures,
                            Optional<Long> seed,
                            Optional<Access> access,
                            boolean roExempt,
                            boolean weatherDisabled,
                            Blocking blocking,
                            VanillaFlags vanilla)
{
    /**
     * Which vanilla generation steps run alongside ours. All default off. The chunk generator hands
     * these to Bukkit through its shouldGenerate methods.
     * <p>
     * Turning on decorations gets you vanilla trees, flowers, and ore without writing any features.
     */
    public record VanillaFlags(boolean surface,
                               boolean caves,
                               boolean decorations,
                               boolean mobs,
                               boolean structures)
    {
    }

    /**
     * Turns this world into one a player needs {@code permission} to enter or linger in, with its
     * own guest list and independent weather/time controls. Absent entirely, a world is open to
     * everyone and none of that machinery applies.
     * <p>
     * Not specific to any one world; any profile can declare this, which is what lets a title-gated
     * world like a "masterbuilder" world reuse the exact same guest-list and access commands the
     * admin world does.
     */
    public record Access(String permission)
    {
    }

    /**
     * Per-world event suppression that used to be a handful of server-wide config.yml toggles.
     * Absent from a profile entirely (or from a world with no profile at all), everything here
     * defaults to {@link #NONE}: nothing suppressed, no cap.
     */
    public record Blocking(boolean spawners,
                           boolean spawnerPlace,
                           boolean portalCreate,
                           boolean pistons,
                           boolean monsters,
                           Optional<Integer> entitySpamMax)
    {
        public static final Blocking NONE = new Blocking(false, false, false, false, false, Optional.empty());
    }
}
