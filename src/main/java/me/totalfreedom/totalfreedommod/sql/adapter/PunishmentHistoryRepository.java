package me.totalfreedom.totalfreedommod.sql.adapter;

import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface PunishmentHistoryRepository
{
    void recordPunishment(PunishmentHistoryEntry entry) throws SQLException;

    List<PunishmentHistoryEntry> findByTarget(String target, int page, int pageSize) throws SQLException;

    List<PunishmentHistoryEntry> findByIp(String ip, int page, int pageSize) throws SQLException;

    int countByTarget(String target) throws SQLException;

    int countByIp(String ip) throws SQLException;

    /**
     * Entries against {@code targetUuid} that count toward punishment points: staff-issued
     * ({@code manual-*} source), not revoked, and applied at or after {@code since} (epoch millis).
     */
    List<PunishmentHistoryEntry> findCountable(String targetUuid, long since) throws SQLException;

    Optional<PunishmentHistoryEntry> findById(long id) throws SQLException;

    /**
     * Marks an entry as revoked on appeal.
     *
     * @return {@code false} when there's no such entry or it was already revoked.
     */
    boolean revoke(long id, String revokedBy, long revokedAt) throws SQLException;
}
