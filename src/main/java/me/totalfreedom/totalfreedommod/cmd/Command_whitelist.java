package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Permission(permission = "tfm.server.whitelist")
@Command(name = "whitelist", description = "Manage the whitelist.", usage = "/<command> <on | off | list | count | add <player> | remove <player> | addall | purge>")
public class Command_whitelist extends FCommand
{
    @Callback
    @Subcommand("list")
    public void list(CommandSender sender)
    {
        msg(sender, "<gray>Whitelisted players: <players>", Placeholder.unparsed("players", FUtil.playerListToNames(server().getWhitelistedPlayers())));
    }

    @Callback
    @Subcommand("count")
    public void count(CommandSender sender)
    {
        int onlineWPs = 0;
        int offlineWPs = 0;

        for (OfflinePlayer player : server().getWhitelistedPlayers())
        {
            if (player.isOnline())
            {
                onlineWPs++;
            }
            else
            {
                offlineWPs++;
            }
        }

        msg(sender, "<gray>Online whitelisted players: <count>", Formatter.number("count", onlineWPs));
        msg(sender, "<gray>Offline whitelisted players: <count>", Formatter.number("count", offlineWPs));
        msg(sender, "<gray>Total whitelisted players: <count>", Formatter.number("count", onlineWPs + offlineWPs));
    }

    @Callback
    @Subcommand("on")
    @Permission(permission = "tfm.server.whitelist.manage")
    public void on(CommandSender sender)
    {
        adminAction(sender, "<aqua>Turning the whitelist on.");
        server().setWhitelist(true);
    }

    @Callback
    @Subcommand("off")
    @Permission(permission = "tfm.server.whitelist.manage")
    public void off(CommandSender sender)
    {
        adminAction(sender, "<aqua>Turning the whitelist off.");
        server().setWhitelist(false);
    }

    @Completer(value = "add", position = 0)
    public List<String> completeAdd(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    /**
     * Whitelisted names, not online ones: removal only makes sense for somebody already on the list,
     * and most of them will be offline.
     */
    @Completer(value = "remove", position = 0)
    public List<String> completeRemove(CommandSender sender, String partial)
    {
        return NameCandidates.whitelisted(partial);
    }

    @Callback
    @Subcommand("add")
    @Permission(permission = "tfm.server.whitelist.manage")
    public void add(CommandSender sender, String name)
    {
        final OfflinePlayer player = resolveOfflinePlayer(name);

        adminAction(sender, "<aqua>Adding <player> to the whitelist.", Placeholder.unparsed("player", player.getName()));
        player.setWhitelisted(true);
    }

    @Callback
    @Subcommand("remove")
    @Permission(permission = "tfm.server.whitelist.manage")
    public void remove(CommandSender sender, String name)
    {
        final OfflinePlayer player = resolveOfflinePlayer(name);

        if (!player.isWhitelisted())
        {
            msg(sender, "<gray>That player is not whitelisted");
            return;
        }

        adminAction(sender, "<aqua>Removing <player> from the whitelist.", Placeholder.unparsed("player", player.getName()));
        player.setWhitelisted(false);
    }

    @Callback
    @Subcommand("addall")
    @Permission(permission = "tfm.server.whitelist.manage")
    public void addAll(CommandSender sender)
    {
        adminAction(sender, "<aqua>Adding all online players to the whitelist.");

        int counter = 0;
        for (Player player : server().getOnlinePlayers())
        {
            if (!player.isWhitelisted())
            {
                player.setWhitelisted(true);
                counter++;
            }
        }

        msg(sender, "<gray>Whitelisted <count> players.", Formatter.number("count", counter));
    }

    @Callback
    @Subcommand("purge")
    @Permission(source = SourceType.ONLY_CONSOLE, permission = "tfm.server.whitelist.manage")
    public void purge(CommandSender sender)
    {
        adminAction(sender, "<aqua>Whitelist purging is temporarily disabled.");
    }

    private OfflinePlayer resolveOfflinePlayer(String name)
    {
        final Player online = server().getPlayer(name);
        return online != null ? online : server().getOfflinePlayer(name);
    }
}
