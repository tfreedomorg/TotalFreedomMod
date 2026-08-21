package me.totalfreedom.totalfreedommod.cmd;

import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.rank.CustomRank;

@Command(name = "saconfig", description = "Manage admins.",
        usage = "/<command> <list | clean | reload | setrank <username> <rank> | <add | remove | info> <username>>")
@Permission(permission = "tfm.admin.saconfig")
public class Command_saconfig extends FCommand
{

    @Callback
    @Subcommand("setrank")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public void setRank(CommandSender sender, Player target, String rankInput)
    {
        final CustomRank rank = plugin().rm == null ? null : plugin().rm.getCustomRank(rankInput);

        if (rank == null)
        {
            msg(sender, "<red>Unknown rank: <rank>", Placeholder.unparsed("rank", rankInput));
            return;
        }

        if (!rank.isAdmin())
        {
            msg(sender, "<red>Rank \"<rank>\" is not an admin rank.",
                    Placeholder.unparsed("rank", rank.getName()));
            return;
        }

        final Admin admin = plugin().al.getEntryByName(target.getName());
        if (admin == null)
        {
            msg(sender, "<gray>Unknown admin: <player>",
                    Placeholder.unparsed("player", target.getName()));
            return;
        }

        adminAction(sender, "<red>Setting <player>'s rank to <rank>",
                Placeholder.unparsed("player", admin.getName()),
                Placeholder.unparsed("rank", rank.getName()));

        admin.setRankId(rank.getId());
        plugin().al.updateTables();
        plugin().al.saveAdminAsync(admin);
        plugin().rm.updatePlayerTeam(target);

        msg(sender, "<gray>Set <player>'s rank to <rank>.",
                Placeholder.unparsed("player", admin.getName()),
                Placeholder.unparsed("rank", rank.getName()));
    }

    @Callback
    @Subcommand("info")
    public boolean getInfo(CommandSender sender, Player target) 
    {
        final Admin admin = plugin().al.getAdmin(target);

        if (admin == null)
        {
            msg(sender, "<gray>Admin not found: <player>",
                    Placeholder.unparsed("player", target.getName()));
        } 
        else 
        {
            msg(sender, admin.toString());
        }

        return true;
    }

    @Callback
    @Subcommand("add")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean addUser(CommandSender sender, Player target) 
    {
        if (plugin().al.isAdmin(target)) 
        {
            msg(sender, "<gray>That player is already admin.");
            return true;
        }

        final String name = target.getName();
        Admin admin = null;
        for (Admin loopAdmin : plugin().al.getAllAdmins().values()) 
        {
            if (loopAdmin.getName().equalsIgnoreCase(name)) 
            {
                admin = loopAdmin;
                break;
            }
        }

        adminAction(sender, "<red><new_entry:A:Re-a>dding <player> to the admin list",
                Formatter.booleanChoice("new_entry", admin == null),
                Placeholder.unparsed("player", target.getName()));

        target.setOp(true);

        if (admin == null)
        {
            plugin().al.addAdmin(new Admin(target));
        } 
        else 
        {
            admin.setName(target.getName());
            admin.addIp(target.getAddress().getAddress().getHostAddress());

            admin.setActive(true);
            admin.setLastLogin(new Date());

            plugin().al.updateTables();
            plugin().al.saveAdminAsync(admin);
        }

        
        if (plugin().rm != null) 
        {
            plugin().rm.updatePlayerTeam(target);
        }

        final FPlayer fPlayer = plugin().pl.getPlayer(target);
        if (fPlayer.getFreezeData().isFrozen()) 
        {
            fPlayer.getFreezeData().setFrozen(false);
            msg(target, "<green>You have been unfrozen.");
        }

        return true;
    }

    @Callback
    @Subcommand("remove")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean removeUser(CommandSender sender, String username) 
    {
        final Admin admin = plugin().al.getEntryByName(username);

        if (admin == null)
        {
            msg(sender, "<gray>Admin not found: <player>", Placeholder.unparsed("player", username));
            return true;
        }

        adminAction(sender, "<red>Removing <player> from the admin list",
                Placeholder.unparsed("player", admin.getName()));
        admin.setActive(false);
        plugin().al.updateTables();
        plugin().al.saveAdminAsync(admin);

        final Player player = Bukkit.getPlayer(username);
        if (player != null && plugin().rm != null) 
        {
            plugin().rm.updatePlayerTeam(player);
        }

        return true;
    }

    @Callback
    @Subcommand("reload")
    public boolean reload(CommandSender sender) 
    {
        adminAction(sender, "<red>Reloading the admin list");
        plugin().al.load();
        plugin().csr.load();
        msg(sender, "<gray>Admin list reloaded!");
        return true;
    }

    @Callback
    @Subcommand("clean")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean clean(CommandSender sender) 
    {
        adminAction(sender, "<red>Cleaning admin list");
        plugin().al.deactivateOldEntries(true);
        getAdminList(sender);
        return true;
    }

    @Callback
    @Subcommand("list")
    public boolean list(CommandSender sender) 
    {
        getAdminList(sender);
        return true;
    }

    @Completer(value = "setrank", position = 0)
    public List<String> completeSetrankAdmin(CommandSender sender, String partial) 
    {
        return adminNames(partial);
    }

    @Completer(value = "setrank", position = 1)
    public List<String> completeSetrankRank(CommandSender sender, String partial) 
    {
        final List<String> candidates = plugin().rm.getCustomRanks()
                                                   .values()
                                                   .stream()
                                                   .filter(CustomRank::isAdmin)
                                                   .map(CustomRank::getId)
                                                   .toList();

        return FuzzyMatch.filter(candidates, partial);
    }

    @Completer(value = "info", position = 0)
    public List<String> completeInfoAdmin(CommandSender sender, String partial) 
    {
        return adminNames(partial);
    }

    @Completer(value = "remove", position = 0)
    public List<String> completeRemoveAdmin(CommandSender sender, String partial) 
    {
        return adminNames(partial);
    }

    @Completer(value = "add", position = 0)
    public List<String> completeAddPlayer(CommandSender sender, String partial) 
    {
        final List<String> names = server().getOnlinePlayers().stream()
            .map(Player::getName)
            .toList();
        return FuzzyMatch.filter(names, partial);
    }

    private List<String> adminNames(String partial)
    {
        final List<String> names = plugin().al.getActiveAdmins().stream()
            .map(Admin::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        return FuzzyMatch.filter(names, partial);
    }

    private void getAdminList(CommandSender sender)
    {
        final Set<Admin> activeAdmins = plugin().al.getActiveAdmins();
        if (activeAdmins.isEmpty())
        {
            msg(sender, "<gray>No active admins.");
            return;
        }

        // Group by the rank each admin actually holds. Ranks come from the registry rather than a
        // fixed set, so a custom defined rank gets its own line instead of being folded into a
        // tier that happens to sit near it.
        final Map<CustomRank, List<Admin>> byRank = activeAdmins.stream()
            .filter(admin -> plugin().rm.getCustomRank(admin.getRankId()) != null)
            .collect(Collectors.groupingBy(admin -> plugin().rm.getCustomRank(admin.getRankId()),
                     Collectors.toList()));

        byRank.keySet()
              .stream()
              .sorted(Comparator.comparingInt(CustomRank::getLevel).reversed())
              .forEach(rank ->
              {
                  final String joinedAdmins = byRank.get(rank)
                          .stream()
                          .sorted(Comparator.comparing(admin -> admin.getName().toLowerCase()))
                          .map(admin -> String.format("<white>%s", admin.getName()))
                          .collect(Collectors.joining("<white>, "));

                  msg(sender, String.format("<%s>%ss: %s",
                          rank.getColor().asHexString(), rank.getName(), joinedAdmins));
              });
    }

}
