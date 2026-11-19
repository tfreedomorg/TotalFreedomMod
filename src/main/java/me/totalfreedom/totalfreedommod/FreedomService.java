package me.totalfreedom.totalfreedommod;

import org.bukkit.event.Listener;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.framework.AbstractService;

public abstract class FreedomService extends AbstractService implements Listener
{

    public FreedomService(FreedomAPI plugin)
    {
        super(plugin);
    }

}
