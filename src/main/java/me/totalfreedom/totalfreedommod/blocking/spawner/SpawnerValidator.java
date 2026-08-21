package me.totalfreedom.totalfreedommod.blocking.spawner;

import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.entity.TrialSpawnerSpawnEvent;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.blocking.sweep.TileEntityVisitor;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.DetectionReporter;
import me.totalfreedom.totalfreedommod.util.FLog;

public class SpawnerValidator extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;
    private static final long SWEEP_INTERVAL_TICKS = 100L;

    private final TileEntityVisitor windChargeSpawnerVisitor = new TileEntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return windChargeActive();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return SWEEP_INTERVAL_TICKS;
        }

        @Override
        public Predicate<Block> blockFilter()
        {
            return b -> b.getType() == Material.SPAWNER;
        }

        @Override
        public void visit(BlockState state, SweepContext context)
        {
            if (state instanceof CreatureSpawner spawner)
            {
                destroyWindChargeSpawner(spawner, "chunk-sweep");
            }
        }
    };

    private final DetectionReporter reporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[SpawnerValidator] Blocked " + count
                    + " spawner threat(s). Reason: " + reason
                    + " | sample: " + sample,
            DetectionReporter.warnAndBroadcastAdmins(plugin));

    public SpawnerValidator(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        plugin.sweepScheduler.register(windChargeSpawnerVisitor);
        FLog.info("[SpawnerValidator] active"
                + " [hanging prevent=" + Boolean.TRUE.equals(ConfigEntry.CRASH_SPAWNERS_PREVENT.getBoolean()) + "]"
                + " [wind-charge prevent=" + windChargeActive() + "]"
                + " [wind-charge sweep every " + SWEEP_INTERVAL_TICKS + "t]");
    }

    @Override
    protected void onStop()
    {
    }

    private boolean hangingPreventActive()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_SPAWNERS_PREVENT.getBoolean());
    }

    /** Wind-charge spawner blocks remain gated on crash_entities (historical behavior). */
    private boolean windChargeActive()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_PREVENT.getBoolean());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event)
    {
        blockHangingFromSpawner(event, "spawner-hanging", "spawner");
        blockWindChargeFromSpawner(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTrialSpawnerSpawn(TrialSpawnerSpawnEvent event)
    {
        blockHangingFromSpawner(event, "trial-spawner-hanging", "trial-spawner");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (!windChargeActive())
        {
            return;
        }
        if (event.getBlock().getType() != Material.SPAWNER)
        {
            return;
        }
        BlockState state = event.getBlock().getState();
        if (state instanceof CreatureSpawner spawner)
        {
            destroyWindChargeSpawner(spawner, "place");
        }
    }

    private void blockWindChargeFromSpawner(SpawnerSpawnEvent event)
    {
        if (!windChargeActive())
        {
            return;
        }
        Entity entity = event.getEntity();
        CreatureSpawner spawner = event.getSpawner();
        if (!(entity instanceof AbstractWindCharge) && !isWindChargeSpawner(spawner))
        {
            return;
        }
        event.setCancelled(true);
        destroySpawner(spawner, "spawn blocked for " + entity.getType());
    }

    private void blockHangingFromSpawner(EntitySpawnEvent event, String reason, String label)
    {
        if (Boolean.TRUE.equals(ConfigEntry.DISABLE_SPAWNERS.getBoolean()))
        {
            return;
        }
        if (!hangingPreventActive())
        {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Hanging)
        {
            event.setCancelled(true);
            recordDetection(reason, label + " on " + entity.getType() + "@" + locShort(entity));
        }
    }

    private static boolean isWindChargeSpawner(CreatureSpawner spawner)
    {
        EntityType type = spawner.getSpawnedType();
        return type == EntityType.BREEZE_WIND_CHARGE || type == EntityType.WIND_CHARGE;
    }

    private void destroyWindChargeSpawner(CreatureSpawner spawner, String context)
    {
        if (!isWindChargeSpawner(spawner))
        {
            return;
        }
        destroySpawner(spawner, context);
    }

    private void destroySpawner(CreatureSpawner spawner, String context)
    {
        Block block = spawner.getBlock();
        if (block.getType() != Material.SPAWNER)
        {
            return;
        }
        try
        {
            block.setType(Material.AIR, false);
        }
        catch (Throwable ignored)
        {
            return;
        }
        recordDetection("wind-charge-spawner", "spawner-destroy " + context + "@" + locShort(block));
    }

    private static String locShort(Entity entity)
    {
        try
        {
            return entity.getWorld().getName()
                    + ":" + Math.round(entity.getLocation().getX())
                    + "," + Math.round(entity.getLocation().getY())
                    + "," + Math.round(entity.getLocation().getZ());
        }
        catch (Throwable t)
        {
            return "?";
        }
    }

    private static String locShort(Block block)
    {
        return block.getWorld().getName()
                + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private void recordDetection(String reason, String context)
    {
        reporter.record(reason, context);
    }
}
