package me.totalfreedom.totalfreedommod.bridge;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;

public class EssentialsBridge extends FreedomService
{

    private Plugin essentialsPlugin = null;

    public EssentialsBridge(TotalFreedomMod plugin)
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

    public Plugin getEssentialsPlugin()
    {
        if (essentialsPlugin == null)
        {
            try
            {
                final Plugin essentials = Bukkit.getServer().getPluginManager().getPlugin("Essentials");
                if (essentials != null && essentials.isEnabled())
                {
                    essentialsPlugin = essentials;
                }
            }
            catch (Exception ex)
            {
                FLog.severe(ex);
            }
        }
        return essentialsPlugin;
    }

    private Object getEssentialsUser(String username)
    {
        try
        {
            final Plugin essentials = getEssentialsPlugin();
            if (essentials != null)
            {
                Object userMap = essentials.getClass().getMethod("getUserMap").invoke(essentials);
                if (userMap != null)
                {
                    return userMap.getClass().getMethod("getUser", String.class).invoke(userMap, username);
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return null;
    }

    public long getLastActivity(String username)
    {
        try
        {
            final Object user = getEssentialsUser(username);
            if (user != null)
            {
                java.lang.reflect.Field field = user.getClass().getDeclaredField("lastActivity");
                field.setAccessible(true);
                Object value = field.get(user);
                if (value instanceof Long)
                {
                    return (Long) value;
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return 0L;
    }

    public boolean isAfk(String username)
    {
        try
        {
            final Object user = getEssentialsUser(username);
            if (user != null)
            {
                Object result = user.getClass().getMethod("isAfk").invoke(user);
                if (result instanceof Boolean)
                {
                    return (Boolean) result;
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return false;
    }

    public boolean isEssentialsEnabled()
    {
        try
        {
            final Plugin essentials = getEssentialsPlugin();
            if (essentials != null)
            {
                return essentials.isEnabled();
            }
        }
        catch (Exception ex)
        {
            FLog.severe(ex);
        }
        return false;
    }
}
