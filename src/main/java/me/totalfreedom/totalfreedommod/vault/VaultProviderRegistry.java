package me.totalfreedom.totalfreedommod.vault;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;

public final class VaultProviderRegistry
{

    private static final String PERMISSION_API = "net.milkbowl.vault.permission.Permission";
    private static final String CHAT_API = "net.milkbowl.vault.chat.Chat";
    private static final String PERMISSION_SERVICE = "me.totalfreedom.totalfreedommod.vault.PermissionService";
    private static final String CHAT_SERVICE = "me.totalfreedom.totalfreedommod.vault.ChatService";

    private final TotalFreedomMod plugin;
    private Object chatProvider;
    private Object permissionProvider;

    private VaultProviderRegistry(TotalFreedomMod plugin)
    {
        this.plugin = plugin;
    }

    public static boolean isVaultPresent(TotalFreedomMod plugin)
    {
        Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled())
        {
            return false;
        }
        try
        {
            Class.forName(PERMISSION_API);
            Class.forName(CHAT_API);
            return true;
        }
        catch (ClassNotFoundException ignored)
        {
            return false;
        }
    }

    public static VaultProviderRegistry create(TotalFreedomMod plugin)
    {
        return isVaultPresent(plugin) ? new VaultProviderRegistry(plugin) : null;
    }

    public boolean register(boolean shouldOverride, boolean essentialsChatInstalled)
    {
        if (!isVaultPresent(plugin))
        {
            return false;
        }

        try
        {
            Class<?> chatApi = Class.forName(CHAT_API);
            @SuppressWarnings("rawtypes")
            RegisteredServiceProvider existingProvider =
                    plugin.getServer().getServicesManager().getRegistration((Class) chatApi);

            if (existingProvider != null && !shouldOverride && !essentialsChatInstalled)
            {
                if (chatProvider == null)
                {
                    FLog.info("Using a registered chat provider (" + providerName(existingProvider.getProvider())
                            + "). To avoid this, set 'override_existing' to 'true' in config.yml.");
                }
                return false;
            }

            if (!essentialsChatInstalled && existingProvider != null)
            {
                FLog.info("Overriding existing chat provider (" + providerName(existingProvider.getProvider()) + ".");
            }

            Class<?> permissionServiceClass = Class.forName(PERMISSION_SERVICE);
            permissionProvider = permissionServiceClass.getConstructor(TotalFreedomMod.class).newInstance(plugin);

            Class<?> permissionApi = Class.forName(PERMISSION_API);
            @SuppressWarnings({"unchecked", "rawtypes"})
            ServicePriority priority = ServicePriority.High;
            plugin.getServer().getServicesManager().register((Class) permissionApi, permissionProvider, plugin, priority);

            Class<?> chatServiceClass = Class.forName(CHAT_SERVICE);
            chatProvider = chatServiceClass.getConstructor(TotalFreedomMod.class, permissionApi)
                    .newInstance(plugin, permissionProvider);

            plugin.getServer().getServicesManager().register((Class) chatApi, chatProvider, plugin, priority);

            triggerEssentialsPermissionRecheck();
            return true;
        }
        catch (Throwable ex)
        {
            FLog.warning("Failed to register Vault chat provider: " + ex.getMessage());
            FLog.warning(ex);
            unregister();
            return false;
        }
    }

    public void unregister()
    {
        if (chatProvider != null)
        {
            try
            {
                Class<?> chatApi = Class.forName(CHAT_API);
                plugin.getServer().getServicesManager().unregister((Class) chatApi, chatProvider);
            }
            catch (Throwable ex)
            {
                FLog.warning("Failed to unregister Vault chat provider: " + ex.getMessage());
            }
            chatProvider = null;
        }
        if (permissionProvider != null)
        {
            try
            {
                Class<?> permissionApi = Class.forName(PERMISSION_API);
                plugin.getServer().getServicesManager().unregister((Class) permissionApi, permissionProvider);
            }
            catch (Throwable ex)
            {
                FLog.warning("Failed to unregister Vault permission provider: " + ex.getMessage());
            }
            permissionProvider = null;
        }
    }

    private static String providerName(Object provider)
    {
        if (provider == null)
        {
            return "?";
        }
        try
        {
            Object result = provider.getClass().getMethod("getName").invoke(provider);
            return result instanceof String string ? string : provider.getClass().getSimpleName();
        }
        catch (Throwable ignored)
        {
            return provider.getClass().getSimpleName();
        }
    }

    private void triggerEssentialsPermissionRecheck()
    {
        Plugin essentials = plugin.getServer().getPluginManager().getPlugin("Essentials");
        if (essentials == null || !essentials.isEnabled())
        {
            return;
        }

        try
        {
            Object permissionsHandler = essentials.getClass().getMethod("getPermissionsHandler").invoke(essentials);
            if (permissionsHandler != null)
            {
                permissionsHandler.getClass().getMethod("checkPermissions").invoke(permissionsHandler);
            }
        }
        catch (Throwable ex)
        {
            FLog.info("Could not trigger Essentials to re-check permissions. Server restart may be required for prefixes to work.");
        }
    }
}
