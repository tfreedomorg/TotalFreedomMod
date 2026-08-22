package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

@Command(name = "flatlands", description = "Goto the flatlands.", usage = "/flatlands")
@Permission(permission = "tfm.world.flatlands", source = SourceType.ONLY_IN_GAME)
public class Command_flatlands extends FCommand
{
    @Callback
    public void flatlands(Player player)
    {
        if (!ConfigEntry.FLATLANDS_GENERATE.getBoolean())
        {
            msg(player, "<gray>Flatlands is currently disabled.");
            return;
        }

        plugin().wm.flatlands.sendToWorld(player);
    }
}
