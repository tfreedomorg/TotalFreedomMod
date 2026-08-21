package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.DiscordLinkRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericDiscordLinkRepository implements DiscordLinkRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String insertSql;
    private final String selectDiscordIdSql;
    private final String selectAdminUuidSql;
    private final String deleteByAdminUuidSql;
    private final String deleteByDiscordUserIdSql;
    private final String maxUpdatedAtSql;
    private final String selectAllSql;

    public GenericDiscordLinkRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        String tblDiscordLinks = adapter.quoteIdentifier("discord_links");
        String colAdminUuid = adapter.quoteIdentifier("admin_uuid");
        String colDiscordUserId = adapter.quoteIdentifier("discord_user_id");
        String colLinkedAt = adapter.quoteIdentifier("linked_at");
        String colUpdatedAt = adapter.quoteIdentifier("updated_at");

        this.insertSql = String.format("INSERT INTO %s (%s, %s, %s, %s) VALUES (?, ?, %s, %s)",
                tblDiscordLinks, colAdminUuid, colDiscordUserId, colLinkedAt, colUpdatedAt,
                adapter.currentTimestamp(), adapter.currentTimestamp());
        this.selectDiscordIdSql = String.format("SELECT %s FROM %s WHERE %s = ?", colDiscordUserId, tblDiscordLinks, colAdminUuid);
        this.selectAdminUuidSql = String.format("SELECT %s FROM %s WHERE %s = ?", colAdminUuid, tblDiscordLinks, colDiscordUserId);
        this.deleteByAdminUuidSql = String.format("DELETE FROM %s WHERE %s = ?", tblDiscordLinks, colAdminUuid);
        this.deleteByDiscordUserIdSql = String.format("DELETE FROM %s WHERE %s = ?", tblDiscordLinks, colDiscordUserId);
        this.maxUpdatedAtSql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblDiscordLinks);
        this.selectAllSql = String.format("SELECT %s, %s FROM %s", colAdminUuid, colDiscordUserId, tblDiscordLinks);
    }

    @Override
    public Map<String, String> loadAll() throws SQLException
    {
        Map<String, String> links = new LinkedHashMap<>();
        try (ResultSet rs = statementHandler.executeQuery(selectAllSql))
        {
            while (rs.next())
            {
                links.put(rs.getString(1), rs.getString(2));
            }
        }
        return links;
    }

    @Override
    public void insert(UUID adminUuid, String discordUserId) throws SQLException
    {
        statementHandler.executeUpdate(insertSql, adminUuid.toString(), discordUserId);
    }

    @Override
    public String findDiscordIdByAdminUuid(UUID adminUuid) throws SQLException
    {
        try (ResultSet rs = statementHandler.executeQuery(selectDiscordIdSql, adminUuid.toString()))
        {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Override
    public UUID findAdminUuidByDiscordId(String discordUserId) throws SQLException
    {
        try (ResultSet rs = statementHandler.executeQuery(selectAdminUuidSql, discordUserId))
        {
            if (!rs.next())
            {
                return null;
            }
            String raw = rs.getString(1);
            return raw == null ? null : UUID.fromString(raw);
        }
    }

    @Override
    public boolean deleteByAdminUuid(UUID adminUuid) throws SQLException
    {
        return statementHandler.executeUpdate(deleteByAdminUuidSql, adminUuid.toString()) > 0;
    }

    @Override
    public boolean deleteByDiscordUserId(String discordUserId) throws SQLException
    {
        return statementHandler.executeUpdate(deleteByDiscordUserIdSql, discordUserId) > 0;
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        try (ResultSet rs = statementHandler.executeQuery(maxUpdatedAtSql))
        {
            if (rs.next())
            {
                return adapter.readTimestamp(rs, 1);
            }
        }
        return null;
    }

    @Override
    public Mono<Map<String, String>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> relinkAsync(UUID adminUuid, String discordUserId)
    {
        return statementHandler.runMono(() ->
        {
            deleteByAdminUuid(adminUuid);
            deleteByDiscordUserId(discordUserId);
            insert(adminUuid, discordUserId);
        });
    }

    @Override
    public Mono<Long> getMaxUpdatedAtAsync()
    {
        return statementHandler.supplyMono(this::getMaxUpdatedAt);
    }
}
