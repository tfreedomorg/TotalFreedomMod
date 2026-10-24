package me.totalfreedom.totalfreedommod.world.profile;

import org.bukkit.block.data.BlockData;

/**
 * The three blocks every profile needs: what the generator fills with, what the sea is made of, and
 * what the floor is, plus how much of a bedrock layer to write.
 * <p>
 * Already looked up, so never call createBlockData in a stage.
 */
public record Materials(BlockData defaultBlock,
                        BlockData fluidBlock,
                        BlockData bedrockBlock,
                        BedrockMode bedrock)
{
}
