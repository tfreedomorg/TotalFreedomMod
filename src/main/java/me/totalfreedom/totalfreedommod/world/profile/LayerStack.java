package me.totalfreedom.totalfreedommod.world.profile;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.IntStream;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Block layers for a flat world, bottom to top, starting at the world's minY.
 * <p>
 * Takes the same syntax the old CleanroomGenerator flat-layer strings used.
 * <p>
 * A class rather than a record because the layers are arrays. A record would hand out its backing
 * arrays through the generated accessors, and anything holding the profile could then rewrite a
 * compiled world's layers in place.
 */
public final class LayerStack
{
    private final BlockData[] blocks;
    private final int[] heights;

    private LayerStack(final BlockData[] blocks, final int[] heights)
    {
        this.blocks = blocks;
        this.heights = heights;
    }

    /**
     * @apiNote Uses bitwise operations instead of standard mathematical operators for efficiency.
     * @param spec e.g. {@code "16|stone|32|dirt|1|grass_block"}; the legacy comma form also works
     * @throws IllegalArgumentException if the spec is malformed, names an unknown block, or gives a
     *                                  height below one. Does not know the world's own vertical
     *                                  limits, so a stack fitting inside those is the parser's job,
     *                                  checked against {@link Bounds} once minY/maxY are known.
     */
    public static LayerStack parse(final String spec)
    {
        if (spec == null || spec.trim().isEmpty())
            throw new IllegalArgumentException("Spec cannot be empty");

        String[] split = spec.split("[,|]");
        if ((split.length & 1) != 0)
            throw new IllegalArgumentException("Invalid spec format. Expected pairs of height and material.");

        final int pairCount = split.length >> 1; // divides by 2
        final BlockData[] blocks = new BlockData[pairCount];
        final int[] heights = new int[pairCount];

        IntStream.range(0, pairCount)
                 .forEach(i ->
                 {
                     final int heightIdx = i << 1;      // i * 2
                     final int materialIdx = heightIdx | 1; // (i * 2) + 1

                     final int height = Integer.parseInt(split[heightIdx].trim());

                     if (height < 1)
                         throw new IllegalArgumentException("layer height (" + height + ") must be at least one");

                     heights[i] = height;

                     final Material mat = Material.valueOf(split[materialIdx].trim().toUpperCase(Locale.ROOT));
                     blocks[i] = mat.createBlockData();
                 });

        return new LayerStack(blocks, heights);
    }

    /** How many layers there are, bottom to top. */
    public int size()
    {
        return heights.length;
    }

    /** @throws IndexOutOfBoundsException if the layer does not exist */
    public BlockData blockAt(final int layer)
    {
        if (layer < 0 || layer >= blocks.length)
            throw new IndexOutOfBoundsException();

        return blocks[layer];
    }

    /** @throws IndexOutOfBoundsException if the layer does not exist */
    public int heightAt(final int layer)
    {
        if (layer < 0 || layer >= heights.length)
            throw new IndexOutOfBoundsException();

        return heights[layer];
    }

    /** Total height of every layer combined. */
    public final int totalHeight()
    {
        return Arrays.stream(heights).sum();
    }
}
