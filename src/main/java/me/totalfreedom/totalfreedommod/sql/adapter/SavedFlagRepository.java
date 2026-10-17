package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * Repository interface for saved flag data.
 */
public interface SavedFlagRepository
{
    Map<String, Boolean> loadAll() throws SQLException;

    void upsert(String flagName, boolean enabled) throws SQLException;

    boolean delete(String flagName) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated flag row, or null if the table is empty.
     * Used to compare SQL freshness against the savedflags.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    Mono<Map<String, Boolean>> loadAllAsync();

    Mono<Void> upsertAsync(String flagName, boolean enabled);

    Mono<Boolean> deleteAsync(String flagName);

    Mono<Void> deleteAll();
}
