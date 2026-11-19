package me.totalfreedom.totalfreedommod.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.bukkit.event.Listener;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.framework.AbstractService;
import me.totalfreedom.api.framework.IServiceManager;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Owns registration, lookup, and lifecycle for a set of services.
 * Services are registered in dependency order, started in that order, and stopped in reverse.
 */
public class ServiceManager implements IServiceManager
{

    private final FreedomAPI plugin;
    private final List<AbstractService> services = new ArrayList<>();

    public ServiceManager(FreedomAPI plugin)
    {
        this.plugin = plugin;
    }

    /**
     * Registers a service by invoking the given factory and adding the result to the service list.
     * Auto-registers the service as a listener if it implements one.
     *
     * @param serviceClass The service class being registered
     * @param factory Produces the service instance from the owning plugin
     * @param <S> The service type
     * @return The instantiated service
     */
    public <S extends AbstractService> S register(Class<S> serviceClass, Function<FreedomAPI, S> factory)
    {
        S service = factory.apply(plugin);
        services.add(service);

        if (service instanceof Listener listener)
        {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }

        return service;
    }

    /**
     * Starts all registered services by calling their onStart() methods, in registration order.
     */
    public void start()
    {
        for (AbstractService service : services)
        {
            try
            {
                service.onStart();
            }
            catch (Exception ex)
            {
                FLog.error("Failed to start service {}", service.getClass().getName(), ex);
            }
        }
    }

    /**
     * Stops all registered services by calling their onStop() methods, in reverse registration order.
     */
    public void stop()
    {
        for (int i = services.size() - 1; i >= 0; i--)
        {
            AbstractService service = services.get(i);
            try
            {
                service.onStop();
            }
            catch (Exception ex)
            {
                FLog.error("Failed to stop service {}", service.getClass().getName(), ex);
            }
        }
    }

    /**
     * @return An immutable snapshot of all registered services, in registration order.
     */
    public List<AbstractService> getServices()
    {
        return List.copyOf(services);
    }

    /**
     * Looks up a registered service by its class.
     *
     * @param serviceClass The service class to find
     * @param <S> The service type
     * @return The service instance, or empty if none is registered
     */
    public <S extends AbstractService> Optional<S> get(Class<S> serviceClass)
    {
        return services.stream()
                       .filter(serviceClass::isInstance)
                       .map(serviceClass::cast)
                       .findFirst();
    }

    /**
     * Looks up a registered service by its class, throwing if it is not present.
     * Use this for dependencies that must exist for the caller to function.
     *
     * @param serviceClass The service class to find
     * @param <S> The service type
     * @return The service instance
     * @throws IllegalStateException if the service is not registered
     */
    public <S extends AbstractService> S require(Class<S> serviceClass)
    {
        return get(serviceClass).orElseThrow(() ->
                new IllegalStateException("Service not registered: " + serviceClass.getName()));
    }
}
