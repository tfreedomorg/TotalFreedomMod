package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "disguisetoggle", description = "Toggle the disguise plugin", usage = "/disguisetoggle", aliases = {"dtoggle"})
@Permission(permission = "tfm.admin.disguisetoggle")
public class Command_disguisetoggle extends FCommand
{
    @Callback
    public void disguisetoggle(CommandSender sender)
    {
        if (!plugin().ldb.isPluginEnabled())
        {
            msg(sender, "<red>LibsDisguises is not enabled.");
            return;
        }

        adminAction(
                    sender, 
                    "<aqua><value:Enabling:Disabling> disguises.",
                    Formatter.booleanChoice("value", !plugin().ldb.isDisguisesEnabled())
                );

        if (plugin().ldb.isDisguisesEnabled())
        {
            plugin().ldb.undisguiseAll(true);
            plugin().ldb.setDisguisesEnabled(false);
        }
        else
        {
            plugin().ldb.setDisguisesEnabled(true);
        }

        msg(
            sender, 
            "<gray>Disguises are now <value:enabled:disabled>",
            Formatter.booleanChoice("value", plugin().ldb.isDisguisesEnabled())
        );
    }
}
