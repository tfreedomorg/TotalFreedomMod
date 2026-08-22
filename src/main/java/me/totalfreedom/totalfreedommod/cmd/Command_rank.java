package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.display.Displayable;
import me.totalfreedom.totalfreedommod.rank.CustomRank;

@Command(name = "rank", description = "Shows ranks", usage = "/<command> [player]")
@Permission(permission = "tfm.player.rank")
public class Command_rank extends FCommand
{
    @Callback
    public void querySelfOrAll(CommandSender sender)
    {
        if (!(sender instanceof Player playerSender))
        {
            server().getOnlinePlayers().forEach(player -> queryPlayer(sender, player));
            return;
        }

        queryPlayer(sender, playerSender);
    }

    @Callback
    public void queryPlayer(CommandSender sender, Player player)
    {
        final Displayable display = plugin().rm.getDisplay(player);
        final CustomRank rank = plugin().rm.getEffectiveRank(player);

        Component result = Component.text(player.getName() + " is ", NamedTextColor.AQUA)
                                    .append(display.getColoredLoginMessage());

        // The display may be a cosmetic rank (developer, owner) that differs from the rank the
        // player actually acts at, in which case both are worth showing.
        if (rank != null && rank != display)
        {
            result = result.append(Component.text(" (", NamedTextColor.AQUA))
                           .append(rank.getColoredName())
                           .append(Component.text(")", NamedTextColor.AQUA));
        }

        msg(sender, "<result>", MessageUtils.component("result", result));
    }
}
