package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "worldtp", description = "Teleport to a world.", usage = "/worldtp <world>", aliases = {"wtp", "tpw", "tpworld"})
@Permission(permission = "tfm.world.tp", source = SourceType.ONLY_IN_GAME)
public class Command_worldtp extends FCommand
{
    @Completer(value = "", position = 0)
    public List<String> completeWorld(Player sender, String partial)
    {
        return FuzzyMatch.filter(plugin().wm.worldNames(), partial);
    }

    @Callback
    public void worldtp(Player player, String world)
    {
        plugin().wm.gotoWorld(player, world);
    }
}
