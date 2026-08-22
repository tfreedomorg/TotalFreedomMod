package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.fun.Landminer.Landmine;

@Command(name = "landmine", description = "Set a landmine trap.", usage = "/<command>")
@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.fun.landmine")
public class Command_landmine extends FCommand
{
    @Callback
    @Subcommand("list")
    public void list(Player player)
    {
        plugin().lm.getLandmines().forEach(landmine -> msg(player, landmine.toString()));
    }

    @Callback
    public void placeDefault(Player player)
    {
        place(player, 2.0);
    }

    @Callback
    public void place(Player player, double radius)
    {
        if (!ConfigEntry.LANDMINES_ENABLED.getBoolean())
        {
            msg(player, "<green>The landmine is currently disabled.");
            return;
        }

        if (!ConfigEntry.ALLOW_EXPLOSIONS.getBoolean())
        {
            msg(player, "<green>Explosions are currently disabled.");
            return;
        }

        final double clamped = Math.clamp(radius, 2.0, 6.0);

        final Block landmine = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
        landmine.setType(Material.TNT);
        plugin().lm.add(new Landmine(landmine.getLocation(), player, clamped));

        msg(player, "<green>Landmine planted. Radius: <radius> blocks.", Formatter.number("radius", clamped));
    }
}
