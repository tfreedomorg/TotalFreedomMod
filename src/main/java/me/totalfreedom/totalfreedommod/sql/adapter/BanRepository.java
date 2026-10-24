package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.banning.Ban;

/**
 * Repository interface for Ban data.
 * Each database type implements this with database-specific queries.
 */
public interface BanRepository
{
    // ============================================
    // CREATE Operations
    // ============================================

    /**
     * Insert a new ban into the database.
     * @param ban The ban object to insert
     * @return The generated ban ID, or -1 on failure
     */
    int insert(Ban ban) throws SQLException;

    /**
     * Insert IPs for a ban.
     */
    void insertIps(int banId, List<String> ips) throws SQLException;

    /**
     * Add a single IP to a ban.
     */
    void addIp(int banId, String ip) throws SQLException;

    // ============================================
    // READ Operations
    // ============================================

    /**
     * Load all bans from the database.
     */
    List<Ban> loadAll() throws SQLException;

    /**
     * Find a ban by UUID.
     */
    Ban findByUuid(UUID uuid) throws SQLException;

    /**
     * Find a ban by username (case-insensitive).
     */
    Ban findByUsername(String username) throws SQLException;

    /**
     * Find a ban by IP address.
     */
    Ban findByIp(String ip) throws SQLException;

    /**
     * Get all active (non-expired) bans.
     */
    List<Ban> findActiveBans() throws SQLException;

    /**
     * Get all expired bans.
     */
    List<Ban> findExpiredBans() throws SQLException;

    /**
     * Get the database ID for a ban by UUID.
     */
    int getBanId(UUID uuid) throws SQLException;

    /**
     * Get the database ID for a ban by username.
     */
    int getBanIdByUsername(String username) throws SQLException;

    /**
     * Get all IPs for a ban.
     */
    List<String> getIps(int banId) throws SQLException;

    /**
     * Check if a player is banned by UUID (active ban).
     */
    boolean isBanned(UUID uuid) throws SQLException;

    /**
     * Check if a player is banned by username (active ban).
     */
    boolean isBannedByUsername(String username) throws SQLException;

    /**
     * Check if an IP is banned (active ban).
     */
    boolean isBannedByIp(String ip) throws SQLException;

    // ============================================
    // UPDATE Operations
    // ============================================

    /**
     * Update an existing ban in the database.
     */
    boolean update(Ban ban) throws SQLException;

    /**
     * Update a ban's reason.
     */
    boolean updateReason(UUID uuid, String reason) throws SQLException;

    /**
     * Update a ban's expiry time.
     */
    boolean updateExpiry(UUID uuid, Date expireAt) throws SQLException;

    /**
     * Sync all IPs for a ban (replace existing with new list).
     */
    void syncIps(int banId, List<String> ips) throws SQLException;
    
    /**
     * Delete all bans from the database (synchronous).
     */
    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated ban row, or null if the table is empty.
     * Used to compare SQL freshness against the bans.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    // ============================================
    // DELETE Operations
    // ============================================

    /**
     * Delete a ban by UUID.
     */
    boolean delete(UUID uuid) throws SQLException;

    /**
     * Delete a ban by username.
     */
    boolean deleteByUsername(String username) throws SQLException;

    /**
     * Delete all bans associated with an IP.
     */
    boolean deleteByIp(String ip) throws SQLException;

    /**
     * Remove a specific IP from a ban.
     */
    boolean removeIp(int banId, String ip) throws SQLException;

    /**
     * Delete all expired bans.
     * @return The number of deleted bans
     */
    int deleteExpiredBans() throws SQLException;

    // ============================================
    // Async Operations
    // ============================================

    Mono<List<Ban>> loadAllAsync();

    Mono<Integer> insertAsync(Ban ban);

    Mono<Boolean> updateAsync(Ban ban);

    Mono<Boolean> deleteAsync(UUID uuid);

    /**
     * Save ban asynchronously (insert or update).
     */
    Mono<Integer> save(Ban ban);

    /**
     * Find all bans asynchronously.
     */
    Mono<List<Ban>> findAll();

    /**
     * Delete ban by UUID asynchronously.
     */
    Mono<Boolean> deleteByUuid(UUID uuid);

    /**
     * Delete every ban carrying {@code ip}, off the main thread.
     */
    Mono<Boolean> deleteByIpAsync(String ip);

    /**
     * Delete all bans asynchronously.
     */
    Mono<Void> deleteAll();
}
