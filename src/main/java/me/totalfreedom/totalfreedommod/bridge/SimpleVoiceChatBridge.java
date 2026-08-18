package me.totalfreedom.totalfreedommod.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BiPredicate;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.util.FLog;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Optional lifecycle bridge for Simple Voice Chat. This class deliberately has
 * no Voice Chat API types in its signature, so TFM can load without the voice
 * chat plugin installed.
 */
public class SimpleVoiceChatBridge extends FreedomService
{
    private static final String VOICECHAT_PLUGIN_NAME = "voicechat";
    private static final String VOICECHAT_SERVICE_CLASS =
            "de.maxhenkel.voicechat.api.BukkitVoicechatService";
    private static final String VOICECHAT_PLUGIN_CLASS =
            "de.maxhenkel.voicechat.api.VoicechatPlugin";
    private static final String ADAPTER_CLASS =
            "me.totalfreedom.totalfreedommod.bridge.SimpleVoiceChatPluginAdapter";

    private Object adapter;
    private Object registeredVoicechatService;
    private boolean running;

    public SimpleVoiceChatBridge(final TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        running = true;
        activateVoiceChatIntegration();
    }

    @Override
    protected void onStop()
    {
        running = false;
        deactivateAdapter();
    }

    @EventHandler
    public void activateAfterVoiceChatEnable(final PluginEnableEvent event)
    {
        if (!VOICECHAT_PLUGIN_NAME.equals(event.getPlugin().getName()))
            return;

        activateVoiceChatIntegration();
    }

    @EventHandler
    public void deactivateAfterVoiceChatDisable(final PluginDisableEvent event)
    {
        if (!VOICECHAT_PLUGIN_NAME.equals(event.getPlugin().getName()))
            return;

        deactivateAdapter();
        registeredVoicechatService = null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void activateVoiceChatIntegration()
    {
        if (!running)
            return;

        final Plugin voicechat = server.getPluginManager().getPlugin(VOICECHAT_PLUGIN_NAME);
        if (voicechat == null || !voicechat.isEnabled())
            return;

        try
        {
            final ClassLoader voicechatClassLoader = voicechat.getClass().getClassLoader();
            final Class<?> serviceClass = Class.forName(
                    VOICECHAT_SERVICE_CLASS,
                    false,
                    voicechatClassLoader);
            final Object voicechatService = server.getServicesManager().load((Class) serviceClass);
            if (voicechatService == null)
                return;

            final Object activeAdapter = getOrCreateAdapter();
            activateAdapter(activeAdapter);
            if (voicechatService == registeredVoicechatService)
                return;

            final Class<?> voicechatPluginClass = Class.forName(
                    VOICECHAT_PLUGIN_CLASS,
                    false,
                    voicechatClassLoader);
            final Method registerPlugin = serviceClass.getMethod(
                    "registerPlugin",
                    voicechatPluginClass);
            registerPlugin.invoke(voicechatService, activeAdapter);
            registeredVoicechatService = voicechatService;
            FLog.info("Simple Voice Chat player-block filtering registered.");
        }
        catch (ReflectiveOperationException | LinkageError ex)
        {
            FLog.warning("Simple Voice Chat player-block filtering is unavailable: " + ex.getMessage());
        }
    }

    private Object getOrCreateAdapter() throws ReflectiveOperationException
    {
        if (adapter != null)
            return adapter;

        final Class<?> adapterClass = Class.forName(
                ADAPTER_CLASS,
                true,
                plugin.getClass().getClassLoader());
        final Constructor<?> constructor = adapterClass.getConstructor(BiPredicate.class);
        adapter = constructor.newInstance((BiPredicate<UUID, UUID>) plugin.pbe::isBlocked);
        return adapter;
    }

    private void activateAdapter(final Object activeAdapter) throws ReflectiveOperationException
    {
        final Method activate = activeAdapter.getClass().getMethod("activate", BiPredicate.class);
        activate.invoke(activeAdapter, (BiPredicate<UUID, UUID>) plugin.pbe::isBlocked);
    }

    private void deactivateAdapter()
    {
        if (adapter == null)
            return;

        try
        {
            adapter.getClass().getMethod("deactivate").invoke(adapter);
        }
        catch (ReflectiveOperationException ex)
        {
            FLog.warning("Could not deactivate Simple Voice Chat player-block filtering: " + ex.getMessage());
        }
    }
}
