package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.player.report")
@Command(name = "report", description = "Report a player for admins to see.", usage = "/report <player> <reason>")
public class Command_report extends FCommand
{

    private void doReport(Player player, OfflinePlayer target, String reason)
    {
        // This cast will never fail since the command may only be executed by players
        if (player.equals(target))
        {
            msg(player, "<red>Please, don't try to report yourself.");
            return;
        }

        final boolean sendFeedback = player.isOnline();

        if (target.getFirstPlayed() == 0)
        {
            if (sendFeedback)
            {
                msg(player, "<red>That player cannot be found!");
            }

            return;
        }

        final Admin reportedAdmin = plugin().al.getAdminByUuid(target.getUniqueId());
        if (reportedAdmin != null && reportedAdmin.isActive())
        {
            if (sendFeedback)
            {
                msg(player, "<red>Please use the <yellow><click:open_url:https://forum.tfreedom.org/>forums</click> <red>for reporting admins."); // based MessageUtils
            }

            return;
        }

        plugin().cm.reportAction(player, target, reason);

        if (sendFeedback)
        {
            msg(player, "<green>Thank you, your report has been successfully logged.");
        }
    }

    @Completer(value = "", position = 0)
    public List<String> completeTarget(Player sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Completer(value = "", position = 1)
    public List<String> completeReason(Player sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void report(Player sender, String playerName, @Greedy String reason) {
        Player player = getPlayer(playerName);

        if (player != null)
        {
            doReport(sender, player, reason);
        }

        else
        {
            async(t -> 
            {
                OfflinePlayer target = server().getOfflinePlayer(playerName);

                // NEVER interact with Bukkit API OR TFM outside the main thread.
                sync(() -> doReport(
                                    sender,
                                    target,
                                    reason
                            ), 0L);
            }, 0L);
        }
    }
}
