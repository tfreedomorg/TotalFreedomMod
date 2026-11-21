package me.totalfreedom.totalfreedommod.world.noise;

/**
 * One noise field's settings. Feed it to {@link NoiseField#of} to get a usable field.
 * <p>
 * Amplitude is set by the spline for terrain and by the threshold for caves, so it is not a knob
 * here. The seed comes from the field's role name mixed with the world seed when the profile
 * parses.
 * <p>
 * persistence and lacunarity must be positive: {@link NoiseField}'s normaliser sums each octave's
 * amplitude, itself the previous octave's times persistence, and a zero or negative persistence can
 * walk that sum through zero (an even octave count with persistence exactly -1 zeroes it outright),
 * turning every sample into infinity or NaN.
 *
 * @throws IllegalArgumentException if octaves is below one or above {@value #MAX_OCTAVES}, if
 *                                   frequency is not positive and finite, or if persistence or
 *                                   lacunarity is not positive and finite
 */
public record NoiseProfile(NoiseType type,
                           int octaves,
                           double frequency,
                           double persistence,
                           double lacunarity,
                           boolean ridged)
{
    /** Above this, an OctaveGenerator's own per-octave cost stops being worth what it buys. */
    private static final int MAX_OCTAVES = 16;

    public NoiseProfile
    {
        if (octaves < 1 || octaves > MAX_OCTAVES)
            throw new IllegalArgumentException("octaves (" + octaves + ") must fall within 1 to " + MAX_OCTAVES);

        if (!(frequency > 0.0D) || Double.isInfinite(frequency))
            throw new IllegalArgumentException("frequency (" + frequency + ") must be positive and finite");

        if (!(persistence > 0.0D) || Double.isInfinite(persistence))
            throw new IllegalArgumentException("persistence (" + persistence + ") must be positive and finite");

        if (!(lacunarity > 0.0D) || Double.isInfinite(lacunarity))
            throw new IllegalArgumentException("lacunarity (" + lacunarity + ") must be positive and finite");
    }
}
