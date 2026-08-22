package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.kyori.adventure.text.format.NamedTextColor;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.TitleRepository;
import me.totalfreedom.totalfreedommod.title.Title;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericTitleRepository implements TitleRepository
{

    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblTitles;
    private final String tblTitlePermissions;
    private final String colId;
    private final String colName;
    private final String colDeterminer;
    private final String colAbbreviation;
    private final String colColor;
    private final String colPrefix;
    private final String colWeight;
    private final String colAnnounce;
    private final String colTitleId;
    private final String colPermission;
    private final String colUpdatedAt;
    private final String selectColumns;

    public GenericTitleRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblTitles = adapter.quoteIdentifier("titles");
        this.tblTitlePermissions = adapter.quoteIdentifier("title_permissions");
        this.colId = adapter.quoteIdentifier("id");
        this.colName = adapter.quoteIdentifier("name");
        this.colDeterminer = adapter.quoteIdentifier("determiner");
        this.colAbbreviation = adapter.quoteIdentifier("abbreviation");
        this.colColor = adapter.quoteIdentifier("color");
        this.colPrefix = adapter.quoteIdentifier("prefix");
        this.colWeight = adapter.quoteIdentifier("weight");
        this.colAnnounce = adapter.quoteIdentifier("announce");
        this.colTitleId = adapter.quoteIdentifier("title_id");
        this.colPermission = adapter.quoteIdentifier("permission");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s, %s, %s, %s, %s",
                colId, colName, colDeterminer, colAbbreviation, colColor, colPrefix, colWeight, colAnnounce);
    }

    @Override
    public void insert(Title title) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, %s)",
                tblTitles, selectColumns, colUpdatedAt, adapter.currentTimestamp());

        statementHandler.executeUpdate(sql,
                title.getId(),
                title.getName(),
                title.getDeterminer(),
                title.getAbbreviation(),
                serializeColor(title.getColor()),
                title.getPrefix(),
                title.getWeight(),
                title.isAnnounce());

        insertPermissions(title.getId(), title.getPermissions());
    }

    @Override
    public void insertPermissions(String titleId, Set<String> permissions) throws SQLException
    {
        if (permissions == null || permissions.isEmpty()) return;

        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblTitlePermissions, colTitleId, colPermission,
                adapter.insertIgnoreSuffix());

        for (String permission : permissions)
        {
            statementHandler.executeUpdate(sql, titleId, permission);
        }
    }

    @Override
    public void addPermission(String titleId, String permission) throws SQLException
    {
        String sql = String.format("%s INTO %s (%s, %s) VALUES (?, ?)%s",
                adapter.insertIgnoreSyntax(), tblTitlePermissions, colTitleId, colPermission,
                adapter.insertIgnoreSuffix());
        statementHandler.executeUpdate(sql, titleId, permission);
    }

    @Override
    public Map<String, Title> loadAll() throws SQLException
    {
        Map<String, Title> titles = new LinkedHashMap<>();

        String sql = String.format("SELECT %s FROM %s", selectColumns, tblTitles);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                Title title = loadTitleFromRow(rs);
                titles.put(title.getId(), title);
            }
        }

        // One flat read of the child table rather than a query per title: the cursor above is
        // already closed, and a nested read would hold two connections at once, which SQLite's
        // single-connection pool cannot serve.
        String permSql = String.format("SELECT %s, %s FROM %s", colTitleId, colPermission, tblTitlePermissions);
        try (ResultSet rs = statementHandler.executeQuery(permSql))
        {
            while (rs.next())
            {
                Title title = titles.get(rs.getString("title_id"));
                if (title != null)
                {
                    title.addPermission(rs.getString("permission"));
                }
            }
        }

        return titles;
    }

    @Override
    public Title findById(String id) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblTitles, colId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, id);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
            {
                Title title = loadTitleFromRow(rs);
                getPermissions(title.getId()).forEach(title::addPermission);
                return title;
            }
        }
        return null;
    }

    @Override
    public boolean exists(String id) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tblTitles, colId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, id);
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public Set<String> getPermissions(String titleId) throws SQLException
    {
        Set<String> permissions = new HashSet<>();
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", colPermission, tblTitlePermissions, colTitleId);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, titleId);
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
    public boolean update(Title title) throws SQLException
    {
        String sql = String.format(
                "UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = %s WHERE %s = ?",
                tblTitles, colName, colDeterminer, colAbbreviation, colColor, colPrefix, colWeight,
                colAnnounce, colUpdatedAt, adapter.currentTimestamp(), colId);

        int rows = statementHandler.executeUpdate(sql,
                title.getName(),
                title.getDeterminer(),
                title.getAbbreviation(),
                serializeColor(title.getColor()),
                title.getPrefix(),
                title.getWeight(),
                title.isAnnounce(),
                title.getId());

        return rows > 0;
    }

    @Override
    public void syncPermissions(String titleId, Set<String> permissions) throws SQLException
    {
        statementHandler.executeUpdate(
                String.format("DELETE FROM %s WHERE %s = ?", tblTitlePermissions, colTitleId), titleId);
        insertPermissions(titleId, permissions);
    }

    @Override
    public void saveOrUpdate(Title title) throws SQLException
    {
        if (exists(title.getId()))
        {
            update(title);
            syncPermissions(title.getId(), title.getPermissions());
        }
        else
        {
            insert(title);
        }
    }

    @Override
    public boolean delete(String id) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblTitles, colId);
        return statementHandler.executeUpdate(sql, id) > 0;
    }

    @Override
    public boolean removePermission(String titleId, String permission) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ? AND %s = ?",
                tblTitlePermissions, colTitleId, colPermission);
        return statementHandler.executeUpdate(sql, titleId, permission) > 0;
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblTitles);
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
    public Mono<Map<String, Title>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> save(Title title)
    {
        return statementHandler.runMono(() -> saveOrUpdate(title));
    }

    @Override
    public Mono<Boolean> deleteAsync(String id)
    {
        return statementHandler.supplyMono(() -> delete(id));
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblTitlePermissions));
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblTitles));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    private Title loadTitleFromRow(ResultSet rs) throws SQLException
    {
        Title title = new Title(rs.getString("id"));
        title.setName(rs.getString("name"));
        title.setDeterminer(rs.getString("determiner"));
        title.setAbbreviation(rs.getString("abbreviation"));
        title.setColor(parseColor(rs.getString("color")));
        title.setPrefix(rs.getString("prefix"));
        title.setWeight(rs.getInt("weight"));
        title.setAnnounce(rs.getBoolean("announce"));
        return title;
    }

    private static String serializeColor(NamedTextColor color)
    {
        return NamedTextColor.NAMES.keyOrThrow(color);
    }

    private static NamedTextColor parseColor(String name)
    {
        if (name == null)
            return NamedTextColor.WHITE;

        final NamedTextColor color = NamedTextColor.NAMES.value(name.toLowerCase());

        return color != null ? color : NamedTextColor.WHITE;
    }

}
