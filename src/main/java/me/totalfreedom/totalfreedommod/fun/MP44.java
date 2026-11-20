package me.totalfreedom.totalfreedommod.fun;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import me.totalfreedom.totalfreedommod.FreedomService;
import org.bukkit.inventory.ItemStack;

public class MP44 extends FreedomService
{

    public MP44(FreedomAPI plugin)
    {
        super(plugin);
    }

    @Override
    public void onStart()
    {
    }

    @Override
    public void onStop()
    {
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        plugin.players().getData(event.getPlayer()).disarmMP44();
    }


    @EventHandler(ignoreCancelled = true)
    public void handleRightClick(PlayerInteractEvent event)
    {
        if (event.getAction().isLeftClick())
        {
            return;
        }

        final ItemStack itemStack = event.getItem();
        final Player player = event.getPlayer();
        final PlayerData playerData = plugin.players().getData(player);

        if (playerData.isMP44Armed()
                && itemStack != null
                && itemStack.getType().equals(Material.GUNPOWDER))
        {
            event.setCancelled(true);

            if (playerData.toggleMP44Firing())
            {
                playerData.startArrowShooter();
            }
            else
            {
                playerData.stopArrowShooter();
            }
        }
    }
}
