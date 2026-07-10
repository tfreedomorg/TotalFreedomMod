package me.totalfreedom.totalfreedommod.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.event.admin.AdminManagementEvent;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

@CommandPermissions(level = Rank.OP, source = SourceType.BOTH, permission = "tfm.manage.saconfig")
@CommandParameters(description = "Manage admins.", usage = "/<command> <list | clean | reload | | setrank <username> <rank> | <add | remove | info> <username>>")
public class Command_saconfig extends FreedomCommand
{
    @CommandDispatchTarget(pattern = "setrank <username> <rankInput>")
    public boolean setRank(CommandContext ctx, String username, String rankInput)
    {
        checkConsole();
        checkRank(Rank.SENIOR_ADMIN);

        final CustomRank custom = plugin.rm != null ? plugin.rm.getCustomRank(rankInput) : null;
        final Rank rank;
        final String displayName;

        if (custom != null)
        {
            if (custom.isConsoleOnly())
            {
                msg("You cannot set players to a console rank");
                return true;
            }
            if (!custom.isAdmin())
            {
                msg("Rank '" + custom.getName() + "' is not an admin rank.", NamedTextColor.RED);
                return true;
            }
            rank = resolveLegacyTier(custom);
            if (rank == null)
            {
                msg("Rank '" + custom.getName() + "' has no legacy tier; set or inherit from super_admin or senior_admin.", NamedTextColor.RED);
                return true;
            }
            displayName = custom.getName();
        }
        else
        {
            try
            {
                rank = Rank.valueOf(rankInput.toUpperCase());
            }
            catch (IllegalArgumentException ex)
            {
                msg("Unknown rank: " + rankInput, NamedTextColor.RED);
                return true;
            }
            if (rank.isConsole())
            {
                msg("You cannot set players to a console rank");
                return true;
            }
            displayName = rank.getName();
        }

        if (!rank.isAtLeast(Rank.SUPER_ADMIN))
        {
            msg("Rank must be superadmin or higher.", NamedTextColor.RED);
            return true;
        }

        Admin admin = plugin.al.getEntryByName(username);
        if (admin == null)
        {
            msg("Unknown admin: " + username);
            return true;
        }

        FUtil.adminAction(sender.getName(), "Setting " + admin.getName() + "'s rank to " + displayName, true);

        admin.setRank(rank);
        admin.setCustomRankId(custom != null ? custom.getId() : null);
        admin.fireRankUpdateEvent(ctx.getSender(), rank, custom);

        msg("Set " + admin.getName() + "'s rank to " + displayName);
        return true;
    }

    @CommandDispatchTarget(pattern = "info <username>")
    public boolean getInfo(CommandContext ctx, String username)
    {
        checkRank(Rank.SUPER_ADMIN);

        Admin admin = plugin.al.getEntryByName(username);

        if (admin == null)
        {
            final Player player = getPlayer(username);
            if (player != null)
            {
                admin = plugin.al.getAdmin(player);
            }
        }

        if (admin == null)
        {
            msg("Superadmin not found: " + username);
        }
        else
        {
            msg(admin.toString());
        }

        return true;
    }

    @CommandDispatchTarget(pattern = "add <username>")
    public boolean addUser(CommandContext ctx, String username)
    {
        checkConsole();
        checkRank(Rank.SENIOR_ADMIN);

        // Player already an admin?
        final Player player = getPlayer(username);
        if (player != null && plugin.al.isAdmin(player))
        {
            msg("That player is already admin.");
            return true;
        }

        // Find the old admin entry
        String name = player != null ? player.getName() : username;
        Admin admin = null;
        for (Admin loopAdmin : plugin.al.getAllAdmins().values())
        {
            if (loopAdmin.getName().equalsIgnoreCase(name))
            {
                admin = loopAdmin;
                break;
            }
        }

        if (admin == null) // New admin
        {
            if (player == null)
            {
                msg(FreedomCommand.PLAYER_NOT_FOUND);
                return true;
            }

            player.setOp(true);
            FUtil.adminAction(sender.getName(), "Adding " + player.getName() + " to the admin list", true);
            admin = new Admin(player);

            plugin.al.addAdmin(admin);
            admin.fireAddEvent(ctx.getSender(), true);
        }
        else // Existing admin
        {
            FUtil.adminAction(sender.getName(), "Re-adding " + admin.getName() + " to the admin list", true);

            if (player != null)
            {
                player.setOp(true);
                admin.setName(player.getName());
                admin.addIp(player.getAddress().getAddress().getHostAddress());
            }

            admin.setActive(true);
            admin.setLastLogin(new Date());
            admin.fireAddEvent(ctx.getSender(), false);
        }

        return true;
    }

    @CommandDispatchTarget(pattern = "remove <username>")
    public boolean removeUser(CommandContext ctx, String username)
    {
        checkConsole();
        checkRank(Rank.SENIOR_ADMIN);

        Player player = getPlayer(username);
        Admin admin = player != null ? plugin.al.getAdmin(player) : plugin.al.getEntryByName(username);

        if (admin == null)
        {
            msg("Superadmin not found: " + username);
            return true;
        }

        FUtil.adminAction(sender.getName(), "Removing " + admin.getName() + " from the admin list", true);
        admin.setActive(false);

        admin.fireGenericManagementEvent(ctx.getSender(), AdminManagementEvent.Action.REMOVE);
        return true;
    }

    @CommandDispatchTarget(pattern = "reload")
    public boolean reload(CommandContext ctx)
    {
        checkRank(Rank.SUPER_ADMIN);

        FUtil.adminAction(sender.getName(), "Reloading the admin list", true);
        plugin.al.load();
        plugin.csr.load();
        msg("Admin list reloaded!");
        return true;
    }

    @CommandDispatchTarget(pattern = "clean")
    public boolean clean(CommandContext ctx)
    {
        checkConsole();
        checkRank(Rank.SENIOR_ADMIN);

        FUtil.adminAction(sender.getName(), "Cleaning admin list", true);
        plugin.al.deactivateOldEntries(true);
        getAdminList();
        return true;
    }

    @CommandDispatchTarget(pattern = "list")
    public boolean list(CommandContext ctx)
    {
        getAdminList();
        return true;
    }

    @Override
    public boolean run(CommandSender sender, Player playerSender, Command cmd, String commandLabel, String[] args, boolean senderIsConsole)
    {
        return false;
    }

    private void getAdminList()
    {
        if (plugin.al.getActiveAdmins().isEmpty())
        {
            msg("No active admins.", NamedTextColor.GRAY);
            return;
        }

        Map<Rank, List<Admin>> byRank = new EnumMap<>(Rank.class);
        for (Admin admin : plugin.al.getActiveAdmins())
        {
            byRank.computeIfAbsent(admin.getRank(), r -> new ArrayList<>()).add(admin);
        }

        List<Rank> order = new ArrayList<>();
        for (Rank r : Rank.values())
        {
            if (r.isAdmin() && !r.isConsole())
            {
                order.add(r);
            }
        }
        order.sort(Comparator.comparingInt(Rank::getLevel).reversed());

        for (Rank rank : order)
        {
            List<Admin> bucket = byRank.get(rank);
            if (bucket == null || bucket.isEmpty())
            {
                continue;
            }

            bucket.sort(Comparator.comparing(a -> a.getName().toLowerCase()));

            Component line = Component.text(rank.getName() + "s: ").color(rank.getColor());
            for (int i = 0; i < bucket.size(); i++)
            {
                if (i > 0)
                {
                    line = line.append(Component.text(", ").color(NamedTextColor.WHITE));
                }
                Admin admin = bucket.get(i);
                CustomRank customRank = admin.getCustomRankId() == null
                        ? null
                        : plugin.rm.getCustomRank(admin.getCustomRankId());
                if (customRank != null)
                {
                    line = line.append(customRank.getColoredTag())
                            .append(Component.text(" "))
                            .append(Component.text(admin.getName()).color(NamedTextColor.WHITE));
                }
                else
                {
                    line = line.append(Component.text(admin.getName()).color(NamedTextColor.WHITE));
                }
            }
            msg(line);
        }
    }

    private Rank resolveLegacyTier(CustomRank custom)
    {
        CustomRank current = custom;
        int safety = 32;
        while (current != null && safety-- > 0)
        {
            try
            {
                return Rank.valueOf(current.getId().toUpperCase());
            }
            catch (IllegalArgumentException ignored)
            {
            }

            String parentId = current.getInheritFrom();
            if (parentId == null)
            {
                return null;
            }
            current = plugin.rm.getCustomRank(parentId);
        }
        return null;
    }

}
