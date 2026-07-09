package me.totalfreedom.totalfreedommod;

import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.server.ServerListPingEvent;

public class ServerPing extends FreedomService
{

    public ServerPing(TotalFreedomMod plugin)
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onServerPing(ServerListPingEvent event)
    {
        final String ip = event.getAddress().getHostAddress().trim();

        // Hide vanished players from the player count and hover list
        final java.util.Iterator<Player> iterator = event.iterator();
        while (iterator.hasNext())
        {
            final Player p = iterator.next();
            if (plugin.vm.isVanished(p))
            {
                iterator.remove();
            }
        }

        if (plugin.bm.isIpBanned(ip))
        {
            event.motd(Component.text("You are banned.", NamedTextColor.RED));
            return;
        }

        if (ConfigEntry.ADMIN_ONLY_MODE.getBoolean())
        {
            event.motd(Component.text("Server is closed.", NamedTextColor.RED));
            return;
        }

        if (Bukkit.hasWhitelist())
        {
            event.motd(Component.text("Whitelist enabled.", NamedTextColor.RED));
            return;
        }

        if (Bukkit.getOnlinePlayers().size() >= Bukkit.getMaxPlayers())
        {
            event.motd(Component.text("Server is full.", NamedTextColor.RED));
            return;
        }

//        String baseMotd = ConfigEntry.SERVER_MOTD.getString().replace("%mcversion%", plugin.si.getVersion());
        String baseMotd = ConfigEntry.SERVER_MOTD.getString();
        baseMotd = baseMotd.replace("\\n", "\n");
        baseMotd = AdventureUtil.componentToLegacySection(FUtil.colorize(baseMotd));

        if (!ConfigEntry.SERVER_COLORFUL_MOTD.getBoolean())
        {
            event.motd(FUtil.colorize(baseMotd));
            return;
        }

        // Colorful MOTD
        Component motd = Component.empty();
        for (String word : baseMotd.split(" "))
        {
            NamedTextColor color = FUtil.randomChatColor();
            motd = motd.append(Component.text(word + " ", color != null ? color : NamedTextColor.WHITE));
        }

        event.motd(motd);
    }
}
