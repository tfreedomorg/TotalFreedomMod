package me.totalfreedom.totalfreedommod.command;

import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CommandPermissions(level = Rank.SUPER_ADMIN, source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.vanish")
@CommandParameters(description = "Toggle vanish mode — hides you from non-admins, TAB, and tab-completion.", usage = "/<command>")
public class Command_vanish extends FreedomCommand
{
    @CommandDispatchTarget
    public boolean toggle(CommandContext ctx)
    {
        final Player player = ctx.getPlayerSender();
        final boolean nowVanished = plugin.vm.toggle(player);

        if (nowVanished)
        {
            final Component adminMsg = Component.text(player.getName() + " - Vanishing from players", NamedTextColor.AQUA);
            for (Player p : server.getOnlinePlayers())
            {
                if (plugin.al.isAdmin(p))
                {
                    p.sendMessage(adminMsg);
                }
            }
            server.getConsoleSender().sendMessage(adminMsg);

            msg(ctx.getSender(), "You are now vanished. Players cannot see you, target you with /tp, or find you in TAB.", NamedTextColor.GREEN);
        }
        else
        {
            final Component adminMsg = Component.text(player.getName() + " - Reappearing to players", NamedTextColor.AQUA);
            for (Player p : server.getOnlinePlayers())
            {
                if (plugin.al.isAdmin(p))
                {
                    p.sendMessage(adminMsg);
                }
            }
            server.getConsoleSender().sendMessage(adminMsg);

            msg(ctx.getSender(), "You are no longer vanished.", NamedTextColor.YELLOW);
        }

        return true;
    }

    @Override
    public boolean run(CommandSender sender, Player playerSender, Command cmd, String commandLabel, String[] args, boolean senderIsConsole)
    {
        return false;
    }
}
