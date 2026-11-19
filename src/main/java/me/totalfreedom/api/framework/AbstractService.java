package me.totalfreedom.api.framework;

import org.bukkit.Server;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.service.Service;

/**
 * Base class for all services.
 * Services are components that have a lifecycle (onStart/onStop).
 */
public abstract class AbstractService implements Service
{

    protected final FreedomAPI plugin;
    protected final Server server;

    public AbstractService(FreedomAPI plugin)
    {
        this.plugin = plugin;
        this.server = plugin.getServer();
    }

    /**
     * Called when the service should start.
     * Override this method to initialize the service.
     */
    @Override
    public abstract void onStart();

    /**
     * Called when the service should stop.
     * Override this method to clean up the service.
     */
    @Override
    public abstract void onStop();
}
