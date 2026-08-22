package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "ops", description = "Manage operators", usage = "/ops")
@Permission(permission = "tfm.player.ops")
public class Command_ops extends FCommand
{
    @Callback
    public void ops(CommandSender sender)
    {
        final int totalOps = server().getOperators().size();
        final int onlineOps = (int) server().getOnlinePlayers().stream().filter(Player::isOp).count();

        msg(sender, "<gray>Online OPs: <count>", Formatter.number("count", onlineOps));
        msg(sender, "<gray>Offline OPs: <count>", Formatter.number("count", totalOps - onlineOps));
        msg(sender, "<gray>Total OPs: <count>", Formatter.number("count", totalOps));
    }
}
