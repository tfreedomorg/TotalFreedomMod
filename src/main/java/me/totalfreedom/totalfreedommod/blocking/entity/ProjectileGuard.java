package me.totalfreedom.totalfreedommod.blocking.entity;

import java.util.Set;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.blocking.sweep.EntityVisitor;
import me.totalfreedom.totalfreedommod.blocking.sweep.SweepContext;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.DetectionReporter;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Removes projectiles that load chunks or otherwise threaten server stability
 * (wind charges, hyperspeed projectiles, and selected unconditional types).
 */
public class ProjectileGuard extends FreedomService
{

    private static final long LOG_INTERVAL_TICKS = 100L;
    private static final long SWEEP_INTERVAL_TICKS = 100L;
    private static final double MAX_PROJECTILE_SPEED_SQ = 3.5 * 3.5;
    private static final Set<EntityType> UNCONDITIONAL_PROJECTILE_TYPES = Set.of(
            EntityType.FIREWORK_ROCKET,
            EntityType.DRAGON_FIREBALL,
            EntityType.FIREBALL,
            EntityType.SMALL_FIREBALL,
            EntityType.WITHER_SKULL
    );

    private final EntityVisitor projectileVisitor = new EntityVisitor()
    {
        @Override
        public boolean enabled()
        {
            return ProjectileGuard.this.enabled();
        }

        @Override
        public long sweepIntervalTicks()
        {
            return SWEEP_INTERVAL_TICKS;
        }

        @Override
        public void visit(Entity entity, SweepContext context)
        {
            removeEntity(entity, context.label());
        }
    };

    private final DetectionReporter reporter = new DetectionReporter(
            LOG_INTERVAL_TICKS, server::getCurrentTick,
            (count, reason, max, sample) -> "[ProjectileGuard] Removed " + count
                    + " bad projectile(s). Sample: " + sample,
            DetectionReporter.warnOnly());

    public ProjectileGuard(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        plugin.sweepScheduler.register(projectileVisitor);
        FLog.info("[ProjectileGuard] active"
                + " [periodic sweep every " + SWEEP_INTERVAL_TICKS + "t]");
    }

    @Override
    protected void onStop()
    {
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
        server.getScheduler().runTask(plugin,
                FTask.guard("ProjectileGuard/removeOnAddToWorld", () -> removeEntity(entity, "addtoworld")));
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

    private void recordDetection(String context)
    {
        reporter.record(context);
    }
}
