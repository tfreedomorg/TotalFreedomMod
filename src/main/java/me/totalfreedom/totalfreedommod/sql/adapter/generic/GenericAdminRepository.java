package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.AdminRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericAdminRepository implements AdminRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblAdmins;
    private final String tblAdminIps;
    private final String colId;
    private final String colUuid;
    private final String colUsername;
    private final String colRank;
    private final String colActive;
    private final String colLastLogin;
    private final String colLoginMessage;
    private final String colCustomRank;
    private final String colAdminId;
    private final String colIp;
    private final String colUpdatedAt;
    private final String selectColumns;

    public GenericAdminRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblAdmins = adapter.quoteIdentifier("admins");
        this.tblAdminIps = adapter.quoteIdentifier("admin_ips");
        this.colId = adapter.quoteIdentifier("id");
        this.colUuid = adapter.quoteIdentifier("uuid");
        this.colUsername = adapter.quoteIdentifier("username");
        this.colRank = adapter.quoteIdentifier("rank");
        this.colActive = adapter.quoteIdentifier("active");
        this.colLastLogin = adapter.quoteIdentifier("last_login");
        this.colLoginMessage = adapter.quoteIdentifier("login_message");
        this.colCustomRank = adapter.quoteIdentifier("custom_rank");
        this.colAdminId = adapter.quoteIdentifier("admin_id");
        this.colIp = adapter.quoteIdentifier("ip");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s, %s, %s, %s, %s",
                colId, colUuid, colUsername, colRank, colActive, colLastLogin, colLoginMessage, colCustomRank);
    }

    @Override
    public int insert(UUID uuid, Admin admin) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s, %s, %s, %s, %s, %s, %s) VALUES (?, ?, ?, ?, %s, ?, ?, %s)",
                tblAdmins, colUuid, colUsername, colRank, colActive, colLastLogin, colLoginMessage, colCustomRank,
                colUpdatedAt, adapter.timestampParamPlaceholder(), adapter.currentTimestamp());

        long adminId = statementHandler.executeUpdateReturnKey(sql,
                uuid.toString(),
                admin.getName(),
                admin.getRankId(),
                admin.isActive(),
                FUtil.dateToString(admin.getLastLogin()),
                admin.getLoginMessage(),
                null);

        if (adminId < 0)
        {
            return -1;
        }
        insertIps((int) adminId, admin.getIps());
        return (int) adminId;
    }

    @Override
    public void insertIps(int adminId, List<String> ips) throws SQLException
    {
        if (ips == null || ips.isEmpty()) return;

        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblAdminIps, colAdminId, colIp, adapter.insertIgnoreSuffix());
        for (String ip : ips)
        {
            statementHandler.executeUpdate(sql, adminId, ip);
        }
    }

    @Override
    public void addIp(int adminId, String ip) throws SQLException
    {
        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblAdminIps, colAdminId, colIp, adapter.insertIgnoreSuffix());
        statementHandler.executeUpdate(sql, adminId, ip);
    }

    @Override
    public Map<String, Admin> loadAll() throws SQLException
    {
        Map<String, Admin> admins = new HashMap<>();
        Map<Integer, Admin> adminById = new HashMap<>();

        String sql = String.format("SELECT %s FROM %s", selectColumns, tblAdmins);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                int id = rs.getInt("id");
                Admin admin = loadAdminFromRow(rs);
                admins.put(admin.getName().toLowerCase(), admin);
                adminById.put(id, admin);
            }
        }

        attachIps(adminById);

        return admins;
    }

    /**
     * Fold the {@code admin_ips} child rows into admins that have already been read.
     * One flat query, run after the caller's cursor is closed: a nested read would hold two
     * connections at once, which SQLite's single-connection pool cannot serve.
     */
    private void attachIps(Map<Integer, Admin> adminById) throws SQLException
    {
        if (adminById.isEmpty())
            return;

        String ipSql = String.format("SELECT %s, %s FROM %s", colAdminId, colIp, tblAdminIps);
        try (ResultSet rs = statementHandler.executeQuery(ipSql))
        {
            while (rs.next())
            {
                Admin admin = adminById.get(rs.getInt("admin_id"));
                if (admin != null)
                {
                    admin.addIp(rs.getString("ip"));
                }
            }
        }
    }

    /**
     * Read a single admin and its IPs, closing the row's statement before looking the IPs up.
     */
    private Admin findOne(String sql, Object... params) throws SQLException
    {
        final Admin admin;
        final int adminId;
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, params);
             ResultSet rs = stmt.executeQuery())
        {
            if (!rs.next())
                return null;

            admin = loadAdminFromRow(rs);
            adminId = rs.getInt("id");
        }

        admin.addIps(getIps(adminId));
        return admin;
    }

    @Override
    public Admin findByUuid(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblAdmins, colUuid);
        return findOne(sql, uuid.toString());
    }

    @Override
    public Admin findByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                selectColumns, tblAdmins, adapter.caseInsensitiveEquals(colUsername, "?"));
        return findOne(sql, username);
    }

    @Override
    public Admin findByIp(String ip) throws SQLException
    {
        String sql = String.format(
                "SELECT a.%s, a.%s, a.%s, a.%s, a.%s, a.%s, a.%s, a.%s FROM %s a INNER JOIN %s ai ON a.%s = ai.%s WHERE ai.%s = ?",
                colId, colUuid, colUsername, colRank, colActive, colLastLogin, colLoginMessage, colCustomRank,
                tblAdmins, tblAdminIps, colId, colAdminId, colIp);
        return findOne(sql, ip);
    }

    @Override
    public int getAdminId(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                colId, tblAdmins, adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next()) return rs.getInt("id");
        }
        return -1;
    }

    @Override
    public int getAdminIdByUuid(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colId, tblAdmins, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next()) return rs.getInt("id");
        }
        return -1;
    }

    @Override
    public List<String> getIps(int adminId) throws SQLException
    {
        List<String> ips = new ArrayList<>();
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colIp, tblAdminIps, colAdminId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, adminId);
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
    public boolean exists(String username) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s",
                tblAdmins, adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean existsByUuid(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tblAdmins, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public UUID getUuidByUsername(String username) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s",
                colUuid, tblAdmins, adapter.caseInsensitiveEquals(colUsername, "?"));
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, username);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
            {
                return UUID.fromString(rs.getString("uuid"));
            }
        }
        return null;
    }

    @Override
    public boolean update(UUID uuid, Admin admin) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = %s, %s = ?, %s = ?, %s = %s WHERE %s = ?",
                tblAdmins, colUsername, colRank, colActive, colLastLogin, adapter.timestampParamPlaceholder(),
                colLoginMessage, colCustomRank, colUpdatedAt, adapter.currentTimestamp(), colUuid);

        int rows = statementHandler.executeUpdate(sql,
                admin.getName(),
                admin.getRankId(),
                admin.isActive(),
                FUtil.dateToString(admin.getLastLogin()),
                admin.getLoginMessage(),
                null,
                uuid.toString());

        return rows > 0;
    }

    @Override
    public boolean updateRank(String username, String rank) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ? WHERE %s",
                tblAdmins, colRank, adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, rank, username) > 0;
    }

    @Override
    public boolean updateActive(String username, boolean active) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ? WHERE %s",
                tblAdmins, colActive, adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, active, username) > 0;
    }

    @Override
    public boolean updateLastLogin(String username, Date lastLogin) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = %s WHERE %s",
                tblAdmins, colLastLogin, adapter.timestampParamPlaceholder(), adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, FUtil.dateToString(lastLogin), username) > 0;
    }

    @Override
    public boolean updateUsername(UUID uuid, String newUsername) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ? WHERE %s = ?", tblAdmins, colUsername, colUuid);
        return statementHandler.executeUpdate(sql, newUsername, uuid.toString()) > 0;
    }

    @Override
    public void syncIps(int adminId, List<String> ips) throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s WHERE %s = ?", tblAdminIps, colAdminId), adminId);
        insertIps(adminId, ips);
    }

    @Override
    public int saveOrUpdate(UUID uuid, Admin admin) throws SQLException
    {
        if (existsByUuid(uuid))
        {
            update(uuid, admin);
            int adminId = getAdminIdByUuid(uuid);
            syncIps(adminId, admin.getIps());
            return adminId;
        }
        else
        {
            return insert(uuid, admin);
        }
    }

    @Override
    public boolean delete(UUID uuid) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblAdmins, colUuid);
        return statementHandler.executeUpdate(sql, uuid.toString()) > 0;
    }

    @Override
    public boolean deleteByUsername(String username) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s", tblAdmins, adapter.caseInsensitiveEquals(colUsername, "?"));
        return statementHandler.executeUpdate(sql, username) > 0;
    }

    @Override
    public boolean removeIp(int adminId, String ip) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ? AND %s = ?", tblAdminIps, colAdminId, colIp);
        return statementHandler.executeUpdate(sql, adminId, ip) > 0;
    }

    @Override
    public boolean clearIps(int adminId) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblAdminIps, colAdminId);
        return statementHandler.executeUpdate(sql, adminId) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblAdminIps));
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblAdmins));
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblAdmins);
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
    public Mono<Map<String, Admin>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Integer> insertAsync(UUID uuid, Admin admin)
    {
        return statementHandler.supplyMono(() -> insert(uuid, admin));
    }

    @Override
    public Mono<Boolean> updateAsync(UUID uuid, Admin admin)
    {
        return statementHandler.supplyMono(() -> update(uuid, admin));
    }

    @Override
    public Mono<Boolean> deleteAsync(UUID uuid)
    {
        return statementHandler.supplyMono(() -> delete(uuid));
    }

    @Override
    public Mono<Integer> save(UUID uuid, Admin admin)
    {
        return statementHandler.supplyMono(() -> saveOrUpdate(uuid, admin));
    }

    @Override
    public Mono<List<Admin>> findAll()
    {
        return statementHandler.supplyMono(() -> new ArrayList<>(loadAll().values()));
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

    /**
     * Picks the rank id out of the two columns admin rows may still carry.
     * <p>
     * {@code custom_rank} held the custom-assigned rank and took precedence over {@code rank},
     * which held a fixed tier name, so it is preferred here as well. Rows are rewritten with the id
     * in {@code rank} and {@code custom_rank} cleared, so this only matters until a row is next
     * saved. A tier name lowercases into an id, which is the convention {@code ranks.json} uses.
     */
    private static String resolveRankId(final String customRank, final String legacyRank)
    {
        if (customRank != null && !customRank.isBlank())
            return customRank.toLowerCase();

        return legacyRank == null || legacyRank.isBlank() ? null : legacyRank.toLowerCase();
    }

    private Admin loadAdminFromRow(ResultSet rs) throws SQLException
    {
        String username = rs.getString("username");
        String rankStr = rs.getString("rank");
        boolean active = rs.getBoolean("active");
        String lastLoginStr = rs.getString("last_login");
        String loginMessage = rs.getString("login_message");

        Admin admin = new Admin(username.toLowerCase());
        admin.setName(username);
        admin.setRankId(resolveRankId(rs.getString("custom_rank"), rankStr));
        admin.setActive(active);
        admin.setLastLogin(FUtil.stringToDate(lastLoginStr));
        admin.setLoginMessage(loginMessage);

        UUID dbUuid = FUtil.parseUuid(rs.getString("uuid"));
        if (dbUuid != null)
        {
            admin.setUuid(dbUuid);
        }

        return admin;
    }
}
