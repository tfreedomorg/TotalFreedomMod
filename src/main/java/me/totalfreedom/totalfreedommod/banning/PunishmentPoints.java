package me.totalfreedom.totalfreedommod.banning;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.sql.adapter.PunishmentHistoryRepository;
import me.totalfreedom.totalfreedommod.cmd.MessageUtils;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Punishment points, worked out from the punishment history rather than kept as a counter of their
 * own, so revoking, expiring and auditing all happen in the one place.
 * <p>
 * An entry counts when a staff member issued it by hand (a {@code manual-*} source), it hasn't been
 * revoked on appeal, and it's newer than the decay window. Points go by the action, not the
 * command used: GTFO is a ban. Lifting a punishment doesn't remove its points; only an appeal does.
 * <p>
 * Reaching {@link #REVIEW_THRESHOLD} doesn't ban anyone. It tells every senior admin online, so a
 * person decides on the permanent ban.
 */
public final class PunishmentPoints
{
    private static final Map<String, Integer> WEIGHTS = Map.of(
            "KICK", 1,
            "MUTE", 1,
            "WARN", 1,
            "SMITE", 1,
            "BLOCKCMD", 1,
            "TEMPBAN", 3,
            "BAN", 5);
    private static final int DEFAULT_DECAY_DAYS = 90;

    public static final int REVIEW_THRESHOLD = 9;

    private PunishmentPoints() {}

    public static int weightOf(PunishmentHistoryEntry entry)
    {
        return WEIGHTS.getOrDefault(entry.punishmentType().toUpperCase(Locale.ROOT), 0);
    }

    public static int total(List<PunishmentHistoryEntry> countable)
    {
        return countable.stream()
                        .mapToInt(PunishmentPoints::weightOf)
                        .sum();
    }

    /**
     * Epoch millis before which a punishment no longer counts.
     */
    public static long windowStart()
    {
        final int days = Optional.ofNullable(ConfigEntry.PUNISHMENT_POINTS_DECAY_DAYS.getInteger())
                                 .filter(configured -> configured > 0)
                                 .orElse(DEFAULT_DECAY_DAYS);
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
    }

    /**
     * Called off the main thread once {@code recorded} is in the history. When it's the entry that
     * takes its target to {@link #REVIEW_THRESHOLD}, senior admins are told; entries past that
     * don't repeat the alert.
     */
    public static void checkThreshold(FreedomAPI plugin, PunishmentHistoryRepository repo, PunishmentHistoryEntry recorded) throws SQLException
    {
        final int weight = weightOf(recorded);
        if (weight == 0 || recorded.targetUuid() == null || !recorded.source().startsWith("manual-"))
            return;

        final int now = total(repo.findCountable(recorded.targetUuid(), windowStart()));
        final int before = now - weight;
        if (before >= REVIEW_THRESHOLD || now < REVIEW_THRESHOLD)
            return;

        final String name = Optional.ofNullable(recorded.targetName()).orElse(recorded.targetUuid());
        FLog.warn(String.format("%s has reached %d punishment points; a senior admin should review them for a permanent ban.", name, now));

        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().getOnlinePlayers()
                .stream()
                .filter(plugin.ranks()::isSeniorAdmin)
                .forEach(senior -> MessageUtils.send(senior,
                        "<red>[Points] <white><name></white> has reached <yellow><points></yellow> punishment points. Review them for a permanent ban: <gray>/ph <name>",
                        Placeholder.unparsed("name", name),
                        Formatter.number("points", now))));
    }
}
