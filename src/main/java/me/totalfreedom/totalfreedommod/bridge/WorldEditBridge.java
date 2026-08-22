package me.totalfreedom.totalfreedommod.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

public class WorldEditBridge extends FreedomService
{

    private WorldEditHook hook = null;
    private Plugin worldedit = null;

    public WorldEditBridge(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        if (!ConfigEntry.WORLDEDIT_ENABLED.getBoolean())
        {
            FLog.info("TFM WorldEdit integration disabled via config (worldedit.enabled=false).");
            return;
        }

        // Defer one tick so other plugins finish enabling first; if WorldEdit
        // still isn't present after that we exit silently.
        server.getScheduler().runTaskLater(plugin, this::attachHook, 20L);
    }

    private void attachHook()
    {
        if (resolveWorldEditProvider() == null)
        {
            return;
        }
        try
        {
            hook = new WorldEditHook(plugin);
            hook.register();
        }
        catch (Throwable t)
        {
            FLog.warning("Failed to attach WorldEdit hook: " + t.getMessage());
            FLog.warning(t);
            hook = null;
        }
    }

    /**
     * Returns whichever WorldEdit-compatible plugin is loaded, or null if none.
     */
    private Plugin resolveWorldEditProvider()
    {
        Plugin we = server.getPluginManager().getPlugin("WorldEdit");
        if (we != null && we.isEnabled())
        {
            return we;
        }
        we = server.getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (we != null && we.isEnabled())
        {
            return we;
        }
        return null;
    }

    @Override
    protected void onStop()
    {
        if (hook != null)
        {
            try
            {
                hook.unregister();
            }
            catch (Throwable ignored)
            {
            }
            hook = null;
        }
    }

    public void undo(Player player, int count)
    {
        if (hook != null)
        {
            hook.undo(player, count);
        }
    }

    public int cancel(Player player)
    {
        if (hook != null)
        {
            return hook.cancel(player);
        }
        return 0;
    }

    public void refreshBypassNegation(Player player)
    {
        if (hook != null)
        {
            hook.refreshBypassNegation(player);
        }
    }

    public void setLimit(Player player, int limit)
    {
        if (hook != null)
        {
            hook.setPlayerLimit(player.getUniqueId(), limit);
        }
        try
        {
            final Object session = getPlayerSession(player);
            if (session != null)
            {
                java.lang.reflect.Method setLimitMethod = session.getClass().getMethod("setBlockChangeLimit", int.class);
                setLimitMethod.invoke(session, limit);
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
    }

    private Plugin getWorldEditPlugin()
    {
        if (worldedit == null)
        {
            try
            {
                worldedit = resolveWorldEditProvider();
            }
            catch (Exception ex)
            {
                FLog.severe(ex);
            }
        }
        return worldedit;
    }

    private Object getPlayerSession(Player player)
    {
        final Plugin wep = getWorldEditPlugin();
        if (wep == null)
        {
            return null;
        }
        try
        {
            java.lang.reflect.Method getSessionMethod = wep.getClass().getMethod("getSession", Player.class);
            return getSessionMethod.invoke(wep, player);
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
            return null;
        }
    }

    public Region getSelection(Player player)
    {
        final Plugin wep = getWorldEditPlugin();
        if (wep == null)
            return null;
        // `session` is a LocalSession.
        final Object session = getPlayerSession(player);
        if (session == null)
            return null;

        Method getSelectionMethod = null;
        try
        {
            getSelectionMethod = session.getClass().getMethod("getSelection");
        }
        catch (NoSuchMethodException ex)
        {
            FLog.severe(ex);
            return null;
        }

        // `selection` is a Region.
        Object selection = null;
        try
        {
            selection = getSelectionMethod.invoke(session);
        }
        catch (InvocationTargetException ex)
        {
            // Do not send diagnostics if the region just doesn't exist
            if (ex.getCause() != null &&
                ex.getCause().getClass().getSimpleName().equals("IncompleteRegionException"))
                return null;
            FLog.severe(ex);
            return null;
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
            return null;
        }

        // `minPoint` and `maxPoint` have type BlockVector3.
        Object minPoint = null;
        Object maxPoint = null;
        // `world` has type World.
        Object world = null;
        try
        {
            // minPoint = selection.getMinimumPoint()
            // maxPoint = selection.getMaximumPoint()
            // world = selection.getWorld()

            final Method getMinimumPointMethod = selection.getClass().getMethod("getMinimumPoint");
            final Method getMaximumPointMethod = selection.getClass().getMethod("getMaximumPoint");
            final Method getWorldMethod = selection.getClass().getMethod("getWorld");
            minPoint = getMinimumPointMethod.invoke(selection);
            maxPoint = getMaximumPointMethod.invoke(selection);
            world = getWorldMethod.invoke(selection);
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
            return null;
        }

        Location min = null;
        Location max = null;
        try
        {
            // WE:
            //  bukkitWorld = world.getWorld()
            // FAWE:
            //  bukkitWorld = world.getParent().getWorld()

            final Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            final Class<?> blockVector3Clazz = Class.forName("com.sk89q.worldedit.math.BlockVector3");

            World bukkitWorld;
            try
            {
                final Method getWorldMethod = world.getClass().getMethod("getWorld");
                bukkitWorld = (World) getWorldMethod.invoke(world);
            }
            // Assuming that we're using FAWE at this point
            catch (NoSuchMethodException ex)
            {
                final Method getParentMethod = world.getClass().getMethod("getParent");
                final Object parentWorld = getParentMethod.invoke(world);
                final Method getWorldMethod = parentWorld.getClass().getMethod("getWorld");
                bukkitWorld = (World) getWorldMethod.invoke(parentWorld);
            }

            // min = BukkitAdapter.adapt(bukkitWorld, minPoint)
            // max = BukkitAdapter.adapt(bukkitWorld, maxPoint)
            
            final Method adaptLocationMethod = adapter.getMethod("adapt", World.class, blockVector3Clazz);
            min = (Location) adaptLocationMethod.invoke(null, bukkitWorld, minPoint);
            max = (Location) adaptLocationMethod.invoke(null, bukkitWorld, maxPoint);
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
            return null;
        }

        return new Region(min.getWorld(), min, max);
    }

    
    public static record Region(World world, Location min, Location max)
    {
    }

    public boolean isWorldEditEnabled()
    {
        try
        {
            final Plugin worldedit = getWorldEditPlugin();
            if (worldedit != null)
            {
                return worldedit.isEnabled();
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return false;
    }
}
