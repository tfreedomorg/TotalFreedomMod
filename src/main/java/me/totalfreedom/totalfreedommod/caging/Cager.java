package me.totalfreedom.totalfreedommod.caging;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class Cager extends FreedomService
{

    public Cager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
    }

    @Override
    protected void onStop()
    {
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event)
    {
        if (isCaged(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (isCaged(event.getPlayer()))
        {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event)
    {
        FPlayer player = plugin.pl.getPlayer(event.getPlayer());
        CageData cage = player.getCageData();

        if (!cage.isCaged())
        {
            return;
        }

        Location playerLoc = event.getTo().clone().add(0, 1, 0);
        Location cageLoc = cage.getLocation();

        final boolean outOfCage;
        if (!playerLoc.getWorld().equals(cageLoc.getWorld()))
        {
            outOfCage = true;
        }
        else
        {
            outOfCage = playerLoc.distanceSquared(cageLoc) > (2.5D * 2.5D);
        }

        if (outOfCage)
        {
            cage.teleportToCenter();
            FUtil.playerMsg(player.getPlayer(), "You may not leave your cage.", NamedTextColor.RED);
            cage.regenerate();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        FPlayer player = plugin.pl.getPlayer(event.getPlayer());
        CageData cage = player.getCageData();

        if (cage.isCaged())
        {
            cage.playerQuit();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event)
    {
        FPlayer player = plugin.pl.getPlayer(event.getPlayer());
        CageData cage = player.getCageData();

        if (cage.isCaged())
        {
            cage.playerQuit();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        FPlayer player = plugin.pl.getPlayer(event.getPlayer());
        CageData cage = player.getCageData();

        if (cage.isCaged())
        {
            cage.playerJoin();
        }
    }

    private boolean isCaged(Player player)
    {
        if (player == null || plugin.al.isAdmin(player))
        {
            return false;
        }

        return plugin.pl.getPlayer(player).getCageData().isCaged();
    }

}
