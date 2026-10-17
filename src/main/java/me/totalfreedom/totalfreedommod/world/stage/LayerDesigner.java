package me.totalfreedom.totalfreedommod.world.stage;

import org.bukkit.generator.ChunkGenerator;

import me.totalfreedom.totalfreedommod.world.base.ChunkContext;
import me.totalfreedom.totalfreedommod.world.base.Designer;
import me.totalfreedom.totalfreedommod.world.profile.BedrockMode;
import me.totalfreedom.totalfreedommod.world.profile.LayerStack;
import me.totalfreedom.totalfreedommod.world.profile.Materials;

/**
 * Flat mode's designer. The layer stack already named every block, so surface is a no-op and only
 * bedrock gets written here.
 */
public final class LayerDesigner implements Designer
{
    private final Materials materials;

    public LayerDesigner(final LayerStack layers, final Materials materials)
    {
        // layers is unneeded
        this.materials = materials;
    }

    @Override
    public void surface(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        // no-op
    }

    @Override
    public void bedrock(final ChunkContext context, final ChunkGenerator.ChunkData data)
    {
        if (this.materials.bedrock() == BedrockMode.NONE)
            return;

        final int minY = context.getProfile().bounds().minY();
        final int maxY = context.getProfile().bounds().maxY();

        data.setRegion(0, minY, 0, 16, minY + 1, 16, this.materials.bedrockBlock());

        if (this.materials.bedrock() == BedrockMode.FLOOR_AND_ROOF)
            data.setRegion(0, maxY - 1, 0, 16, maxY, 16, this.materials.bedrockBlock());
    }

}
