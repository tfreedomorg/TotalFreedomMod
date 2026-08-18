package me.totalfreedom.totalfreedommod.cmd.resolver;

import me.totalfreedom.totalfreedommod.PluginProvider;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PlayerVisibilityPolicy
{
    private PlayerVisibilityPolicy()
    {
    }

    public static boolean canExpose(
            final CommandSender sender,
            final Player target,
            final boolean allowHiddenTarget)
    {
        if (!(sender instanceof Player viewer))
            return true;

        final boolean activeStaff = PluginProvider.get().al.isAdmin(viewer);
        return shouldExpose(activeStaff, viewer.canSee(target), allowHiddenTarget);
    }

    public static boolean canExpose(
            final CommandSender sender,
            final OfflinePlayer target,
            final boolean allowHiddenTarget)
    {
        final Player onlineTarget = target.getPlayer();
        if (onlineTarget != null)
            return canExpose(sender, onlineTarget, allowHiddenTarget);

        if (!(sender instanceof final Player viewer))
            return true;

        final boolean activeStaff = PluginProvider.get().al.isAdmin(viewer);
        final boolean blockedIdentity = PluginProvider.get().pbe != null
                && PluginProvider.get().pbe.isBlocked(viewer.getUniqueId(), target.getUniqueId());
        return shouldExpose(activeStaff, !blockedIdentity, allowHiddenTarget);
    }

    static boolean shouldExpose(
            final boolean activeStaff,
            final boolean visible,
            final boolean allowHiddenTarget)
    {
        return activeStaff || visible || allowHiddenTarget;
    }
}
