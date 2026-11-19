package me.totalfreedom.totalfreedommod.cmd.internal;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.ssh.AttributedConsoleSender;

/**
 * Enforces {@link Permission} declarations.
 * <p>
 * A declaration carries only a permission node, never a rank. This class does not compare rank
 * levels: it asks {@code RankManager} whether the sender's rank holds the node. A handler that
 * needs to be stricter than its class does so by declaring a distinct node.
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
    public static boolean test(final FreedomAPI plugin, final CommandSender sender,
                               final Permission perm, final boolean sendMsg)
    {
        if (perm == null)
            return true;

        if (!passesChannelGate(plugin, sender, sendMsg))
            return false;

        if (!passesSourceGate(sender, perm, sendMsg))
            return false;

        final boolean allowed = plugin.ranks().hasPermission(sender, perm.permission());
        if (!allowed && sendMsg)
            sender.sendMessage(Component.text(perm.message(), NamedTextColor.RED));

        return allowed;
    }

    /**
     * Whether the sender is permitted to issue commands over the channel it arrived on at all,
     * independently of what the command itself requires.
     */
    private static boolean passesChannelGate(final FreedomAPI plugin, final CommandSender sender,
                                             final boolean sendMsg)
    {
        final RemoteDispatchSession dispatch = RemoteDispatchContext.getActiveSession();

        if (dispatch != null)
        {
            if (!dispatch.isIdentified() || dispatch.getChannel() == RemoteDispatchSession.Channel.DISCORD)
                return true;

            return requireChannelNode(plugin, sender, "tfm.manage.ssh", "SSH", sendMsg);
        }

        if (!(sender instanceof Player) && plugin.players().getStaffByName(sender.getName()).isPresent())
            return requireChannelNode(plugin, sender, "tfm.manage.telnet", "telnet", sendMsg);

        return true;
    }

    private static boolean requireChannelNode(final FreedomAPI plugin, final CommandSender sender,
                                              final String node, final String label, final boolean sendMsg)
    {
        if (plugin.ranks().hasPermission(sender, node))
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
        if (matchesSource(sender, perm.source()))
            return true;

        if (sendMsg)
        {
            sender.sendMessage(perm.source() == SourceType.ONLY_CONSOLE ? ONLY_CONSOLE_MESSAGE : ONLY_PLAYER_MESSAGE);
        }

        return false;
    }

    /**
     * Whether {@code sender}'s type satisfies {@code source}. Shared with call sites outside the
     * {@link Permission} annotation framework, such as {@code Command_settings}, so a source-only
     * restriction is checked the same way everywhere rather than each site re-deriving it from
     * {@code instanceof Player}.
     */
    public static boolean matchesSource(final CommandSender sender, final SourceType source)
    {
        final boolean isPlayer = sender instanceof Player;

        if (source == SourceType.ONLY_CONSOLE && isPlayer)
            return false;

        return source != SourceType.ONLY_IN_GAME || isPlayer;
    }

}
