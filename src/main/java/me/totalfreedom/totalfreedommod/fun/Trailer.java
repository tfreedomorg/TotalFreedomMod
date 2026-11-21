package me.totalfreedom.totalfreedommod.fun;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;

public class Trailer extends FreedomService
{
    private final Material[] woolColors = new Material[] {
            Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL, Material.GREEN_WOOL,
            Material.CYAN_WOOL, Material.LIGHT_BLUE_WOOL, Material.BLUE_WOOL, Material.PURPLE_WOOL,
            Material.MAGENTA_WOOL, Material.PINK_WOOL, Material.WHITE_WOOL, Material.LIGHT_GRAY_WOOL,
            Material.GRAY_WOOL, Material.BLACK_WOOL, Material.BROWN_WOOL
    };
    private final Map<UUID, AtomicInteger> trailPlayers = new HashMap<>(); // player uuid

    public Trailer(TotalFreedomMod plugin)
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event)
    {
        if (trailPlayers.isEmpty()
                || !event.hasExplicitlyChangedBlock()
                || !trailPlayers.containsKey(event.getPlayer().getUniqueId()))
        {
            return;
        }

        Block fromBlock = event.getFrom().getBlock();
        if (!fromBlock.isEmpty())
        {
            return;
        }

        final AtomicInteger entry = trailPlayers.get(event.getPlayer().getUniqueId());
        if (entry.get() >= woolColors.length)
        {
            entry.set(0);
        }

        fromBlock.setType(woolColors[entry.getAndIncrement()]);
    }

    public void remove(Player player)
    {
        trailPlayers.remove(player.getUniqueId());
    }

    public void add(Player player)
    {
        trailPlayers.put(player.getUniqueId(), new AtomicInteger(0));
    }

    public boolean has(Player player)
    {
        return trailPlayers.containsKey(player.getUniqueId());
    }
}
