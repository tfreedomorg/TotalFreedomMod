package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;


@Permission(permission = "tfm.admin.kick")
@Command(name = "kick", aliases = "k", description = "Kick a player.", usage = "/<command> [-s] <player> [reason]")
public class Command_kick extends FCommand
{
    @Callback
    public void kickNoReason(CommandSender sender, Player player, @Switch("s") boolean silent)
    {
        kick(sender, player, null, silent);
    }

    @Completer(value = "", position = 1)
    public List<String> completeReason(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void kick(CommandSender sender, Player player, @Greedy String reason, @Switch("s") boolean silent)
    {
        if (isProtectedAdmin(sender, player))
            return;

        final String kickMessage = reason != null
                ? "<red>You have been kicked from the server.\n<red>Kicked by: <gold><sender>\n<red>Reason: <gold><reason>"
                : "<red>You have been kicked from the server.\n<red>Kicked by: <gold><sender>";

        if (!silent)
        {
            adminAction(sender, reason == null ? "<red>Kicking <player>" : "<red>Kicking <player> - Reason: <reason>",
                    Placeholder.unparsed("player", player.getName()),
                    MessageUtils.parsed("reason", reason != null ? reason : ""));

            plugin().db.sendActionMessage(sender.getName(), player.getName(), reason, ConfigEntry.DISCORD_PLAYER_KICK_MESSAGE);
        }

        kickPlayer(player, kickMessage,
                Placeholder.unparsed("sender", sender.getName()),
                MessageUtils.parsed("reason", reason != null ? reason : ""));
    }
}
