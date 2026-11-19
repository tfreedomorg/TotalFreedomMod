package me.totalfreedom.totalfreedommod.httpd;

import java.lang.reflect.Constructor;
import java.util.concurrent.Callable;

import org.bukkit.Bukkit;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.PluginProvider;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.httpd.module.HTTPDModule;
import me.totalfreedom.totalfreedommod.util.FLog;

public abstract class ModuleExecutable
{

    private final boolean async;

    public ModuleExecutable(boolean async)
    {
        this.async = async;
    }

    public boolean isAsync()
    {
        return async;
    }

    public NanoHTTPD.Response execute(final NanoHTTPD.HTTPSession session)
    {
        try
        {
            if (async)
            {
                return getResponse(session);
            }

            // Sync to server thread
            return Bukkit.getScheduler().callSyncMethod(PluginProvider.get(), new Callable<NanoHTTPD.Response>()
            {
                @Override
                public NanoHTTPD.Response call() throws Exception
                {
                    return getResponse(session);
                }
            }).get();

        }
        catch (Exception ex)
        {
            FLog.error("Failed to execute", ex);
        }
        return null;
    }

    public abstract NanoHTTPD.Response getResponse(NanoHTTPD.HTTPSession session);

    public static ModuleExecutable forClass(final FreedomAPI plugin, Class<? extends HTTPDModule> clazz, boolean async)
    {
        final Constructor<? extends HTTPDModule> cons;
        try
        {
            cons = clazz.getConstructor(TotalFreedomMod.class, NanoHTTPD.HTTPSession.class);
        }
        catch (Exception ex)
        {
            throw new IllegalArgumentException("Improperly defined module!");
        }

        return new ModuleExecutable(async)
        {
            @Override
            public NanoHTTPD.Response getResponse(NanoHTTPD.HTTPSession session)
            {
                try
                {
                    return cons.newInstance(plugin, session).getResponse();
                }
                catch (Exception ex)
                {
                    FLog.error("Failed to create response", ex);
                    return null;
                }
            }
        };
    }

}
