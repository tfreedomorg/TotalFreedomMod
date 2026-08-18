package me.totalfreedom.totalfreedommod.cmd;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Callback;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Command;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Completer;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Permission;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Resolve;
import me.totalfreedom.totalfreedommod.cmd.internal.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.rank.Rank;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

@Command(
        name = "block",
        description = "Hide another player and prevent interaction with them. In-game only; does not hide shared Discord relay messages.",
        usage = "/block [list [page] | clear | player <player> | inspect <player> [page] | force <block|unblock> <blocker> <target> | <player>]")
@Permission(permission = "tfm.player.block", level = Rank.NON_OP, source = SourceType.ONLY_IN_GAME)
public class Command_block extends FCommand
{
    private static final int PAGE_SIZE = 10;
    private static final int MAX_COMPLETION_RESULTS = 100;

    @Callback
    public void listDefault(final Player sender)
    {
        sendOwnBlockList(sender, 1);
    }

    @Callback
    @Subcommand("list")
    public void list(final Player sender)
    {
        sendOwnBlockList(sender, 1);
    }

    @Callback
    @Subcommand("list")
    public void listPage(final Player sender, final int page)
    {
        sendOwnBlockList(sender, page);
    }

    @Callback
    @Subcommand("clear")
    public void clear(final Player sender)
    {
        final UUID senderId = sender.getUniqueId();
        final Set<UUID> blockedPlayers = plugin().pbl.getBlockedPlayers(senderId);
        blockedPlayers.forEach(targetId -> plugin().pbe.beginSeparationGrace(senderId, targetId));
        final int cleared = plugin().pbl.clear(senderId);
        plugin().pbe.clearBlockedIdentities(senderId, blockedPlayers);

        blockedPlayers.forEach(targetId ->
        {
            final Player target = server().getPlayer(targetId);
            if (target != null)
                plugin().pbe.refreshVisibility(sender, target);
        });

        blockMsg(sender, "<green>Cleared <count> player<plural> from your block list.",
                Formatter.number("count", cleared),
                Placeholder.unparsed("plural", cleared == 1 ? "" : "s"));
    }

    @Callback
    public void toggle(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers,allowBlockedHidden,cachedOnly") final OfflinePlayer target)
    {
        togglePlayer(sender, target);
    }

    @Callback
    @Subcommand("player")
    public void toggleExplicit(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers,allowBlockedHidden,cachedOnly") final OfflinePlayer target)
    {
        togglePlayer(sender, target);
    }

    @Callback
    @Subcommand("inspect")
    @Permission(
            permission = "tfm.admin.block.inspect",
            level = Rank.SUPER_ADMIN,
            source = SourceType.ONLY_IN_GAME)
    public void inspect(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers,cachedOnly") final OfflinePlayer blocker)
    {
        sendInspectedBlockList(sender, blocker, 1);
    }

    @Callback
    @Subcommand("inspect")
    @Permission(
            permission = "tfm.admin.block.inspect",
            level = Rank.SUPER_ADMIN,
            source = SourceType.ONLY_IN_GAME)
    public void inspectPage(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers,cachedOnly") final OfflinePlayer blocker,
            final int page)
    {
        sendInspectedBlockList(sender, blocker, page);
    }

    @Callback
    @Subcommand("force block")
    @Permission(
            permission = "tfm.admin.block.modify",
            level = Rank.SUPER_ADMIN,
            source = SourceType.ONLY_IN_GAME)
    public void forceBlock(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers,cachedOnly") final OfflinePlayer blocker,
            @Resolve(strategy = "hideUnknownPlayers,cachedOnly") final OfflinePlayer target)
    {
        final UUID blockerId = blocker.getUniqueId();
        final UUID targetId = target.getUniqueId();
        if (blockerId.equals(targetId))
        {
            blockMsg(sender, "<red>A player cannot block themselves.");
            return;
        }

        if (isProtectedAdmin(target))
        {
            blockMsg(sender, "<red>Admins, Developers, and the Owner cannot be blocked.");
            return;
        }

        if (!plugin().pbl.block(blockerId, targetId))
        {
            blockMsg(sender, "<gray><blocker> already blocks <target>; no change was made.",
                    Placeholder.unparsed("blocker", displayName(blocker)),
                    Placeholder.unparsed("target", displayName(target)));
            return;
        }

        plugin().pbe.addBlockedIdentity(blockerId, targetId);
        refreshVisibility(blocker, target);
        FLog.info(String.format(
                "%s force-blocked %s for %s",
                sender.getName(),
                displayName(target),
                displayName(blocker)));
        blockMsg(sender, "<green>Block override: <blocker> now blocks <target>. <gray>In-game only; Discord relay messages are unaffected.",
                Placeholder.unparsed("blocker", displayName(blocker)),
                Placeholder.unparsed("target", displayName(target)));
    }

    @Callback
    @Subcommand("force unblock")
    @Permission(
            permission = "tfm.admin.block.modify",
            level = Rank.SUPER_ADMIN,
            source = SourceType.ONLY_IN_GAME)
    public void forceUnblock(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers,cachedOnly") final OfflinePlayer blocker,
            @Resolve(strategy = "hideUnknownPlayers,cachedOnly") final OfflinePlayer target)
    {
        final UUID blockerId = blocker.getUniqueId();
        final UUID targetId = target.getUniqueId();
        final boolean removed = plugin().pbl.unblock(blockerId, targetId);
        plugin().pbe.clearSeparationGrace(blockerId, targetId);
        plugin().pbe.removeBlockedIdentity(blockerId, targetId);

        if (!removed)
        {
            blockMsg(sender, "<gray><blocker> did not block <target>; stale separation state was cleared.",
                    Placeholder.unparsed("blocker", displayName(blocker)),
                    Placeholder.unparsed("target", displayName(target)));
            return;
        }

        refreshVisibility(blocker, target);
        FLog.info(String.format(
                "%s force-unblocked %s for %s",
                sender.getName(),
                displayName(target),
                displayName(blocker)));
        blockMsg(sender, "<green>Block override: removed <target> from <blocker>'s block list.",
                Placeholder.unparsed("blocker", displayName(blocker)),
                Placeholder.unparsed("target", displayName(target)));
    }

    @Completer(value = "", position = 0)
    public List<String> completeToggleTarget(final Player sender, final String partial)
    {
        return blockTargetSuggestions(sender, partial);
    }

    @Completer(value = "player", position = 0)
    public List<String> completeExplicitTarget(final Player sender, final String partial)
    {
        return blockTargetSuggestions(sender, partial);
    }

    static int pageCount(final int itemCount)
    {
        return itemCount == 0 ? 1 : ((itemCount - 1) / PAGE_SIZE) + 1;
    }

    static boolean isValidPage(final int page, final int itemCount)
    {
        return page >= 1 && page <= pageCount(itemCount);
    }

    static <T> List<T> pageItems(final List<T> items, final int page)
    {
        final int start = (page - 1) * PAGE_SIZE;
        final int end = Math.min(start + PAGE_SIZE, items.size());
        return List.copyOf(items.subList(start, end));
    }

    private void togglePlayer(final Player sender, final OfflinePlayer target)
    {
        final UUID senderId = sender.getUniqueId();
        final UUID targetId = target.getUniqueId();
        if (senderId.equals(targetId))
        {
            blockMsg(sender, "<red>You cannot block yourself.");
            return;
        }

        if (plugin().pbl.hasBlocked(senderId, targetId))
        {
            plugin().pbe.beginSeparationGrace(senderId, targetId);
            plugin().pbl.unblock(senderId, targetId);
            plugin().pbe.removeBlockedIdentity(senderId, targetId);
            refreshVisibility(sender, target);
            blockMsg(sender, "<yellow>Unblocked <player>. They are visible to you again.",
                    Placeholder.unparsed("player", displayName(target)));
            return;
        }

        if (isProtectedAdmin(target))
        {
            blockMsg(sender, "<red>Admins, Developers, and the Owner cannot be blocked.");
            return;
        }

        plugin().pbl.block(senderId, targetId);
        plugin().pbe.addBlockedIdentity(senderId, targetId);
        refreshVisibility(sender, target);
        blockMsg(sender, "<green>Blocked <player>. <gray>This is in-game only and does not hide shared Discord relay messages.",
                Placeholder.unparsed("player", displayName(target)));
    }

    private void sendOwnBlockList(final Player sender, final int page)
    {
        sendBlockList(sender, sender.getUniqueId(), "Your", page);
    }

    private void sendInspectedBlockList(
            final Player sender,
            final OfflinePlayer blocker,
            final int page)
    {
        sendBlockList(sender, blocker.getUniqueId(), String.format("%s's", displayName(blocker)), page);
    }

    private void sendBlockList(
            final Player sender,
            final UUID blocker,
            final String ownerLabel,
            final int page)
    {
        final int blockedPlayerCount = plugin().pbl.getBlockedPlayerCount(blocker);

        if (blockedPlayerCount == 0)
        {
            blockMsg(sender, "<gray><owner> block list is empty.",
                    Placeholder.unparsed("owner", ownerLabel));
            return;
        }

        if (!isValidPage(page, blockedPlayerCount))
        {
            blockMsg(sender, "<red>Page must be between 1 and <pages>.",
                    Formatter.number("pages", pageCount(blockedPlayerCount)));
            return;
        }

        final List<String> blockedNames = plugin().pbl.getBlockedPlayerPage(
                        blocker,
                        (page - 1) * PAGE_SIZE,
                        PAGE_SIZE)
                .stream()
                .map(server()::getOfflinePlayer)
                .map(this::displayName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        blockMsg(sender, "<gray><owner> blocked players (<count>) - page <page>/<pages>: <white><players>",
                Placeholder.unparsed("owner", ownerLabel),
                Formatter.number("count", blockedPlayerCount),
                Formatter.number("page", page),
                Formatter.number("pages", pageCount(blockedPlayerCount)),
                Placeholder.unparsed("players", String.join(", ", blockedNames)));
    }

    private List<String> blockTargetSuggestions(final Player sender, final String partial)
    {
        final UUID senderId = sender.getUniqueId();
        final Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        server().getOnlinePlayers()
                .stream()
                .filter(target -> plugin().al.isAdmin(sender)
                        || sender.canSee(target)
                        || plugin().pbl.hasBlocked(senderId, target.getUniqueId()))
                .map(Player::getName)
                .forEach(candidates::add);
        plugin().pbl.getBlockedPlayerPage(senderId, 0, MAX_COMPLETION_RESULTS)
                .stream()
                .map(server()::getOfflinePlayer)
                .map(this::displayName)
                .forEach(candidates::add);

        return FuzzyMatch.filter(List.copyOf(candidates), partial, MAX_COMPLETION_RESULTS);
    }

    private void refreshVisibility(final OfflinePlayer blocker, final OfflinePlayer target)
    {
        final Player onlineBlocker = blocker.getPlayer();
        final Player onlineTarget = target.getPlayer();
        if (onlineBlocker != null && onlineTarget != null)
            plugin().pbe.refreshVisibility(onlineBlocker, onlineTarget);
    }

    private boolean isProtectedAdmin(final OfflinePlayer player)
    {
        final Player onlinePlayer = player.getPlayer();
        if (onlinePlayer != null && plugin().al.isAdmin(onlinePlayer))
            return true;

        final Admin uuidAdmin = plugin().al.getAdminByUuid(player.getUniqueId());
        if (uuidAdmin != null && uuidAdmin.isActive())
            return true;

        final String name = player.getName();
        if (name == null)
            return false;

        if (ConfigEntry.SERVER_OWNERS.getStringList()
                .stream()
                .anyMatch(owner -> owner.equalsIgnoreCase(name)))
            return true;

        if (FUtil.DEVELOPERS
                .stream()
                .anyMatch(developer -> developer.equalsIgnoreCase(name)))
            return true;

        final Admin namedAdmin = plugin().al.getEntryByName(name);
        return namedAdmin != null && namedAdmin.isActive();
    }

    private String displayName(final OfflinePlayer player)
    {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString();
    }

    private void blockMsg(
            final Player sender,
            final String miniMessage,
            final TagResolver... resolvers)
    {
        plugin().pbe.sendIdentityManagementMessage(sender, MessageUtils.parse(miniMessage, resolvers));
    }
}
