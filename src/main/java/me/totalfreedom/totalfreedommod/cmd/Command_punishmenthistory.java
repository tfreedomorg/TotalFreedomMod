package me.totalfreedom.totalfreedommod.cmd;

import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.api.cmd.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.banning.PunishmentPoints;
import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;
import me.totalfreedom.totalfreedommod.sql.adapter.PunishmentHistoryRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Command(name = "punishmenthistory", description = "Shows a player's punishment history.", usage = "/<command> <player|ip> [page]", aliases = {"ph"})
@Permission(permission = "tfm.admin.punishmenthistory")
public class Command_punishmenthistory extends FCommand
{
    private static final int ENTRIES_PER_PAGE = 10;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yy").withLocale(Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm").withLocale(Locale.US).withZone(ZoneOffset.UTC);

    @Callback
    public void showFirstPage(CommandSender sender, String target)
    {
        showPage(sender, target, 1);
    }

    /**
     * Revokes an entry on a successful appeal. The entry stays in the history, marked as appealed,
     * and stops counting toward punishment points. Lifting the punishment itself (an unban, say)
     * doesn't do this; only an appeal does.
     */
    @Callback
    @Subcommand("revoke")
    @Permission(permission = "tfm.admin.senior.revokepunishment")
    public void revoke(CommandSender sender, int id)
    {
        if (!plugin().database().isInitialized())
        {
            msg(sender, "<gray>Punishment history is unavailable because the SQL database is not initialized.");
            return;
        }

        final PunishmentHistoryRepository repo = plugin().database().getPunishmentHistoryRepository();
        async(task ->
        {
            try
            {
                final Optional<PunishmentHistoryEntry> entry = repo.findById(id);
                if (entry.isEmpty())
                {
                    sync(() -> msg(sender, "<red>There is no punishment #<id>.", Formatter.number("id", id)), 0L);
                    return;
                }

                if (!repo.revoke(id, sender.getName(), System.currentTimeMillis()))
                {
                    sync(() -> msg(sender, "<gray>Punishment #<id> was already revoked.", Formatter.number("id", id)), 0L);
                    return;
                }

                sync(() -> adminAction(sender, "<red>Revoking punishment #<id> (<type> on <target>) on appeal",
                        Formatter.number("id", id),
                        Placeholder.unparsed("type", safe(entry.get().punishmentType(), "UNKNOWN")),
                        Placeholder.unparsed("target", safe(entry.get().targetName(), "(unknown)"))), 0L);
            }
            catch (SQLException | IllegalStateException ex)
            {
                plugin().getLogger().warning("Revoking punishment #" + id + " failed: " + ex.getMessage());
                sync(() -> msg(sender, "<red>Could not revoke that punishment. Please try again later."), 0L);
            }
        }, 0L);
    }

    @Callback
    public void showPage(CommandSender sender, String target, int page)
    {
        if (target == null || target.isBlank())
        {
            msg(sender, "<gray>Usage: /punishmenthistory <player|ip> [page]");
            return;
        }

        if (!plugin().database().isInitialized())
        {
            msg(sender, "<gray>Punishment history is unavailable because the SQL database is not initialized.");
            return;
        }

        if (page < 1)
        {
            msg(sender, "<red>Invalid page. Please choose a page greater than zero.");
            return;
        }

        final String normalizedIp = normalizeIpLiteral(target);
        final boolean ipSearch = normalizedIp != null;
        final String searchTarget = ipSearch ? normalizedIp : target;
        final PunishmentHistoryRepository repo = plugin().database().getPunishmentHistoryRepository();

        async(task ->
        {
            try
            {
                final int totalRecords = ipSearch ? repo.countByIp(searchTarget) : repo.countByTarget(searchTarget);
                final int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / ENTRIES_PER_PAGE));

                if (page > totalPages)
                {
                    sync(() -> msg(sender,
                            "<red>Invalid page. Please choose a page from 1 to <total>.",
                            Formatter.number("total", totalPages)), 0L);
                    return;
                }

                final List<PunishmentHistoryEntry> entries = ipSearch
                        ? repo.findByIp(searchTarget, page, ENTRIES_PER_PAGE)
                        : repo.findByTarget(searchTarget, page, ENTRIES_PER_PAGE);

                // Points belong to a player's record, so there's nothing to total for an address.
                final Optional<UUID> targetUuid = ipSearch
                        ? Optional.empty()
                        : Optional.ofNullable(plugin().players().getData(searchTarget)).map(PlayerData::getUuid);
                final Optional<Integer> points = targetUuid.isEmpty()
                        ? Optional.empty()
                        : Optional.of(PunishmentPoints.total(repo.findCountable(targetUuid.get().toString(), PunishmentPoints.windowStart())));

                sync(() -> sendHistoryPage(sender, target, page, totalPages, entries, points), 0L);
            }
            catch (SQLException | IllegalStateException ex)
            {
                plugin().getLogger().warning("Punishment history lookup failed for target '" + target + "': " + ex.getMessage());
                sync(() ->
                {
                    msg(sender, "<red>Punishment history lookup failed. Please try again later.");
                }, 0L);
            }
        }, 0L);
    }

    private void sendHistoryPage(CommandSender sender, String target, int page, int totalPages, List<PunishmentHistoryEntry> entries,
                                 Optional<Integer> points)
    {
        if (entries.isEmpty())
        {
            msg(sender, "<gray>No punishment history found for <white><target>.", Placeholder.unparsed("target", target));
            return;
        }

        msg(sender, "<dark_red>History for <white><target><dark_red> (<page>/<total>, UTC)",
                Placeholder.unparsed("target", target),
                Formatter.number("page", page),
                Formatter.number("total", totalPages));

        points.ifPresent(total -> msg(sender, "<gray>Punishment points: <color><points></color>/<threshold>",
                Placeholder.styling("color", total >= PunishmentPoints.REVIEW_THRESHOLD ? NamedTextColor.RED : NamedTextColor.WHITE),
                Formatter.number("points", total),
                Formatter.number("threshold", PunishmentPoints.REVIEW_THRESHOLD)));

        for (int index = 0; index < entries.size(); index++)
        {
            sendHistoryEntry(sender, entries.get(index), (page - 1) * ENTRIES_PER_PAGE + index + 1);
        }

        sendNavigation(sender, target, page, totalPages);
    }

    private void sendHistoryEntry(CommandSender sender, PunishmentHistoryEntry entry, int number)
    {
        final String actor = safeActor(entry.actorName());
        final String type = safe(entry.punishmentType(), "UNKNOWN");
        final Component details = buildHoverDetails(entry, actor, type);
        final Component summary = Component.text(number + ". ", NamedTextColor.DARK_GRAY)
                .append(Component.text(formatDate(entry.appliedAt()) + " ", NamedTextColor.GRAY))
                .append(Component.text(type + " ", NamedTextColor.WHITE))
                .append(Component.text("by ", NamedTextColor.DARK_GRAY))
                .append(coloredActor(actor))
                .append(entry.isRevoked() ? Component.text(" (appealed)", NamedTextColor.GREEN) : Component.empty())
                .hoverEvent(HoverEvent.showText(details));

        sender.sendMessage(summary);
    }

    private Component buildHoverDetails(PunishmentHistoryEntry entry, String actor, String type)
    {
        Component details = Component.text("Punishment details", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(detailLine("ID", String.valueOf(entry.id())))
                .append(detailLine("Type", type))
                .append(detailLine("Target", safe(entry.targetName(), "(unknown)")))
                .append(detailLine("Issued by", actor))
                .append(detailLine("Applied", formatDateTime(entry.appliedAt())))
                .append(detailLine("Expires", entry.hasExpiry() ? formatDateTime(entry.expiryAt()) : "Never"));

        if (entry.targetUuid() != null && !entry.targetUuid().isBlank())
        {
            details = details.append(detailLine("UUID", entry.targetUuid()));
        }

        if (entry.reason() != null && !entry.reason().isBlank())
        {
            details = details.append(detailLine("Reason", entry.reason()));
        }

        if (entry.source() != null && !entry.source().isBlank())
        {
            details = details.append(detailLine("Source", entry.source()));
        }

        if (entry.isRevoked())
        {
            details = details.append(detailLine("Appealed", String.format("%s by %s", formatDateTime(entry.revokedAt()),
                    safe(entry.revokedBy(), "(unknown)"))));
        }

        return details;
    }

    private static Component detailLine(String label, String value)
    {
        return Component.newline()
                .append(Component.text(label + ": ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private Component coloredActor(String actorName)
    {
        if (actorName.equalsIgnoreCase("CONSOLE"))
        {
            return Component.text(actorName, NamedTextColor.RED);
        }

        final Component actor = Component.text(actorName, NamedTextColor.WHITE);
        return plugin().players().getStaffByName(actorName)
                       .flatMap(PlayerData::getRankId)
                       .map(plugin().ranks()::getCustomRank)
                       .map(customRank -> actor.color(customRank.getColor()))
                       .orElse(actor);
    }

    private void sendNavigation(CommandSender sender, String target, int page, int totalPages)
    {
        if (totalPages < 2)
        {
            return;
        }

        final int firstWindowPage = Math.max(1, page - 2);
        final int lastWindowPage = Math.min(totalPages, page + 2);
        Component pages = pageButton(target, "‹", page - 1, page > 1);

        if (firstWindowPage > 1)
        {
            pages = pages.append(Component.space())
                    .append(pageLink(target, 1, false));
            if (firstWindowPage > 2)
            {
                pages = pages.append(Component.text(" … ", NamedTextColor.DARK_GRAY));
            }
        }

        for (int currentPage = firstWindowPage; currentPage <= lastWindowPage; currentPage++)
        {
            pages = pages.append(Component.space())
                    .append(pageLink(target, currentPage, currentPage == page));
        }

        if (lastWindowPage < totalPages)
        {
            if (lastWindowPage < totalPages - 1)
            {
                pages = pages.append(Component.text(" … ", NamedTextColor.DARK_GRAY));
            }
            pages = pages.append(Component.space())
                    .append(pageLink(target, totalPages, page == totalPages));
        }

        pages = pages.append(Component.space())
                .append(pageButton(target, "›", page + 1, page < totalPages));

        sender.sendMessage(pages);
    }

    private static Component pageLink(String target, int page, boolean current)
    {
        final Component pageComponent = Component.text("[" + page + "]", current ? NamedTextColor.DARK_RED : NamedTextColor.GRAY)
                .hoverEvent(HoverEvent.showText(Component.text("Go to page " + page, NamedTextColor.GRAY)))
                .clickEvent(ClickEvent.runCommand("/ph " + target + " " + page));

        return current ? pageComponent.decorate(TextDecoration.UNDERLINED) : pageComponent;
    }

    private static Component pageButton(String target, String label, int page, boolean enabled)
    {
        final Component button = Component.text("[" + label + "]", enabled ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY);
        if (enabled)
        {
            return button.hoverEvent(HoverEvent.showText(Component.text("Go to page " + page, NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.runCommand("/ph " + target + " " + page));
        }
        return button;
    }

    private static String formatDate(long epochMillis)
    {
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatDateTime(long epochMillis)
    {
        return DATE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String safe(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeActor(String value)
    {
        if (value == null || value.isBlank())
        {
            return "Console";
        }
        return value;
    }

    private static String normalizeIpLiteral(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }

        final String candidate = value.trim();
        if (candidate.contains(":"))
        {
            try
            {
                return InetAddress.getByName(candidate).getHostAddress();
            }
            catch (UnknownHostException ex)
            {
                return null;
            }
        }

        final String[] parts = candidate.split("\\.", -1);
        if (parts.length != 4)
        {
            return null;
        }

        final StringBuilder normalized = new StringBuilder();
        for (String part : parts)
        {
            if (part.isBlank())
            {
                return null;
            }
            try
            {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255)
                {
                    return null;
                }
                if (!normalized.isEmpty())
                {
                    normalized.append('.');
                }
                normalized.append(octet);
            }
            catch (NumberFormatException ex)
            {
                return null;
            }
        }
        return normalized.toString();
    }
}
