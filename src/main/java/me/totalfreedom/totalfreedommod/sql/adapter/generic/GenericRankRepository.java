package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

import net.kyori.adventure.text.format.NamedTextColor;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRole;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.RankRepository;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericRankRepository implements RankRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblRanks;
    private final String tblRankPermissions;
    private final String colId;
    private final String colName;
    private final String colDeterminer;
    private final String colAbbreviation;
    private final String colLevel;
    private final String colColor;
    private final String colAdmin;
    private final String colPrefix;
    private final String colInheritFrom;
    private final String colRoles;
    private final String colRankId;
    private final String colPermission;
    private final String colUpdatedAt;
    private final String selectColumns;

    public GenericRankRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblRanks = adapter.quoteIdentifier("ranks");
        this.tblRankPermissions = adapter.quoteIdentifier("rank_permissions");
        this.colId = adapter.quoteIdentifier("id");
        this.colName = adapter.quoteIdentifier("name");
        this.colDeterminer = adapter.quoteIdentifier("determiner");
        this.colAbbreviation = adapter.quoteIdentifier("abbreviation");
        this.colLevel = adapter.quoteIdentifier("level");
        this.colColor = adapter.quoteIdentifier("color");
        this.colAdmin = adapter.quoteIdentifier("admin");
        this.colPrefix = adapter.quoteIdentifier("prefix");
        this.colInheritFrom = adapter.quoteIdentifier("inherit_from");
        this.colRoles = adapter.quoteIdentifier("roles");
        this.colRankId = adapter.quoteIdentifier("rank_id");
        this.colPermission = adapter.quoteIdentifier("permission");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s, %s, %s, %s, %s, %s, %s",
                colId, colName, colDeterminer, colAbbreviation, colLevel, colColor, colAdmin,
                colPrefix, colInheritFrom, colRoles);
    }

    @Override
    public void insert(CustomRank rank) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s)",
                tblRanks, selectColumns, colUpdatedAt, adapter.currentTimestamp());

        statementHandler.executeUpdate(sql,
                rank.getId(),
                rank.getName(),
                rank.getDeterminer(),
                rank.getAbbreviation(),
                rank.getLevel(),
                serializeColor(rank.getColor()),
                rank.isAdmin(),
                rank.getPrefix(),
                rank.getInheritFrom(),
                serializeRoles(rank.getRoles()));

        insertPermissions(rank.getId(), rank.getPermissions());
    }

    @Override
    public void insertPermissions(String rankId, Set<String> permissions) throws SQLException
    {
        if (permissions == null || permissions.isEmpty()) return;

        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblRankPermissions, colRankId, colPermission, adapter.insertIgnoreSuffix());
        for (String permission : permissions)
        {
            statementHandler.executeUpdate(sql, rankId, permission);
        }
    }

    @Override
    public void addPermission(String rankId, String permission) throws SQLException
    {
        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblRankPermissions, colRankId, colPermission, adapter.insertIgnoreSuffix());
        statementHandler.executeUpdate(sql, rankId, permission);
    }

    @Override
    public Map<String, CustomRank> loadAll() throws SQLException
    {
        Map<String, CustomRank> ranks = new LinkedHashMap<>();

        String sql = String.format("SELECT %s FROM %s", selectColumns, tblRanks);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                CustomRank rank = loadRankFromRow(rs);
                ranks.put(rank.getId(), rank);
            }
        }

        String permSql = String.format("SELECT %s, %s FROM %s", colRankId, colPermission, tblRankPermissions);
        try (ResultSet rs = statementHandler.executeQuery(permSql))
        {
            while (rs.next())
            {
                CustomRank rank = ranks.get(rs.getString("rank_id"));
                if (rank != null)
                {
                    rank.addPermission(rs.getString("permission"));
                }
            }
        }

        return ranks;
    }

    @Override
    public CustomRank findById(String id) throws SQLException
    {
        CustomRank rank = null;
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblRanks, colId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, id);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
            {
                rank = loadRankFromRow(rs);
            }
        }

        if (rank == null)
        {
            return null;
        }

        getPermissions(rank.getId()).forEach(rank::addPermission);
        return rank;
    }

    @Override
    public boolean exists(String id) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tblRanks, colId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, id);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public Set<String> getPermissions(String rankId) throws SQLException
    {
        Set<String> permissions = new HashSet<>();
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colPermission, tblRankPermissions, colRankId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, rankId);
             ResultSet rs = stmt.executeQuery())
        {
            while (rs.next())
            {
                permissions.add(rs.getString("permission"));
            }
        }
        return permissions;
    }

    @Override
    public boolean update(CustomRank rank) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = %s WHERE %s = ?",
                tblRanks, colName, colDeterminer, colAbbreviation, colLevel, colColor, colAdmin,
                colPrefix, colInheritFrom, colRoles, colUpdatedAt, adapter.currentTimestamp(), colId);

        int rows = statementHandler.executeUpdate(sql,
                rank.getName(),
                rank.getDeterminer(),
                rank.getAbbreviation(),
                rank.getLevel(),
                serializeColor(rank.getColor()),
                rank.isAdmin(),
                rank.getPrefix(),
                rank.getInheritFrom(),
                serializeRoles(rank.getRoles()),
                rank.getId());

        return rows > 0;
    }

    @Override
    public void syncPermissions(String rankId, Set<String> permissions) throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s WHERE %s = ?", tblRankPermissions, colRankId), rankId);
        insertPermissions(rankId, permissions);
    }

    @Override
    public void saveOrUpdate(CustomRank rank) throws SQLException
    {
        if (exists(rank.getId()))
        {
            update(rank);
            syncPermissions(rank.getId(), rank.getPermissions());
        }
        else
        {
            insert(rank);
        }
    }

    @Override
    public boolean delete(String id) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblRanks, colId);
        return statementHandler.executeUpdate(sql, id) > 0;
    }

    @Override
    public boolean removePermission(String rankId, String permission) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ? AND %s = ?", tblRankPermissions, colRankId, colPermission);
        return statementHandler.executeUpdate(sql, rankId, permission) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblRankPermissions));
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblRanks));
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblRanks);
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
    public Mono<Map<String, CustomRank>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> save(CustomRank rank)
    {
        return statementHandler.runMono(() -> saveOrUpdate(rank));
    }

    @Override
    public Mono<Boolean> deleteAsync(String id)
    {
        return statementHandler.supplyMono(() -> delete(id));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    private CustomRank loadRankFromRow(ResultSet rs) throws SQLException
    {
        String id = rs.getString("id");
        CustomRank rank = new CustomRank(id);
        rank.setName(rs.getString("name"));
        rank.setDeterminer(rs.getString("determiner"));
        rank.setAbbreviation(rs.getString("abbreviation"));
        rank.setLevel(rs.getInt("level"));
        rank.setColor(parseColor(rs.getString("color")));
        rank.setAdmin(rs.getBoolean("admin"));
        rank.setPrefix(rs.getString("prefix"));
        rank.setInheritFrom(rs.getString("inherit_from"));
        rank.setRoles(parseRoles(rs.getString("roles")));
        return rank;
    }

    /**
     * Roles are stored as a comma-separated list in one column rather than as a child table: the
     * set is tiny, closed, and always read with the rank it belongs to, so a join would cost more
     * than it saves.
     */
    private static String serializeRoles(Set<RankRole> roles)
    {
        return roles == null || roles.isEmpty()
                ? null
                : roles.stream()
                       .filter(Objects::nonNull)
                       .map(RankRole::getId)
                       .collect(Collectors.joining(","));
    }

    /**
     * Reads roles back, ignoring any name this build does not know so that a database written by a
     * newer version still loads.
     */
    private static Set<RankRole> parseRoles(String stored)
    {
        if (stored == null || stored.isBlank())
            return EnumSet.noneOf(RankRole.class);

        return Arrays.stream(stored.split(","))
                     .map(RankRole::fromId)
                     .filter(Optional::isPresent)
                     .map(Optional::get)
                     .collect(Collectors.toCollection(() -> EnumSet.noneOf(RankRole.class)));
    }

    private static String serializeColor(NamedTextColor color)
    {
        return NamedTextColor.NAMES.keyOrThrow(color);
    }

    private static NamedTextColor parseColor(String name)
    {
        NamedTextColor color = name == null ? null : NamedTextColor.NAMES.value(name.toLowerCase());
        return color != null ? color : NamedTextColor.WHITE;
    }
}
