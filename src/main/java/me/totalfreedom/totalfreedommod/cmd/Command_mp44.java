package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;

@Command(name = "mp44", description = "Modern weaponry, FTW. Use 'draw' to start firing, 'sling' to stop firing.", usage = "/<command> <draw | sling>")
@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.fun.mp44")
public class Command_mp44 extends FCommand
{
    @Callback
    @Subcommand("draw")
    public void draw(Player player)
    {
        if (!ConfigEntry.MP44_ENABLED.getBoolean())
        {
            msg(player, "<green>The mp44 is currently disabled.");
            return;
        }

        fplayer(player).armMP44();

        msg(player, "<green>mp44 is ARMED! Left click with gunpowder to start firing, left click again to quit.");
        msg(player, "<green>Type /mp44 sling to disable.  -by Madgeek1450");

        player.getEquipment().setItemInMainHand(new ItemStack(Material.GUNPOWDER, 1));
    }

    @Callback
    @Subcommand("sling")
    public void sling(Player player)
    {
        if (!ConfigEntry.MP44_ENABLED.getBoolean())
        {
            msg(player, "<green>The mp44 is currently disabled.");
            return;
        }

        fplayer(player).disarmMP44();

        msg(player, "<green>mp44 Disarmed.");
    }
}
