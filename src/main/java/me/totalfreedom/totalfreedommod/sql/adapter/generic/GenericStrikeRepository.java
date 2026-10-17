package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.banning.StrikeRecord;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.StrikeRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericStrikeRepository implements StrikeRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblStrikes;
    private final String colIp;
    private final String colStrikeCount;
    private final String colLastStrikeUnix;
    private final String colLastUsername;
    private final String colUpdatedAt;
    private final String upsertSql;
    private final String selectSql;

    public GenericStrikeRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblStrikes = adapter.quoteIdentifier("strikes");
        this.colIp = adapter.quoteIdentifier("ip");
        this.colStrikeCount = adapter.quoteIdentifier("strike_count");
        this.colLastStrikeUnix = adapter.quoteIdentifier("last_strike_unix");
        this.colLastUsername = adapter.quoteIdentifier("last_username");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");

        this.selectSql = String.format("SELECT %s, %s, %s, %s FROM %s",
                colIp, colStrikeCount, colLastStrikeUnix, colLastUsername, tblStrikes);
        this.upsertSql = String.format("INSERT INTO %s (%s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, %s) %s",
                tblStrikes, colIp, colStrikeCount, colLastStrikeUnix, colLastUsername, colUpdatedAt,
                adapter.currentTimestamp(),
                adapter.upsertClause(colIp, colStrikeCount, colLastStrikeUnix, colLastUsername, colUpdatedAt));
    }

    @Override
    public Map<String, StrikeRecord> loadAll() throws SQLException
    {
        Map<String, StrikeRecord> out = new HashMap<>();
        try (ResultSet rs = statementHandler.executeQuery(selectSql))
        {
            while (rs.next())
            {
                String ip = rs.getString("ip");
                int count = rs.getInt("strike_count");
                long last = rs.getLong("last_strike_unix");
                String username = rs.getString("last_username");
                out.put(ip, new StrikeRecord(ip, count, last, username));
            }
        }
        return out;
    }

    @Override
    public void upsert(StrikeRecord r) throws SQLException
    {
        statementHandler.executeUpdate(upsertSql, r.getIp(), r.getCount(), r.getLastStrikeUnix(), r.getLastUsername());
    }

    @Override
    public boolean deleteByIp(String ip) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblStrikes, colIp);
        return statementHandler.executeUpdate(sql, ip) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblStrikes));
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblStrikes);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            if (rs.next())
            {
                return adapter.readTimestamp(rs, 1);
            }
        }
        return null;
    }

    @Override
    public Mono<Map<String, StrikeRecord>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> upsertAsync(StrikeRecord r)
    {
        return statementHandler.runMono(() -> upsert(r));
    }

    @Override
    public Mono<Boolean> deleteByIpAsync(String ip)
    {
        return statementHandler.supplyMono(() -> deleteByIp(ip));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }
}
