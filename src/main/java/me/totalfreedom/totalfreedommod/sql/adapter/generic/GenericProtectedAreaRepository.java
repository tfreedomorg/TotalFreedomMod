package me.totalfreedom.totalfreedommod.sql.adapter.generic;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion;
import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion.CantFindWorldException;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.DatabaseAdapter;
import me.totalfreedom.totalfreedommod.sql.adapter.ProtectedAreaRepository;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * All dialect differences are resolved through the {@link DatabaseAdapter} passed in.
 */
public class GenericProtectedAreaRepository implements ProtectedAreaRepository
{
    private final StatementHandler statementHandler;
    private final DatabaseAdapter adapter;

    private final String tblProtectedAreas;
    private final String colUuid;
    private final String colName;
    private final String colMinX;
    private final String colMinY;
    private final String colMinZ;
    private final String colMaxX;
    private final String colMaxY;
    private final String colMaxZ;
    private final String colWorldUuid;
    private final String colUpdatedAt;
    private final String selectColumns;

    public GenericProtectedAreaRepository(StatementHandler statementHandler, DatabaseAdapter adapter)
    {
        this.statementHandler = statementHandler;
        this.adapter = adapter;

        this.tblProtectedAreas = adapter.quoteIdentifier("protected_areas");
        this.colUuid = adapter.quoteIdentifier("uuid");
        this.colName = adapter.quoteIdentifier("name");
        this.colMinX = adapter.quoteIdentifier("min_x");
        this.colMinY = adapter.quoteIdentifier("min_y");
        this.colMinZ = adapter.quoteIdentifier("min_z");
        this.colMaxX = adapter.quoteIdentifier("max_x");
        this.colMaxY = adapter.quoteIdentifier("max_y");
        this.colMaxZ = adapter.quoteIdentifier("max_z");
        this.colWorldUuid = adapter.quoteIdentifier("world_uuid");
        this.colUpdatedAt = adapter.quoteIdentifier("updated_at");
        this.selectColumns = String.format("%s, %s, %s, %s, %s, %s, %s, %s, %s",
                colUuid, colName, colMinX, colMinY, colMinZ, colMaxX, colMaxY, colMaxZ, colWorldUuid);
    }

    @Override
    public void insert(ProtectedRegion region) throws SQLException
    {
        String sql = String.format("INSERT INTO %s (%s, %s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, %s)",
                tblProtectedAreas, selectColumns, colUpdatedAt, adapter.currentTimestamp());
        statementHandler.executeUpdate(sql,
                region.getUuid().toString(),
                region.getName(),
                region.getMinVector().getBlockX(),
                region.getMinVector().getBlockY(),
                region.getMinVector().getBlockZ(),
                region.getMaxVector().getBlockX(),
                region.getMaxVector().getBlockY(),
                region.getMaxVector().getBlockZ(),
                region.getWorldUUID().toString());
    }

    @Override
    public List<ProtectedRegion> loadAll() throws SQLException
    {
        List<ProtectedRegion> regions = new ArrayList<>();
        String sql = String.format("SELECT %s FROM %s", selectColumns, tblProtectedAreas);
        try (ResultSet rs = statementHandler.executeQuery(sql))
        {
            while (rs.next())
            {
                ProtectedRegion region = tryLoadRegionFromRow(rs);
                if (region != null)
                {
                    regions.add(region);
                }
            }
        }
        return regions;
    }

    @Override
    public ProtectedRegion findByUuid(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblProtectedAreas, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
            {
                return tryLoadRegionFromRow(rs);
            }
        }
        return null;
    }

    @Override
    public ProtectedRegion findByName(String name) throws SQLException
    {
        String sql = String.format("SELECT %s FROM %s WHERE %s = ?", selectColumns, tblProtectedAreas, colName);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, name);
             ResultSet rs = stmt.executeQuery())
        {
            if (rs.next())
            {
                return tryLoadRegionFromRow(rs);
            }
        }
        return null;
    }

    @Override
    public boolean exists(UUID uuid) throws SQLException
    {
        String sql = String.format("SELECT COUNT(*) FROM %s WHERE %s = ?", tblProtectedAreas, colUuid);
        try (PreparedStatement stmt = statementHandler.prepareStatement(sql, uuid.toString());
             ResultSet rs = stmt.executeQuery())
        {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override
    public boolean update(ProtectedRegion region) throws SQLException
    {
        String sql = String.format("UPDATE %s SET %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = ?, %s = %s WHERE %s = ?",
                tblProtectedAreas, colName, colMinX, colMinY, colMinZ, colMaxX, colMaxY, colMaxZ, colWorldUuid,
                colUpdatedAt, adapter.currentTimestamp(), colUuid);

        int rows = statementHandler.executeUpdate(sql,
                region.getName(),
                region.getMinVector().getBlockX(),
                region.getMinVector().getBlockY(),
                region.getMinVector().getBlockZ(),
                region.getMaxVector().getBlockX(),
                region.getMaxVector().getBlockY(),
                region.getMaxVector().getBlockZ(),
                region.getWorldUUID().toString(),
                region.getUuid().toString());

        return rows > 0;
    }

    @Override
    public void saveOrUpdate(ProtectedRegion region) throws SQLException
    {
        if (exists(region.getUuid()))
        {
            update(region);
        }
        else
        {
            insert(region);
        }
    }

    @Override
    public boolean delete(UUID uuid) throws SQLException
    {
        String sql = String.format("DELETE FROM %s WHERE %s = ?", tblProtectedAreas, colUuid);
        return statementHandler.executeUpdate(sql, uuid.toString()) > 0;
    }

    @Override
    public void deleteAllSync() throws SQLException
    {
        statementHandler.executeUpdate(String.format("DELETE FROM %s", tblProtectedAreas));
    }

    @Override
    public Long getMaxUpdatedAt() throws SQLException
    {
        String sql = String.format("SELECT MAX(%s) FROM %s", colUpdatedAt, tblProtectedAreas);
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
    public Mono<List<ProtectedRegion>> loadAllAsync()
    {
        return statementHandler.supplyMono(this::loadAll);
    }

    @Override
    public Mono<Void> save(ProtectedRegion region)
    {
        return statementHandler.runMono(() -> saveOrUpdate(region));
    }

    @Override
    public Mono<Boolean> deleteAsync(UUID uuid)
    {
        return statementHandler.supplyMono(() -> delete(uuid));
    }

    @Override
    public Mono<Void> deleteAll()
    {
        return statementHandler.runMono(this::deleteAllSync);
    }

    private ProtectedRegion tryLoadRegionFromRow(ResultSet rs) throws SQLException
    {
        UUID uuid = UUID.fromString(rs.getString("uuid"));
        String name = rs.getString("name");
        int minX = rs.getInt("min_x");
        int minY = rs.getInt("min_y");
        int minZ = rs.getInt("min_z");
        int maxX = rs.getInt("max_x");
        int maxY = rs.getInt("max_y");
        int maxZ = rs.getInt("max_z");
        String worldUuid = rs.getString("world_uuid");

        try
        {
            return new ProtectedRegion(uuid, name, minX, minY, minZ, maxX, maxY, maxZ, worldUuid);
        }
        catch (CantFindWorldException ex)
        {
            FLog.warning(ex.getMessage());
            return null;
        }
    }
}
