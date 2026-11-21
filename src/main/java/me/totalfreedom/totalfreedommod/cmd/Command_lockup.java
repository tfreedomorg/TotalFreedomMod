package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.lockup.LockupEntry;

@Command(name = "lockup", 
    description = "Mute, blind, freeze, and block commands for a player and everyone on its address for 24 hours.",
    usage = "/lockup <<player> | off <ip | player> | list | all | purge>")
@Permission(permission = "tfm.admin.lockup")
public class Command_lockup extends FCommand
{
    @Callback
    @Subcommand("list")
    public void list(CommandSender sender)
    {
        final List<LockupEntry> entries = plugin().lu.getLockups();

        if (entries.isEmpty())
        {
            msg(sender, "<gray>No lockups are currently standing.");
            return;
        }

        final long now = System.currentTimeMillis();

        msg(sender, "<gray>Standing lockups:");
        entries.forEach(entry -> msg(sender,
                "<gray> <white><name></white> <dark_gray>(<ip>)</dark_gray> by <white><admin></white>, <white><remaining></white> left",
                Placeholder.unparsed("name", entry.username()),
                Placeholder.unparsed("ip", entry.ip()),
                Placeholder.unparsed("admin", entry.lockedBy()),
                Placeholder.unparsed("remaining", entry.remainingLabel(now))));
    }

    @Callback
    @Subcommand("all")
    public void lockAll(CommandSender sender)
    {
        adminAction(sender, "<red>Locking up all non-admins");

        final List<Player> targets = server().getOnlinePlayers()
                                             .stream()
                                             .map(Player.class::cast)
                                             .filter(player -> !isAdmin(player))
                                             .toList();

        targets.forEach(player -> plugin().lu.lockup(sender, player));

        msg(sender, "<gray>Locked up <count> player<plural>.",
                Formatter.number("count", targets.size()),
                Placeholder.unparsed("plural", targets.size() == 1 ? "" : "s"));
    }

    @Callback
    @Subcommand("purge")
    public void purge(CommandSender sender)
    {
        adminAction(sender, "<aqua>Purging all lockups");

        final int count = plugin().lu.purge();

        msg(sender, "<gray>Purged <count> lockup<plural>.",
                Formatter.number("count", count),
                Placeholder.unparsed("plural", count == 1 ? "" : "s"));
    }

    @Completer(value = "off", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return FuzzyMatch.filter(plugin().lu.getLockups()
                                            .stream()
                                            .flatMap(entry -> Stream.of(entry.username(), entry.ip()))
                                            .sorted()
                                            .toList(),
                                partial);
    }

    @Callback
    @Subcommand("off")
    public void release(CommandSender sender, String target)
    {
        plugin().lu.findByTarget(target).ifPresentOrElse(
                entry -> release(sender, entry),
                () -> msg(sender, "<gray>No lockup is standing on that player or address."));
    }

    @Callback
    public void lockup(CommandSender sender, Player player)
    {
        final String ip = fplayer(player).getIp();

        if (isProtectedAdmin(sender, player))
            return;

        adminAction(sender, "<red>Locking up <player>",
                Placeholder.unparsed("player", player.getName()));

        final int held = plugin().lu.lockup(sender, player).size();

        msg(player, "<red>You have been locked up.");
        msg(sender, "<gray>Locked up <count> player<plural> on <ip>.",
                Formatter.number("count", held),
                Placeholder.unparsed("plural", held == 1 ? "" : "s"),
                Placeholder.unparsed("ip", ip));
    }

    private void release(CommandSender sender, LockupEntry entry)
    {
        adminAction(sender, "<aqua>Lifting lockup on <player>",
                Placeholder.unparsed("player", entry.username()));

        final int freed = plugin().lu.release(entry.ip()).size();

        msg(sender, "<gray>Lifted lockup on <player>, freeing <count> online player<plural>.",
                Placeholder.unparsed("player", entry.username()),
                Formatter.number("count", freed),
                Placeholder.unparsed("plural", freed == 1 ? "" : "s"));
    }
}