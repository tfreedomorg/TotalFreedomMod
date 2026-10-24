package me.totalfreedom.totalfreedommod.sql.adapter.sqlite;

import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.PunishmentHistoryRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class SQLitePunishmentHistoryRepository implements PunishmentHistoryRepository
{
    private final StatementHandler statementHandler;

    public SQLitePunishmentHistoryRepository(StatementHandler statementHandler)
    {
        this.statementHandler = statementHandler;
    }

    @Override
    public void recordPunishment(PunishmentHistoryEntry entry) throws SQLException
    {
        String sql = """
            INSERT INTO punishment_history (
                target_uuid,
                target_name,
                actor_uuid,
                actor_name,
                punishment_type,
                reason,
                applied_at,
                expires_at,
                source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        long historyId = statementHandler.executeUpdateReturnKey(sql,
                entry.targetUuid(),
                entry.targetName(),
                entry.actorUuid(),
                entry.actorName(),
                entry.punishmentType(),
                entry.reason(),
                entry.appliedAt(),
                entry.expiryAt(),
                entry.source());

        if (entry.ips() == null || entry.ips().isEmpty())
        {
            return;
        }

        String ipSql = "INSERT INTO punishment_history_ips (history_id, ip) VALUES (?, ?)";
        for (String ip : entry.ips())
        {
            if (ip == null || ip.isBlank())
            {
                continue;
            }
            statementHandler.executeUpdate(ipSql, historyId, ip);
        }
    }

    @Override
    public List<PunishmentHistoryEntry> findByTarget(String target, int page, int pageSize) throws SQLException
    {
        String query = normalizeTarget(target);
        if (query.isEmpty())
        {
            return List.of();
        }

        String sql = """
            SELECT h.id, h.target_uuid, h.target_name, h.actor_uuid, h.actor_name,
                   h.punishment_type, h.reason, h.applied_at, h.expires_at, h.source,
                   NULL AS ip
            FROM punishment_history h
            WHERE (LOWER(COALESCE(h.target_name, '')) = LOWER(?)
                OR (h.target_uuid IS NOT NULL AND LOWER(h.target_uuid) = LOWER(?)))
            ORDER BY h.applied_at DESC, h.id DESC
            LIMIT ? OFFSET ?
            """;

        return executeSearch(sql, query, query, pageSize, Math.max(0, (page - 1) * pageSize));
    }

    @Override
    public List<PunishmentHistoryEntry> findByIp(String ip, int page, int pageSize) throws SQLException
    {
        String normalized = ip == null ? "" : ip.trim();
        if (normalized.isEmpty())
        {
            return List.of();
        }

        String sql = """
            SELECT h.id, h.target_uuid, h.target_name, h.actor_uuid, h.actor_name,
                   h.punishment_type, h.reason, h.applied_at, h.expires_at, h.source,
                   i.ip
            FROM punishment_history h
            INNER JOIN punishment_history_ips i ON i.history_id = h.id
            WHERE i.ip = ?
            ORDER BY h.applied_at DESC, h.id DESC
            LIMIT ? OFFSET ?
            """;

        return executeSearch(sql, normalized, pageSize, Math.max(0, (page - 1) * pageSize));
    }

    @Override
    public int countByTarget(String target) throws SQLException
    {
        String query = normalizeTarget(target);
        if (query.isEmpty())
        {
            return 0;
        }

        String sql = """
            SELECT COUNT(*)
            FROM punishment_history
            WHERE LOWER(COALESCE(target_name, '')) = LOWER(?)
               OR (target_uuid IS NOT NULL AND LOWER(target_uuid) = LOWER(?))
            """;

        try (PreparedStatement statement = statementHandler.prepareStatement(sql, query, query);
             ResultSet rs = statement.executeQuery())
        {
            if (rs.next())
            {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    @Override
    public int countByIp(String ip) throws SQLException
    {
        String normalized = ip == null ? "" : ip.trim();
        if (normalized.isEmpty())
        {
            return 0;
        }

        String sql = "SELECT COUNT(*) FROM punishment_history_ips WHERE ip = ?";
        try (PreparedStatement statement = statementHandler.prepareStatement(sql, normalized);
             ResultSet rs = statement.executeQuery())
        {
            if (rs.next())
            {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private List<PunishmentHistoryEntry> executeSearch(String sql, Object... params) throws SQLException
    {
        List<PunishmentHistoryEntry> entries = new ArrayList<>();

        try (PreparedStatement statement = statementHandler.prepareStatement(sql, params);
             ResultSet rs = statement.executeQuery())
        {
            while (rs.next())
            {
                String ip = rs.getString("ip");
                entries.add(new PunishmentHistoryEntry(
                        rs.getLong("id"),
                        rs.getString("target_uuid"),
                        rs.getString("target_name"),
                        rs.getString("actor_uuid"),
                        rs.getString("actor_name"),
                        rs.getString("punishment_type"),
                        rs.getString("reason"),
                        rs.getLong("applied_at"),
                        rs.getObject("expires_at") == null ? null : rs.getLong("expires_at"),
                        rs.getString("source"),
                        ip == null || ip.isBlank() ? List.of() : List.of(ip)));
            }
        }

        return entries;
    }

    private static String normalizeTarget(String target)
    {
        if (target == null)
        {
            return "";
        }
        return target.trim();
    }
}
