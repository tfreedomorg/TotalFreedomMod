package me.totalfreedom.totalfreedommod;

import java.util.Iterator;
import java.util.List;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class AntiDrop extends FreedomService
{

    public AntiDrop(TotalFreedomMod plugin)
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

    private static boolean enabled()
    {
        return ConfigEntry.ANTIDROP_ENABLED.getBoolean(true);
    }

    private static long window()
    {
        final int v = ConfigEntry.ANTIDROP_TIME_WINDOW.getInteger(1000);
        return v <= 0 ? 1000L : v;
    }

    private static int dropLimit()
    {
        return ConfigEntry.ANTIDROP_DROP_LIMIT.getInteger(20);
    }

    private static int dropEjectLimit()
    {
        return ConfigEntry.ANTIDROP_DROP_EJECT_LIMIT.getInteger(60);
    }

    private static int dropItemLimit()
    {
        return ConfigEntry.ANTIDROP_DROP_ITEM_LIMIT.getInteger(512);
    }

    private static int dropItemEjectLimit()
    {
        return ConfigEntry.ANTIDROP_DROP_ITEM_EJECT_LIMIT.getInteger(-1);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (!enabled())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        final long window = window();
        final FPlayer fPlayer = plugin.pl.getPlayer(player);

        final ItemStack stack = event.getItemDrop().getItemStack();
        final int amount = (stack == null) ? 1 : Math.max(stack.getAmount(), 1);

        boolean cancel = false;
        boolean eject = false;
        boolean justCrossed = false;

        // 64 stack counts as a single drop.
        final int eventLimit = dropLimit();
        if (eventLimit >= 0)
        {
            final int before = fPlayer.incrementDropCount(window);
            final int ejectAt = dropEjectLimit();
            if (ejectAt >= 0 && before >= ejectAt)
            {
                eject = true;
            }
            else if (before >= eventLimit)
            {
                cancel = true;
                justCrossed |= (before == eventLimit);
            }
        }

        // 64 stack counts as 64 (items).
        final int itemLimit = dropItemLimit();
        if (itemLimit >= 0)
        {
            final int before = fPlayer.incrementDropItemCount(amount, window);
            final int ejectAt = dropItemEjectLimit();
            if (ejectAt >= 0 && before + amount > ejectAt)
            {
                eject = true;
            }
            else if (before + amount > itemLimit)
            {
                cancel = true;
                justCrossed |= (before <= itemLimit);
            }
        }

        if (eject)
        {
            ejectForDropFlood(player, fPlayer);
            event.setCancelled(true);
            return;
        }

        if (cancel)
        {
            event.setCancelled(true);
            // Warn at most once per window — only on the drop that first crosses
            // a soft limit — so the throttle can't itself become a chat flood.
            if (justCrossed)
            {
                FUtil.playerMsg(player, "You are dropping items too quickly.", NamedTextColor.GRAY);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event)
    {
        if (!enabled())
        {
            return;
        }

        final Player player = event.getPlayer();
        if (plugin.al.isAdmin(player))
        {
            return;
        }

        final List<Item> items = event.getItems();
        if (items.isEmpty())
        {
            return;
        }

        final int eventLimit = dropLimit();
        final int itemLimit = dropItemLimit();
        if (eventLimit < 0 && itemLimit < 0)
        {
            return;
        }

        int events = 0;
        int quantity = 0;
        boolean warned = false;
        final Iterator<Item> it = items.iterator();
        while (it.hasNext())
        {
            final ItemStack stack = it.next().getItemStack();
            final int amount = (stack == null) ? 1 : Math.max(stack.getAmount(), 1);

            boolean cancelThis = false;

            if (eventLimit >= 0 && ++events > eventLimit)
            {
                cancelThis = true;
            }

            if (itemLimit >= 0 && (quantity += amount) > itemLimit)
            {
                cancelThis = true;
            }

            if (cancelThis)
            {
                it.remove();
                if (!warned)
                {
                    warned = true;
                    FUtil.playerMsg(player, "That dropped too many items at once.", NamedTextColor.GRAY);
                }
            }
        }
    }

    private void ejectForDropFlood(Player player, FPlayer fPlayer)
    {
        FUtil.bcastMsg(player.getName() + " was automatically kicked for dropping too many items.", NamedTextColor.RED);
        plugin.ae.autoEject(player, "Kicked for dropping too many items at once.");
        fPlayer.resetDropCount();
        fPlayer.resetDropItemCount();
    }

}
