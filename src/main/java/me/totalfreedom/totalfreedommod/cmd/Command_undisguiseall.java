package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "undisguiseall", description = "Undisguise all players on the server", usage = "/undisguiseall", aliases = {"uall"})
@Permission(permission = "tfm.admin.undisguiseall")
public class Command_undisguiseall extends FCommand
{
    @Callback
    public void undisguiseall(CommandSender sender)
    {
        if (!plugin().ldb.isPluginEnabled())
        {
            msg(sender, "<gray>LibsDisguises is not enabled.");
            return;
        }

        if (!plugin().ldb.isDisguisesEnabled())
        {
            msg(sender, "<gray>Disguises are not enabled.");
            return;
        }

        adminAction(sender, "<red>Undisguising all non-admins");

        plugin().ldb.undisguiseAll(false);
    }
}
