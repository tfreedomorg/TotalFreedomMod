package me.totalfreedom.totalfreedommod.blocking.sweep;

import java.util.function.Predicate;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

public interface TileEntityVisitor
{

    boolean enabled();

    long sweepIntervalTicks();

    Predicate<Block> blockFilter();

    void visit(BlockState state, SweepContext context);
}
