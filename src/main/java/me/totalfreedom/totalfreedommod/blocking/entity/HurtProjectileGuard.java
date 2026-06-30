package me.totalfreedom.totalfreedommod.blocking.entity;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.util.List;
import java.util.Set;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Vector;

/**
 * Targets projectiles that load chunks, such as wind charges, and hurt projetiles
 * (AbstractHurtProjectile).
 */
public class HurtProjectileGuard extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;
    private static final long SWEEP_INTERVAL_TICKS = 20L;
    private static final double MAX_PROJECTILE_SPEED_SQ = 3.5 * 3.5;
    private static final Set<EntityType> UNCONDITIONAL_PROJECTILE_TYPES = Set.of(
            EntityType.FIREWORK_ROCKET,
            EntityType.DRAGON_FIREBALL,
            EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL,
            EntityType.WITHER_SKULL
    );

    private int sweepTaskId = -1;

    private long lastSummaryTick = 0L;
    private long detectionsSinceLastSummary = 0L;
    private String sampleContext = null;

    public HurtProjectileGuard(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        schedulePeriodicSweep();
        server.getScheduler().runTaskLater(plugin, this::sweepAllWorlds, 1L);
        FLog.info("[WindChargeGuard] active"
                + " [periodic sweep every " + SWEEP_INTERVAL_TICKS + "t]");
    }

    @Override
    protected void onStop()
    {
        if (sweepTaskId != -1)
        {
            server.getScheduler().cancelTask(sweepTaskId);
            sweepTaskId = -1;
        }
    }

    private boolean enabled()
    {
        return Boolean.TRUE.equals(ConfigEntry.CRASH_ENTITIES_PREVENT.getBoolean());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityMount(EntityMountEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Entity mount = event.getMount();
        if (!isBlockedProjectile(mount))
        {
            return;
        }
        event.setCancelled(true);
        removeEntity(mount, "mount blocked for " + event.getEntity().getName());

        Entity rider = event.getEntity();
        if (rider instanceof Player player)
        {
            FUtil.playerMsg(player, "You cannot ride a wind charge.", NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event)
    {
        if (!enabled())
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (!enabled())
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Entity entity = event.getEntity();
        if (!isBlockedProjectile(entity))
        {
            return;
        }
        event.setCancelled(true);
        recordDetection("spawn-cancel on " + entity.getType() + "@" + locShort(entity));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Entity entity = event.getEntity();
        if (!isBlockedProjectile(entity))
        {
            return;
        }
        event.setCancelled(true);
        removeEntity(entity, "launch-cancel");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityAddToWorld(EntityAddToWorldEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Entity entity = event.getEntity();
        if (!isBlockedProjectile(entity))
        {
            return;
        }
        // EntityAddToWorldEvent is not cancellable, so defer the removal one tick.
        server.getScheduler().runTask(plugin, () -> removeEntity(entity, "addtoworld"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event)
    {
        if (!enabled())
        {
            return;
        }
        Chunk chunk = event.getChunk();
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
            removeEntity(entity, "chunkload");
        }
        sweepSpawnersInChunk(chunk);
    }

    private void schedulePeriodicSweep()
    {
        sweepTaskId = server.getScheduler()
                .runTaskTimer(plugin, this::sweepAllWorlds, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS)
                .getTaskId();
    }

    private void sweepAllWorlds()
    {
        if (!enabled())
        {
            return;
        }
        for (World world : Bukkit.getWorlds())
        {
            List<Entity> entities;
            try
            {
                entities = world.getEntities();
            }
            catch (Throwable t)
            {
                continue;
            }
            for (Entity entity : entities)
            {
                removeEntity(entity, "periodic-sweep");
            }
            for (Chunk chunk : world.getLoadedChunks())
            {
                sweepSpawnersInChunk(chunk);
            }
        }
    }

    private void sweepSpawnersInChunk(Chunk chunk)
    {
        BlockState[] tileEntities;
        try
        {
            tileEntities = chunk.getTileEntities(block -> block.getType() == Material.SPAWNER, false);
        }
        catch (Throwable t)
        {
            return;
        }
        for (BlockState state : tileEntities)
        {
            if (state instanceof CreatureSpawner spawner)
            {
                destroyWindChargeSpawner(spawner, "chunk-sweep");
            }
        }
    }

    private boolean isWindChargeSpawner(CreatureSpawner spawner)
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
        recordDetection("spawner-destroy " + context + "@" + locShort(block));
    }

    private boolean isBlockedProjectile(Entity entity)
    {
        if (entity instanceof AbstractWindCharge)
        {
            return true;
        }
        if (UNCONDITIONAL_PROJECTILE_TYPES.contains(entity.getType()))
        {
            return true;
        }
        return isHyperspeedProjectile(entity);
    }

    private boolean isHyperspeedProjectile(Entity entity)
    {
        if (!(entity instanceof Projectile projectile))
        {
            return false;
        }
        Vector velocity = projectile.getVelocity();
        return velocity.lengthSquared() > MAX_PROJECTILE_SPEED_SQ;
    }

    private void removeEntity(Entity entity, String context)
    {
        if (!isBlockedProjectile(entity))
        {
            return;
        }
        try
        {
            entity.remove();
        }
        catch (Throwable ignored)
        {
            return;
        }
        recordDetection(context + " on " + entity.getType() + "@" + locShort(entity));
    }

    private static String locShort(Entity entity)
    {
        try
        {
            return locShort(entity.getWorld().getName(), entity.getLocation().getX(),
                    entity.getLocation().getY(), entity.getLocation().getZ());
        }
        catch (Throwable t)
        {
            return "?";
        }
    }

    private static String locShort(Block block)
    {
        return locShort(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String locShort(String world, double x, double y, double z)
    {
        return world + ":" + Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
    }

    private void recordDetection(String context)
    {
        detectionsSinceLastSummary++;
        if (sampleContext == null)
        {
            sampleContext = context;
        }

        long nowTick = server.getCurrentTick();
        if (lastSummaryTick == 0L || nowTick - lastSummaryTick >= LOG_INTERVAL_TICKS)
        {
            FLog.warning("[EntityValidator] Removed " + detectionsSinceLastSummary
                    + " bad projectile(s). Sample: " + sampleContext);

            lastSummaryTick = nowTick;
            detectionsSinceLastSummary = 0L;
            sampleContext = null;
        }
    }
}
