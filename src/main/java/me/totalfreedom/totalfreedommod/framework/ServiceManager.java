package me.totalfreedom.totalfreedommod.framework;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.event.Listener;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Manages the lifecycle of services.
 * Services are registered and then started/stopped in order.
 */
public class ServiceManager<T extends TotalFreedomMod>
{

    private final T plugin;
    private final List<AbstractService<T>> services;

    public ServiceManager(T plugin)
    {
        this.plugin = plugin;
        this.services = new ArrayList<>();
    }

    /**
     * Registers a service by instantiating it and adding it to the service list.
     * The service must have a constructor that takes a single parameter of type T (the plugin).
     *
     * @param serviceClass The service class to register
     * @param <S> The service type
     * @return The instantiated service
     */
    @SuppressWarnings("unchecked")
    public <S extends AbstractService<T>> S registerService(Class<S> serviceClass)
    {
        try
        {
            // Find constructor that takes plugin as parameter
            java.lang.reflect.Constructor<S> constructor = serviceClass.getConstructor(plugin.getClass());
            S service = constructor.newInstance(plugin);
            services.add(service);

            // Auto-register as listener if it implements Listener
            if (service instanceof Listener)
            {
                plugin.getServer().getPluginManager().registerEvents((Listener) service, plugin);
            }

            return service;
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to register service: " + serviceClass.getName());
            FLog.severe(ex);
            throw new RuntimeException("Service registration failed", ex);
        }
    }

    /**
     * Starts all registered services by calling their onStart() methods.
     */
    public void start()
    {
        for (AbstractService<T> service : services)
        {
            try
            {
                service.onStart();
            }
            catch (Exception ex)
            {
                FLog.severe("Failed to start service: " + service.getClass().getName());
                FLog.severe(ex);
            }
        }
    }

    /**
     * Stops all registered services by calling their onStop() methods.
     * Services are stopped in reverse order of registration.
     */
    public void stop()
    {
        // Stop in reverse order
        for (int i = services.size() - 1; i >= 0; i--)
        {
            AbstractService<T> service = services.get(i);
            try
            {
                service.onStop();
            }
            catch (Exception ex)
            {
                FLog.severe("Failed to stop service: " + service.getClass().getName());
                FLog.severe(ex);
            }
        }
    }

    /**
     * Gets all registered services.
     *
     * @return List of all services
     */
    public List<AbstractService<T>> getServices()
    {
        return new ArrayList<>(services);
    }

    /**
     * Gets a service by its class type.
     *
     * @param serviceClass The service class to find
     * @param <S> The service type
     * @return The service instance, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <S extends AbstractService<T>> S getService(Class<S> serviceClass)
    {
        for (AbstractService<T> service : services)
        {
            if (serviceClass.isInstance(service))
            {
                return (S) service;
            }
        }
        return null;
    }
}

