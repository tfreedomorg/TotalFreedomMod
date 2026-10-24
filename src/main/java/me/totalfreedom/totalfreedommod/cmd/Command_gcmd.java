package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Greedy;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;

@Command(name = "gcmd", description = "Send a command as someone else.", usage = "/gcmd <player> <command>")
@Permission(permission = "tfm.admin.gcmd")
public class Command_gcmd extends FCommand
{
    @Completer(value = "", position = 1, scope = Completer.Scope.ARGUMENT_TO_WORD)
    public List<String> completeCommand(CommandSender sender, String partial)
    {
        return CommandCandidates.inner(server(), sender, partial);
    }

    @Callback
    public void runAsOtherPlayer(CommandSender sender, Player player, @Greedy String command)
    {
        if (plugin().cb.isCommandBlocked(command, player) || plugin().cb.isCommandBlocked(command, sender))
        {
            msg(sender, "<red>You cannot run blocked commands on another player.");
            return;
        }

        if (isProtectedAdmin(sender, player))
            return;

        msg(sender, "<gray>Sending command as <yellow><player><gray>: <white><command>",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("command", command));

        try
        {
            if (server().getCommandMap().dispatch(player, command)) 
                msg(sender, "<green>Command sent.");
            else 
                msg(sender, "<red>Unknown error sending command.");
        }
        catch (Throwable ex)
        {
            msg(sender, "<red>Error sending command: <error>", Placeholder.unparsed("error", ex.getMessage()));
        }
    }
}