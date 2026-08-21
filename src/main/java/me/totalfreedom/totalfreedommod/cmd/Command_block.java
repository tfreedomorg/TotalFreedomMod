package me.totalfreedom.totalfreedommod.cmd;

import java.util.*;
import java.util.stream.Stream;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.*;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.player.PlayerBlockList;

@Command(
        name = "block",
        description = "Hide a player from your client.",
        usage = "/block [list [page] | clear | player <player> | <player>]",
        aliases = "ignore")
@Permission(permission = "tfm.player.block", source = SourceType.ONLY_IN_GAME)
public class Command_block extends FCommand
{
    private static final int MAX_COMPLETION_RESULTS = 100;
    private static final int PAGE_SIZE = 10;

    @Callback
    public void listDefault(final Player sender)
    {
        sendBlockList(sender, 1);
    }

    @Callback
    @Subcommand("list")
    public void list(final Player sender)
    {
        sendBlockList(sender, 1);
    }

    @Callback
    @Subcommand("list")
    public void listPage(final Player sender, final int page)
    {
        sendBlockList(sender, page);
    }

    @Callback
    @Subcommand("clear")
    public void clear(final Player sender)
    {
        final Optional<Integer> cleared = blockList().clear(sender.getUniqueId());
        if (cleared.isEmpty())
        {
            sendUnavailable(sender);
            return;
        }

        final int clearedCount = cleared.orElseThrow();
        msg(
                sender,
                "<green>Cleared <count> player<plural> from your block list.",
                Formatter.number("count", clearedCount),
                Placeholder.unparsed("plural", clearedCount == 1 ? "" : "s"));
    }

    @Callback
    public void toggle(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers") final OfflinePlayer target)
    {
        toggleTarget(sender, target);
    }

    @Callback
    @Subcommand("player")
    public void toggleExplicit(
            final Player sender,
            @Resolve(strategy = "hideUnknownPlayers") final OfflinePlayer target)
    {
        toggleTarget(sender, target);
    }

    @Completer(value = "", position = 0)
    public List<String> completeTarget(final Player sender, final String partial)
    {
        return completeTargetName(sender, partial);
    }

    @Completer(value = "player", position = 0)
    public List<String> completeExplicitTarget(final Player sender, final String partial)
    {
        return completeTargetName(sender, partial);
    }

    private static int pageCount(final int itemCount)
    {
        return itemCount == 0 ? 1 : ((itemCount - 1) / PAGE_SIZE) + 1;
    }

    private static boolean isValidPage(final int page, final int itemCount)
    {
        return page >= 1 && page <= pageCount(itemCount);
    }

    private static List<String> mergeTargetSuggestions(
            final Collection<String> onlinePlayerNames,
            final Collection<String> blockedPlayerNames,
            final String partial)
    {
        final Map<String, String> candidates = new LinkedHashMap<>();
        Stream.concat(onlinePlayerNames.stream(), blockedPlayerNames.stream())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(candidate -> candidates.putIfAbsent(
                        candidate.toLowerCase(Locale.ROOT),
                        candidate));
        return FuzzyMatch.filter(List.copyOf(candidates.values()), partial)
                .stream()
                .limit(MAX_COMPLETION_RESULTS)
                .toList();
    }

    private void toggleTarget(final Player sender, final OfflinePlayer target)
    {
        final UUID senderId = sender.getUniqueId();
        final UUID targetId = target.getUniqueId();
        if (senderId.equals(targetId))
        {
            msg(sender, "<red>You cannot block yourself.");
            return;
        }

        if (blockList().hasBlocked(senderId, targetId))
        {
            final Optional<Boolean> unblocked = blockList().unblock(senderId, targetId);
            if (unblocked.isEmpty())
            {
                sendUnavailable(sender);
                return;
            }
            if (!unblocked.orElseThrow())
            {
                msg(sender, "<gray><player> was already unblocked.",
                        Placeholder.unparsed("player", displayName(target)));
                return;
            }
            msg(
                    sender,
                    "<yellow>Removed <player> from your block list.",
                    Placeholder.unparsed("player", displayName(target)));
            return;
        }

        if (blockList().isProtectedTarget(target))
        {
            msg(sender, "<red>Admins, Developers, and the Owner cannot be blocked.");
            return;
        }

        final Optional<Boolean> blocked = blockList().block(senderId, targetId);
        if (blocked.isEmpty())
        {
            sendUnavailable(sender);
            return;
        }
        if (!blocked.orElseThrow())
        {
            msg(sender, "<gray><player> is already blocked.",
                    Placeholder.unparsed("player", displayName(target)));
            return;
        }
        msg(
                sender,
                "<green>Blocked <player>. They are hidden from you while online.",
                Placeholder.unparsed("player", displayName(target)));
    }

    private List<String> completeTargetName(final Player sender, final String partial)
    {
        if (!blockList().isAvailable())
            return List.of();

        final UUID senderId = sender.getUniqueId();
        final List<String> onlinePlayerNames = server().getOnlinePlayers()
                .stream()
                .filter(target -> blockList().hasBlocked(senderId, target.getUniqueId())
                        || !blockList().isProtectedTarget(target)
                        && sender.canSee(target)
                        && plugin().vanish().canSee(sender, target))
                .map(Player::getName)
                .toList();
        final List<String> blockedPlayerNames = blockList().getBlockedPlayers(senderId)
                .stream()
                .map(server()::getOfflinePlayer)
                .flatMap(player -> Optional.ofNullable(player.getName()).stream())
                .toList();
        return mergeTargetSuggestions(onlinePlayerNames, blockedPlayerNames, partial);
    }

    private void sendBlockList(final Player sender, final int page)
    {
        if (!requireAvailable(sender))
            return;

        final List<String> blockedPlayerNames = blockList().getBlockedPlayers(sender.getUniqueId())
                .stream()
                .map(server()::getOfflinePlayer)
                .map(this::displayName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        if (blockedPlayerNames.isEmpty())
        {
            msg(sender, "<gray>Your block list is empty.");
            return;
        }

        if (!isValidPage(page, blockedPlayerNames.size()))
        {
            msg(
                    sender,
                    "<red>Page must be between 1 and <pages>.",
                    Formatter.number("pages", pageCount(blockedPlayerNames.size())));
            return;
        }

        final int firstIndex = (page - 1) * PAGE_SIZE;
        final int lastIndex = Math.min(blockedPlayerNames.size(), firstIndex + PAGE_SIZE);
        final List<String> pageEntries = blockedPlayerNames.subList(firstIndex, lastIndex);
        msg(
                sender,
                "<gray>Your blocked players (<count>) - page <page>/<pages>: <white><players>",
                Formatter.number("count", blockedPlayerNames.size()),
                Formatter.number("page", page),
                Formatter.number("pages", pageCount(blockedPlayerNames.size())),
                Placeholder.unparsed("players", String.join(", ", pageEntries)));
    }

    private boolean requireAvailable(final Player sender)
    {
        if (blockList().isAvailable())
            return true;

        sendUnavailable(sender);
        return false;
    }

    private void sendUnavailable(final Player sender)
    {
        msg(
                sender,
                "<red>Player blocks are temporarily unavailable because their data could not be loaded or saved safely.");
    }

    private String displayName(final OfflinePlayer player)
    {
        return Optional.ofNullable(player.getName())
                .orElseGet(() -> player.getUniqueId().toString());
    }

    private PlayerBlockList blockList()
    {
        return plugin().services().require(PlayerBlockList.class);
    }
}
