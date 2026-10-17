package me.totalfreedom.totalfreedommod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class GameModeGuard extends FreedomService
{
    private final Map<UUID, RateWindow> changeWindows = new ConcurrentHashMap<>();

    public GameModeGuard(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        changeWindows.clear();
    }

    @Override
    protected void onStop()
    {
        changeWindows.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        changeWindows.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event)
    {
        if (!ConfigEntry.GAMEMODE_FLOOD_ENABLED.getBoolean(true))
        {
            return;
        }

        final int maxPerSecond = ConfigEntry.GAMEMODE_FLOOD_MAX_CHANGES_PER_SECOND.getInteger(8);
        if (maxPerSecond <= 0)
        {
            return;
        }

        final Player player = event.getPlayer();
        final int perSecond = recordChange(player.getUniqueId());
        if (perSecond <= maxPerSecond)
        {
            return;
        }

        event.setCancelled(true);

        if (perSecond == maxPerSecond + 1)
        {
            FLog.warning(player.getName() + " is spamming game mode changes; rate-limiting"
                    + " and suppressing further warnings.");

            if (ConfigEntry.GAMEMODE_FLOOD_KICK_FLOODERS.getBoolean(true))
            {
                FUtil.bcastMsg(player.getName() + " was automatically kicked for spamming"
                        + " game mode changes.", NamedTextColor.RED);
                plugin.ae.autoEject(player, "Kicked for spamming game mode changes.");
            }
        }
    }

    private int recordChange(UUID id)
    {
        final long second = System.currentTimeMillis() / 1000L;
        final RateWindow window = changeWindows.computeIfAbsent(id, k -> new RateWindow());
        synchronized (window)
        {
            if (window.second != second)
            {
                window.second = second;
                window.count = 0;
            }
            return ++window.count;
        }
    }

    private static final class RateWindow
    {
        private long second;
        private int count;
    }
}
