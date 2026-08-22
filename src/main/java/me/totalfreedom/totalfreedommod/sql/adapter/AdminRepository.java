package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.admin.Admin;

/**
 * Abstract repository interface for Admin data.
 * Each database type implements this with database-specific queries.
 */
public interface AdminRepository
{
    // ============================================
    // CREATE Operations
    // ============================================

    /**
     * Insert a new admin into the database.
     * @param uuid The player's UUID
     * @param admin The admin object to insert
     * @return The generated admin ID, or -1 on failure
     */
    int insert(UUID uuid, Admin admin) throws SQLException;

    /**
     * Insert IPs for an admin.
     */
    void insertIps(int adminId, List<String> ips) throws SQLException;

    /**
     * Add a single IP to an admin.
     */
    void addIp(int adminId, String ip) throws SQLException;

    // ============================================
    // READ Operations
    // ============================================

    /**
     * Load all admins from the database.
     * @return Map of config_key (lowercase username) -> Admin
     */
    Map<String, Admin> loadAll() throws SQLException;

    /**
     * Find an admin by UUID.
     */
    Admin findByUuid(UUID uuid) throws SQLException;

    /**
     * Find an admin by username (case-insensitive).
     */
    Admin findByUsername(String username) throws SQLException;

    /**
     * Find an admin by IP address.
     */
    Admin findByIp(String ip) throws SQLException;

    /**
     * Get the database ID for an admin by username.
     */
    int getAdminId(String username) throws SQLException;

    /**
     * Get the database ID for an admin by UUID.
     */
    int getAdminIdByUuid(UUID uuid) throws SQLException;

    /**
     * Get all IPs for an admin.
     */
    List<String> getIps(int adminId) throws SQLException;

    /**
     * Check if an admin exists by username.
     */
    boolean exists(String username) throws SQLException;

    /**
     * Check if an admin exists by UUID.
     */
    boolean existsByUuid(UUID uuid) throws SQLException;

    /**
     * Get the UUID for an admin by username.
     */
    UUID getUuidByUsername(String username) throws SQLException;

    // ============================================
    // UPDATE Operations
    // ============================================

    /**
     * Update an existing admin in the database.
     */
    boolean update(UUID uuid, Admin admin) throws SQLException;

    /**
     * Update an admin's rank.
     */
    boolean updateRank(String username, String rank) throws SQLException;

    /**
     * Update an admin's active status.
     */
    boolean updateActive(String username, boolean active) throws SQLException;

    /**
     * Update an admin's last login time.
     */
    boolean updateLastLogin(String username, Date lastLogin) throws SQLException;

    /**
     * Update an admin's username (when they change their name).
     */
    boolean updateUsername(UUID uuid, String newUsername) throws SQLException;

    /**
     * Sync all IPs for an admin (replace existing with new list).
     */
    void syncIps(int adminId, List<String> ips) throws SQLException;

    /**
     * Save or update an admin (upsert).
     */
    int saveOrUpdate(UUID uuid, Admin admin) throws SQLException;

    // ============================================
    // DELETE Operations
    // ============================================

    /**
     * Delete an admin by UUID.
     */
    boolean delete(UUID uuid) throws SQLException;

    /**
     * Delete an admin by username.
     */
    boolean deleteByUsername(String username) throws SQLException;

    /**
     * Remove a specific IP from an admin.
     */
    boolean removeIp(int adminId, String ip) throws SQLException;

    /**
     * Remove all IPs from an admin.
     */
    boolean clearIps(int adminId) throws SQLException;
    
    /**
     * Delete all admins from the database (synchronous).
     */
    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated admin row, or null if the table is empty.
     * Used to compare SQL freshness against the admins.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    // ============================================
    // Async Operations
    // ============================================

    Mono<Map<String, Admin>> loadAllAsync();

    Mono<Integer> insertAsync(UUID uuid, Admin admin);

    Mono<Boolean> updateAsync(UUID uuid, Admin admin);

    Mono<Boolean> deleteAsync(UUID uuid);

    /**
     * Save admin asynchronously (upsert).
     */
    Mono<Integer> save(UUID uuid, Admin admin);

    /**
     * Find all admins asynchronously.
     */
    Mono<List<Admin>> findAll();

    /**
     * Delete admin by UUID asynchronously.
     */
    Mono<Boolean> deleteByUuid(UUID uuid);

    /**
     * Delete all admins asynchronously.
     */
    Mono<Void> deleteAll();
}
