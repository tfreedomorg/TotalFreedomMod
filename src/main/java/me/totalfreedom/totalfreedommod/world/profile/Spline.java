package me.totalfreedom.totalfreedommod.world.profile;

import java.util.Arrays;
import java.util.List;

import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Maps raw noise to a terrain height through control points. Binary search plus a lerp per column.
 * <p>
 * Plateaus, cliffs, and flat plains all come out of one array of points.
 */
public final class Spline
{
    private final double[] inputs;
    private final double[] outputs;

    private Spline(final double[] inputs, final double[] outputs)
    {
        this.inputs = inputs;
        this.outputs = outputs;
    }

    /**
     * @param points {@code [noise, height]} pairs
     * @throws IllegalArgumentException if fewer than two points, a point is not a pair, or the
     *                                  noise values are not strictly ascending, which would make
     *                                  the search ambiguous
     */
    public static Spline of(final List<double[]> points)
    {
        if (points == null || points.size() < 2)
            throw new IllegalArgumentException("A spline needs at least two points");

        final double[] inputs = new double[points.size()];
        final double[] outputs = new double[points.size()];

        for (int i = 0; i < points.size(); i++)
        {
            final double[] point = points.get(i);

            if (point == null || point.length != 2)
                throw new IllegalArgumentException("Point " + i + " must be a [noise, height] pair");

            if (i > 0 && point[0] <= inputs[i - 1])
                throw new IllegalArgumentException("Point " + i + " noise (" + point[0]
                        + ") must be above the previous point's (" + inputs[i - 1] + ")");

            inputs[i] = point[0];
            outputs[i] = point[1];
        }

        return new Spline(inputs, outputs);
    }

    /**
     * The height this noise maps to. Clamps to the first and last point outside their range, so
     * noise beyond the outermost control points flattens off rather than running away.
     */
    public double apply(final double noise)
    {
        final int last = this.inputs.length - 1;

        if (noise <= this.inputs[0])
            return this.outputs[0];

        if (noise >= this.inputs[last])
            return this.outputs[last];

        final int found = Arrays.binarySearch(this.inputs, noise);

        if (found >= 0)
            return this.outputs[found];

        // A miss returns -(insertion point) - 1, and the insertion point is the first control point
        // above the noise, so the segment we want is the one ending there.
        final int upper = -(found + 1);
        final int lower = upper - 1;

        final double progress = (noise - this.inputs[lower]) / (this.inputs[upper] - this.inputs[lower]);

        return FUtil.lerp(progress, this.outputs[lower], this.outputs[upper]);
    }

    /** How many control points there are. */
    public int size()
    {
        return this.inputs.length;
    }
}
