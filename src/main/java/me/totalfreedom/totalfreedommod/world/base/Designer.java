package me.totalfreedom.totalfreedommod.world.base;

import org.bukkit.generator.ChunkGenerator;

/**
 * Decides what the base shape is made of. Surface blocks and bedrock.
 * <p>
 * Runs under generateSurface then generateBedrock, after {@link Generator} and before
 * {@link Carver}.
 * <p>
 * Driven by the surface rules in the world's profile. First rule that matches wins.
 */
public interface Designer
{
    /**
     * Swaps the generator's filler for real blocks. Runs under generateSurface.
     * <p>
     * Substitution only; never change which positions are solid. Walk down from the context's
     * column top, not its terrain height, or cave mouths get floating grass. Leave water alone.
     */
    void surface(ChunkContext context, ChunkGenerator.ChunkData data);

    /**
     * Writes the bedrock layer. Runs under generateBedrock.
     * <p>
     * How much to write comes from the profile's bedrock mode: a floor, a floor and a roof, or
     * nothing at all for a world made of floating islands.
     * <p>
     * The {@link Carver} runs after this and will happily eat bedrock, so keep its
     * {@link Carver#minY()} above this layer.
     */
    void bedrock(ChunkContext context, ChunkGenerator.ChunkData data);
}
