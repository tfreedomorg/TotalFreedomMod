package me.totalfreedom.totalfreedommod.framework;

import org.bukkit.Server;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;

/**
 * Base class for plugin components.
 * Provides access to the plugin and server instances.
 */
public class PluginComponent<T extends TotalFreedomMod>
{

    protected final T plugin;
    protected final Server server;

    public PluginComponent(T plugin)
    {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }
}

