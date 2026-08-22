package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Greedy;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

@Command(name = "warn", description = "Warns a player.", usage = "/<command> <player> <reason>")
@Permission(permission = "tfm.admin.warn")
public class Command_warn extends FCommand
{
    @Completer(value = "", position = 1)
    public List<String> completeReason(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void warnPlayer(CommandSender sender, Player player, @Greedy String reason)
    {
        if (sender instanceof Player playerSender && playerSender.equals(player))
        {
            msg(sender, "<red>Please, don't try to warn yourself.");
            return;
        }

        if (isProtectedAdmin(sender, player))
            return;

        adminAction(sender, "<red>Warning <player>", Placeholder.unparsed("player", player.getName()));
        MessageUtils.broadcast("<red>  Reason: <yellow><reason>", MessageUtils.parsed("reason", reason));

        fplayer(player).incrementWarnings();

        msg(player, "<red>[WARNING] You have received a warning: <yellow><reason>", MessageUtils.parsed("reason", reason));

        msg(sender, "<green><player> has been successfully warned.", Placeholder.unparsed("player", player.getName()));

        plugin().db.sendActionMessage(sender.getName(), player.getName(), reason, ConfigEntry.DISCORD_PLAYER_WARN_MESSAGE);
    }
}
