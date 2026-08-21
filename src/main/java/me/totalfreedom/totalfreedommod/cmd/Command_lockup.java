package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.FTask;

@Command(name = "lockup", description = "Block target's minecraft input. This is evil, and I never should have wrote it.", usage = "/<command> <all | purge | <<partialname> on | off>>")
@Permission(source = SourceType.ONLY_CONSOLE, permission = "tfm.admin.senior.lockup")
public class Command_lockup extends FCommand
{
    @Callback
    @Subcommand("all")
    public void lockAll(CommandSender sender)
    {
        adminAction(sender, "<red>Locking up all players");

        server().getOnlinePlayers()
                .stream()
                .filter(player -> !isAdmin(player))
                .forEach(this::startLockup);

        msg(sender, "<gray>Locked up all players.");
    }

    @Callback
    @Subcommand("purge")
    public void unlockAll(CommandSender sender)
    {
        adminAction(sender, "<aqua>Unlocking all players");

        server().getOnlinePlayers().forEach(this::cancelLockup);

        msg(sender, "<gray>Unlocked all players.");
    }

    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Completer(value = "", position = 1)
    public List<String> completeState(CommandSender sender, String partial)
    {
        return FuzzyMatch.filter(List.of("on", "off"), partial);
    }

    @Callback
    public void toggle(CommandSender sender, String name, String state)
    {
        final Player player = server().getPlayer(name);

        if (player == null)
        {
            msg(sender, "<gray>Player not found!");
            return;
        }

        if (state.equalsIgnoreCase("on"))
        {
            if (isProtectedAdmin(sender, player))
                return;

            adminAction(sender, "<red>Locking up <player>", Placeholder.unparsed("player", player.getName()));
            startLockup(player);
            msg(sender, "<gray>Locked up <player>.", Placeholder.unparsed("player", player.getName()));
        }
        else if (state.equalsIgnoreCase("off"))
        {
            adminAction(sender, "<aqua>Unlocking <player>", Placeholder.unparsed("player", player.getName()));
            cancelLockup(player);
            msg(sender, "<gray>Unlocked <player>.", Placeholder.unparsed("player", player.getName()));
        }
    }

    private void cancelLockup(FPlayer playerdata)
    {
        BukkitTask lockupScheduleId = playerdata.getLockupScheduleID();
        if (lockupScheduleId != null)
        {
            lockupScheduleId.cancel();
            playerdata.setLockupScheduleId(null);
        }
    }

    private void cancelLockup(final Player player)
    {
        cancelLockup(fplayer(player));
    }

    private void startLockup(final Player player)
    {
        final FPlayer playerdata = fplayer(player);

        cancelLockup(playerdata);

        playerdata.setLockupScheduleId(new BukkitRunnable()
        {
            @Override
            public void run()
            {
                FTask.run("Command_lockup/lockup", () ->
                {
                    if (player.isOnline())
                        player.openInventory(player.getInventory());
                    else
                        cancelLockup(playerdata);
                });
            }
        }.runTaskTimer(plugin(), 0L, 5L));
    }
}
