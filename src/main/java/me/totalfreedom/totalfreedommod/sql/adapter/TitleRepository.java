package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.title.Title;

/**
 * Repository interface for {@link Title} data.
 * <p>
 * A title's own {@code id} string is its primary key, so no generated-key handling is needed. The
 * shape mirrors {@link RankRepository} deliberately: titles carry a permission set that lives in a
 * child table for the same reasons rank permissions do, so the two persist the same way even though
 * a title has no level and no parent.
 */
public interface TitleRepository
{
    void insert(Title title) throws SQLException;

    void insertPermissions(String titleId, Set<String> permissions) throws SQLException;

    void addPermission(String titleId, String permission) throws SQLException;

    Map<String, Title> loadAll() throws SQLException;

    Title findById(String id) throws SQLException;

    boolean exists(String id) throws SQLException;

    Set<String> getPermissions(String titleId) throws SQLException;

    boolean update(Title title) throws SQLException;

    void syncPermissions(String titleId, Set<String> permissions) throws SQLException;

    void saveOrUpdate(Title title) throws SQLException;

    boolean delete(String id) throws SQLException;

    boolean removePermission(String titleId, String permission) throws SQLException;

    void deleteAllSync() throws SQLException;

    /**
     * Epoch millis of the most recently updated title row, or null if the table is empty.
     * Used to compare SQL freshness against the titles.json snapshot's last-modified time.
     */
    Long getMaxUpdatedAt() throws SQLException;

    Mono<Map<String, Title>> loadAllAsync();

    Mono<Void> save(Title title);

    Mono<Boolean> deleteAsync(String id);

    Mono<Void> deleteAll();
}
