package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "deop", description = "Deop a player.", usage = "/deop <player>")
@Permission(permission = "tfm.admin.deop")
public class Command_deop extends FCommand
{
    @Callback
    public void deop(CommandSender sender, OfflinePlayer player)
    {
        if (isProtectedAdminByName(sender, player.getName()))
            return;

        adminAction(sender, "<aqua>De-opping <player>", Placeholder.unparsed("player", player.getName()));
        player.setOp(false);

        if (player.isOnline())
        {
            msg(player.getPlayer(), "<yellow>You are no longer op!");
        }
    }
}
