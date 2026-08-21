package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import reactor.core.publisher.Mono;

public interface DiscordLinkRepository
{
    /**
     * @return all links, keyed by admin UUID string, valued by Discord user id.
     * Used to write the discord_links.json snapshot.
     */
    Map<String, String> loadAll() throws SQLException;

    /**
     * Insert a new link. Fails if either side is already linked. Callers
     * should remove any existing link first via {@link #deleteByAdminUuid} or
     * {@link #deleteByDiscordUserId}.
     */
    void insert(UUID adminUuid, String discordUserId) throws SQLException;

    /**
     * @return Discord user id linked to {@code adminUuid}, or {@code null} if unlinked.
     */
    String findDiscordIdByAdminUuid(UUID adminUuid) throws SQLException;

    /**
     * @return admin UUID linked to {@code discordUserId}, or {@code null} if unlinked.
     */
    UUID findAdminUuidByDiscordId(String discordUserId) throws SQLException;

    /**
     * @return true if a row was deleted.
     */
    boolean deleteByAdminUuid(UUID adminUuid) throws SQLException;

    /**
     * @return true if a row was deleted.
     */
    boolean deleteByDiscordUserId(String discordUserId) throws SQLException;

    /**
     * Epoch millis of the most recently updated link row, or null if the table is empty.
     * Used to compare SQL freshness against the discord_links.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    Mono<Map<String, String>> loadAllAsync();

    /**
     * Replace any existing link for either side, then link {@code adminUuid} to
     * {@code discordUserId}.
     */
    Mono<Void> relinkAsync(UUID adminUuid, String discordUserId);

    Mono<Long> getMaxUpdatedAtAsync();
}
