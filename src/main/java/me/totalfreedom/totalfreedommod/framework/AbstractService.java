package me.totalfreedom.totalfreedommod.framework;

import org.bukkit.Server;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;

/**
 * Base class for all services.
 * Services are components that have a lifecycle (onStart/onStop).
 */
public abstract class AbstractService<T extends TotalFreedomMod>
{

    protected final T plugin;
    protected final Server server;

    public AbstractService(T plugin)
    {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    /**
     * Called when the service should start.
     * Override this method to initialize the service.
     */
    protected abstract void onStart();

    /**
     * Called when the service should stop.
     * Override this method to clean up the service.
     */
    protected abstract void onStop();
}

