package me.totalfreedom.totalfreedommod.cmd;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.api.cmd.FCommand;
import me.totalfreedom.api.cmd.annotation.Callback;
import me.totalfreedom.api.cmd.annotation.Command;
import me.totalfreedom.api.cmd.annotation.Completer;
import me.totalfreedom.api.cmd.annotation.Greedy;
import me.totalfreedom.api.cmd.annotation.Permission;
import me.totalfreedom.api.cmd.annotation.Switch;
import me.totalfreedom.api.cmd.annotation.Subcommand;
import me.totalfreedom.totalfreedommod.bridge.CoreProtectBridge;
import me.totalfreedom.totalfreedommod.banish.BanishEntry;
import me.totalfreedom.totalfreedommod.banish.BanishManager;
import me.totalfreedom.totalfreedommod.cmd.internal.FuzzyMatch;
import me.totalfreedom.totalfreedommod.util.FUtil;

@Command(name = "banish",
        description = "Restricts a player to spectator mode until released or the duration expires.",
        usage = "/banish [-rb] <player> [duration] [reason] | off <player> | list")
@Permission(permission = "tfm.admin.ban")
public class Command_banish extends FCommand
{
    private static final List<String> DURATIONS = List.of("30s", "5m", "15m", "30m", "1h", "6h", "12h", "24h");
    private static final long DEFAULT_DURATION_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long MAX_DURATION_MS = TimeUnit.HOURS.toMillis(24);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z");

    @Completer(value = "", position = 1)
    public List<String> completeDuration(CommandSender sender, String partial)
    {
        return FuzzyMatch.filter(DURATIONS, partial);
    }

    @Completer(value = "off", position = 0)
    public List<String> completeRelease(CommandSender sender, String partial)
    {
        final BanishManager manager = banishManager();
        return FuzzyMatch.filter(manager.getBanishments()
                        .stream()
                        .map(BanishEntry::username)
                        .distinct()
                        .sorted()
                        .toList(), partial);
    }

    @Callback
    public void banish(CommandSender sender, Player target, @Switch("rb") boolean rollback)
    {
        doBanish(sender, target, defaultExpiry(), null, rollback);
    }

    @Callback
    public void banish(CommandSender sender, Player target, @Greedy String args, @Switch("rb") boolean rollback)
    {
        final String tail = args.strip();
        final int split = tail.indexOf(' ');
        final String first = split == -1 ? tail : tail.substring(0, split);
        final Date parsedExpiry = FUtil.parseDateOffset(first);
        final Date expiry = parsedExpiry == null ? defaultExpiry() : parsedExpiry;
        final String reason = parsedExpiry == null ? tail : (split == -1 ? null : tail.substring(split + 1).strip());
        doBanish(sender, target, expiry, reason == null || reason.isBlank() ? null : reason, rollback);
    }

    private static Date defaultExpiry()
    {
        return new Date(System.currentTimeMillis() + DEFAULT_DURATION_MS);
    }

    private void doBanish(CommandSender sender, Player target, Date expiry, String reason, boolean rollback)
    {
        if (isProtectedAdmin(sender, target))
            return;

        final long now = System.currentTimeMillis();
        if (expiry.getTime() <= now)
        {
            msg(sender, "<red>The duration must be greater than zero.");
            return;
        }

        final Date maximumExpiry = new Date(now + MAX_DURATION_MS);
        if (expiry.after(maximumExpiry))
        {
            expiry = maximumExpiry;
            msg(sender, "<gray>Banishments are capped at 24 hours.");
        }
        final Date effectiveExpiry = expiry;

        final BanishManager manager = banishManager();
        if (!manager.isPacketListenerActive())
        {
            msg(sender, "<red>Cannot banish players because PacketEvents is unavailable.");
            return;
        }

        try
        {
            final long expiresAt = effectiveExpiry.getTime();
            final List<Player> affected = manager.banish(target, sender.getName(), reason, expiresAt);
            if (rollback)
                plugin().bridges().require(CoreProtectBridge.class).rollback(target.getName());

            adminAction(sender, "<red>Temporarily banishing <player> until <until><include_reason:\" - Reason: <yellow><reason>\":\"\">",
                    Placeholder.unparsed("player", target.getName()),
                    Placeholder.unparsed("until", dateFormat.format(effectiveExpiry)),
                    Formatter.booleanChoice("include_reason", reason != null && !reason.isEmpty()),
                    Placeholder.unparsed("reason", reason == null ? "" : reason));
            msg(sender, "<gray>Banishment applied to <count> player<plural>.",
                    Placeholder.unparsed("count", Integer.toString(affected.size())),
                    Placeholder.unparsed("plural", affected.size() == 1 ? "" : "s"));
            affected.forEach(player -> msg(player,
                    "<red>You have been banished until <until><include_reason:\" - Reason: <yellow><reason>\":\"\">. You may only spectate.",
                    Placeholder.unparsed("until", dateFormat.format(effectiveExpiry)),
                    Formatter.booleanChoice("include_reason", reason != null && !reason.isEmpty()),
                    Placeholder.unparsed("reason", reason == null ? "" : reason)));
            plugin().database().recordPunishment(target, sender, "BANISH", reason,
                    expiresAt, "manual-banish");
        }
        catch (IllegalStateException ex)
        {
            msg(sender, "<red><error>", Placeholder.unparsed("error", ex.getMessage()));
        }
    }

    @Callback
    @Subcommand("off")
    public void release(CommandSender sender, String target)
    {
        try
        {
            final BanishEntry entry = banishManager().release(target).orElse(null);
            if (entry == null)
            {
                msg(sender, "<gray>No banishment is active for that player.");
                return;
            }

            adminAction(sender, "<aqua>Lifting banishment on <player>",
                    Placeholder.unparsed("player", entry.username()));
            msg(sender, "<gray>Released the banishment for <player>.",
                    Placeholder.unparsed("player", entry.username()));
        }
        catch (IllegalStateException ex)
        {
            msg(sender, "<red><error>", Placeholder.unparsed("error", ex.getMessage()));
        }
    }

    @Callback
    @Subcommand("list")
    public void list(CommandSender sender)
    {
        final List<BanishEntry> entries = banishManager().getBanishments()
                .stream()
                .sorted(Comparator.comparing(BanishEntry::username, String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (entries.isEmpty())
        {
            msg(sender, "<gray>No active banishments.");
            return;
        }

        msg(sender, "<gray>Active banishments:");
        entries.forEach(entry ->
        {
            final String duration = " until " + dateFormat.format(new Date(entry.expiresAt()));
            msg(sender, "<gray>- <white><name></white><duration> by <admin><reason>",
                    Placeholder.unparsed("name", entry.username()),
                    Placeholder.unparsed("duration", duration),
                    Placeholder.unparsed("admin", entry.issuedBy()),
                    Placeholder.unparsed("reason", entry.reason() == null || entry.reason().isBlank()
                            ? ""
                            : " - " + entry.reason()));
        });
    }

    private BanishManager banishManager()
    {
        return plugin().services().require(BanishManager.class);
    }
}
