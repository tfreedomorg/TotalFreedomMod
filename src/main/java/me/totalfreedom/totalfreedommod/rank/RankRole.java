package me.totalfreedom.totalfreedommod.rank;

import java.util.Arrays;
import java.util.Optional;

/**
 * A job the plugin needs some rank to do, bound to an actual rank by {@code ranks.json}.
 * <p>
 * A few code paths cannot be expressed as a permission check because they have to answer "which
 * rank?" rather than "may they?": what an unidentified sender falls back to, what a newly added
 * admin starts as, which rank marks an impostor. Naming those ranks in code would break the moment
 * a custom renamed or deleted one, so a rank instead declares the roles it fills:
 * <pre>
 * "senior_admin": { ..., "roles": ["console_floor"] }
 * </pre>
 * Roles are a closed set because each one exists to satisfy a specific call site. Every role has a
 * structural fallback in {@link RankRegistry#byRole}, so a config that declares none still resolves
 * sensibly; declaring them only removes the guesswork.
 */
public enum RankRole
{

    /**
     * The rank an ordinary, non-opped player holds, and the floor any sender falls back to when it
     * cannot be placed. Falls back to the lowest-level rank that is neither an admin nor the
     * impostor marker.
     */
    DEFAULT("default"),

    /**
     * The rank an opped but non-admin player holds. Falls back to the highest-level rank that is
     * neither an admin nor the impostor marker.
     */
    DEFAULT_OP("default_op"),

    /**
     * The display-only rank shown for a player flagged as impersonating an admin. Falls back to the
     * lowest-level rank overall, which is where an impostor marker naturally sits.
     */
    IMPOSTOR("impostor"),

    /**
     * The rank a newly added admin receives, and the one an admin is reset to. Falls back to the
     * lowest-level rank marked {@code admin}.
     */
    ADMIN_DEFAULT("admin_default"),

    /**
     * The floor held by console channels that prove no user identity (RCON, RemoteBukkit, the
     * server console). Console access is itself the privilege, so {@code host_senders:} may raise a
     * channel above this but never below it. Falls back to {@link #ADMIN_DEFAULT}.
     */
    CONSOLE_FLOOR("console_floor");

    private final String id;

    RankRole(final String id)
    {
        this.id = id;
    }

    public String getId()
    {
        return id;
    }

    /**
     * The role written as {@code id} in configuration, or empty when the name is not a known role.
     * Unknown names are ignored rather than rejected so that a config written for a newer build
     * still loads on an older one.
     */
    public static Optional<RankRole> fromId(final String id)
    {
        return id == null
                ? Optional.empty()
                : Arrays.stream(values())
                        .filter(role -> role.id.equalsIgnoreCase(id.trim()))
                        .findFirst();
    }

}
