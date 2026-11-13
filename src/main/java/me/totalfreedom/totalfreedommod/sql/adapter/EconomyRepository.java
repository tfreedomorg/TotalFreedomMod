package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.eco.EcoBankData;
import me.totalfreedom.totalfreedommod.eco.EcoPlayerData;

/**
 * Repository interface for per-player economy balances, plus the bank's own native account.
 *
 * A player's own {@code username} is its primary key, so no generated-key handling is needed.
 */
public interface EconomyRepository
{
    void insert(EcoPlayerData data) throws SQLException;

    Optional<EcoPlayerData> findByUsername(String username) throws SQLException;

    boolean exists(String username) throws SQLException;

    boolean update(EcoPlayerData data) throws SQLException;

    void saveOrUpdate(EcoPlayerData data) throws SQLException;

    boolean delete(String username) throws SQLException;

    void deleteAllSync() throws SQLException;

    Map<String, EcoPlayerData> loadAll() throws SQLException;

    Mono<Map<String, EcoPlayerData>> loadAllAsync();

    Mono<Void> save(EcoPlayerData data);

    Mono<Boolean> deleteAsync(String username);

    Mono<Void> deleteAll();

    /**
     * @return The bank's own native account balances, or empty if it has never been saved.
     */
    Optional<EcoBankData> loadBank() throws SQLException;

    void saveBank(EcoBankData data) throws SQLException;

    Mono<Optional<EcoBankData>> loadBankAsync();

    Mono<Void> saveBankAsync(EcoBankData data);
}
