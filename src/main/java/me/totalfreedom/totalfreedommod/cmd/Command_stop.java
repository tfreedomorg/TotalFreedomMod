package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "stop", description = "Kicks everyone and stops the server.", usage = "/stop")
@Permission(permission = "tfm.server.stop", source = SourceType.ONLY_CONSOLE)
public class Command_stop extends FCommand
{
    @Callback
    public void stop(CommandSender sender)
    {
        MessageUtils.broadcast("<light_purple>Server is going offline!");

        server().getOnlinePlayers().forEach(player -> kickPlayer(player, "Server is going offline, come back in about 20 seconds."));

        server().shutdown();
    }
}
