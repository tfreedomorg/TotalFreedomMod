package me.totalfreedom.api.framework;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import me.totalfreedom.api.FreedomAPI;

public interface IServiceManager
{
    /**
     * Registers a service by invoking the given factory and adding the result to the service list.
     * Auto-registers the service as a listener if it implements one.
     *
     * @param serviceClass The service class being registered
     * @param factory Produces the service instance from the owning plugin
     * @param <S> The service type
     * @return The instantiated service
     */
    <S extends AbstractService> S register(Class<S> serviceClass, Function<FreedomAPI, S> factory);

    /**
     * Starts all registered services by calling their onStart() methods, in registration order.
     */
    void start();

    /**
     * Stops all registered services by calling their onStop() methods, in reverse registration order.
     */
    void stop();

    /**
     * @return An immutable snapshot of all registered services, in registration order.
     */
    List<AbstractService> getServices();

    /**
     * Looks up a registered service by its class.
     *
     * @param serviceClass The service class to find
     * @param <S> The service type
     * @return The service instance, or empty if none is registered
     */
    <S extends AbstractService> Optional<S> get(Class<S> serviceClass);

    /**
     * Looks up a registered service by its class, throwing if it is not present.
     * Use this for dependencies that must exist for the caller to function.
     *
     * @param serviceClass The service class to find
     * @param <S> The service type
     * @return The service instance
     * @throws IllegalStateException if the service is not registered
     */
    <S extends AbstractService> S require(Class<S> serviceClass);
}
