package me.totalfreedom.totalfreedommod;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.player.PlayerData;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.SoundCategory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.api.display.Displayable;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

public class CommandSpy extends FreedomService
{

    public CommandSpy(FreedomAPI plugin)
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
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event)
    {
        final Player commandSender = event.getPlayer();
        final boolean senderIsAdmin = plugin.admins().isAdmin(commandSender);

        for (Player player : plugin.admins().getOnlineAdmins())
        {
            final FPlayer playerData = plugin.players().getPlayer(player);
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

            final PlayerData data = plugin.players().getData(player);
            final String command = event.getMessage();
            final boolean alert = data.commandSpyAlertMatches(command);
            final Component message = alert
                    ? Component.text(commandSender.getName() + ": " + command, NamedTextColor.RED).decorate(TextDecoration.BOLD)
                    : Component.text(commandSender.getName() + ": " + command, NamedTextColor.GRAY);

            if (!senderIsAdmin)
            {
                FUtil.playerMsg(player, message);
                if (alert)
                {
                    player.playSound(player.getLocation(), data.getCommandSpyAlertSound(), SoundCategory.MASTER, 1.0F, 1.0F);
                }
                continue;
            }

            final Displayable display = plugin.ranks().getDisplay(commandSender);
            String prefix = AdventureUtil.componentToPlainText(display.getColoredTag()).trim();
            if (prefix.isEmpty())
            {
                final String tag = display.getTag();
                prefix = tag != null ? tag : "";
            }

            FUtil.playerMsg(player, Component.empty()
                    .append(prefix.isEmpty() ? Component.empty() : Component.text(prefix + " ", display.getColor()))
                    .append(message));
            if (alert)
            {
                player.playSound(player.getLocation(), data.getCommandSpyAlertSound(), SoundCategory.MASTER, 1.0F, 1.0F);
            }
        }
    }
}
