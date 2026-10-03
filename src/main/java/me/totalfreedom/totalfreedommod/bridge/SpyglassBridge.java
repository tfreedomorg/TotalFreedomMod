package me.totalfreedom.totalfreedommod.bridge;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;
import org.bukkit.plugin.Plugin;

public class SpyglassBridge extends FreedomService
{
    private static final String ROLLBACK_TIME = "30d";

    public SpyglassBridge(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        if (isEnabled())
        {
            FLog.info("Spyglass integration enabled.");
        }
    }

    @Override
    protected void onStop()
    {
    }

    public boolean isEnabled()
    {
        final Plugin spyglass = server.getPluginManager().getPlugin("Spyglass");
        return spyglass != null && spyglass.isEnabled();
    }

    public boolean rollback(String username)
    {
        return dispatch("rollback", username);
    }

    public boolean restore(String username)
    {
        return dispatch("restore", username);
    }

    private boolean dispatch(String operation, String username)
    {
        if (!isEnabled())
        {
            return false;
        }

        return server.dispatchCommand(server.getConsoleSender(),
                String.format("spyglass %s p:%s t:%s -g", operation, username, ROLLBACK_TIME));
    }
}