package me.totalfreedom.totalfreedommod.sql;

import java.util.List;

public record PunishmentHistoryEntry(
        long id,
        String targetUuid,
        String targetName,
        String actorUuid,
        String actorName,
        String punishmentType,
        String reason,
        long appliedAt,
        Long expiryAt,
        String source,
        List<String> ips)
{
    public PunishmentHistoryEntry
    {
        ips = ips == null ? List.of() : List.copyOf(ips);
    }

    public boolean hasExpiry()
    {
        return expiryAt != null;
    }
}
