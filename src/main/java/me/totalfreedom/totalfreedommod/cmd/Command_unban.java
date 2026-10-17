package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Set;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;

@Command(name = "unban", description = "Unbans an online or offline player and linked IP addresses.", usage = "/unban [-s] [-r] <player>")
@Permission(permission = "tfm.admin.ban")
public class Command_unban extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.banned(plugin(), partial);
    }

    @Callback
    public void unban(CommandSender sender, String playerName, @Switch("s") boolean silent, @Switch("r") boolean restore)
    {
        Player player;
        try
        {
            player = getPlayer(playerName);
        }
        catch (CommandFailException ex)
        {
            player = null;
        }

        final PlayerData data = BanCommandUtil.getData(plugin(), playerName, player);
        final String name = BanCommandUtil.getCanonicalName(playerName, player, data);
        final Set<Ban> bans = BanCommandUtil.findLinkedBans(plugin(), playerName, player, data);

        if (bans.isEmpty())
        {
            msg(sender, "<gray>No ban on record for <player>.", Placeholder.unparsed("player", playerName));
            return;
        }

        for (Ban ban : bans)
        {
            plugin().bm.removeBan(ban);
        }

        if (!silent)
        {
            adminAction(sender, "<red>Unbanning <player>", Placeholder.unparsed("player", name));
        }

        if (restore)
        {
            plugin().cpb.restore(name);
        }
    }
}
