package me.totalfreedom.totalfreedommod.bridge;

import java.lang.reflect.Method;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Bridge to EaglercraftXBackendRPC, which tells the backend which players joined through the
 * proxy's Eaglercraft (browser) listener. This covers every Eagler client, whichever site
 * served it, because the proxy identifies the connection by its handshake.
 * <p>
 * Reflection keeps TFM loadable where the RPC plugin is absent. TFM loads at STARTUP, before
 * the RPC plugin, so the API is looked up on first use and cached once found.
 */
public class EaglerBridge extends FreedomService
{
    private static final String RPC_PLUGIN_NAME = "EaglercraftXBackendRPC";
    private static final String BUKKIT_API_CLASS = "net.lax1dude.eaglercraft.backend.rpc.api.bukkit.EaglerXBackendRPC";
    private static final String RPC_INTERFACE = "net.lax1dude.eaglercraft.backend.rpc.api.IEaglerXBackendRPC";

    private Optional<EaglerApi> api = Optional.empty();

    public EaglerBridge(final TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        api = Optional.empty();
    }

    @Override
    protected void onStop()
    {
        api = Optional.empty();
    }

    /**
     * Whether {@code player} is on an Eaglercraft client. False when the RPC plugin is missing.
     */
    public boolean isEaglerPlayer(final Player player)
    {
        return resolveApi()
                .map(resolved -> resolved.check(player))
                .orElse(false);
    }

    private Optional<EaglerApi> resolveApi()
    {
        if (api.isEmpty())
            api = lookupApi();

        return api;
    }

    private Optional<EaglerApi> lookupApi()
    {
        final Plugin rpcPlugin = server.getPluginManager().getPlugin(RPC_PLUGIN_NAME);
        if (rpcPlugin == null || !rpcPlugin.isEnabled())
            return Optional.empty();

        try
        {
            final ClassLoader loader = rpcPlugin.getClass().getClassLoader();
            final Object instance = Class.forName(BUKKIT_API_CLASS, true, loader)
                                         .getMethod("instance")
                                         .invoke(null);
            // Generic parameter PlayerObject erases to Object.
            final Method isEaglerPlayer = Class.forName(RPC_INTERFACE, true, loader)
                                               .getMethod("isEaglerPlayer", Object.class);
            return Optional.of(new EaglerApi(instance, isEaglerPlayer));
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            FLog.warning(String.format("Could not hook %s: %s", RPC_PLUGIN_NAME, ex));
            return Optional.empty();
        }
    }

    /**
     * The resolved RPC instance and its {@code isEaglerPlayer} method.
     */
    private record EaglerApi(Object instance, Method method)
    {
        private boolean check(final Player player)
        {
            try
            {
                return Boolean.TRUE.equals(method.invoke(instance, player));
            }
            catch (ReflectiveOperationException | RuntimeException ex)
            {
                return false;
            }
        }
    }
}