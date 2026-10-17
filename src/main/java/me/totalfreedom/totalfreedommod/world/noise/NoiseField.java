package me.totalfreedom.totalfreedommod.world.noise;

import java.util.Random;

import org.bukkit.util.noise.OctaveGenerator;
import org.bukkit.util.noise.PerlinOctaveGenerator;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * A built noise field. Immutable, sampled concurrently from every worldgen thread.
 * <p>
 * Build once at compile and hand it to a stage as a final field; that is what guarantees other
 * threads see it fully constructed. Never reconfigure it afterward, setScale mutates and may only
 * be touched inside {@link #of}.
 */
public final class NoiseField
{
    private final NoiseProfile profile;
    private final OctaveGenerator generator;
    private final double normaliser;

    private NoiseField(final NoiseProfile profile, final OctaveGenerator generator, final double normaliser)
    {
        this.profile = profile;
        this.generator = generator;
        this.normaliser = normaliser;
    }

    /**
     * @param role stable name like "terrain" or "caves", mixed into the seed so two fields in one
     *             profile cannot end up on the same stream
     */
    public static NoiseField of(final NoiseProfile profile, final long seed, final String role)
    {
        final Random source = new Random(seed ^ role.hashCode());

        final OctaveGenerator generator = switch (profile.type())
        {
            case PERLIN -> new PerlinOctaveGenerator(source, profile.octaves());
            case SIMPLEX -> new SimplexOctaveGenerator(source, profile.octaves());
        };

        generator.setScale(profile.frequency());

        return new NoiseField(profile, generator, normaliserFor(profile));
    }

    /** 2D sample, in the range -1 to 1. */
    public double sample(final int x, final int z)
    {
        final double raw = this.generator.noise(x, 0.0D, z, profile.lacunarity(), profile.persistence(), false);

        return shape(raw / this.normaliser);
    }

    /** 3D sample, in the range -1 to 1. */
    public double sample(final int x, final int y, final int z)
    {
        final double raw = this.generator.noise(x, y, z, profile.lacunarity(), profile.persistence(), false);

        return shape(raw / this.normaliser);
    }

    public NoiseProfile getProfile()
    {
        return this.profile;
    }

    /**
     * Bukkit sums its octaves without scaling them back down, so a four octave field at persistence
     * 0.5 returns roughly plus or minus 1.875 rather than 1. Dividing by the summed amplitudes is
     * what puts it back in range.
     */
    private static double normaliserFor(final NoiseProfile profile)
    {
        double total = 0.0D;
        double amplitude = 1.0D;

        for (int octave = 0; octave < profile.octaves(); octave++)
        {
            total += amplitude;
            amplitude *= profile.persistence();
        }

        return total;
    }

    /**
     * Ridged noise folds the field about zero and inverts it, turning the smooth peaks into sharp
     * ones. Good for mountain ridges and for cave tunnels.
     */
    private double shape(final double normalised)
    {
        if (!this.profile.ridged())
            return normalised;

        return 1.0D - Math.abs(normalised) * 2.0D;
    }
}
