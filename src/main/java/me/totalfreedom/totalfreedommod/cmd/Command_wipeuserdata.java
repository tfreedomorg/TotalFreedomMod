package me.totalfreedom.totalfreedommod.cmd;

import java.io.File;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "wipeuserdata", description = "Removes essentials playerdata", usage = "/wipeuserdata")
@Permission(permission = "tfm.admin.senior.wipeuserdata", source = SourceType.ONLY_CONSOLE)
public class Command_wipeuserdata extends FCommand
{
    @Callback
    public void wipeuserdata(CommandSender sender)
    {
        if (!server().getPluginManager().isPluginEnabled("Essentials"))
        {
            msg(sender, "<gray>Essentials is not enabled on this server");
            return;
        }

        adminAction(sender, "<red>Wiping Essentials playerdata");

        FUtil.deleteFolder(new File(server().getPluginManager().getPlugin("Essentials").getDataFolder(), "userdata"));

        msg(sender, "<gray>All playerdata deleted.");
    }
}
