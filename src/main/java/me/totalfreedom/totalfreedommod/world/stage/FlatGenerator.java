package me.totalfreedom.totalfreedommod.world.stage;

import java.util.stream.IntStream;

import org.bukkit.generator.ChunkGenerator;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Generator;
import me.totalfreedom.totalfreedommod.world.profile.Bounds;
import me.totalfreedom.totalfreedommod.world.profile.LayerStack;

/**
 * Flat mode. Samples no noise at all and writes each layer as a single setRegion call.
 */
public final class FlatGenerator implements Generator
{
    private final LayerStack layers;
    private final Bounds bounds;

    public FlatGenerator(final LayerStack layers, final Bounds bounds)
    {
        this.layers = layers;
        this.bounds = bounds;
    }

    @Override
    public void generateBase(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        final int[] y = { this.bounds.minY() }; // effectively final

        IntStream.range(0, this.layers.size()).forEach(layer ->
        {
            final int height = this.layers.heightAt(layer);

            data.setRegion(0, y[0], 0, 16, y[0] + height, 16, this.layers.blockAt(layer));
            y[0] += height;
        });
    }

    @Override
    public int surfaceHeight(final int worldX, final int worldZ)
    {
        return this.bounds.minY() + this.layers.totalHeight() - 1;
    }
}
