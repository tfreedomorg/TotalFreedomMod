package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.FPlayer;

@Command(name = "blockcmd", description = "Block all commands for a specific player.", usage = "/<command> <-a | purge | <player>>", aliases = {"blockcommands", "blockcommand", "bc", "bcmd"})
@Permission(permission = "tfm.admin.blockcmd")
public class Command_blockcmd extends FCommand
{
    @Callback
    public void blockPlayer(final CommandSender sender, final Player player)
    {
        if (isAdmin(player))
        {
            msg(sender, "<gray><player> is an admin, and cannot have their commands blocked.",
                    Placeholder.unparsed("player", player.getName()));
            return;
        }

        final FPlayer playerdata = plugin().pl.getPlayer(player);

        adminAction(sender, "<red><blocked:Unb:B>locking all commands for <player>",
                Formatter.booleanChoice("blocked", playerdata.allCommandsBlocked()),
                Placeholder.unparsed("player", player.getName()));

        playerdata.setCommandsBlocked(!playerdata.allCommandsBlocked());

        if (playerdata.allCommandsBlocked())
            msg(player, "<red>Your commands have been blocked by an admin.");

        msg(sender, "<gray><blocked:B:Unb>locked all commands for <player>.",
                Formatter.booleanChoice("blocked", playerdata.allCommandsBlocked()),
                Placeholder.unparsed("player", player.getName()));
    }

    @Callback
    @Subcommand("-a") // This one is also not fit for a switch, but this case is due to the way handlers are implemented
    public void blockAll(final CommandSender sender)
    {
        adminAction(sender, "<red>Blocking commands for all non-admins");

        final long count = server().getOnlinePlayers()
                .stream()
                .filter(p -> !isAdmin(p))
                .peek(p -> {
                    plugin().pl.getPlayer(p).setCommandsBlocked(true);
                    msg(p, "<red>Your commands have been blocked by an admin.");
                })
                .count();

        msg(sender, "<gray>Blocked commands for <count> player<plural>.",
                Formatter.number("count", count),
                Placeholder.unparsed("plural", count == 1 ? "" : "s"));
    }

    @Callback
    @Subcommand("purge")
    public void purge(final CommandSender sender)
    {
        adminAction(sender, "<red>Unblocking commands for all players");

        final long count = server().getOnlinePlayers()
              .stream()
              .map(plugin().pl::getPlayer)
              .filter(FPlayer::allCommandsBlocked)
              .reduce(0L, (total, data) ->
                    {
                        data.setCommandsBlocked(false);
                        return total + 1;
                    }, Long::sum);

        msg(sender, "<gray>Unblocked commands for <count> player<plural>.",
                Formatter.number("count", count),
                Placeholder.unparsed("plural", count == 1 ? "" : "s"));
    }
}