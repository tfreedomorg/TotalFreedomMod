package me.totalfreedom.totalfreedommod.cmd;

import me.totalfreedom.totalfreedommod.player.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.annotation.*;

@Command(name = "findip", description = "Shows all IPs registered to a player.", usage = "/findip [player]", aliases = {"ips", "ip"})
@Permission(permission = "tfm.admin.findip")
public class Command_findip extends FCommand
{
    @Callback
    public void findipSelf(Player player)
    {
        findipPlayer(player, plugin().players().getData(player));
    }

    @Callback
    public void findipPlayer(CommandSender sender,
                             PlayerData data)
    {
        msg(sender,
                "<gray><white><player></white>'s IP(s):</gray> <ips>",
                data.hasCustomNickname() ?
                        Placeholder.component("player", data.getDisplayedNickname()) :
                        Placeholder.unparsed("player", data.getUsername()),
                MessageUtils.joinedList("ips", data.getStoredIp().stream().toList(), NamedTextColor.WHITE)
        );
    }
}
