package me.totalfreedom.totalfreedommod.world.profile;

/**
 * One thing wrong with a profile file.
 * <p>
 * path is the dotted key it came from, so an admin can go straight to it rather than hunting.
 *
 * @param path    for example {@code shape.caves.threshold}
 * @param message what was wrong, in words, including what was found
 */
public record ProfileError(String path, String message)
{
    /** Renders as {@code shape.caves.threshold: expected a number, got "0.5x"}. */
    @Override
    public String toString()
    {
        return this.path + ": " + this.message;
    }
}
