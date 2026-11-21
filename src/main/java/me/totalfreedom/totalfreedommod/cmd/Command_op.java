package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "op", description = "Makes a player operator", usage = "/op [player]")
@Permission(permission = "tfm.player.op")
public class Command_op extends FCommand
{
    @Callback
    public void opSelf(Player player)
    {
        if (!isAdmin(player))
        {
            return;
        }

        opPlayer(player, player);
    }

    @Callback
    public void opPlayer(CommandSender sender, Player player)
    {
        adminAction(sender, "<aqua>Opping <player>", Placeholder.unparsed("player", player.getName()));
        player.setOp(true);
        msg(player, "<yellow>You are now op!");
    }
}
