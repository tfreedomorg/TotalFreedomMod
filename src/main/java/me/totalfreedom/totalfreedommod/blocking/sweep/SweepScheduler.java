package me.totalfreedom.totalfreedommod.blocking.sweep;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.ChunkLoadEvent;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;

public class SweepScheduler extends FreedomService
{

    private static final long DRAIN_BUDGET_NANOS = 5_000_000L;

    private final Map<Integer, Registered> registry = new LinkedHashMap<>();
    private final Deque<Job> pending = new ArrayDeque<>();
    private final Set<String> pendingKeys = new HashSet<>();

    private int nextVisitorId = 0;
    private int drainTaskId = -1;
    private boolean started;

    public SweepScheduler(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        started = true;
        ensureDrainTask();
        for (Registered r : registry.values())
        {
            activate(r);
        }
        FLog.info("[SweepScheduler] active [budget=" + (DRAIN_BUDGET_NANOS / 1_000_000L) + "ms/tick]");
    }

    @Override
    protected void onStop()
    {
        started = false;
        if (drainTaskId != -1)
        {
            server.getScheduler().cancelTask(drainTaskId);
            drainTaskId = -1;
        }
        for (Registered r : registry.values())
        {
            if (r.periodicTaskId != -1)
            {
                server.getScheduler().cancelTask(r.periodicTaskId);
                r.periodicTaskId = -1;
            }
        }
        registry.clear();
        pending.clear();
        pendingKeys.clear();
    }

    public void register(EntityVisitor visitor)
    {
        Registered r = new Registered(nextVisitorId++, visitor::enabled, visitor.sweepIntervalTicks(),
                (chunk, ctx) ->
                {
                    Entity[] entities;
                    try
                    {
                        entities = chunk.getEntities();
                    }
                    catch (Throwable t)
                    {
                        return;
                    }
                    for (Entity entity : entities)
                    {
                        visitor.visit(entity, ctx);
                    }
                });
        registry.put(r.id, r);
        if (started)
        {
            activate(r);
        }
    }

    public void register(TileEntityVisitor visitor)
    {
        Registered r = new Registered(nextVisitorId++, visitor::enabled, visitor.sweepIntervalTicks(),
                (chunk, ctx) ->
                {
                    Collection<BlockState> tiles;
                    try
                    {
                        tiles = chunk.getTileEntities(visitor.blockFilter(), false);
                    }
                    catch (Throwable t)
                    {
                        return;
                    }
                    for (BlockState state : tiles)
                    {
                        visitor.visit(state, ctx);
                    }
                });
        registry.put(r.id, r);
        if (started)
        {
            activate(r);
        }
    }

    private void activate(Registered r)
    {
        if (r.intervalTicks > 0 && r.periodicTaskId == -1)
        {
            r.periodicTaskId = server.getScheduler().runTaskTimer(plugin,
                FTask.guard("SweepScheduler/periodic#" + r.id, () ->
                {
                    if (r.enabled.getAsBoolean())
                    {
                        enqueueAllLoaded(r, SweepContext.PERIODIC);
                    }
                }), r.intervalTicks, r.intervalTicks).getTaskId();
        }
        if (r.intervalTicks >= 0 && r.enabled.getAsBoolean())
        {
            enqueueAllLoaded(r, SweepContext.INITIAL);
        }
    }

    private void enqueueAllLoaded(Registered r, SweepContext ctx)
    {
        for (World world : Bukkit.getWorlds())
        {
            for (Chunk chunk : world.getLoadedChunks())
            {
                enqueue(r, chunk, ctx);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event)
    {
        if (!started || event.isNewChunk())
        {
            return;
        }
        Chunk chunk = event.getChunk();
        for (Registered r : registry.values())
        {
            if (r.enabled.getAsBoolean())
            {
                enqueue(r, chunk, SweepContext.CHUNK_LOAD);
            }
        }
    }

    public void enqueueChunk(Chunk chunk, SweepContext ctx)
    {
        if (!started || chunk == null)
        {
            return;
        }
        for (Registered r : registry.values())
        {
            if (r.enabled.getAsBoolean())
            {
                enqueue(r, chunk, ctx);
            }
        }
    }

    private void enqueue(Registered r, Chunk chunk, SweepContext ctx)
    {
        if (chunk == null)
        {
            return;
        }
        String key = r.id + ":" + chunk.getWorld().getName() + ':' + chunk.getChunkKey();
        if (pendingKeys.add(key))
        {
            pending.add(new Job(r.id, chunk, key, ctx));
            ensureDrainTask();
        }
    }

    private void ensureDrainTask()
    {
        if (started && drainTaskId == -1)
        {
            drainTaskId = server.getScheduler().runTaskTimer(plugin,
                    FTask.guard("SweepScheduler/drain", this::drain), 1L, 1L).getTaskId();
        }
    }

    private void drain()
    {
        if (pending.isEmpty())
        {
            return;
        }
        long deadline = System.nanoTime() + DRAIN_BUDGET_NANOS;
        do
        {
            Job job = pending.poll();
            pendingKeys.remove(job.key);
            Registered r = registry.get(job.visitorId);
            if (r == null || !r.enabled.getAsBoolean())
            {
                continue;
            }
            Chunk chunk = job.chunk;
            if (chunk != null && chunk.isLoaded())
            {
                try
                {
                    r.applier.apply(chunk, job.context);
                }
                catch (Throwable ignored)
                {
                }
            }
        }
        while (!pending.isEmpty() && System.nanoTime() < deadline);
    }

    @FunctionalInterface
    private interface ChunkApplier
    {
        void apply(Chunk chunk, SweepContext context);
    }

    private static final class Registered
    {
        private final int id;
        private final BooleanSupplier enabled;
        private final long intervalTicks;
        private final ChunkApplier applier;
        private int periodicTaskId = -1;

        private Registered(int id, BooleanSupplier enabled, long intervalTicks, ChunkApplier applier)
        {
            this.id = id;
            this.enabled = enabled;
            this.intervalTicks = intervalTicks;
            this.applier = applier;
        }
    }

    private static final class Job
    {
        private final int visitorId;
        private final Chunk chunk;
        private final String key;
        private final SweepContext context;

        private Job(int visitorId, Chunk chunk, String key, SweepContext context)
        {
            this.visitorId = visitorId;
            this.chunk = chunk;
            this.key = key;
            this.context = context;
        }
    }
}
