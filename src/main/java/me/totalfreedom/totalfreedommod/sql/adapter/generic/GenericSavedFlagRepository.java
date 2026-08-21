package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.SavedFlagRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericSavedFlagRepository implements SavedFlagRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblSavedFlags;
    private final String colFlagName;
    private final String colEnabled;
    private final String colUpdatedAt;
    private final String selectSql;
    private final String upsertSql;
    private final String maxUpdatedAtSql;

    public GenericSavedFlagRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblSavedFlags = adapter.quoteIdentifier("saved_flags");
        this.colFlagName = adapter.quoteIdentifier("flag_name");
        this.colEnabled = adapter.quoteIdentifier("enabled");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");

        this.selectSql = String.format("SELECT %s, %s FROM %s", colFlagName, colEnabled, tblSavedFlags);
        this.upsertSql = String.format("INSERT INTO %s (%s, %s, %s) VALUES (?, ?, %s) %s",
                tblSavedFlags, colFlagName, colEnabled, colUpdatedAt, adapter.currentTimestamp(),
                adapter.upsertClause(colFlagName, colEnabled, colUpdatedAt));
        this.maxUpdatedAtSql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblSavedFlags);
    }

    @Override
    public Map<String, Boolean> loadAll() throws SQLException
    {
        Map<String, Boolean> flags = new HashMap<>();
        try (ResultSet rs = statementHandler.executeQuery(selectSql))
        {
            while (rs.next())
            {
                flags.put(rs.getString("flag_name"), rs.getBoolean("enabled"));
            }
        }
        return flags;
    }

    @Override
    public void upsert(String flagName, boolean enabled) throws SQLException
    {
        statementHandler.executeUpdate(upsertSql, flagName, enabled);
    }

    @Override
    public boolean delete(String flagName) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblSavedFlags, colFlagName);
        return statementHandler.executeUpdate(sql, flagName) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblSavedFlags));
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
    public Mono<Map<String, Boolean>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> upsertAsync(String flagName, boolean enabled)
    {
        return statementHandler.runMono(() -> upsert(flagName, enabled));
    }

    @Override
    public Mono<Boolean> deleteAsync(String flagName)
    {
        return statementHandler.supplyMono(() -> delete(flagName));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }
}
