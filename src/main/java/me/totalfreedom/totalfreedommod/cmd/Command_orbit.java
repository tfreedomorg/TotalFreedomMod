package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.player.FPlayer;

@Command(name = "orbit", description = "POW!!! Right in the kisser! One of these days Alice, straight to the Moon!", usage = "/orbit <target>")
@Permission(permission = "tfm.fun.orbit")
public class Command_orbit extends FCommand
{
    @Callback
    public void setOrbit(CommandSender sender, Player player)
    {
        final FPlayer target = plugin().pl.getPlayer(player);
        if (!target.isOrbiting())
        {
            if (isProtectedAdmin(sender, player))
                return;

            player.setGameMode(GameMode.SURVIVAL);
            final double strength = 10.0;
            target.startOrbiting(strength);

            player.setVelocity(new Vector(0, strength, 0));
            adminAction(sender, "<aqua>Orbiting <target>",
                    Placeholder.unparsed("target", target.getName()));
        }
        else
        {
            msg(sender, "<gray>Stopped orbiting <player>", Placeholder.unparsed("player", target.getName()));
            target.stopOrbiting();
        }
    }
}