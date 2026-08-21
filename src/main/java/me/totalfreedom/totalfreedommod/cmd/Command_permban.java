package me.totalfreedom.totalfreedommod.cmd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.banning.PermBan;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.*;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "permban", description = "Manage permanently banned players and IPs.", usage = "/permban <add <name> [ip...] | remove <name|ip> | reload>")
@Permission(permission = "tfm.admin.ban.perm", source = SourceType.ONLY_CONSOLE)
public class Command_permban extends FCommand
{
    @Callback
    @Subcommand("reload")
    public void reload(CommandSender sender)
    {
        requireLocalConsole(sender);

        msg(sender, "<red>Reloading permban list...");
        plugin().pm.reload();
        msg(sender, "<green>Reloaded permban list.");
        msg(
            sender,
            "<gray><count> IPs and <names> usernames loaded.",
            Formatter.number("count", plugin().pm.getPermbannedIps().size()),
            Formatter.number("names", plugin().pm.getPermbannedNames().size())
        );
    }

    @Completer(value = "add", position = 0)
    public List<String> completeAdd(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

    @Completer(value = "remove", position = 0)
    public List<String> completeRemove(CommandSender sender, String partial)
    {
        return NameCandidates.permbanned(plugin(), partial);
    }

    @Completer(value = "delete", position = 0)
    public List<String> completeDelete(CommandSender sender, String partial)
    {
        return NameCandidates.permbanned(plugin(), partial);
    }

    @Callback
    @Subcommand("add")
    public void add(CommandSender sender, String name)
    {
        add(sender, name, "");
    }

    @Callback
    @Subcommand("add")
    public void add(CommandSender sender, String name, @Greedy String extraIps)
    {
        requireLocalConsole(sender);

        Player online;
        try
        {
            online = getPlayer(name);
        }
        catch (CommandFailException ex)
        {
            online = null;
        }

        final PlayerData data = BanCommandUtil.getData(plugin(), name, online);
        final String canonicalName = BanCommandUtil.getCanonicalName(name, online, data);

        if (isProtectedAdminByName(sender, canonicalName))
            return;

        final Set<String> ips = new LinkedHashSet<>(BanCommandUtil.getIps(online, data));
        for (String ip : extraIps.trim().split("\\s+"))
        {
            if (ip.isEmpty())
                continue;

            if (isValidIpOrRange(ip))
                ips.add(ip);
            else
                msg(sender, "<red>Ignoring invalid IP/range: <ip>", Placeholder.unparsed("ip", ip));
        }

        UUID uuid = online != null ? online.getUniqueId() : FUtil.usernameToUuid(canonicalName);

        PermBan permban = plugin().pm.getPermban(canonicalName);

        final boolean existed = permban != null;

        if (permban == null) // this caused a semantic issue with nullability. replaced with a proper null check instead of the cached boolean.
            permban = new PermBan(uuid, canonicalName, "Permbanned by " + sender.getName());
        else if (uuid != null && !permban.hasUuid())
            permban.setUuid(uuid);

        final int before = permban.getIps().size();
        permban.addIps(new ArrayList<>(ips));
        final int addedIps = permban.getIps().size() - before;

        plugin().pm.addPermban(permban);

        adminAction(
            sender,
            "<red><action> <name><ipnote>",
            Placeholder.unparsed("action", existed ? "Updating permban for" : "Permbanning"),
            Placeholder.unparsed("name", canonicalName),
            Placeholder.unparsed("ipnote", addedIps > 0 ? " (" + addedIps + " IP record(s))" : "")
        );

        if (existed)
        {
            msg(
                sender,
                "<gray>Updated permban for <name>; added <added> IP record(s) (<total> total).",
                Placeholder.unparsed("name", canonicalName),
                Formatter.number("added", addedIps),
                Formatter.number("total", permban.getIps().size())
            );
        }
        else
        {
            msg(
                sender,
                "<gray>Permbanned <name> with <total> IP record(s).",
                Placeholder.unparsed("name", canonicalName),
                Formatter.number("total", permban.getIps().size())
            );
        }

        if (online != null)
            kickPlayer(online, permbanKickMessage());
    }

    @Callback
    @Subcommand("remove")
    public void remove(CommandSender sender, String target)
    {
        doRemove(sender, target);
    }

    @Callback
    @Subcommand("delete")
    public void delete(CommandSender sender, String target)
    {
        doRemove(sender, target);
    }

    private void doRemove(CommandSender sender, String target)
    {
        requireLocalConsole(sender);

        if (isValidIpOrRange(target))
        {
            final List<String> removed = plugin().pm.removePermbansByIp(target);

            if (removed.isEmpty())
                msg(sender, "<red>No permbans matched the IP <target>.", Placeholder.unparsed("target", target));
            else
            {
                adminAction(
                    sender,
                    "<red>Removing <count> permban(s) matching IP <target>",
                    Formatter.number("count", removed.size()),
                    Placeholder.unparsed("target", target)
                );
                msg(
                    sender,
                    "<gray>Removed <count> permban(s) matching <target>: <names>.",
                    Formatter.number("count", removed.size()),
                    Placeholder.unparsed("target", target),
                    Placeholder.unparsed("names", String.join(", ", removed))
                );
            }
            return;
        }

        if (plugin().pm.removePermban(target))
        {
            adminAction(sender, "<red>Removing the permban for <target>", Placeholder.unparsed("target", target));
            msg(sender, "<gray>Removed the permban for <target>.", Placeholder.unparsed("target", target));
        }
        else
        {
            msg(sender, "<red><target> is not permbanned.", Placeholder.unparsed("target", target));
        }
    }

    private void requireLocalConsole(CommandSender sender)
    {
        if (RemoteDispatchContext.isActive() || !sender.getName().equalsIgnoreCase("CONSOLE"))
            throw new CommandFailException("This command can only be used from the server panel console.");
    }

    private String permbanKickMessage()
    {
        return "<red>Your username is permanently banned from this server.\n"
            + "Release procedures are available at\n"
            + "<gold>" + ConfigEntry.SERVER_PERMBAN_URL.getString();
    }

    private static boolean isValidIpOrRange(String ip)
    {
        final String[] parts = ip.split("\\.");
        if (parts.length != 4)
            return false;

        return Stream.of(parts)
                     .filter(part -> !part.equals("*"))
                     .allMatch(Command_permban::isValidOctet);
    }

    private static boolean isValidOctet(String part)
    {
        try
        {
            final int octet = Integer.parseInt(part);
            return octet >= 0 && octet <= 255;
        }
        catch (NumberFormatException ex)
        {
            return false;
        }
    }
}
