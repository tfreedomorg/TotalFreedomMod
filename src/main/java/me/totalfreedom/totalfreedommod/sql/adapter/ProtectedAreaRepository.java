package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion;

/**
 * Repository interface for ProtectedRegion data.
 * 
 * A region's own {@code uuid} is its primary key, so no generated-key handling is needed.
 */
public interface ProtectedAreaRepository
{
    void insert(ProtectedRegion region) throws SQLException;

    /**
     * Load all regions. Rows whose stored world UUID fails to parse are skipped
     * with a warning, matching {@code ProtectArea}'s existing YAML-load behavior.
     */
    List<ProtectedRegion> loadAll() throws SQLException;

    ProtectedRegion findByUuid(UUID uuid) throws SQLException;

    ProtectedRegion findByName(String name) throws SQLException;

    boolean exists(UUID uuid) throws SQLException;

    boolean update(ProtectedRegion region) throws SQLException;

    void saveOrUpdate(ProtectedRegion region) throws SQLException;

    boolean delete(UUID uuid) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated region row, or null if the table is empty.
     * Used to compare SQL freshness against the protectedareas.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    Mono<List<ProtectedRegion>> loadAllAsync();

    Mono<Void> save(ProtectedRegion region);

    Mono<Boolean> deleteAsync(UUID uuid);

    Mono<Void> deleteAll();
}
