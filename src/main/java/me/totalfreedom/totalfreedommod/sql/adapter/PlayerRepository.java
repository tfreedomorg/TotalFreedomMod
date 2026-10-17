package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.player.PlayerData;

/**
 * Repository interface for per-player data.
 *
 * A player's own {@code username} is its primary key, so no generated-key handling is needed.
 */
public interface PlayerRepository
{
    void insert(PlayerData data) throws SQLException;

    /**
     * Insert IPs for a player, preserving list order (oldest first) so it round-trips
     * through {@link #getIps} correctly.
     */
    void insertIps(String username, List<String> ips) throws SQLException;

    void addIp(String username, String ip) throws SQLException;

    Map<String, PlayerData> loadAll() throws SQLException;

    Optional<PlayerData> findByUsername(String username) throws SQLException;

    boolean exists(String username) throws SQLException;

    List<String> getIps(String username) throws SQLException;

    boolean update(PlayerData data) throws SQLException;

    void syncIps(String username, List<String> ips) throws SQLException;

    void saveOrUpdate(PlayerData data) throws SQLException;

    boolean delete(String username) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of this player's row's last update, or null if no such row exists.
     * Used to compare SQL freshness against that player's {@code players/<username>.json}
     * snapshot file's last-modified time.
     */
    Long getUpdatedAt(String username) throws SQLException;

    Mono<Map<String, PlayerData>> loadAllAsync();

    Mono<Void> save(PlayerData data);

    Mono<Boolean> deleteAsync(String username);

    Mono<Void> deleteAll();
}
