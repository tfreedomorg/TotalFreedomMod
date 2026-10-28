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
import me.totalfreedom.api.sql.adapter.PunishmentHistoryRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
@Permission(permission = "tfm.admin.punishmenthistory", source = SourceType.ONLY_IN_GAME)
public class Command_punishmenthistory extends FCommand
{
    private static final int ENTRIES_PER_BOOK = 99;
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
        final Player player = (Player) sender;

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

        async(task ->
        {
            try
            {
                final int totalRecords = ipSearch ? repo.countByIp(searchTarget) : repo.countByTarget(searchTarget);
                final int totalPages = Math.max(1, (int) Math.ceil((double) totalRecords / ENTRIES_PER_BOOK));

                if (page > totalPages)
                {
                    sync(() -> msg(sender,
                            "<red>Invalid page. Please choose a page from 1 to <total>.",
                            Formatter.number("total", totalPages)), 0L);
                    return;
                }

                final List<PunishmentHistoryEntry> entries = ipSearch
                        ? repo.findByIp(searchTarget, page, ENTRIES_PER_BOOK)
                        : repo.findByTarget(searchTarget, page, ENTRIES_PER_BOOK);

                // Points belong to a player's record, so there's nothing to total for an address.
                final Optional<UUID> targetUuid = ipSearch
                        ? Optional.empty()
                        : Optional.ofNullable(plugin().players().getData(searchTarget)).map(PlayerData::getUuid);
                final Optional<Integer> points = targetUuid.isEmpty()
                        ? Optional.empty()
                        : Optional.of(PunishmentPoints.total(repo.findCountable(targetUuid.get().toString(),
                                PunishmentPoints.windowStart())));

                sync(() -> sendHistoryPage(player, target, page, totalPages, totalRecords, entries, points), 0L);
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

    private void sendHistoryPage(Player player, String target, int page, int totalPages, int totalRecords,
                                 List<PunishmentHistoryEntry> entries, Optional<Integer> points)
    {
        if (entries.isEmpty())
        {
            msg(player, "<gray>No punishment history found for <white><target>.", Placeholder.unparsed("target", target));
            return;
        }

        final List<Component> pages = new ArrayList<>(entries.size() + 1);
        pages.add(buildOverviewPage(totalRecords, entries, points));
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

    private static Component buildOverviewPage(int totalRecords, List<PunishmentHistoryEntry> entries,
                                               Optional<Integer> points)
    {
        Component overview = Component.empty();
        final String targetName = entries.stream()
                .map(PunishmentHistoryEntry::targetName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("(unknown)");
        Component targetHeader = Component.text(targetName, NamedTextColor.DARK_GRAY);
        if (PlayerHeadObjectContents.isValidName(targetName))
        {
            targetHeader = targetHeader.append(Component.space())
                    .append(Component.object(ObjectContents.playerHead(targetName)));
        }
        overview = overview.append(targetHeader);

        if (points.isPresent())
        {
            final int totalPoints = points.get();
            overview = overview.append(Component.newline())
                    .append(Component.text("Punishment points: ", NamedTextColor.BLACK))
                    .append(Component.text(totalPoints + "/" + PunishmentPoints.REVIEW_THRESHOLD,
                            totalPoints >= PunishmentPoints.REVIEW_THRESHOLD ? NamedTextColor.RED : NamedTextColor.DARK_GRAY))
                    .append(Component.newline())
                    .append(Component.newline());
        }

        for (int index = 0; index < entries.size(); index++)
        {
            final PunishmentHistoryEntry entry = entries.get(index);
            final Component label = Component.text("#" + entry.id(), NamedTextColor.DARK_GRAY)
                    .append(Component.space())
                    .append(Component.text(safe(entry.punishmentType(), "UNKNOWN"), NamedTextColor.DARK_RED))
                    .clickEvent(ClickEvent.changePage(index + 2));
            overview = overview.append(Component.newline())
                    .append(label);
        }

        if (totalRecords > 9)
        {
            overview = overview.append(Component.newline())
                    .append(Component.text("...", NamedTextColor.DARK_GRAY));
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
                        MessageUtils.parse("<dark_gray>" + 
                                safe(entry.targetName(), "(unknown)") + 
                                "</dark_gray>")))
                .append(bookField("Type",
                        MessageUtils.parse("<red>" + 
                                type + 
                                "</red>")))
                .append(bookField("Issued By", coloredActor(actor)))
                .append(bookField("Date and length",
                        MessageUtils.parse("<dark_gray>" + 
                                formatDate(entry.appliedAt()) + 
                                "\n" + 
                                formatBanLength(entry) + 
                                "</dark_gray>")))
                .append(Component.newline())
                .append(bookField("Reason",
                        MessageUtils.parse("<dark_gray>" +
                                safe(entry.reason(), "No reason provided.") +
                                "</dark_gray>")))
                .append(Component.newline())
                .append(navigation);
    }

    private static Component bookField(String label, Component value)
    {
        return MessageUtils.parse("<black>" + label + ":</black>")
                .append(Component.newline())
                .append(value)
                .append(Component.newline());
    }

    private Component coloredActor(String actorName)
    {
        if (actorName.equalsIgnoreCase("CONSOLE"))
        {
            return Component.text(actorName, NamedTextColor.DARK_RED);
        }

        final Component actor = Component.text(actorName, NamedTextColor.DARK_GRAY);
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
        if (!entry.hasExpiry())
        {
            return "Permanent";
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
