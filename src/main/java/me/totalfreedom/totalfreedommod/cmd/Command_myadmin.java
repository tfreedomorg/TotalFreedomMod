package me.totalfreedom.totalfreedommod.cmd;

import java.net.InetAddress;
import java.util.List;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;

@Permission(source = SourceType.ONLY_IN_GAME, permission = "tfm.admin.myadmin")
@Command(name = "myadmin", description = "Manage my admin entry", usage = "/myadmin <clearips | clearip <ip> | setlogin <message> | clearlogin>")
public class Command_myadmin extends FCommand
{

    @Callback
    @Subcommand("clearip")
    public void removeIp(Player player, @Resolve("IP") InetAddress address)
    {
        if (player.getAddress().getAddress().equals(address))
        {
            msg(player, "<red>You can't remove your current IP address.");
            return;
        }

        final Admin entry = plugin().al.getAdmin(player);
        if (!entry.getIps().contains(address.getHostAddress()))
        {
            msg(player, "<red>That IP address isn't in your entry.");
            return;
        }

        entry.removeIp(address.getHostAddress());
        plugin().al.refreshIps(entry);
        plugin().al.saveAdminAsync(entry);

        msg(player, "<gray>Removed <ip> from your admin entry.", MessageUtils.unparsed("ip", address.getHostAddress()));
    }

    @Callback
    @Subcommand("clearips")
    public void clearIps(Player player)
    {
        final Admin entry = plugin().al.getAdmin(player);
        final List<String> ips = entry.getIps();
        final int count = ips.size();

        ips.clear();
        ips.add(player.getAddress().getAddress().getHostAddress());

        plugin().al.refreshIps(entry);
        plugin().al.saveAdminAsync(entry);

        msg(player, "<gray><count> IP address(es) were removed.", Formatter.number("count", count - 1));
    }

    @Callback
    @Subcommand("setlogin")
    public void setLoginMessage(Player player, @Greedy String message)
    {
        final Admin entry = plugin().al.getAdmin(player);
        //removed conversion method. we want to enforce <tags> because tags are peak.

        entry.setLoginMessage(message);

        msg(
            player, // removing the tabs in the string block because they are also processed by MessageUtils. 
            """ 
            Your login message is now:
            > <login>\
            """,
            MessageUtils.component("login", plugin().rm.formatLoginMessage(player))
        );

        plugin().al.saveAdminAsync(entry);
    }

    @Callback
    @Subcommand("clearlogin")
    public void clearLogin(Player player)
    {
        final Admin entry = plugin().al.getAdmin(player);

        entry.setLoginMessage(null); // this needs a .resetLoginMessage() call so that we AVOID nullability. Not my problem right now.
        msg(player, "<gray>Your login message has been removed.");
        plugin().al.saveAdminAsync(entry);
    }
}
