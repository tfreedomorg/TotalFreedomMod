package me.totalfreedom.totalfreedommod;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import me.totalfreedom.totalfreedommod.player.FPlayer;

public class Fuckoff extends FreedomService
{

    private final Set<Player> fuckoffPlayers = new HashSet<>();

    public Fuckoff(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        fuckoffPlayers.clear();
    }

    @Override
    protected void onStop()
    {
        fuckoffPlayers.clear();
    }

    public void enable(Player player, double radius)
    {
        plugin.pl.getPlayer(player).setFuckoff(radius);
        fuckoffPlayers.add(player);
    }

    public void disable(Player player)
    {
        plugin.pl.getPlayer(player).disableFuckoff();
        fuckoffPlayers.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        fuckoffPlayers.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (fuckoffPlayers.isEmpty() || !event.hasChangedPosition())
        {
            return;
        }

        final Player fuckoffPlayer = event.getPlayer();
        if (plugin.al.isAdmin(fuckoffPlayer))
        {
            return;
        }

        for (Player onlinePlayer : fuckoffPlayers)
        {
            if (fuckoffPlayer.equals(onlinePlayer))
            {
                continue;
            }

            final FPlayer fPlayer = plugin.pl.getPlayer(onlinePlayer);
            double fuckoffRange = fPlayer.getFuckoffRadius();
            Location opLocation = onlinePlayer.getLocation();
            Location foLocation = fuckoffPlayer.getLocation();

            double distanceSquared;
            try
            {
                distanceSquared = opLocation.distanceSquared(foLocation);
            }
            catch (IllegalArgumentException ex)
            {
                continue;
            }

            if (distanceSquared < (fuckoffRange * fuckoffRange))
            {
                final Vector away = foLocation.toVector().subtract(opLocation.toVector());

                if (away.lengthSquared() == 0.0)
                {
                    continue;
                }

                final Location destination = opLocation.clone().add(away.normalize().multiply(fuckoffRange * 1.1));
                destination.setYaw(foLocation.getYaw());
                destination.setPitch(foLocation.getPitch());
                event.setTo(destination);
                break;
            }
        }
    }

}
