package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.player.PlayerData;

@Permission(permission = "tfm.admin.strike")
@Command(name = "strikes", aliases = "strike", description = "Manages the strikes for a player.", usage = "/<command> <add | remove | clear> <player>")
public class Command_strikes extends FCommand
{
    @Callback
    @Subcommand("add")
    public void add(CommandSender sender, Player player)
    {
        modify(sender, player, 1);
    }

    @Callback
    @Subcommand("remove")
    public void remove(CommandSender sender, Player player)
    {
        modify(sender, player, -1);
    }

    private void modify(CommandSender sender, Player player, int mod)
    {
        if (isAdmin(player))
        {
            msg(sender, "<gray>Strikes may not be modified for admins.");
            return;
        }

        final PlayerData data = getData(player);
        final int newValue = data.getStrikes() + mod;

        if (newValue < 0 || newValue > PlayerData.MAX_STRIKES)
        {
            msg(sender, "<gray><player> may only have between 0 and <max> strikes.",
                    Placeholder.unparsed("player", player.getName()),
                    Formatter.number("max", PlayerData.MAX_STRIKES));
            return;
        }

        data.setStrikes(newValue);
        msg(sender, "<gray><player> now has <count> strike<plural>.",
                Placeholder.unparsed("player", player.getName()),
                Formatter.number("count", data.getStrikes()),
                Placeholder.unparsed("plural", data.getStrikes() != 1 ? "s" : ""));
    }

    @Callback
    @Subcommand("clear")
    public void clear(CommandSender sender, Player player)
    {
        if (isAdmin(player))
        {
            msg(sender, "<gray>Strikes may not be modified for admins.");
            return;
        }

        final PlayerData data = getData(player);
        data.setStrikes(0);
        msg(sender, "<gray><player> now has no strikes.", Placeholder.unparsed("player", player.getName()));
    }

    @Callback
    @Subcommand("purge")
    public void purge(CommandSender sender)
    {
        final long count = plugin().pl
                                   .getAllData()
                                   .stream()
                                   .peek(data -> data.setStrikes(0))
                                   .count();

        msg(sender, "<gray>Purged strikes for <count> player(s).", Formatter.number("count", count));
    }
}
