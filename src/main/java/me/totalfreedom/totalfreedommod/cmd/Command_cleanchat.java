package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "cleanchat", description = "Clears the chat.", usage = "/cleanchat", aliases = {"cc", "clearchat"})
@Permission(permission = "tfm.admin.cleanchat")
public class Command_cleanchat extends FCommand
{
    @Callback
    public void execute(final CommandSender sender)
    {
        server().getOnlinePlayers()
                .stream()
                .filter(player -> !plugin().al.isAdmin(player))
                .forEach(player -> msg(player, "\n".repeat(100)));

        adminAction(sender, "<red>Cleared chat.");
    }
}