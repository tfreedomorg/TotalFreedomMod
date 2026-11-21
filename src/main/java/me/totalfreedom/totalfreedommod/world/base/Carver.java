package me.totalfreedom.totalfreedommod.world.base;

/**
 * Carves caves and ravines out of the terrain. Subtractive only; terrain shaping belongs to
 * {@link Generator}.
 * <p>
 * Runs under generateCaves, and only ever answers yes or no about one block at a time.
 * {@code ProfileChunkGenerator} runs the loop and decides what actually happens to a block this
 * flags: leaving bedrock alone, and filling with water instead of air below the depth the profile
 * sets.
 */
public interface Carver
{
    /**
     * True if the block should be removed. Only called for y within {@link #minY()} and
     * {@link #maxY()}.
     * <p>
     * The answer has to depend only on the position. Do not use the context's random here; it is
     * seeded per chunk, so one block could get two different answers depending on which chunk was
     * being generated at the time, and you end up with seams along every chunk border.
     * <p>
     * Reading the context's terrain height is fine, and tightening the threshold as you get near it
     * blends cave mouths into the hillside. Do not read the column top, though; that is worked out
     * by calling this method, so you will recurse into yourself until the stack overflows.
     */
    boolean isCarved(ChunkContext context, int worldX, int y, int worldZ);

    /**
     * Lowest y this carver touches, inclusive. Read once per chunk and checked outside the block
     * loop, so a deep carver costs nothing in the columns above it. Must be constant.
     * <p>
     * A world with no caves has no carver at all, so there is never a reason to report a range that
     * cannot match.
     */
    int minY();

    /** Highest y this carver touches, inclusive. See {@link #minY()}. */
    int maxY();
}
