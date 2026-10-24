package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Completer;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.totalfreedommod.banish.BanishEntry;
import me.totalfreedom.totalfreedommod.banish.BanishManager;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;

@Command(name = "unbanish", description = "Lifts a player's banishment.", usage = "/unbanish <player>", aliases = {"unexile", "uex"})
@Permission(permission = "tfm.admin.ban")
public class Command_unbanish extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return FuzzyMatch.filter(plugin().services().require(BanishManager.class).getBanishments()
                .stream()
                .map(BanishEntry::username)
                .distinct()
                .sorted()
                .toList(), partial);
    }

    @Callback
    public void unbanish(CommandSender sender, String target)
    {
        try
        {
            final BanishEntry entry = plugin().services().require(BanishManager.class).release(target).orElse(null);
            if (entry == null)
            {
                msg(sender, "<gray>No banishment is active for that player.");
                return;
            }

            adminAction(sender, "<aqua>Lifting banishment on <player>",
                    Placeholder.unparsed("player", entry.username()));
            msg(sender, "<gray>Released the banishment for <player>.",
                    Placeholder.unparsed("player", entry.username()));
        }
        catch (IllegalStateException ex)
        {
            msg(sender, "<red><error>", Placeholder.unparsed("error", ex.getMessage()));
        }
    }
}
