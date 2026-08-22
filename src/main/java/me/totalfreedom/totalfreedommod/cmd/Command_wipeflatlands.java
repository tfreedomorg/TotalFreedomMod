package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "wipeflatlands", description = "Wipe the flatlands map. Requires manual restart after command is used.", usage = "/wipeflatlands")
@Permission(permission = "tfm.admin.senior.wipeflatlands", source = SourceType.ONLY_CONSOLE)
public class Command_wipeflatlands extends FCommand
{
    @Callback
    public void wipeflatlands(CommandSender sender)
    {
        plugin().sf.setSavedFlag("do_wipe_flatlands", true);

        MessageUtils.broadcast("<gray>Server is going offline for flatlands wipe.");

        server().getOnlinePlayers().forEach(player -> 
        {
            kickPlayer(player, "Server is going offline for flatlands wipe, come back in a few minutes.");
        });

        server().shutdown();
    }
}
