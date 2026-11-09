package me.totalfreedom.totalfreedommod;

import me.totalfreedom.api.FreedomAPI;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.PlayerData;

public class AntiNuke extends FreedomService
{

    public AntiNuke(FreedomAPI plugin)
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event)
    {
        if (!ConfigEntry.NUKE_MONITOR_ENABLED.getBoolean() || !plugin.autoEject().canEject(event.getPlayer(), "nuker"))
        {
            return;
        }

        final Player player = event.getPlayer();
        final PlayerData playerData = plugin.players().getData(player);
        if (playerData.incrementAndGetBlockDestroyCount() > ConfigEntry.NUKE_MONITOR_COUNT_BREAK.getInteger())
        {
            event.setCancelled(true);
            playerData.resetBlockDestroyCount();
            plugin.autoEject().eject(player, "nuker", "was breaking blocks too fast", "You are breaking blocks too fast. Nukers are not permitted on this server.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event)
    {
        if (!ConfigEntry.NUKE_MONITOR_ENABLED.getBoolean() || !plugin.autoEject().canEject(event.getPlayer(), "nuker"))
        {
            return;
        }

        final Player player = event.getPlayer();
        final PlayerData playerData = plugin.players().getData(player);
        if (playerData.incrementAndGetBlockPlaceCount() > ConfigEntry.NUKE_MONITOR_COUNT_PLACE.getInteger())
        {
            event.setCancelled(true);
            playerData.resetBlockDestroyCount();
            plugin.autoEject().eject(player, "nuker", "was placing blocks too fast", "You are placing blocks too fast.");
        }
    }
}
