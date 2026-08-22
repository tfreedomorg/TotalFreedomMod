package me.totalfreedom.totalfreedommod.cmd.internal;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.cmd.SourceType;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.ssh.AttributedConsoleSender;

/**
 * Enforces {@link Permission} declarations.
 * <p>
 * A declaration carries only a permission node, never a rank. The tier a node requires is derived
 * from {@code ranks.json}, so this class does not compare rank levels itself: it asks
 * {@code RankManager} whether the sender satisfies the node and lets the rank registry decide what
 * that means. A handler that needs to be stricter than its class does so by declaring a distinct
 * node, which removes the old special case where a repeated node had to be ignored so that a
 * stricter {@code level()} could take over.
 */
public final class PermissionGate
{

    public static final Component ONLY_CONSOLE_MESSAGE =
        Component.text("This command can only be used from the console.", NamedTextColor.RED);
    public static final Component ONLY_PLAYER_MESSAGE =
        Component.text("This command can only be used by players.", NamedTextColor.RED);

    private PermissionGate() {}

    public static CommandSender resolveSender(final CommandSender raw)
    {
        final RemoteDispatchSession session = RemoteDispatchContext.getActiveSession();
        if (session != null && !(raw instanceof Player))
            return new AttributedConsoleSender(raw, session.getDisplayName());

        return raw;
    }

    /**
     * Tests whether {@code sender} may execute a handler guarded by {@code perm}.
     *
     * @param sendMsg whether to message the sender on failure (pass {@code false} for silent checks
     *                such as Brigadier {@code requires()} / tab visibility)
     */
    public static boolean test(final TotalFreedomMod plugin, final CommandSender sender,
                               final Permission perm, final boolean sendMsg)
    {
        if (perm == null)
            return true;

        if (!passesChannelGate(plugin, sender, sendMsg))
            return false;

        if (!passesSourceGate(sender, perm, sendMsg))
            return false;

        final boolean allowed = plugin.rm.hasPermission(sender, perm.permission());
        if (!allowed && sendMsg)
            sender.sendMessage(Component.text(perm.message(), NamedTextColor.RED));

        return allowed;
    }

    /**
     * Whether the sender is permitted to issue commands over the channel it arrived on at all,
     * independently of what the command itself requires.
     */
    private static boolean passesChannelGate(final TotalFreedomMod plugin, final CommandSender sender,
                                             final boolean sendMsg)
    {
        final RemoteDispatchSession dispatch = RemoteDispatchContext.getActiveSession();

        if (dispatch != null)
        {
            if (!dispatch.isIdentified())
                return true;

            final String node = switch (dispatch.getChannel())
            {
                case SSH -> "tfm.manage.ssh";
                case DISCORD -> "tfm.manage.discord";
            };
            final String label = switch (dispatch.getChannel())
            {
                case SSH -> "SSH";
                case DISCORD -> "Discord";
            };

            return requireChannelNode(plugin, sender, node, label, sendMsg);
        }

        if (!(sender instanceof Player) && plugin.al.getEntryByName(sender.getName()) != null)
            return requireChannelNode(plugin, sender, "tfm.manage.telnet", "telnet", sendMsg);

        return true;
    }

    private static boolean requireChannelNode(final TotalFreedomMod plugin, final CommandSender sender,
                                              final String node, final String label, final boolean sendMsg)
    {
        if (plugin.rm.hasPermission(sender, node))
            return true;

        if (sendMsg)
            sender.sendMessage(Component.text(
                String.format("You do not have permission to run commands via %s.", label),
                NamedTextColor.RED));

        return false;
    }

    private static boolean passesSourceGate(final CommandSender sender, final Permission perm,
                                            final boolean sendMsg)
    {
        final boolean isPlayer = sender instanceof Player;

        if (perm.source() == SourceType.ONLY_CONSOLE && isPlayer)
        {
            if (sendMsg)
                sender.sendMessage(ONLY_CONSOLE_MESSAGE);

            return false;
        }

        if (perm.source() == SourceType.ONLY_IN_GAME && !isPlayer)
        {
            if (sendMsg)
                sender.sendMessage(ONLY_PLAYER_MESSAGE);

            return false;
        }

        return true;
    }

}
