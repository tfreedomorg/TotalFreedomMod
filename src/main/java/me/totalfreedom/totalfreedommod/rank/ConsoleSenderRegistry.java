package me.totalfreedom.totalfreedommod.rank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * Loads and exposes the {@code host_senders:} whitelist that binds shared-secret / no-identity
 * senders to a specific rank.
 * <p>
 * Reload by calling {@link #load()}.
 */
public class ConsoleSenderRegistry
{

    /**
     * Console-class channels that reach the server through the host itself rather than through a
     * user identity. Console access is itself the privilege, so these hold the floor named by
     * {@link RankRole#CONSOLE_FLOOR}, which {@code host_senders:} may raise but never lower.
     * <p>
     * SSH and Discord are deliberately absent: they carry a real identity and are resolved per user
     * by {@link RankRegistry}, falling back to their {@code host_senders:} binding only when the
     * session proved no identity.
     */
    private static final Set<String> HOST_CHANNELS = Set.of("rcon", "remotebukkit", "console");

    private static final String ENTRY_SEPARATOR = ":";

    private final TotalFreedomMod plugin;

    private final Map<String, String> senderToRankId = new HashMap<>();

    public ConsoleSenderRegistry(TotalFreedomMod plugin)
    {
        this.plugin = plugin;
    }

    public void load()
    {
        senderToRankId.clear();

        final List<?> raw = ConfigEntry.HOST_SENDERS.getList();
        if (raw == null)
        {
            FLog.warning("Host sender whitelist (config 'host_senders:') is missing. Identity-less senders "
                    + "will be denied, except the host channels, which hold their floor.");
            applyHostChannelFloor();
            return;
        }

        raw.forEach(this::loadEntry);
        applyHostChannelFloor();

        FLog.info(String.format("Loaded %d console whitelist binding(s).", senderToRankId.size()));
    }

    public String getRankIdForSender(String senderName)
    {
        return senderName == null ? null : senderToRankId.get(senderName.toLowerCase());
    }

    public boolean isWhitelisted(String senderName)
    {
        return getRankIdForSender(senderName) != null;
    }

    /**
     * Parses one {@code <rank_id>:<sender_name>} entry, rejecting anything that is malformed or
     * names a rank the registry does not know.
     */
    private void loadEntry(final Object obj)
    {
        if (!(obj instanceof String))
        {
            FLog.warning(String.format("Console whitelist entry is not a string, skipping: %s", obj));
            return;
        }

        final String entry = ((String) obj).trim();
        final int colon = entry.indexOf(ENTRY_SEPARATOR);
        if (colon <= 0 || colon == entry.length() - 1)
        {
            FLog.warning(String.format(
                    "Console whitelist entry is malformed (expected '<rank_id>:<sender_name>'), skipping: %s", entry));
            return;
        }

        final String rankId = entry.substring(0, colon).trim().toLowerCase();
        final String senderName = entry.substring(colon + 1).trim().toLowerCase();

        if (!isKnownRank(rankId))
        {
            FLog.warning(String.format("Console whitelist entry references unknown rank '%s', skipping: %s",
                    rankId, entry));
            return;
        }

        final String existing = senderToRankId.put(senderName, rankId);
        if (existing != null && !existing.equals(rankId))
        {
            FLog.warning(String.format("Console whitelist binds sender '%s' to multiple ranks; using %s",
                    senderName, rankId));
        }
    }

    /**
     * Raises every {@link #HOST_CHANNELS host channel} to {@link #HOST_CHANNEL_FLOOR}, whether it
     * was bound too low or left out of {@code host_senders:} entirely. A binding at or above the
     * floor is left alone, so any console user can still hand a host channel a higher custom rank.
     */
    private void applyHostChannelFloor()
    {
        final CustomRank floor = hostChannelFloor();
        if (floor == null)
        {
            FLog.warning("No rank fills the console floor role, so host channels keep whatever "
                    + "'host_senders:' bound them to.");
            return;
        }

        HOST_CHANNELS.forEach(channel ->
        {
            final String boundId = senderToRankId.get(channel);

            if (boundId != null && outranksFloor(boundId, floor))
                return;

            if (boundId != null)
            {
                FLog.warning(String.format(
                        "Console whitelist binds host channel '%s' to '%s', below the '%s' floor for host "
                        + "channels; raising it.", channel, boundId, floor.getId()));
            }

            senderToRankId.put(channel, floor.getId());
        });
    }

    /**
     * The rank host channels are floored at, taken from whichever rank fills that role.
     */
    private CustomRank hostChannelFloor()
    {
        return plugin.rm == null
                ? null
                : plugin.rm.getRegistry().byRole(RankRole.CONSOLE_FLOOR).orElse(null);
    }

    /**
     * Whether {@code rankId} sits at or above the host-channel floor, compared on the registry's own
     * level scale so custom defined numbering is honoured.
     */
    private boolean outranksFloor(final String rankId, final CustomRank floor)
    {
        final CustomRank bound = plugin.rm.getCustomRank(rankId);

        return bound != null && bound.isAtLeast(floor);
    }

    private boolean isKnownRank(final String rankId)
    {
        return plugin.rm != null && plugin.rm.getCustomRank(rankId) != null;
    }

}
