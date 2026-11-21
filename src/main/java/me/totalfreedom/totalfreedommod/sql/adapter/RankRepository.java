package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.rank.CustomRank;

/**
 * Repository interface for CustomRank data.
 * 
 * A rank's own {@code id} string is its primary key, so no generated-key handling is needed.
 */
public interface RankRepository
{
    void insert(CustomRank rank) throws SQLException;

    void insertPermissions(String rankId, Set<String> permissions) throws SQLException;

    void addPermission(String rankId, String permission) throws SQLException;

    Map<String, CustomRank> loadAll() throws SQLException;

    CustomRank findById(String id) throws SQLException;

    boolean exists(String id) throws SQLException;

    Set<String> getPermissions(String rankId) throws SQLException;

    boolean update(CustomRank rank) throws SQLException;

    void syncPermissions(String rankId, Set<String> permissions) throws SQLException;

    void saveOrUpdate(CustomRank rank) throws SQLException;

    boolean delete(String id) throws SQLException;

    boolean removePermission(String rankId, String permission) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated rank row, or null if the table is empty.
     * Used to compare SQL freshness against the ranks.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    Mono<Map<String, CustomRank>> loadAllAsync();

    Mono<Void> save(CustomRank rank);

    Mono<Boolean> deleteAsync(String id);

    Mono<Void> deleteAll();
}
