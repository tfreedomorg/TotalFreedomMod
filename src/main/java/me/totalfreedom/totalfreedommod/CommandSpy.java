package me.totalfreedom.totalfreedommod;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.player.SpyMode;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class CommandSpy extends FreedomService
{

    public CommandSpy(TotalFreedomMod plugin)
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        final Player commandSender = event.getPlayer();
        final boolean senderIsAdmin = plugin.al.isAdmin(commandSender);

        for (Player player : plugin.al.getOnlineAdmins())
        {
            final FPlayer playerData = plugin.pl.getPlayer(player);
            if (!playerData.cmdspyEnabled())
            {
                continue;
            }

            if (player.equals(commandSender))
            {
                continue;
            }

            if (!playerData.getCommandSpyMode().shows(senderIsAdmin))
            {
                continue;
            }

            if (!senderIsAdmin)
            {
                FUtil.playerMsg(player, Component.text(commandSender.getName() + ": " + event.getMessage(), NamedTextColor.GRAY));
                continue;
            }

            final Displayable display = plugin.rm.getDisplay(commandSender);
            String prefix = AdventureUtil.componentToPlainText(display.getColoredTag()).trim();
            if (prefix.isEmpty())
            {
                final String tag = display.getTag();
                prefix = tag != null ? tag : "";
            }

            Component message = Component.empty();
            if (!prefix.isEmpty())
            {
                message = Component.text(prefix + " ", display.getColor());
            }

            message = message.append(Component.text(commandSender.getName() + ": " + event.getMessage(), NamedTextColor.GRAY));
            FUtil.playerMsg(player, message);
        }
    }
}
