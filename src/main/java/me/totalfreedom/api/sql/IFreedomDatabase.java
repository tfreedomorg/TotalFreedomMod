package me.totalfreedom.api.sql;

import java.util.function.Consumer;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import reactor.core.publisher.Mono;

import me.totalfreedom.totalfreedommod.sql.adapter.BanRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.EconomyRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.MigrationRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.PermbanRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.PlayerRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.ProtectedAreaRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.PunishmentHistoryRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.RankRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.SavedFlagRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.StrikeRepository;
import me.totalfreedom.totalfreedommod.sql.adapter.TitleRepository;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler;
import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;

public interface IFreedomDatabase
{
    /**
     * Build the connection pool, run schema migrations, then fold any legacy YAML data in,
     * entirely on a background thread.
     */
    void initializeAsync();

    /**
     * Register {@code callback} to run on the main thread once the database is up and its YAML
     * migrations have finished, or immediately if that has already happened. Callbacks run in
     * registration order and are skipped entirely if the database never comes up.
     * <p>
     * Main thread only.
     */
    void whenReady(Runnable callback);

    /**
     * Run {@code query} off the main thread and hand its result to {@code apply} back on it.
     * A failed or empty query logs against {@code label} and runs {@code onFailure} instead,
     * also on the main thread.
     */
    <T> void readAsync(String label, Mono<T> query, Consumer<T> apply, Runnable onFailure);

    /**
     * Run {@code body} on the main thread, or inline if the plugin is already disabled.
     */
    void sync(String label, Runnable body);

    /**
     * {@link #sync} as a {@link Mono} that completes only once {@code body} has run on the main
     * thread, whether or not it threw. Chain on this rather than on a bare {@code sync} call when
     * a persistence queue must hold its place until memory has been updated.
     */
    Mono<Void> syncMono(String label, Runnable body);

    /**
     * Shutdown the database connection.
     */
    void shutdown();

    /**
     * Check if the database is initialized.
     */
    boolean isInitialized();

    BanRepository getBanRepository();

    PermbanRepository getPermbanRepository();

    StrikeRepository getStrikeRepository();

    RankRepository getRankRepository();

    TitleRepository getTitleRepository();

    ProtectedAreaRepository getProtectedAreaRepository();

    SavedFlagRepository getSavedFlagRepository();

    PlayerRepository getPlayerRepository();

    MigrationRepository getMigrationRepository();

    /**
     * Get the per-player economy balance repository.
     */
    EconomyRepository getEconomyRepository();

    PunishmentHistoryRepository getPunishmentHistoryRepository();

    /**
     * Records a punishment for {@code target}, attributing it to {@code actor} (or "TotalFreedom"
     * if console/null), asynchronously.
     */
    void recordPunishment(Player target, CommandSender actor, String type, String reason, Long expiryAt, String source);

    void recordPunishmentAsync(PunishmentHistoryEntry entry);

    /**
     * Snapshot of live connection pool and fairness-queue health, or {@code null} if the
     * database isn't initialized.
     */
    ConnectionHandler.PoolStats getPoolStats();

    /**
     * Get the database type.
     */
    DatabaseType getDatabaseType();
}
