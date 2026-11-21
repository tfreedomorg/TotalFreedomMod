package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "setspawn", description = "Set the server spawn to your current location.", usage = "/setspawn")
@Permission(permission = "tfm.world.setspawn", source = SourceType.ONLY_IN_GAME)
public class Command_setspawn extends FCommand
{
    @Callback
    public void setspawn(Player player)
    {
        final Location location = player.getLocation();
        plugin().sm.setSpawnLocation(location);
        msg(player, "<gray>Server spawn set to: <location>", Placeholder.unparsed("location", FUtil.formatLocation(location)));
    }
}
