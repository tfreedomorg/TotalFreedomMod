package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "ban", description = "Bans an online or previously known player and their known IP addresses.", usage = "/<command> [-s] [-nrb] <player> [reason]", aliases = {"gtfo"})
@Permission(permission = "tfm.admin.ban", level = Rank.SUPER_ADMIN)
public class Command_ban extends FCommand
{

    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Completer(value = "", position = 1)
    public List<String> completeReason(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void ban(CommandSender sender, String name, @Greedy String reason, @Switch("s") boolean silent, @Switch("nrb") boolean noRollback)
    {
        doBan(sender, name, reason, silent, noRollback);
    }

    @Callback
    public void banNoReason(CommandSender sender, String name, @Switch("s") boolean silent, @Switch("nrb") boolean noRollback)
    {
        doBan(sender, name, null, silent, noRollback);
    }

    private void doBan(CommandSender sender, String name, String reason, boolean silent, boolean noRollback)
    {
        final Player player = server().getPlayer(name);
        PlayerData data = BanCommandUtil.getData(plugin(), name, player);

        if (data == null)
        {
            msg(sender, "<gray>Can't find that player. Use /banname to ban an arbitrary name.");
            return;
        }

        name = BanCommandUtil.getCanonicalName(name, player, data);

        if (plugin().bm.getByUsername(name) != null)
        {
            msg(sender, "<gray><player> is already banned.",
                    Placeholder.unparsed("player", name));
            return;
        }

        List<String> ips = BanCommandUtil.getIps(player, data);
        Ban ban = BanCommandUtil.createFullBan(name, ips, sender, FUtil.parseDateOffset("24h"), reason);

        if (!silent && player != null)
        {
            MessageUtils.broadcast("<red><player> has been a VERY naughty, naughty boy.",
                    Placeholder.unparsed("player", name));
        }

        plugin().bm.addBan(ban);
        if (!silent)
        {
            adminAction(sender, "<red>Banning <player><include_reason:\" - Reason: <yellow><reason>\":\"\">",
                    Placeholder.unparsed("player", name),
                    Formatter.booleanChoice("include_reason", reason != null && !reason.isEmpty()),
                    Placeholder.unparsed("reason", reason != null ? reason : ""));

            plugin().db.sendActionMessage(sender.getName(), name, reason, ConfigEntry.DISCORD_PLAYER_BAN_MESSAGE);
        }

        if (!noRollback)
        {
            plugin().cpb.rollback(name);
        }

        if (player == null || !plugin().al.isAdmin(player))
        {
            data.setStrikes(0);
        }

        if (player != null)
        {
            try
            {
                plugin().web.undo(player, 15);
            }
            catch (NoClassDefFoundError ignored)
            {
            }

            if (!silent)
            {
                player.setOp(false);
                player.setGameMode(GameMode.SURVIVAL);
                player.getInventory().clear();
                smitePlayer(player);
            }

            kickPlayer(player, MessageUtils.toPlainText(ban.bakeKickMessage()));
        }
    }
}
