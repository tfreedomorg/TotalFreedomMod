package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.player.PlayerData;

/**
 * Repository interface for per-player data.
 *
 * A player's {@code uuid} is its primary key. The username is stored for name lookups only.
 */
public interface PlayerRepository
{
    void insert(PlayerData data) throws SQLException;

    Map<UUID, PlayerData> loadAll() throws SQLException;

    /**
     * Every record holding a staff rank, active or not.
     */
    List<PlayerData> findStaff() throws SQLException;

    Optional<PlayerData> findByUuid(UUID uuid) throws SQLException;

    /**
     * Names can change hands, so when several records carry {@code username} this returns the one
     * that joined most recently.
     */
    Optional<PlayerData> findByUsername(String username) throws SQLException;

    boolean exists(UUID uuid) throws SQLException;

    boolean update(PlayerData data) throws SQLException;

    void saveOrUpdate(PlayerData data) throws SQLException;

    boolean delete(UUID uuid) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of this player's row's last update, or null if no such row exists.
     * Used to compare SQL freshness against that player's {@code players/<uuid>.json}
     * snapshot file's last-modified time.
     */
    Long getUpdatedAt(UUID uuid) throws SQLException;

    Mono<Map<UUID, PlayerData>> loadAllAsync();

    Mono<List<PlayerData>> findStaffAsync();

    Mono<Void> save(PlayerData data);

    Mono<Boolean> deleteAsync(UUID uuid);

    Mono<Void> deleteAll();
}
