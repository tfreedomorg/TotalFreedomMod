package me.totalfreedom.totalfreedommod.world.profile;

/**
 * What the designer writes for a world's bedrock layer.
 * <p>
 * FLOOR is the usual one. FLOOR_AND_ROOF caps the world off as well, which is what the nether
 * wants. NONE writes nothing at all, for worlds made of floating islands.
 */
public enum BedrockMode
{
    FLOOR,
    FLOOR_AND_ROOF,
    NONE
}
