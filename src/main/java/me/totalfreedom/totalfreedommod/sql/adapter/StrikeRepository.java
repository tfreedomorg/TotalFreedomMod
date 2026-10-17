package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Map;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.banning.StrikeRecord;

public interface StrikeRepository
{
    Map<String, StrikeRecord> loadAll() throws SQLException;

    void upsert(StrikeRecord record) throws SQLException;

    boolean deleteByIp(String ip) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated strike row, or null if the table is empty.
     * Used to compare SQL freshness against the strikes.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    Mono<Map<String, StrikeRecord>> loadAllAsync();

    Mono<Void> upsertAsync(StrikeRecord record);

    Mono<Boolean> deleteByIpAsync(String ip);

    Mono<Void> deleteAll();
}
