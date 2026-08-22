package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "autoclear", description = "Toggle whether or not a player has their inventory automatically cleared when they join.", usage = "/autoclear <player>")
@Permission(permission = "tfm.admin.autoclear")
public class Command_autoclear extends FCommand
{

    @Callback
    public void autoclear(CommandSender sender, OfflinePlayer target)
    {
        final boolean enabled = !plugin().lp.CLEAR_ON_JOIN.removeIf(entry -> entry.equals(target.getUniqueId()));
        if (enabled)
        {
            plugin().lp.CLEAR_ON_JOIN.add(target.getUniqueId());
        }

        msg(sender, "<gold><player> <aqua>will <enabled:now:no longer> have their inventory cleared when they join.",
                Placeholder.unparsed("player", target.getName()),
                Formatter.booleanChoice("enabled", enabled));
    }
    
}