package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.CommandFailException;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Permission;

@Command(name = "vanish", aliases = {"v"}, description = "Toggles vanish, hiding you from other players.", usage = "/vanish")
@Permission(permission = "tfm.admin.vanish")
public class Command_vanish extends FCommand
{
    @Callback
    public void vanish(CommandSender sender)
    {
        if (!(sender instanceof Player player))
        {
            throw new CommandFailException("This command can only be used by players.");
        }

        final boolean nowVanished = plugin().vanish().toggle(player);
        msg(sender, nowVanished ? "<gray>You are now <aqua>vanished<gray>." : "<gray>You are no longer vanished.");
    }
}
