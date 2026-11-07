package me.totalfreedom.totalfreedommod.cmd;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Completer;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.api.cmd.annotation.Subcommand;
import me.totalfreedom.api.rank.RankRole;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRegistry;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "saconfig", description = "Manage admins.",
        usage = "/<command> <list | clean | reload | setrank <username> <rank> | rekey <oldname> <player> | <add | remove | info> <username>>")
@Permission(permission = "tfm.admin.saconfig")
public class Command_saconfig extends FCommand
{

    @Callback
    @Subcommand("setrank")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public void setRank(CommandSender sender, Player target, String rankInput)
    {
        final CustomRank rank = plugin().ranks() == null ? null : plugin().ranks().getCustomRank(rankInput);

        if (rank == null)
        {
            msg(sender, "<red>Unknown rank: <rank>", Placeholder.unparsed("rank", rankInput));
            return;
        }

        if (!plugin().ranks().getRegistry().hasMarker(rank, RankRegistry.ADMIN_TAG))
        {
            msg(sender, "<red>Rank \"<rank>\" is not an admin rank.",
                    Placeholder.unparsed("rank", rank.getName()));
            return;
        }

        final PlayerData admin = plugin().players().getData(target);
        if (!admin.isStaff())
        {
            msg(sender, "<gray>Unknown admin: <player>",
                    Placeholder.unparsed("player", target.getName()));
            return;
        }

        adminAction(sender, "<red>Setting <player>'s rank to <rank>",
                Placeholder.unparsed("player", admin.getUsername()),
                Placeholder.unparsed("rank", rank.getName()));

        admin.setRankId(rank.getId());
        plugin().players().saveData(admin);
        plugin().ranks().updatePlayerTeam(target);
        target.updateCommands();

        msg(sender, "<gray>Set <player>'s rank to <rank>.",
                Placeholder.unparsed("player", admin.getUsername()),
                Placeholder.unparsed("rank", rank.getName()));
    }

    @Callback
    @Subcommand("info")
    public boolean getInfo(CommandSender sender, Player target)
    {
        final PlayerData admin = plugin().players().getData(target);

        if (!admin.isStaff())
        {
            msg(sender, "<gray>Admin not found: <player>",
                    Placeholder.unparsed("player", target.getName()));
            return true;
        }

        msg(sender, """
                <gray>Admin: <white><name>
                <gray>- UUID: <white><uuid>
                <gray>- Trusted IPs: <white><ips>
                <gray>- Rank: <white><rank>
                <gray>- Active: <white><active:yes:no>
                <gray>- Last join: <white><last_join>
                <gray>- Login message: <white><login>
                <gray>- Discord: <white><discord>\
                """,
                Placeholder.unparsed("name", admin.getUsername()),
                Placeholder.unparsed("uuid", admin.getUuid().toString()),
                Placeholder.unparsed("ips", admin.getTrustedIps().isEmpty() ? "none" : String.join(", ", admin.getTrustedIps())),
                Placeholder.unparsed("rank", admin.getRankId().orElse("none")),
                Formatter.booleanChoice("active", admin.isAdminActive()),
                Placeholder.unparsed("last_join", FUtil.dateToString(new Date(admin.getLastJoinUnix() * 1000L))),
                Placeholder.unparsed("login", admin.getLoginMessage().orElse("none")),
                Placeholder.unparsed("discord", admin.getDiscordIdentifier().orElse("not linked")));

        return true;
    }

    /**
     * Also the manual verification path: run from console against a live session, which is what
     * vouches for it, so this session's address joins the ones the identity check accepts.
     */
    @Callback
    @Subcommand("add")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean addUser(CommandSender sender, Player target)
    {
        if (plugin().ranks().isAdmin(target))
        {
            msg(sender, "<gray>That player is already admin.");
            return true;
        }

        final PlayerData admin = plugin().players().getData(target);
        final boolean newEntry = !admin.isStaff();

        // A new admin starts on whichever rank fills the default admin role.
        final Optional<String> rankId = admin.getRankId()
                                             .or(() -> plugin().ranks().getRegistry()
                                                               .byRole(RankRole.ADMIN_DEFAULT)
                                                               .map(CustomRank::getId));
        if (rankId.isEmpty())
        {
            msg(sender, "<red>No rank fills the default admin role; set one in ranks.json first.");
            return true;
        }

        adminAction(sender, "<red><new_entry:A:Re-a>dding <player> to the admin list",
                Formatter.booleanChoice("new_entry", newEntry),
                Placeholder.unparsed("player", target.getName()));

        target.setOp(true);

        final String ip = target.getAddress().getAddress().getHostAddress();
        admin.setRankId(rankId.get());
        admin.setIp(ip);
        admin.trustIp(ip);
        plugin().players().saveData(admin);
        target.updateCommands();

        if (plugin().ranks() != null)
        {
            plugin().ranks().updatePlayerTeam(target);
        }

        if (admin.getFreezeData().isFrozen())
        {
            admin.getFreezeData().setFrozen(false);
            msg(target, "<green>You have been unfrozen.");
        }

        return true;
    }

    /**
     * For an admin whose record no longer matches their account: a name change (offline, that's a
     * new UUID too), or a record keyed by name that join wouldn't hand over. Run against them while
     * they're online, which is what vouches for them. Their current record, including a second
     * staff record made by {@code add} in the meantime, is replaced.
     */
    @Callback
    @Subcommand("rekey")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean rekey(CommandSender sender, String oldName, Player target)
    {
        final Optional<PlayerData> staff = plugin().players().getStaffByName(oldName);
        if (staff.isEmpty())
        {
            msg(sender, "<gray>Admin not found: <player>", Placeholder.unparsed("player", oldName));
            return true;
        }

        final PlayerData record = staff.get();
        if (record.getUuid().equals(target.getUniqueId()))
        {
            msg(sender, "<gray><player> is already on that record.", Placeholder.unparsed("player", target.getName()));
            return true;
        }

        final Optional<Player> previousHolder = Optional.ofNullable(server().getPlayer(record.getUuid()));
        adminAction(sender, "<red>Moving <old>'s admin record to <player>",
                Placeholder.unparsed("old", record.getUsername()),
                Placeholder.unparsed("player", target.getName()));

        plugin().players().rekeyStaff(record, target);
        target.updateCommands();
        plugin().ranks().updatePlayerTeam(target);
        previousHolder.ifPresent(Player::updateCommands);
        previousHolder.ifPresent(plugin().ranks()::updatePlayerTeam);
        return true;
    }

    @Callback
    @Subcommand("remove")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean removeUser(CommandSender sender, String username)
    {
        final Optional<PlayerData> staff = plugin().players().getStaffByName(username);

        if (staff.isEmpty())
        {
            msg(sender, "<gray>Admin not found: <player>", Placeholder.unparsed("player", username));
            return true;
        }

        final PlayerData admin = staff.get();
        adminAction(sender, "<red>Removing <player> from the admin list",
                Placeholder.unparsed("player", admin.getUsername()));
        admin.setAdminActive(false);
        plugin().players().saveData(admin);

        final Player player = Bukkit.getPlayer(admin.getUuid());
        if (player != null && plugin().ranks() != null)
        {
            plugin().ranks().updatePlayerTeam(player);
            player.updateCommands();
        }

        return true;
    }

    @Callback
    @Subcommand("reload")
    public boolean reload(CommandSender sender)
    {
        adminAction(sender, "<red>Reloading the admin list");
        plugin().consoleSenders().load();
        plugin().players().reloadStaff();
        msg(sender, "<gray>Reloaded console senders and the staff list from storage. Online staff keep the record they joined with.");
        return true;
    }

    @Callback
    @Subcommand("clean")
    @Permission(permission = "tfm.manage.saconfig", source = SourceType.ONLY_CONSOLE)
    public boolean clean(CommandSender sender)
    {
        adminAction(sender, "<red>Cleaning admin list");
        plugin().players().deactivateOldStaff(true);
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
        final List<String> candidates = plugin().ranks().getCustomRanks()
                                                   .values()
                                                   .stream()
                                                   .filter(rank -> plugin().ranks().getRegistry().hasMarker(rank, RankRegistry.ADMIN_TAG))
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

    @Completer(value = "rekey", position = 0)
    public List<String> completeRekeyAdmin(CommandSender sender, String partial)
    {
        final List<String> names = plugin().players().getStaff()
                                           .stream()
                                           .map(PlayerData::getUsername)
                                           .distinct()
                                           .sorted(String.CASE_INSENSITIVE_ORDER)
                                           .toList();
        return FuzzyMatch.filter(names, partial);
    }

    @Completer(value = "rekey", position = 1)
    public List<String> completeRekeyPlayer(CommandSender sender, String partial)
    {
        return completeAddPlayer(sender, partial);
    }

    @Completer(value = "add", position = 0)
    public List<String> completeAddPlayer(CommandSender sender, String partial)
    {
        final List<String> names = server().getOnlinePlayers()
                                           .stream()
                                           .map(Player::getName)
                                           .toList();
        return FuzzyMatch.filter(names, partial);
    }

    private List<String> adminNames(String partial)
    {
        final List<String> names = activeStaff().stream()
                                                .map(PlayerData::getUsername)
                                                .sorted(String.CASE_INSENSITIVE_ORDER)
                                                .toList();
        return FuzzyMatch.filter(names, partial);
    }

    private void getAdminList(CommandSender sender)
    {
        final List<PlayerData> activeAdmins = activeStaff();
        if (activeAdmins.isEmpty())
        {
            msg(sender, "<gray>No active admins.");
            return;
        }

        // Group by the rank each admin actually holds. Ranks come from the registry rather than a
        // fixed set, so a custom defined rank gets its own line instead of being folded into a
        // tier that happens to sit near it.
        final Map<CustomRank, List<PlayerData>> byRank = activeAdmins.stream()
                                                                     .filter(admin -> heldRank(admin).isPresent())
                                                                     .collect(Collectors.groupingBy(admin -> heldRank(admin).orElseThrow()));

        byRank.keySet()
              .stream()
              .sorted(Comparator.comparingInt(CustomRank::getLevel).reversed())
              .forEach(rank ->
              {
                  final String joinedAdmins = byRank.get(rank)
                          .stream()
                          .sorted(Comparator.comparing(admin -> admin.getUsername().toLowerCase()))
                          .map(admin -> String.format("<white>%s", admin.getUsername()))
                          .collect(Collectors.joining("<white>, "));

                  msg(sender, String.format("<%s>%ss: %s",
                          rank.getColor().asHexString(), rank.getName(), joinedAdmins));
              });
    }

    private List<PlayerData> activeStaff()
    {
        return plugin().players().getStaff()
                       .stream()
                       .filter(PlayerData::isActiveStaff)
                       .toList();
    }

    private Optional<CustomRank> heldRank(PlayerData admin)
    {
        return admin.getRankId().map(plugin().ranks()::getCustomRank);
    }
}
