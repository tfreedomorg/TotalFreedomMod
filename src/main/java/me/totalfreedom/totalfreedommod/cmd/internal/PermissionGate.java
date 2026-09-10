package me.totalfreedom.totalfreedommod.cmd.internal;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.SourceType;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.ssh.AttributedConsoleSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Enforces {@link Permission} declarations.
 */
public final class PermissionGate
{

    public static final Component ONLY_CONSOLE_MESSAGE =
        Component.text("This command can only be used from the console.", NamedTextColor.RED);
    public static final Component ONLY_PLAYER_MESSAGE =
        Component.text("This command can only be used by players.", NamedTextColor.RED);

    private PermissionGate() {}

    public static CommandSender resolveSender(CommandSender raw)
    {
        RemoteDispatchSession session = RemoteDispatchContext.getActiveSession();
        if (session != null && !(raw instanceof Player))
        {
            return new AttributedConsoleSender(raw, session.getDisplayName());
        }
        return raw;
    }

    /**
     * Tests whether {@code sender} may execute a handler guarded by {@code perm}.
     *
     * @param sendMsg whether to message the sender on failure (pass {@code false} for
     *                silent checks such as Brigadier {@code requires()} / tab visibility)
     */
    public static boolean test(TotalFreedomMod plugin, CommandSender sender, Permission perm, boolean sendMsg)
    {
        return test(plugin, sender, perm, null, sendMsg);
    }

    /**
     * Tests whether {@code sender} may execute a subcommand handler guarded by {@code perm},
     * declared inside a command whose class-level guard is {@code parent}.
     * <p>
     * When a handler repeats its parent's permission node, that node cannot tell the two apart:
     * anyone who passed the parent gate holds it already, so checking it again always succeeds
     * and a stricter {@code level()} on the handler is silently ignored. In that case the node is
     * skipped and {@link Permission#level()} decides, which is the only field that still
     * discriminates. The node itself is not lost - the parent's own gate already enforced it
     * before the sender could reach this handler.
     *
     * @param parent the class-level guard, or {@code null} when {@code perm} is itself the
     *               class-level guard and there is no outer scope to compare against
     */
    public static boolean test(TotalFreedomMod plugin, CommandSender sender, Permission perm, Permission parent, boolean sendMsg)
    {
        if (perm == null)
        {
            return true;
        }

        RemoteDispatchSession dispatch = RemoteDispatchContext.getActiveSession();
        if (dispatch != null)
        {
            if (dispatch.isIdentified())
            {
                String permNode = switch (dispatch.getChannel())
                {
                    case SSH -> "tfm.manage.ssh";
                    case DISCORD -> "tfm.manage.discord";
                };
                String channelLabel = switch (dispatch.getChannel())
                {
                    case SSH -> "SSH";
                    case DISCORD -> "Discord";
                };
                if (!plugin.rm.hasPermission(sender, permNode))
                {
                    if (sendMsg)
                    {
                        sender.sendMessage(Component.text("You do not have permission to run commands via " + channelLabel + ".", NamedTextColor.RED));
                    }
                    return false;
                }
            }
        }
        else if (!(sender instanceof Player) && plugin.al.getEntryByName(sender.getName()) != null)
        {
            if (!plugin.rm.hasPermission(sender, "tfm.manage.telnet"))
            {
                if (sendMsg)
                {
                    sender.sendMessage(Component.text("You do not have permission to run commands via telnet.", NamedTextColor.RED));
                }
                return false;
            }
        }

        final Player player = sender instanceof Player p ? p : null;

        if (perm.source() == SourceType.ONLY_CONSOLE && player != null)
        {
            if (sendMsg)
            {
                sender.sendMessage(ONLY_CONSOLE_MESSAGE);
            }
            return false;
        }

        if (perm.source() == SourceType.ONLY_IN_GAME && player == null)
        {
            if (sendMsg)
            {
                sender.sendMessage(ONLY_PLAYER_MESSAGE);
            }
            return false;
        }

        String tfmPermission = perm.permission();
        if (tfmPermission != null && !tfmPermission.isEmpty() && !repeatsParentNode(perm, parent)
                && plugin.rm.hasPermission(sender, tfmPermission))
        {
            return true;
        }

        if (player != null)
        {
            boolean result = plugin.rm.getRank(player).isAtLeast(perm.level());
            if (!result && sendMsg)
            {
                sender.sendMessage(Component.text(perm.message(), NamedTextColor.RED));
            }
            return result;
        }

        // getEffectiveRank resolves how this sender earned its rank: an identified SSH or Discord
        // session becomes that admin's own profile rank, custom rank included, while a host
        // channel becomes its host_senders binding. Falling back to getRank keeps senders it
        // cannot place working on the legacy scale.
        final CustomRank effective = plugin.rm.getEffectiveRank(sender);

        // A CustomRank's level is an operator-defined scale that need not line up with
        // Rank.ordinal(), so testing one straight against perm.level() can be off by a tier and
        // deny a sender its own rank. Resolve the requirement through the same registry the
        // sender's rank came from, so both sides are read off one scale.
        final CustomRank required = plugin.rm.getCustomRankForLegacy(perm.level());
        final boolean result = effective != null && required != null
            ? effective.isAtLeast(required)
            : plugin.rm.getRank(sender).isAtLeast(perm.level());
        if (!result && sendMsg)
        {
            sender.sendMessage(Component.text(perm.message(), NamedTextColor.RED));
        }
        return result;
    }

    /**
     * Whether {@code perm} is a handler-level guard that declares the same node as its parent.
     * <p>
     * The identity check matters: a handler with no {@code @Permission} of its own is handed the
     * class-level annotation itself, and that is not a repeat - there is no distinct handler
     * level to defer to, so the node check must stand.
     */
    private static boolean repeatsParentNode(Permission perm, Permission parent)
    {
        return parent != null
            && parent != perm
            && perm.permission().equals(parent.permission());
    }
}
