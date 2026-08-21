package me.totalfreedom.totalfreedommod.blocking;

import io.papermc.paper.event.block.BlockPreDispenseEvent;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.world.PortalCreateEvent;

import me.totalfreedom.totalfreedommod.EntityWiper;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

public class EventBlocker extends FreedomService
{

    public EventBlocker(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBurn(BlockBurnEvent event)
    {
        if (!ConfigEntry.ALLOW_FIRE_SPREAD.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockIgnite(BlockIgniteEvent event)
    {
        final boolean placement = event.getCause() == BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL;
        final boolean allowed = placement
            ? ConfigEntry.ALLOW_FIRE_PLACE.getBoolean()
            : ConfigEntry.ALLOW_FIRE_SPREAD.getBoolean();
        if (!allowed)
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockSpread(BlockSpreadEvent event)
    {
        if (event.getSource().getType() == Material.FIRE
            && !ConfigEntry.ALLOW_FIRE_SPREAD.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockFromTo(BlockFromToEvent event)
    {
        if (!ConfigEntry.ALLOW_FLUID_SPREAD.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event)
    {
        if (!ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            event.setCancelled(true);
            return;
        }

        event.setYield(0.0F);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event)
    {
        if (!ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            event.setCancelled(true);
            return;
        }

        event.setYield(0.0F);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExplosionPrime(ExplosionPrimeEvent event)
    {
        if (!ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            event.setCancelled(true);
            return;
        }

        event.setRadius(ConfigEntry.EXPLOSIVE_RADIUS.getDouble().floatValue());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityCombust(EntityCombustEvent event)
    {
        if (!ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event)
    {
        if (EntityWiper.isAutoWipeEnabled())
        {
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event)
    {
        if (ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            Projectile entity = event.getEntity();
            if (event.getEntityType() == EntityType.ARROW)
            {
                entity.getWorld().createExplosion(entity.getLocation(), 2F);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event)
    {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if ((cause == EntityDamageEvent.DamageCause.MAGIC
                || cause == EntityDamageEvent.DamageCause.WITHER
                || cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.CUSTOM)
                && event.getEntity() instanceof Player player
                && (player.getGameMode() == GameMode.CREATIVE || player.isInvulnerable()))
        {
            event.setCancelled(true);
            return;
        }

        switch (event.getCause())
        {
            case LAVA:
            {
                if (!ConfigEntry.ALLOW_LAVA_DAMAGE.getBoolean())
                {
                    event.setCancelled(true);
                    return;
                }
            }
            default: break;
        }

        if (ConfigEntry.ENABLE_PET_PROTECT.getBoolean())
        {
            Entity entity = event.getEntity();
            if (entity instanceof Tameable)
            {
                if (((Tameable) entity).isTamed())
                {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityRegainHealth(EntityRegainHealthEvent event)
    {
        double amount = event.getAmount();
        if ((amount < 0.0D || !Double.isFinite(amount))
                && event.getEntity() instanceof Player player
                && (player.getGameMode() == GameMode.CREATIVE || player.isInvulnerable()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (!EntityWiper.isAutoWipeEnabled())
        {
            return;
        }

        if (event.getPlayer().getWorld().getEntities().size() > 750)
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onLeavesDecay(LeavesDecayEvent event)
    {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerSpawn(SpawnerSpawnEvent event)
    {
        if (ConfigEntry.DISABLE_SPAWNERS.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrialSpawnerSpawn(TrialSpawnerSpawnEvent event)
    {
        if (ConfigEntry.DISABLE_SPAWNERS.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPortalCreate(PortalCreateEvent event)
    {
        if (ConfigEntry.DISABLE_PORTAL_CREATE.getBoolean())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event)
    {
        if (ConfigEntry.DISABLE_PISTONS.getBoolean() || redstoneBlocked())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event)
    {
        if (ConfigEntry.DISABLE_PISTONS.getBoolean() || redstoneBlocked())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntitySpawn(EntitySpawnEvent event)
    {
        if (!ConfigEntry.DISABLE_ENTITY_SPAM.getBoolean())
        {
            return;
        }

        final Entity entity = event.getEntity();
        final boolean spammy = entity instanceof Item
                || entity instanceof ArmorStand
                || entity instanceof Hanging
                || entity instanceof Minecart
                || entity instanceof Boat
                || entity instanceof FallingBlock;
        if (!spammy)
        {
            return;
        }

        final int max = ConfigEntry.DISABLE_ENTITY_SPAM_MAX.getInteger();
        if (max > 0 && event.getLocation().getWorld().getEntities().size() > max)
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockRedstone(BlockRedstoneEvent event)
    {
        if (!ConfigEntry.ALLOW_REDSTONE.getBoolean())
        {
            event.setNewCurrent(0);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPreDispense(BlockPreDispenseEvent event)
    {
        if (redstoneBlocked())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockDispense(BlockDispenseEvent event)
    {
        if (redstoneBlocked())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryMoveItem(InventoryMoveItemEvent event)
    {
        if (redstoneBlocked())
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryPickupItem(InventoryPickupItemEvent event)
    {
        if (redstoneBlocked())
        {
            event.setCancelled(true);
        }
    }

    private static boolean redstoneBlocked()
    {
        return !ConfigEntry.ALLOW_REDSTONE.getBoolean();
    }
}