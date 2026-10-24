package me.totalfreedom.totalfreedommod.world.base;

import java.util.Optional;

/**
 * The four stages wired up for one world.
 * <p>
 * Built once by the chunk generator, which pattern matches the profile's shape to decide what goes
 * in here. An empty carver means the world has no caves, so nothing has to invent a y range that
 * never matches.
 */
public record Stages(Generator generator,
                     Designer designer,
                     Optional<Carver> carver,
                     Populator populator)
{
}