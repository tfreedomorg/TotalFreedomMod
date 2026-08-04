package me.totalfreedom.totalfreedommod.cmd;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.banning.Ban;
import java.util.List;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.rank.Rank;

@Command(name = "banname", description = "Bans the specified name.", usage = "/banname <name> [reason]")
@Permission(level = Rank.SUPER_ADMIN, permission = "tfm.admin.ban")
public class Command_banname extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Callback
    public void banName(CommandSender sender, String name)
    {
        banNameWithReason(sender, name, null);
    }

    @Completer(value = "", position = 1)
    public List<String> completeReason(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void banNameWithReason(CommandSender sender, String name, @Greedy String reason)
    {
        if (plugin().bm.getByUsername(name) != null)
        {
            msg(sender, "<gray><name> is already banned.", Placeholder.unparsed("name", name));
            return;
        }
        final Ban ban = Ban.forPlayerName(name, sender, null, reason);

        plugin().bm.addBan(ban);

        adminAction(sender, "<red>Banning the username <name>",
                Placeholder.unparsed("name", name));

        final Player player = server().getPlayer(name);
        if (player != null)
        {
            kickPlayer(player, MessageUtils.toPlainText(ban.bakeKickMessage()));
        }
    }
}
