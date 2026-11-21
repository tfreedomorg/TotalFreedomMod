package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.BanRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericBanRepository implements BanRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblBans;
    private final String tblBanIps;
    private final String colId;
    private final String colUuid;
    private final String colUsername;
    private final String colBannedBy;
    private final String colBannedByUuid;
    private final String colReason;
    private final String colExpireAt;
    private final String colBanId;
    private final String colIp;
    private final String colUpdatedAt;
    private final String selectColumns;

    public GenericBanRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblBans = adapter.quoteIdentifier("bans");
        this.tblBanIps = adapter.quoteIdentifier("ban_ips");
        this.colId = adapter.quoteIdentifier("id");
        this.colUuid = adapter.quoteIdentifier("uuid");
        this.colUsername = adapter.quoteIdentifier("username");
        this.colBannedBy = adapter.quoteIdentifier("banned_by");
        this.colBannedByUuid = adapter.quoteIdentifier("banned_by_uuid");
        this.colReason = adapter.quoteIdentifier("reason");
        this.colExpireAt = adapter.quoteIdentifier("expire_at");
        this.colBanId = adapter.quoteIdentifier("ban_id");
        this.colIp = adapter.quoteIdentifier("ip");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s, %s, %s, %s",
                colId, colUuid, colUsername, colBannedBy, colBannedByUuid, colReason, colExpireAt);
    }

    @Override
    public int insert(Ban ban) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s, %s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, ?, %s, %s)",
                tblBans, colUuid, colUsername, colBannedBy, colBannedByUuid, colReason, colExpireAt, colUpdatedAt,
                adapter.timestampParamPlaceholder(), adapter.currentTimestamp());

        long banId = statementHandler.executeUpdateReturnKey(sql,
                ban.getUuid() != null ? ban.getUuid().toString() : null,
                ban.getUsername(),
                ban.getBannedBy(),
                ban.getBannedByUuid() != null ? ban.getBannedByUuid().toString() : null,
                ban.getReason(),
                ban.getExpireAt() != null ? FUtil.dateToString(ban.getExpireAt()) : null);

        if (banId < 0)
        {
            return -1;
        }
        insertIps((int) banId, ban.getIps());
        return (int) banId;
    }

    @Override
    public void insertIps(int banId, List<String> ips) throws SQLException
    {
        if (ips == null || ips.isEmpty()) return;

        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblBanIps, colBanId, colIp, adapter.insertIgnoreSuffix());
        for (String ip : ips)
        {
            statementHandler.executeUpdate(sql, banId, ip);
        }
    }

    @Override
    public void addIp(int banId, String ip) throws SQLException
    {
        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblBanIps, colBanId, colIp, adapter.insertIgnoreSuffix());
        statementHandler.executeUpdate(sql, banId, ip);
    }

    @Override
    public List<Ban> loadAll() throws SQLException
    {
        List<Ban> bans = new ArrayList<>();
        Map<Integer, Ban> banById = new HashMap<>();

        String sql = String.format("SELECT %s FROM %s", selectColumns, tblBans);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                int id = rs.getInt("id");
                Ban ban = loadBanFromRow(rs);
                bans.add(ban);
                banById.put(id, ban);
            }
        }

        attachIps(banById);

        return bans;
    }

    /**
     * Fold the {@code ban_ips} child rows into bans that have already been read.
     * One flat query, run after the caller's cursor is closed: a nested read would hold two
     * connections at once, which SQLite's single-connection pool cannot serve.
     */
    private void attachIps(Map<Integer, Ban> banById) throws SQLException
    {
        if (banById.isEmpty())
            return;

        String ipSql = String.format("SELECT %s, %s FROM %s", colBanId, colIp, tblBanIps);
        try (ResultSet rs = statementHandler.executeQuery(ipSql))
        {
            while (rs.next())
            {
                Ban ban = banById.get(rs.getInt("ban_id"));
                if (ban != null)
                {
                    ban.addIp(rs.getString("ip"));
                }
            }
        }
    }

    /**
     * Read a single ban and its IPs, closing the row's statement before looking the IPs up.
     */
    private Ban findOne(String sql, Object... params) throws SQLException
    {
        final Ban ban;
        final int banId;
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, params);
             ResultSet rs = stmt.executeQuery())
        {
            if (!rs.next())
                return null;

            ban = loadBanFromRow(rs);
            banId = rs.getInt("id");
        }

        ban.setIps(getIps(banId));
        return ban;
    }

    /**
     * Read every ban matching {@code sql}, then attach IPs once the cursor is closed.
     */
    private List<Ban> findMany(final String sql) throws SQLException
    {
        List<Ban> bans = new ArrayList<>();
        Map<Integer, Ban> banById = new HashMap<>();

        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                Ban ban = loadBanFromRow(rs);
                bans.add(ban);
                banById.put(rs.getInt("id"), ban);
            }
        }

        attachIps(banById);
        return bans;
    }

    @Override
    public Ban findByUuid(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblBans, colUuid);
        return findOne(sql, uuid.toString());
    }

    @Override
    public Ban findByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                selectColumns, tblBans, adapter.caseInsensitiveEquals(colUsername, "?"));
        return findOne(sql, username);
    }

    @Override
    public Ban findByIp(String ip) throws SQLException
    {
        String sql = String.format(
                "SELECT b.%s, b.%s, b.%s, b.%s, b.%s, b.%s, b.%s FROM %s b INNER JOIN %s bi ON b.%s = bi.%s WHERE bi.%s = ?",
                colId, colUuid, colUsername, colBannedBy, colBannedByUuid, colReason, colExpireAt,
                tblBans, tblBanIps, colId, colBanId, colIp);
        return findOne(sql, ip);
    }

    @Override
    public List<Ban> findActiveBans() throws SQLException
    {
        return findMany(String.format("SELECT %s FROM %s WHERE %s IS NULL OR %s",
                selectColumns, tblBans, colExpireAt, adapter.compareToNow(colExpireAt, ">")));
    }

    @Override
    public List<Ban> findExpiredBans() throws SQLException
    {
        return findMany(String.format("SELECT %s FROM %s WHERE %s IS NOT NULL AND %s",
                selectColumns, tblBans, colExpireAt, adapter.compareToNow(colExpireAt, "<=")));
    }

    @Override
    public int getBanId(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colId, tblBans, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next()) return rs.getInt("id");
        }
        return -1;
    }

    @Override
    public int getBanIdByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                colId, tblBans, adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next()) return rs.getInt("id");
        }
        return -1;
    }

    @Override
    public List<String> getIps(int banId) throws SQLException
    {
        List<String> ips = new ArrayList<>();
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colIp, tblBanIps, colBanId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, banId);
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
    public boolean isBanned(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ? AND (%s IS NULL OR %s)",
                tblBans, colUuid, colExpireAt, adapter.compareToNow(colExpireAt, ">"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean isBannedByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s AND (%s IS NULL OR %s)",
                tblBans, adapter.caseInsensitiveEquals(colUsername, "?"), colExpireAt, adapter.compareToNow(colExpireAt, ">"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean isBannedByIp(String ip) throws SQLException
    {
        String sql = String.format(
                "SELECT COUNT(*) FROM %s b INNER JOIN %s bi ON b.%s = bi.%s WHERE bi.%s = ? AND (b.%s IS NULL OR %s)",
                tblBans, tblBanIps, colId, colBanId, colIp, colExpireAt, adapter.compareToNow("b." + colExpireAt, ">"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, ip);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean update(Ban ban) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ?, %s = %s, %s = %s WHERE %s = ?",
                tblBans, colUsername, colBannedBy, colBannedByUuid, colReason, colExpireAt,
                adapter.timestampParamPlaceholder(), colUpdatedAt, adapter.currentTimestamp(), colUuid);

        int rows = statementHandler.executeUpdate(sql,
                ban.getUsername(),
                ban.getBannedBy(),
                ban.getBannedByUuid() != null ? ban.getBannedByUuid().toString() : null,
                ban.getReason(),
                ban.getExpireAt() != null ? FUtil.dateToString(ban.getExpireAt()) : null,
                ban.getUuid().toString());

        return rows > 0;
    }

    @Override
    public boolean updateReason(UUID uuid, String reason) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ? WHERE %s = ?", tblBans, colReason, colUuid);
        return statementHandler.executeUpdate(sql, reason, uuid.toString()) > 0;
    }

    @Override
    public boolean updateExpiry(UUID uuid, Date expireAt) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = %s WHERE %s = ?",
                tblBans, colExpireAt, adapter.timestampParamPlaceholder(), colUuid);
        return statementHandler.executeUpdate(sql, expireAt != null ? FUtil.dateToString(expireAt) : null, uuid.toString()) > 0;
    }

    @Override
    public void syncIps(int banId, List<String> ips) throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s WHERE %s = ?", tblBanIps, colBanId), banId);
        insertIps(banId, ips);
    }

    @Override
    public boolean delete(UUID uuid) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblBans, colUuid);
        return statementHandler.executeUpdate(sql, uuid.toString()) > 0;
    }

    @Override
    public boolean deleteByUsername(String username) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s", tblBans, adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, username) > 0;
    }

    @Override
    public boolean deleteByIp(String ip) throws SQLException
    {
        String selectSql = String.format("SELECT %s FROM %s WHERE %s = ?", colBanId, tblBanIps, colIp);
        List<Integer> banIds = new ArrayList<>();
        try (PreparedStatement stmt = statementHandler.prepareStatement(selectSql, ip);
             ResultSet rs = stmt.executeQuery())
        {
            while (rs.next())
            {
                banIds.add(rs.getInt("ban_id"));
            }
        }

        String deleteSql = String.format("DELETE FROM %s WHERE %s = ?", tblBans, colId);
        for (int banId : banIds)
        {
            statementHandler.executeUpdate(deleteSql, banId);
        }

        return !banIds.isEmpty();
    }

    @Override
    public boolean removeIp(int banId, String ip) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ? AND %s = ?", tblBanIps, colBanId, colIp);
        return statementHandler.executeUpdate(sql, banId, ip) > 0;
    }

    @Override
    public int deleteExpiredBans() throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s IS NOT NULL AND %s",
                tblBans, colExpireAt, adapter.compareToNow(colExpireAt, "<="));
        return statementHandler.executeUpdate(sql);
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblBanIps));
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblBans));
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblBans);
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
    public Mono<List<Ban>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Integer> insertAsync(Ban ban)
    {
        return statementHandler.supplyMono(() -> insert(ban));
    }

    @Override
    public Mono<Boolean> updateAsync(Ban ban)
    {
        return statementHandler.supplyMono(() -> update(ban));
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
    public Mono<Integer> save(Ban ban)
    {
        return statementHandler.supplyMono(() -> 
        {
            final int existing = findBanId(ban);
            if (existing <= 0)
                return insert(ban);

            updateById(existing, ban);
            syncIps(existing, ban.getIps());
            return existing;
        });
    }

     private int findBanId(final Ban ban) throws SQLException
     {
        if (ban.getUuid() != null)
            return getBanId(ban.getUuid());

        if (ban.hasUsername())
            return getBanIdByUsername(ban.getUsername());

        for (final String ip : ban.getIps())
        {
            final int id = getBanIdByIp(ip);
            if (id > 0)
                return id;
        }

        return -1;
     }

     private int getBanIdByIp(final String ip) throws SQLException
     {
        String sql = String.format(
            "SELECT b.%s FROM %s b INNER JOIN %s i ON b.%s = i.%s WHERE i.%s = ? AND b.%s IS NULL AND b.%s IS NULL",
            colId, tblBans, tblBanIps, colId, colBanId, colIp, colUuid, colUsername);

        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, ip);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
                return rs.getInt(1);
        }

        return -1;
     }

     private boolean updateById(final int banId, final Ban ban) throws SQLException
     {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ?, %s = %s, %s = %s WHERE %s = ?",
                                   tblBans, colUsername, colBannedBy, colBannedByUuid, colReason, colExpireAt,
                                   adapter.timestampParamPlaceholder(), colUpdatedAt, adapter.currentTimestamp(), colId);

        return statementHandler.executeUpdate(sql,
                ban.getUsername(),
                ban.getBannedBy(),
                ban.getBannedByUuid() != null ? ban.getBannedByUuid().toString() : null,
                ban.getReason(),
                ban.getExpireAt() != null ? FUtil.dateToString(ban.getExpireAt()) : null,
                banId) > 0;
     }

    @Override
    public Mono<List<Ban>> findAll()
    {
        return loadAllAsync();
    }

    @Override
    public Mono<Boolean> deleteByUuid(UUID uuid)
    {
        return deleteAsync(uuid);
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    private Ban loadBanFromRow(ResultSet rs) throws SQLException
    {
        String uuidStr = rs.getString("uuid");
        String username = rs.getString("username");
        String bannedBy = rs.getString("banned_by");
        String bannedByUuidStr = rs.getString("banned_by_uuid");
        String reason = rs.getString("reason");
        String expireAtStr = rs.getString("expire_at");

        Ban ban = new Ban();
        ban.setUuid(uuidStr != null ? UUID.fromString(uuidStr) : null);
        ban.setUsername(username);
        ban.setBannedBy(bannedBy);
        ban.setBannedByUuid(bannedByUuidStr != null ? UUID.fromString(bannedByUuidStr) : null);
        ban.setReason(reason);
        ban.setExpireAt(expireAtStr != null ? FUtil.stringToDate(expireAtStr) : null);

        return ban;
    }
}
