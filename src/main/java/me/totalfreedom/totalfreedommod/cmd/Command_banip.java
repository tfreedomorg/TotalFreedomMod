package me.totalfreedom.totalfreedommod.cmd;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.rank.Rank;

@Command(name = "banip", description = "Bans an IP address or all known IP addresses for a player.", usage = "/banip <player|ip> [reason]")
@Permission(level = Rank.SUPER_ADMIN, permission = "tfm.admin.ban")
public class Command_banip extends FCommand 
{
    
    // @Resolve("IPs") indicates to the command processor that the provided argument should be resolved via the InetAddressListResolver
    @Callback
    public void banIps(CommandSender sender, @Resolve(value = "IPs", strategy = "allowPlayers,all") List<InetAddress> addressList)
    {
        banIpsWithReason(sender, addressList, null);
    }
    
    @Completer(value = "", position = 1)
    public List<String> completeReason(CommandSender sender, String partial)
    {
        return NameCandidates.onlineTyped(server(), partial);
    }

    @Callback
    public void banIpsWithReason(CommandSender sender, @Resolve(value = "IPs", strategy = "allowPlayers,all") List<InetAddress> addressList, @Greedy String reason)
    {
        adminAction(sender, "<red>Banning <count> address<plural:es:><include_reason:\" - Reason: <yellow><reason>\":\"\">",
                Formatter.number("count", addressList.size()),
                Formatter.booleanChoice("plural", addressList.size() != 1),
                Formatter.booleanChoice("include_reason", reason != null && !reason.isEmpty()),
                Placeholder.unparsed("reason", reason != null ? reason : ""));

        addressList.stream()
                .filter(ip -> !plugin().bm.isIpBanned(ip.getHostAddress()))
                .forEach(ip -> 
                    {
                        final Ban ban = Ban.forPlayerIp(ip.getHostAddress(), sender, null, reason);
                        BanCommandUtil.addRangeIpIfEnabled(ban, ip.getHostAddress());

                        if (plugin().bm.addBan(ban))
                        {
                            server().getOnlinePlayers().stream()
                                .filter(player -> Objects.requireNonNull(player.getAddress()).getAddress().equals(ip))
                                .map(player -> (Player) player)
                                .forEach(player -> player.kick(ban.bakeKickMessage()));
                        }
                    });
    }
}
