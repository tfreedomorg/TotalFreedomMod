package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * Ledger of one-off data migrations that have already been applied to this database, backed by
 * the {@code migrations} table every adapter creates.
 * <p>
 * Keyed by an opaque version string rather than by table contents, so a migration whose source
 * data was empty still counts as done and is not retried on every boot.
 */
public interface MigrationRepository
{
    /**
     * Version keys already recorded as applied.
     */
    Set<String> findApplied() throws SQLException;

    /**
     * Record {@code version} as applied. Does nothing if it already is.
     */
    void markApplied(String version) throws SQLException;

    /**
     * Forget every recorded version, so the migrations they guard run again.
     */
    void clear() throws SQLException;

    Mono<Set<String>> findAppliedAsync();

    Mono<Void> markAppliedAsync(String version);
}
