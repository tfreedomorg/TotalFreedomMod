package me.totalfreedom.totalfreedommod.cmd;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Command(name = "findip", description = "Shows all IPs registered to a player.", usage = "/findip [player]", aliases = {"ips", "ip"})
@Permission(permission = "tfm.admin.findip")
public class Command_findip extends FCommand
{
    @Callback
    public void findipSelf(Player player)
    {
        findipPlayer(player, player);
    }

    @Callback
    public void findipPlayer(CommandSender sender, Player player)
    {
        msg(
            sender,
            "<gray><player></gray>'s IP(s): <ips>",
            Placeholder.unparsed("player", player.getName()),
            MessageUtils.joinedList("ips", plugin().pl.getData(player).getIps(), NamedTextColor.WHITE)
        );
    }
}
