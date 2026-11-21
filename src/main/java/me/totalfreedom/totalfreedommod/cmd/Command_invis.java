package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;

@Command(name = "invis", description = "Shows (and optionally clears) invisisible players", usage = "/invis [clear]")
@Permission(permission = "tfm.admin.invis")
public class Command_invis extends FCommand
{
    @Callback
    @Subcommand("clear")
    public void clear(CommandSender sender)
    {
        adminAction(sender, "<aqua>Clearing invisibility for all players");

        final long count = server().getOnlinePlayers()
                                   .stream()
                                   .filter(player -> player.hasPotionEffect(PotionEffectType.INVISIBILITY))
                                   .filter(player -> !isAdmin(player))
                                   .peek(player -> player.removePotionEffect(PotionEffectType.INVISIBILITY))
                                   .count();

        msg(sender, "<gray>Cleared invisibility effect from <count> players.", MessageUtils.unparsed("count", String.valueOf(count)));
    }

    @Callback
    public void list(CommandSender sender)
    {
        final boolean senderIsConsole = !(sender instanceof Player);

        final List<Component> players = server().getOnlinePlayers()
                                                .stream()
                                                .filter(player -> player.hasPotionEffect(PotionEffectType.INVISIBILITY))
                                                .map(player -> senderIsConsole ? 
                                                                    Component.text(player.getName(), NamedTextColor.WHITE) : 
                                                                    player.displayName()
                                                                          .colorIfAbsent(NamedTextColor.WHITE)
                                                                          .hoverEvent(HoverEvent.showText(Component.text(player.getName())))
                                                                          .clickEvent(ClickEvent.runCommand("/tp" + (plugin().esb.isEssentialsEnabled() ? "o " : " ") + player.getName()))
                                                    )
                                                .toList();

        if (players.isEmpty())
        {
            msg(sender, "<gray>There are no invisible players.");
            return;
        }

        msg(sender, "<gray>Invisible players (<count>): <players>",
                MessageUtils.unparsed("count", String.valueOf(players.size())),
                MessageUtils.joinedComponents("players", players));
    }
}
