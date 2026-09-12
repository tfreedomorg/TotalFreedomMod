package me.totalfreedom.totalfreedommod.bridge;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.*;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.disguise.DisallowedDisguises;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Bridge to LibsDisguises plugin.
 * Supports modern LibsDisguises 11.0.0+ API using reflection for compatibility.
 * Based on TF-LibsDisguises functionality.
 */
public class LibsDisguisesBridge extends FreedomService
{

    private Plugin libsDisguisesPlugin = null;
    private Class<?> disguiseAPI = null;
    private Method isDisguisedMethod = null;
    private Method undisguiseToAllMethod = null;
    private DisallowedDisguises disallowedDisguises = null;
    private BukkitTask retryTask = null;
    private final Listener disguiseListener = new Listener() {};
    private boolean disguiseListenerRegistered = false;

    public LibsDisguisesBridge(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        // Get DisallowedDisguises service
        disallowedDisguises = plugin.services.getService(DisallowedDisguises.class);

        // Initialize API lazily - LibsDisguises might not be loaded yet
        // We'll try to initialize it when first needed
        if (initializeAPI())
        {
            FLog.info("LibsDisguises bridge initialized successfully.");
            registerDisguiseListener();
        }

        // Schedule a delayed retry in case LibsDisguises loads after TFM
        // This handles the case where LibsDisguises is enabled but not fully initialized yet
        retryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () ->
        {
            if (initializeAPI())
            {
                FLog.info("LibsDisguises bridge initialized successfully (delayed initialization).");
                registerDisguiseListener();
            }
        }, 40L);
    }

    private boolean isInitialized()
    {
        return disguiseAPI != null && isDisguisedMethod != null && undisguiseToAllMethod != null;
    }

    /**
     * Initializes the LibsDisguises API connection.
     * Can be called multiple times safely.
     */
    private boolean initializeAPI()
    {
        if (isInitialized())
        {
            return false;
        }

        try
        {
            final Plugin ldPlugin = server.getPluginManager().getPlugin("LibsDisguises");
            if (ldPlugin == null || !ldPlugin.isEnabled())
            {
                // Plugin not available yet, will retry later
                return false;
            }

            libsDisguisesPlugin = ldPlugin;

            // Use the plugin's classloader to load the API class
            // This is more reliable than Class.forName() which uses the default classloader
            ClassLoader pluginClassLoader = ldPlugin.getClass().getClassLoader();
            
            try
            {
                // Try to get DisguiseAPI class using the plugin's classloader
                disguiseAPI = Class.forName("me.libraryaddict.disguise.DisguiseAPI", true, pluginClassLoader);
                
                // LibsDisguises 11.0.13 uses Entity instead of Player
                // Methods: isDisguised(Entity), undisguiseToAll(Entity)
                try
                {
                    Class<?> entityClass = Class.forName("org.bukkit.entity.Entity", true, pluginClassLoader);
                    isDisguisedMethod = disguiseAPI.getMethod("isDisguised", entityClass);
                    undisguiseToAllMethod = disguiseAPI.getMethod("undisguiseToAll", entityClass);
                    return true;
                }
                catch (NoSuchMethodException | ClassNotFoundException ex1)
                {
                    // Fallback: try with Player (which extends Entity, so this should work too)
                    try
                    {
                        isDisguisedMethod = disguiseAPI.getMethod("isDisguised", Player.class);
                        undisguiseToAllMethod = disguiseAPI.getMethod("undisguiseToAll", Player.class);
                        return true;
                    }
                    catch (NoSuchMethodException ex2)
                    {
                        // List all available methods for debugging
                        FLog.warning("LibsDisguises API found but methods not accessible. Available methods:");
                        for (Method method : disguiseAPI.getMethods())
                        {
                            if (method.getName().contains("isDisguised") || method.getName().contains("undisguise"))
                            {
                                StringBuilder params = new StringBuilder();
                                for (Class<?> param : method.getParameterTypes())
                                {
                                    if (params.length() > 0) params.append(", ");
                                    params.append(param.getSimpleName());
                                }
                                FLog.warning("  - " + method.getName() + "(" + params + ")");
                            }
                        }
                        FLog.warning("Expected methods: isDisguised(Entity), undisguiseToAll(Entity)");
                        disguiseAPI = null;
                        return false;
                    }
                }
            }
            catch (ClassNotFoundException ex)
            {
                // Try alternative class name (some versions use different package)
                try
                {
                    disguiseAPI = Class.forName("me.libraryaddict.disguise.api.DisguiseAPI", true, pluginClassLoader);
                    isDisguisedMethod = disguiseAPI.getMethod("isDisguised", Player.class);
                    undisguiseToAllMethod = disguiseAPI.getMethod("undisguiseToAll", Player.class);
                    return true;
                }
                catch (Exception ex2)
                {
                    FLog.warning("LibsDisguises API not found. Tried: me.libraryaddict.disguise.DisguiseAPI and me.libraryaddict.disguise.api.DisguiseAPI");
                    FLog.warning("LibsDisguises plugin is loaded but API class not accessible. Disguise features will be limited.");
                    disguiseAPI = null;
                    return false;
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Error initializing LibsDisguises bridge: " + ex.getMessage());
            FLog.severe(ex);
            return false;
        }
    }

    /**
     * Registers a listener for LibsDisguises's DisguiseEvent so every disguise is judged when it is
     * applied. The event class is loaded from LD's own classloader and the listener registered
     * dynamically, because TFM has no compile-time dependency on LibsDisguises.
     */
    @SuppressWarnings("unchecked")
    private boolean registerDisguiseListener()
    {
        if (disguiseListenerRegistered || libsDisguisesPlugin == null)
        {
            return false;
        }

        try
        {
            final ClassLoader loader = libsDisguisesPlugin.getClass().getClassLoader();
            final Class<? extends Event> eventClass = (Class<? extends Event>)
                Class.forName("me.libraryaddict.disguise.events.DisguiseEvent", true, loader);

            server.getPluginManager().registerEvent(
                eventClass, disguiseListener, EventPriority.HIGHEST,
                (listener, event) -> onDisguiseApplied(event), plugin, true);

            disguiseListenerRegistered = true;
            FLog.info("Apply-time disguise checks registered.");
            return true;
        }
        catch (ClassNotFoundException ex)
        {
            FLog.warning("LibsDisguises is loaded but DisguiseEvent was not found; apply-time disguise "
                + "checks are INACTIVE and only command filtering applies.");
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to register apply-time disguise checks: " + ex.getMessage());
            FLog.severe(ex);
        }
        return false;
    }

    /**
     * Judges a disguise as LibsDisguise applies it, cancelling the event when the resolved
     * disguise is not permitted. Admins are exempt.
     */
    private void onDisguiseApplied(final Event event)
    {
        if (disallowedDisguises == null || !(event instanceof Cancellable cancellable))
        {
            return;
        }

        final Object disguise = invokeNoArg(event, "getDisguise");
        if (disguise == null)
        {
            return;
        }

        final Player player = invokeNoArg(event, "getEntity") instanceof Player p ? p : null;
        if (player != null && plugin.al.isAdmin(player))
        {
            return;
        }

        final Object type = invokeNoArg(disguise, "getType");
        final Object watcher = invokeNoArg(disguise, "getWatcher");
        final Object tablistName = invokeNoArg(watcher, "getTablistName");

        final Optional<String> reason = disallowedDisguises.denyReason(
            type instanceof Enum<?> constant ? constant.name() : null,
            setOptions(watcher),
            tablistName instanceof String name ? name : null);

        if (reason.isEmpty())
        {
            return;
        }

        cancellable.setCancelled(true);

        if (player != null)
        {
            FUtil.playerMsg(player, reason.get(), NamedTextColor.RED);
        }

        FLog.info(String.format("Blocked disguise for %s: %s",
            player != null ? player.getName() : "an entity", reason.get()));
    }

    /**
     * Which of the configured forbidden options are set on this disguise. Each entry is a
     * LibsDisguises watcher getter name; a watcher that does not expose one is simply skipped,
     * so a mob disguise has none of the player-only options.
     */
    private Set<String> setOptions(final Object watcher)
    {
        if (watcher == null)
        {
            return Set.of();
        }

        return disallowedDisguises.getForbiddenOptions()
                                  .stream()
                                  .filter(option -> Boolean.TRUE.equals(invokeNoArg(watcher, option)))
                                  .collect(Collectors.toSet());
    }

    /**
     * Calls a no-argument method by name, yielding null when the target is null or does not expose
     * it. A miss is normal rather than an error: only PlayerWatcher carries the tab list methods,
     * so a mob or misc disguise simply has none.
     */
    private Object invokeNoArg(final Object target, final String method)
    {
        if (target == null)
        {
            return null;
        }

        try
        {
            return target.getClass().getMethod(method).invoke(target);
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    @Override
    protected void onStop()
    {
        if (retryTask != null)
        {
            retryTask.cancel();
            retryTask = null;
        }

        libsDisguisesPlugin = null;
        disguiseAPI = null;
        isDisguisedMethod = null;
        undisguiseToAllMethod = null;
    }

    /**
     * Checks if LibsDisguises plugin is enabled.
     * 
     * @return true if enabled, false otherwise
     */
    public boolean isPluginEnabled()
    {
        // Try to initialize if not already done
        if (libsDisguisesPlugin == null)
        {
            initializeAPI();
        }
        
        return libsDisguisesPlugin != null && libsDisguisesPlugin.isEnabled();
    }

    /**
     * Checks if a player is disguised.
     * 
     * @param player The player to check
     * @return true if disguised, false otherwise, null if plugin not available
     */
    public Boolean isDisguised(Player player)
    {
        // Try to initialize API if not already done
        if (disguiseAPI == null || isDisguisedMethod == null)
        {
            initializeAPI();
        }
        
        if (!isPluginEnabled() || disguiseAPI == null || isDisguisedMethod == null)
        {
            return null;
        }

        try
        {
            // Player extends Entity, so we can pass Player to Entity methods
            Object result = isDisguisedMethod.invoke(null, (Entity) player);
            
            if (result instanceof Boolean)
            {
                return (Boolean) result;
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Error checking if player is disguised: " + ex.getMessage());
            FLog.severe(ex);
        }

        return null;
    }

    /**
     * Undisguises all players (optionally excluding admins).
     * 
     * @param includeAdmins If true, undisguises admins too; if false, skips admins
     */
    public void undisguiseAll(boolean includeAdmins)
    {
        // Try to initialize API if not already done
        if (disguiseAPI == null || undisguiseToAllMethod == null)
        {
            initializeAPI();
        }
        
        if (!isPluginEnabled() || disguiseAPI == null || undisguiseToAllMethod == null)
        {
            return;
        }

        try
        {
            for (Player player : server.getOnlinePlayers())
            {
                Boolean disguised = isDisguised(player);
                if (disguised != null && disguised)
                {
                    if (!includeAdmins && plugin.al.isAdmin(player))
                    {
                        continue;
                    }

                    // Player extends Entity, so we can pass Player to Entity methods
                    undisguiseToAllMethod.invoke(null, (Entity) player);
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Error undisguising players: " + ex.getMessage());
            FLog.severe(ex);
        }
    }

    /**
     * Enables or disables disguises globally.
     * 
     * @param enabled true to enable, false to disable
     */
    public void setDisguisesEnabled(boolean enabled)
    {
        if (disallowedDisguises != null)
        {
            disallowedDisguises.setDisabled(!enabled);
        }
    }

    /**
     * Checks if disguises are enabled globally.
     * 
     * @return true if enabled, false if disabled
     */
    public boolean isDisguisesEnabled()
    {
        if (disallowedDisguises != null)
        {
            return !disallowedDisguises.isDisabled();
        }
        return false;
    }

    /**
     * Checks if a disguise type is allowed.
     * 
     * @param disguiseTypeName The disguise type name
     * @return true if allowed, false if forbidden
     */
    public boolean isDisguiseAllowed(String disguiseTypeName)
    {
        if (disallowedDisguises != null)
        {
            return disallowedDisguises.isAllowed(disguiseTypeName);
        }
        return true; // Default to allowed if service not available
    }

    /**
     * Gets the DisallowedDisguises service.
     * 
     * @return The DisallowedDisguises service, or null if not available
     */
    public DisallowedDisguises getDisallowedDisguises()
    {
        return disallowedDisguises;
    }
}
