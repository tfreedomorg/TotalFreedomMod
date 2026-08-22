package me.totalfreedom.totalfreedommod;


import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

public class MovementValidator extends FreedomService
{

    public static final int MAX_XZ_COORD = 30000000;

    public MovementValidator(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        applyWorldBorders();
    }

    @Override
    protected void onStop()
    {
    }

    private long borderRadius()
    {
        return ConfigEntry.WORLD_BORDER.getInteger();
    }

    private long boundsLimit()
    {
        final long v = borderRadius();
        return v > 0 ? v : MAX_XZ_COORD;
    }

    private boolean outOfBounds(Location loc)
    {
        if (loc == null)
        {
            return false;
        }
        final long limit = boundsLimit();
        return Math.abs(loc.getX()) >= limit
                || Math.abs(loc.getZ()) >= limit
                || Math.abs(loc.getY()) >= limit;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event)
    {
        if (outOfBounds(event.getTo()))
        {
            event.setCancelled(true); // illegal position, cancel it
        }
    }

    /**
     * {@link org.bukkit.event.player.PlayerLoginEvent} is deprecated as of 1.21.7 and {@link org.spigotmc.event.player.PlayerSpawnLocationEvent} as of 1.21.9, 
     * and the only suitable replacement I've found is {@link PlayerClientLoadedWorldEvent} in Paper 1.21.9 and later.
     * 
     * @param event The player join event.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerSpawnLocation(PlayerClientLoadedWorldEvent event)
    {
        final Location loc = event.getPlayer().getLocation();
        if (!outOfBounds(loc))
        {
            return;
        }
        final World world = loc != null ? loc.getWorld() : event.getPlayer().getWorld();
        event.getPlayer().setRespawnLocation(world.getSpawnLocation(), true);
        event.getPlayer().teleport(world.getSpawnLocation()); // It doesn't actually teleport the player, so we have to do it manually.
        FLog.warning("[MovementValidator] " + event.getPlayer().getName()
                + " joined out of bounds; relocated to " + world.getName() + " spawn.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event)
    {
        applyWorldBorder(event.getWorld());
    }

    private void applyWorldBorders()
    {
        for (World world : server.getWorlds())
        {
            applyWorldBorder(world);
        }
    }

    private void applyWorldBorder(World world)
    {
        final long radius = borderRadius();
        if (radius <= 0 || world == null)
        {
            return;
        }
        try
        {
            final WorldBorder border = world.getWorldBorder();
            border.setCenter(0.0, 0.0);
            border.setSize(radius * 2.0); // setSize takes the full diameter
        }
        catch (Throwable t)
        {
            FLog.warning("[MovementValidator] Failed to apply world border to "
                    + world.getName() + ": " + t.getMessage());
        }
    }

}
