package me.totalfreedom.totalfreedommod;

import org.bukkit.event.Listener;

import me.totalfreedom.totalfreedommod.framework.AbstractService;

public abstract class FreedomService extends AbstractService<TotalFreedomMod> implements Listener
{

    public FreedomService(TotalFreedomMod plugin)
    {
        super(plugin);
    }

}
