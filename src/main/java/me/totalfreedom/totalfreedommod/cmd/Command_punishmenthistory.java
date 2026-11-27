package me.totalfreedom.totalfreedommod.cmd;

import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.SourceType;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Completer;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.api.cmd.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.banning.PunishmentPoints;
import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;
import me.totalfreedom.totalfreedommod.sql.adapter.PunishmentHistoryRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Command(name = "punishmenthistory", description = "Shows a player's punishment history.", usage = "/<command> <player|ip> [page]", aliases = {"ph"})
@Permission(permission = "tfm.admin.punishmenthistory", source = SourceType.BOTH)
public class Command_punishmenthistory extends FCommand
{
    private static final int ENTRIES_PER_BOOK = 99;
    private static final int ENTRIES_PER_CONSOLE_PAGE = 10;
    private static final int MAX_OVERVIEW_ENTRIES = 9;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy - HH:mm")
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC);

    @Completer(value = "", position = 0)
    public List<String> completeTarget(CommandSender sender, String partial)
    {
        return NameCandidates.online(server(), partial);
    }

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

        if (page < 1)
        {
            msg(sender, "<red>Invalid page. Please choose a page greater than zero.");
            return;
        }

        final String normalizedIp = normalizeIpLiteral(target);
        final boolean ipSearch = normalizedIp != null;
        final String searchTarget = ipSearch ? normalizedIp : target;
        final PunishmentHistoryRepository repo = plugin().database().getPunishmentHistoryRepository();
        final int entriesPerPage = sender instanceof Player ? ENTRIES_PER_BOOK : ENTRIES_PER_CONSOLE_PAGE;

        async(task ->
        {
            try
            {
                final int totalRecords = ipSearch ? repo.countByIp(searchTarget) : repo.countByTarget(searchTarget);
                final int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / entriesPerPage));

                if (page > totalPages)
                {
                    sync(() -> msg(sender,
                            "<red>Invalid page. Please choose a page from 1 to <total>.",
                            Formatter.number("total", totalPages)), 0L);
                    return;
                }

                final List<PunishmentHistoryEntry> entries = ipSearch
                        ? repo.findByIp(searchTarget, page, entriesPerPage)
                        : repo.findByTarget(searchTarget, page, entriesPerPage);

                // Points belong to a player's record, so there's nothing to total for an address.
                final Optional<UUID> targetUuid = ipSearch
                        ? Optional.empty()
                        : Optional.ofNullable(plugin().players().getData(searchTarget)).map(PlayerData::getUuid);
                final Optional<Integer> points = targetUuid.isEmpty()
                        ? Optional.empty()
                        : Optional.of(PunishmentPoints.total(repo.findCountable(targetUuid.get().toString(),
                                PunishmentPoints.windowStart())));

                sync(() -> sendHistoryPage(sender, target, page, totalPages, totalRecords, entries, points), 0L);
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

    private void sendHistoryPage(CommandSender sender, String target, int page, int totalPages, int totalRecords,
                                 List<PunishmentHistoryEntry> entries, Optional<Integer> points)
    {
        if (entries.isEmpty())
        {
            msg(sender, "<gray>No punishment history found for <white><target>.", Placeholder.unparsed("target", target));
            return;
        }

        if (!(sender instanceof Player player))
        {
            sendConsoleHistoryPage(sender, target, page, totalPages, totalRecords, entries, points);
            return;
        }

        final List<Component> pages = new ArrayList<>(entries.size() + 1);
        pages.add(buildOverviewPage(entries, points));
        for (int index = 0; index < entries.size(); index++)
        {
            pages.add(buildHistoryPage(entries.get(index)));
        }

        final ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(BookMeta.class, meta ->
        {
            meta.title(MessageUtils.parse("<dark_red>Punishment History</dark_red>"));
            meta.author(MessageUtils.parse("<gray>TotalFreedom</gray>"));
            meta.pages(pages);
        });

        if (player.isOnline())
        {
            player.openBook(book);
        }
    }

    private static Component buildOverviewPage(List<PunishmentHistoryEntry> entries, Optional<Integer> points)
    {
        Component overview = Component.empty();
        final String targetName = entries.stream()
                .map(PunishmentHistoryEntry::targetName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("(unknown)");
        Component targetHeader = MessageUtils.parse("<dark_gray><target></dark_gray>",
                Placeholder.unparsed("target", targetName));
        if (PlayerHeadObjectContents.isValidName(targetName))
        {
            targetHeader = targetHeader.append(Component.space())
                    .append(Component.object(ObjectContents.playerHead(targetName)));
        }
        overview = overview.append(targetHeader);

        if (points.isPresent())
        {
            final int totalPoints = points.get();
            final String pointsColor = totalPoints >= PunishmentPoints.REVIEW_THRESHOLD ? "red" : "dark_gray";
            final Component pointsValue = MessageUtils.parse(
                    String.format(Locale.ROOT, "<%s>%d/%d</%s>", pointsColor, totalPoints,
                            PunishmentPoints.REVIEW_THRESHOLD, pointsColor));
            overview = overview.append(Component.newline())
                    .append(MessageUtils.parse("<black>Punishment points: </black><points>\n\n",
                            Placeholder.component("points", pointsValue)));
        }

        final int visibleEntries = Math.min(entries.size(), MAX_OVERVIEW_ENTRIES);
        for (int index = 0; index < visibleEntries; index++)
        {
            final PunishmentHistoryEntry entry = entries.get(index);
            final Component revokedMarker = entry.isRevoked()
                    ? MessageUtils.parse("<red> [REVOKED]</red>")
                    : Component.empty();
            final Component label = MessageUtils.parse("<dark_gray>#<id></dark_gray> <dark_red><type></dark_red><revoked>",
                    Placeholder.unparsed("id", String.valueOf(entry.id())),
                    Placeholder.unparsed("type", safe(entry.punishmentType(), "UNKNOWN")),
                    Placeholder.component("revoked", revokedMarker))
                    .clickEvent(ClickEvent.changePage(index + 2));
            overview = overview.append(Component.newline())
                    .append(label);
        }

        if (entries.size() > visibleEntries)
        {
            overview = overview.append(Component.newline())
                    .append(MessageUtils.parse("<dark_gray>...</dark_gray>"));
        }

        return overview;
    }

    private Component buildHistoryPage(PunishmentHistoryEntry entry)
    {
        final String actor = safeActor(entry.actorName());
        final String type = safe(entry.punishmentType(), "UNKNOWN");

        final Component navigation = MessageUtils.parse("<dark_red>[Back to Overview]</dark_red>")
                .clickEvent(ClickEvent.changePage(1));

        return Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .append(bookField("Name",
                        MessageUtils.parse("<dark_gray><name></dark_gray>",
                                Placeholder.unparsed("name", safe(entry.targetName(), "(unknown)")))))
                .append(bookField("Type",
                        MessageUtils.parse("<red><type></red>", Placeholder.unparsed("type", type))))
                .append(entry.isRevoked()
                        ? bookField("Appeal",
                                MessageUtils.parse("<red>Revoked on <date> by <actor></red>",
                                        Placeholder.unparsed("date", formatDate(entry.revokedAt())),
                                        Placeholder.unparsed("actor", safe(entry.revokedBy(), "unknown"))))
                        : Component.empty())
                .append(bookField("Issued By", coloredActor(actor)))
                .append(bookField("Date and length",
                        MessageUtils.parse("<dark_gray><date>\n<length></dark_gray>",
                                Placeholder.unparsed("date", formatDate(entry.appliedAt())),
                                Placeholder.unparsed("length", formatBanLength(entry)))))
                .append(Component.newline())
                .append(bookField("Reason",
                        MessageUtils.parse("<dark_gray><reason></dark_gray>",
                                Placeholder.unparsed("reason", safe(entry.reason(), "No reason provided.")))))
                .append(Component.newline())
                .append(navigation);
    }

    private void sendConsoleHistoryPage(CommandSender sender, String target, int page, int totalPages, int totalRecords,
                                        List<PunishmentHistoryEntry> entries, Optional<Integer> points)
    {
        MessageUtils.send(sender,
                "<dark_red>Punishment history for <target> (page <page>/<totalpages>, <records> records)</dark_red>",
                Placeholder.unparsed("target", target),
                Formatter.number("page", page),
                Formatter.number("totalpages", totalPages),
                Formatter.number("records", totalRecords));

        points.ifPresent(total -> MessageUtils.send(sender,
                "<dark_gray>Punishment points: <points>/<threshold></dark_gray>",
                Formatter.number("points", total),
                Formatter.number("threshold", PunishmentPoints.REVIEW_THRESHOLD)));

        for (PunishmentHistoryEntry entry : entries)
        {
            final String revoked = entry.isRevoked() ? " [REVOKED]" : "";
            MessageUtils.send(sender, "<dark_gray>#<id> <type><revoked> | <date></dark_gray>",
                    Placeholder.unparsed("id", String.valueOf(entry.id())),
                    Placeholder.unparsed("type", safe(entry.punishmentType(), "UNKNOWN")),
                    Placeholder.unparsed("revoked", revoked),
                    Placeholder.unparsed("date", formatDate(entry.appliedAt())));
            MessageUtils.send(sender, "<dark_gray>  Target: <target> | Issued by: <actor> | Length: <length></dark_gray>",
                    Placeholder.unparsed("target", safe(entry.targetName(), "(unknown)")),
                    Placeholder.unparsed("actor", safeActor(entry.actorName())),
                    Placeholder.unparsed("length", formatBanLength(entry)));
            MessageUtils.send(sender, "<gray>  Reason: <reason></gray>",
                    Placeholder.unparsed("reason", safe(entry.reason(), "No reason provided.")));
        }
    }

    private static Component bookField(String label, Component value)
    {
        return MessageUtils.parse("<black><label>:</black>\n<value>\n",
                Placeholder.unparsed("label", label),
                Placeholder.component("value", value));
    }

    private Component coloredActor(String actorName)
    {
        if (actorName.equalsIgnoreCase("CONSOLE"))
        {
            return MessageUtils.parse("<dark_red><actor></dark_red>",
                    Placeholder.unparsed("actor", actorName));
        }

        final Component actor = MessageUtils.parse("<dark_gray><actor></dark_gray>",
                Placeholder.unparsed("actor", actorName));
        return plugin().players().getStaffByName(actorName)
                       .flatMap(PlayerData::getRankId)
                       .map(plugin().ranks()::getCustomRank)
                       .map(customRank -> actor.color(customRank.getColor()))
                       .orElse(actor);
    }

    private static String formatDate(long epochMillis)
    {
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatBanLength(PunishmentHistoryEntry entry)
    {
        final String punishmentType = safe(entry.punishmentType(), "").toUpperCase(Locale.ROOT);
        if (punishmentType.equals("PERMBAN"))
        {
            return "Permanent";
        }

        if (!punishmentType.equals("BAN") && !punishmentType.equals("TEMPBAN"))
        {
            return "N/A";
        }

        if (!entry.hasExpiry())
        {
            return "N/A";
        }

        final Duration duration = Duration.between(Instant.ofEpochMilli(entry.appliedAt()),
                Instant.ofEpochMilli(entry.expiryAt()));
        if (duration.isNegative())
        {
            return "Unknown";
        }

        final List<String> fullParts = new ArrayList<>();
        final List<String> shortParts = new ArrayList<>();
        long seconds = duration.getSeconds();
        if (seconds < 60)
        {
            seconds += duration.getNano() > 0 ? 1 : 0;
            addDurationPart(fullParts, shortParts, seconds, "second", "s");
            return formatDurationParts(fullParts, shortParts);
        }

        long minutes = seconds / 60;
        if (seconds % 60 > 0 || duration.getNano() > 0)
        {
            minutes++;
        }

        final long days = minutes / 1_440;
        minutes %= 1_440;
        final long hours = minutes / 60;
        minutes %= 60;

        addDurationPart(fullParts, shortParts, days, "day", "d");
        addDurationPart(fullParts, shortParts, hours, "hour", "h");
        addDurationPart(fullParts, shortParts, minutes, "minute", "m");
        return formatDurationParts(fullParts, shortParts);
    }

    private static String formatDurationParts(List<String> fullParts, List<String> shortParts)
    {
        final String fullDuration = String.join(" ", fullParts);
        return fullDuration.length() <= 15 ? fullDuration : String.join(" ", shortParts);
    }

    private static void addDurationPart(List<String> fullParts, List<String> shortParts, long amount,
                                        String fullUnit, String shortUnit)
    {
        if (amount > 0)
        {
            fullParts.add(amount + " " + fullUnit + (amount == 1 ? "" : "s"));
            shortParts.add(amount + shortUnit);
        }
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
