package me.totalfreedom.totalfreedommod.world.profile;

import java.util.List;

/**
 * Thrown when a profile file cannot be turned into a world.
 * <p>
 * Carries every problem found, not just the first one, so an admin fixes the file in one pass
 * instead of rerunning after each typo.
 * <p>
 * Checked on purpose. Parsing a profile is the one place a bad world can enter the system, and the
 * caller has to decide what to do about it rather than letting it slip past.
 */
public final class ProfileException extends Exception
{
    private final List<ProfileError> errors;

    public ProfileException(final String worldName, final List<ProfileError> errors)
    {
        super("Profile for world '" + worldName + "' has " + errors.size() + " problem(s)");

        this.errors = List.copyOf(errors);
    }

    public List<ProfileError> getErrors()
    {
        return this.errors;
    }
}
