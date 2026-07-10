package me.totalfreedom.totalfreedommod.freeze;

import lombok.Getter;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.event.admin.AdminAddEvent;
import me.totalfreedom.totalfreedommod.util.FUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

public class Freezer extends FreedomService
{

    @Getter
    private boolean globalFreeze = false;

    public Freezer(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        globalFreeze = false;
    }

    @Override
    protected void onStop()
    {
    }

    public void setGlobalFreeze(boolean frozen)
    {
        this.globalFreeze = frozen;
    }

    public void purge()
    {
        this.globalFreeze = false;

        for (Player player : server.getOnlinePlayers())
        {
            plugin.pl.getPlayer(player).getFreezeData().setFrozen(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (!event.hasChangedPosition())
        {
            return;
        }

        final Player player = event.getPlayer();

        if (plugin.al.isAdmin(player))
        {
            return;
        }

        final FreezeData fd = plugin.pl.getPlayer(player).getFreezeData();
        if (!fd.isFrozen() && !globalFreeze)
        {
            return;
        }

        FUtil.setFlying(player, true);

        Location loc = fd.getLocation();
        if (loc == null)
        {
            loc = event.getFrom();
        }

        event.setTo(loc);
    }

    @EventHandler
    public void onAdminAdd(AdminAddEvent event)
    {
        final Player player = event.getAffectedAdminAsPlayer();

        if (player != null)
        {
            final FreezeData fd = plugin.pl.getPlayer(player).getFreezeData();

            if (fd.isFrozen())
            {
                fd.setFrozen(false);
                FUtil.playerMsg(player, "You have been unfrozen.");
            }
        }
    }
}
