package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.banning.PermBan;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.PermbanRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericPermbanRepository implements PermbanRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblPermbans;
    private final String tblPermbanIps;
    private final String colId;
    private final String colUuid;
    private final String colUsername;
    private final String colReason;
    private final String colPermbanId;
    private final String colIp;
    private final String colUpdatedAt;
    private final String selectColumns;

    public GenericPermbanRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblPermbans = adapter.quoteIdentifier("permbans");
        this.tblPermbanIps = adapter.quoteIdentifier("permban_ips");
        this.colId = adapter.quoteIdentifier("id");
        this.colUuid = adapter.quoteIdentifier("uuid");
        this.colUsername = adapter.quoteIdentifier("username");
        this.colReason = adapter.quoteIdentifier("reason");
        this.colPermbanId = adapter.quoteIdentifier("permban_id");
        this.colIp = adapter.quoteIdentifier("ip");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s", colId, colUuid, colUsername, colReason);
    }

    @Override
    public int insert(PermBan permban) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s, %s, %s) VALUES (?, ?, ?, %s)",
                tblPermbans, colUuid, colUsername, colReason, colUpdatedAt, adapter.currentTimestamp());

        long permbanId = statementHandler.executeUpdateReturnKey(sql,
                permban.getUuid() != null ? permban.getUuid().toString() : null,
                permban.getUsername(),
                permban.getReason());

        if (permbanId < 0)
        {
            return -1;
        }
        insertIps((int) permbanId, permban.getIps());
        return (int) permbanId;
    }

    @Override
    public void insertIps(int permbanId, List<String> ips) throws SQLException
    {
        if (ips == null || ips.isEmpty()) return;

        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblPermbanIps, colPermbanId, colIp, adapter.insertIgnoreSuffix());
        for (String ip : ips)
        {
            statementHandler.executeUpdate(sql, permbanId, ip);
        }
    }

    @Override
    public void addIp(int permbanId, String ip) throws SQLException
    {
        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblPermbanIps, colPermbanId, colIp, adapter.insertIgnoreSuffix());
        statementHandler.executeUpdate(sql, permbanId, ip);
    }

    @Override
    public List<PermBan> loadAll() throws SQLException
    {
        List<PermBan> permbans = new ArrayList<>();
        Map<Integer, PermBan> permbanById = new HashMap<>();

        String sql = String.format("SELECT %s FROM %s", selectColumns, tblPermbans);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                int id = rs.getInt("id");
                PermBan permban = loadPermbanFromRow(rs);
                permbans.add(permban);
                permbanById.put(id, permban);
            }
        }

        attachIps(permbanById);

        return permbans;
    }

    /**
     * Fold the {@code permban_ips} child rows into permbans that have already been read.
     * One flat query, run after the caller's cursor is closed: a nested read would hold two
     * connections at once, which SQLite's single-connection pool cannot serve.
     */
    private void attachIps(Map<Integer, PermBan> permbanById) throws SQLException
    {
        if (permbanById.isEmpty())
            return;

        String ipSql = String.format("SELECT %s, %s FROM %s", colPermbanId, colIp, tblPermbanIps);
        try (ResultSet rs = statementHandler.executeQuery(ipSql))
        {
            while (rs.next())
            {
                PermBan permban = permbanById.get(rs.getInt("permban_id"));
                if (permban != null)
                {
                    permban.addIp(rs.getString("ip"));
                }
            }
        }
    }

    /**
     * Read a single permban and its IPs, closing the row's statement before looking the IPs up.
     */
    private PermBan findOne(String sql, Object... params) throws SQLException
    {
        final PermBan permban;
        final int permbanId;
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, params);
             ResultSet rs = stmt.executeQuery())
        {
            if (!rs.next())
                return null;

            permban = loadPermbanFromRow(rs);
            permbanId = rs.getInt("id");
        }

        permban.setIps(getIps(permbanId));
        return permban;
    }

    @Override
    public PermBan findByUuid(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblPermbans, colUuid);
        return findOne(sql, uuid.toString());
    }

    @Override
    public PermBan findByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                selectColumns, tblPermbans, adapter.caseInsensitiveEquals(colUsername, "?"));
        return findOne(sql, username);
    }

    @Override
    public PermBan findByIp(String ip) throws SQLException
    {
        String sql = String.format(
                "SELECT p.%s, p.%s, p.%s, p.%s FROM %s p INNER JOIN %s pi ON p.%s = pi.%s WHERE pi.%s = ?",
                colId, colUuid, colUsername, colReason, tblPermbans, tblPermbanIps, colId, colPermbanId, colIp);
        return findOne(sql, ip);
    }

    @Override
    public int getPermbanId(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colId, tblPermbans, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next()) return rs.getInt("id");
        }
        return -1;
    }

    @Override
    public int getPermbanIdByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                colId, tblPermbans, adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next()) return rs.getInt("id");
        }
        return -1;
    }

    @Override
    public List<String> getIps(int permbanId) throws SQLException
    {
        List<String> ips = new ArrayList<>();
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colIp, tblPermbanIps, colPermbanId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, permbanId);
             ResultSet rs = stmt.executeQuery())
        {
            while (rs.next())
            {
                ips.add(rs.getString("ip"));
            }
        }
        return ips;
    }

    @Override
    public boolean isPermBanned(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tblPermbans, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean isPermBannedByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s",
                tblPermbans, adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean isPermBannedByIp(String ip) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s p INNER JOIN %s pi ON p.%s = pi.%s WHERE pi.%s = ?",
                tblPermbans, tblPermbanIps, colId, colPermbanId, colIp);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, ip);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean update(PermBan permban) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = %s WHERE %s = ?",
                tblPermbans, colUsername, colReason, colUpdatedAt, adapter.currentTimestamp(), colUuid);

        int rows = statementHandler.executeUpdate(sql,
                permban.getUsername(),
                permban.getReason(),
                permban.getUuid().toString());

        return rows > 0;
    }

    @Override
    public boolean updateReason(UUID uuid, String reason) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ? WHERE %s = ?", tblPermbans, colReason, colUuid);
        return statementHandler.executeUpdate(sql, reason, uuid.toString()) > 0;
    }

    @Override
    public void syncIps(int permbanId, List<String> ips) throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s WHERE %s = ?", tblPermbanIps, colPermbanId), permbanId);
        insertIps(permbanId, ips);
    }

    @Override
    public boolean delete(UUID uuid) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblPermbans, colUuid);
        return statementHandler.executeUpdate(sql, uuid.toString()) > 0;
    }

    @Override
    public boolean deleteByUsername(String username) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s", tblPermbans, adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, username) > 0;
    }

    @Override
    public boolean deleteByIp(String ip) throws SQLException
    {
        String selectSql = String.format("SELECT %s FROM %s WHERE %s = ?", colPermbanId, tblPermbanIps, colIp);
        List<Integer> permbanIds = new ArrayList<>();
        try (PreparedStatement stmt = statementHandler.prepareStatement(selectSql, ip);
             ResultSet rs = stmt.executeQuery())
        {
            while (rs.next())
            {
                permbanIds.add(rs.getInt("permban_id"));
            }
        }

        String deleteSql = String.format("DELETE FROM %s WHERE %s = ?", tblPermbans, colId);
        for (int permbanId : permbanIds)
        {
            statementHandler.executeUpdate(deleteSql, permbanId);
        }

        return !permbanIds.isEmpty();
    }

    @Override
    public boolean removeIp(int permbanId, String ip) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ? AND %s = ?", tblPermbanIps, colPermbanId, colIp);
        return statementHandler.executeUpdate(sql, permbanId, ip) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblPermbanIps));
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblPermbans));
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblPermbans);
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
    public Mono<List<PermBan>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Integer> insertAsync(PermBan permban)
    {
        return statementHandler.supplyMono(() -> insert(permban));
    }

    @Override
    public Mono<Boolean> updateAsync(PermBan permban)
    {
        return statementHandler.supplyMono(() -> update(permban));
    }

    @Override
    public Mono<Boolean> deleteAsync(UUID uuid)
    {
        return statementHandler.supplyMono(() -> delete(uuid));
    }

    @Override
    public Mono<Boolean> deleteByIpAsync(String ip)
    {
        return statementHandler.supplyMono(() -> deleteByIp(ip));
    }

    @Override
    public Mono<Integer> save(PermBan permban)
    {
        return statementHandler.supplyMono(() -> {
            if (permban.getUuid() != null && getPermbanId(permban.getUuid()) > 0)
            {
                update(permban);
                return getPermbanId(permban.getUuid());
            }
            return insert(permban);
        });
    }

    @Override
    public Mono<List<PermBan>> findAll()
    {
        return loadAllAsync();
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    private PermBan loadPermbanFromRow(ResultSet rs) throws SQLException
    {
        String uuidStr = rs.getString("uuid");
        String username = rs.getString("username");
        String reason = rs.getString("reason");

        PermBan permban = new PermBan();
        permban.setUuid(uuidStr != null ? UUID.fromString(uuidStr) : null);
        permban.setUsername(username);
        permban.setReason(reason);

        return permban;
    }
}
