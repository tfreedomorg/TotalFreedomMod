package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Greedy;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@Command(name = "smite", description = "Someone being a little bitch? Smite them down...", usage = "/smite <player> [reason]")
@Permission(permission = "tfm.fun.smite", level = Rank.SUPER_ADMIN)
public class Command_smite extends FCommand
{
    @Callback
    public void smiteNoReason(CommandSender sender, Player player)
    {
        smite(sender, player, null);
    }

    @Completer(value = "", position = 1)
    public List<String> completeReason(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void smite(CommandSender sender, Player player, @Greedy String reason)
    {
        FUtil.bcastMsg("<red><player> has been a naughty, naughty boy.", Placeholder.unparsed("player", player.getName()));

        if (reason != null)
        {
            FUtil.bcastMsg("  <yellow>Reason: <reason>", Placeholder.unparsed("reason", reason));
        }

        plugin().db.sendActionMessage(sender.getName(), player.getName(), reason, ConfigEntry.DISCORD_PLAYER_SMITE_MESSAGE);

        // Deop
        player.setOp(false);

        // Set gamemode to survival
        player.setGameMode(GameMode.SURVIVAL);

        // Clear inventory
        player.getInventory().clear();

        // Strike with lightning effect
        final Location targetPos = player.getLocation();
        final World world = player.getWorld();
        for (int x = -1; x <= 1; x++)
        {
            for (int z = -1; z <= 1; z++)
            {
                final Location strike_pos = new Location(world, targetPos.getBlockX() + x, targetPos.getBlockY(), targetPos.getBlockZ() + z);
                world.strikeLightning(strike_pos);
            }
        }

        // Kill
        player.setHealth(0.0);

        if (reason != null) 
            msg(
                player,
                """
                    <red>You have been smitten.
                    <red>Reason: <yellow><reason>\
                """,
                MessageUtils.parsed("reason", reason)
            );
    }
}