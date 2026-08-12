package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

@Command(name = "opall", description = "Op everyone on the server, optionally changing everyone's gamemode at the same time.", usage = "/opall [-c | -s]")
@Permission(permission = "tfm.player.opall")
public class Command_opall extends FCommand
{
    @Callback
    public void opall(CommandSender sender, @Switch("c") boolean creative, @Switch("s") boolean survival)
    {
        if ((creative || survival) && !isAdmin(sender))
        {
            throw new CommandFailException("<gray>Only admins may change everyone's gamemode with -c or -s.");
        }

        if (creative && survival)
        {
            throw new CommandFailException("<gray>Cannot use both -c and -s at the same time.");
        }

        final GameMode gamemode = creative ? GameMode.CREATIVE : survival ? GameMode.SURVIVAL : null;

        server().getOnlinePlayers().forEach(player -> 
        {
            msg(player, "<aqua><name> - Opping all players on the server", Placeholder.unparsed("name", sender.getName()));
            player.setOp(true);
            msg(player, "<yellow>You are now op!");

            if (gamemode != null)
            {
                player.setGameMode(gamemode);
            }
        });
    }
}