package me.totalfreedom.totalfreedommod.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.sql.SQLProperties.DatabaseType;
import me.totalfreedom.totalfreedommod.sql.adapter.*;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;

/**
 * Central database management service.
 * Handles database initialization, adapter creation, and provides access to repositories.
 * 
 * This is the main entry point for database operations in TotalFreedomMod.
 * It initializes the appropriate database adapter based on configuration and
 * provides repository access for Admin, Ban, and Permban data.
 */
public class FreedomDatabase extends FreedomService
{
    private ConnectionHandler connectionHandler;
    private StatementHandler statementHandler;
    private DatabaseAdapter adapter;
    private volatile boolean initialized = false;

    /**
     * Callbacks waiting on the database, and whether they have already been drained. Both are
     * touched only on the main thread, so registering during {@code onStart} cannot race the
     * drain that happens once the background bootstrap finishes.
     */
    private final List<Runnable> readyCallbacks = new ArrayList<>();
    private boolean readyFired = false;

    public FreedomDatabase(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        initializeAsync();
    }

    @Override
    protected void onStop()
    {
        shutdown();
    }

    /**
     * Build the connection pool, run schema migrations, then fold any legacy YAML data in,
     * entirely on a background thread. {@code onEnable} does not wait for any of it: every
     * domain starts on its JSON snapshot and swaps to SQL through {@link #whenReady(Runnable)}
     * once this finishes, so an unreachable database host delays nothing but the swap.
     */
    public void initializeAsync()
    {
        if (initialized)
        {
            FLog.warning("DatabaseManager already initialized");
            return;
        }

        FLog.info("Initializing database in the background...");

        connectionHandler = new ConnectionHandler(plugin);
        final SQLProperties properties = connectionHandler.getSqlProperties();
        final DatabaseType dbType = properties.getDatabaseType();

        Mono.fromCallable(() ->
        {
            connectionHandler.connect();
            statementHandler = new StatementHandler(connectionHandler);
            DatabaseAdapter created = AdapterFactory.createAdapter(plugin, properties, connectionHandler, statementHandler);
            created.initialize();
            return created;
        })
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(built ->
            {
                adapter = built;
                initialized = true;
                FLog.info(String.format("Database initialized successfully (%s)", dbType.getName()));
            })
            // Migrations have to finish before any domain reads SQL, so they ride this
            // chain rather than registering as another ready callback.
            .then(Mono.defer(() -> new YamlMigrationService(plugin, this).runMigrations()))
            .subscribe(
                    ignored -> {},
                    ex -> FLog.severe(String.format(
                            "Failed to initialize database, every domain stays on its JSON fallback: %s",
                            ex.getMessage())),
                    () -> sync("FreedomDatabase/ready", this::fireReady));
    }

    /**
     * Register {@code callback} to run on the main thread once the database is up and its YAML
     * migrations have finished, or immediately if that has already happened. Callbacks run in
     * registration order and are skipped entirely if the database never comes up.
     * <p>
     * Main thread only.
     */
    public void whenReady(final Runnable callback)
    {
        if (readyFired)
        {
            callback.run();
            return;
        }
        readyCallbacks.add(callback);
    }

    /**
     * Run {@code query} off the main thread and hand its result to {@code apply} back on it.
     * A failed or empty query logs against {@code label} and runs {@code onFailure} instead,
     * also on the main thread.
     */
    public <T> void readAsync(final String label, final Mono<T> query, final Consumer<T> apply,
            final Runnable onFailure)
    {
        query.switchIfEmpty(Mono.error(new IllegalStateException("query returned no result")))
                .subscribe(
                        result -> sync(label, () -> apply.accept(result)),
                        ex ->
                        {
                            FLog.warning(String.format("%s failed: %s", label, ex.getMessage()));
                            sync(label, onFailure);
                        });
    }

    /**
     * Run {@code body} on the main thread, or inline if the plugin is already disabled.
     */
    public void sync(final String label, final Runnable body)
    {
        if (!plugin.isEnabled())
        {
            FTask.run(label, body);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, FTask.guard(label, body));
    }

    /**
     * Shutdown the database connection.
     */
    public void shutdown()
    {
        if (!initialized)
        {
            return;
        }

        FLog.info("Shutting down database...");

        if (adapter != null)
        {
            adapter.shutdown();
        }

        if (statementHandler != null)
        {
            statementHandler.close();
        }

        initialized = false;

        // Cleared so a tfm reload queues the domains' swap callbacks again instead of running them immediately against a pool that is still being rebuilt. 
        readyFired = false;
        readyCallbacks.clear();

        FLog.info("Database shutdown complete");
    }

    /**
     * Check if the database is initialized.
     */
    public boolean isInitialized()
    {
        return initialized;
    }

    /**
     * Get the database adapter.
     */
    public DatabaseAdapter getAdapter()
    {
        return adapter;
    }

    /**
     * Get the connection handler.
     */
    public ConnectionHandler getConnectionHandler()
    {
        return connectionHandler;
    }

    /**
     * Get the statement handler.
     */
    public StatementHandler getStatementHandler()
    {
        return statementHandler;
    }

    /**
     * Get the admin repository.
     */
    public AdminRepository getAdminRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getAdminRepository();
    }

    /**
     * Get the ban repository.
     */
    public BanRepository getBanRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getBanRepository();
    }

    /**
     * Get the permban repository.
     */
    public PermbanRepository getPermbanRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getPermbanRepository();
    }

    public StrikeRepository getStrikeRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getStrikeRepository();
    }

    public DiscordLinkRepository getDiscordLinkRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getDiscordLinkRepository();
    }

    public RankRepository getRankRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getRankRepository();
    }

    public TitleRepository getTitleRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getTitleRepository();
    }

    public ProtectedAreaRepository getProtectedAreaRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getProtectedAreaRepository();
    }

    public SavedFlagRepository getSavedFlagRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getSavedFlagRepository();
    }

    public PlayerRepository getPlayerRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getPlayerRepository();
    }

    public MigrationRepository getMigrationRepository()
    {
        if (adapter == null)
        {
            throw new IllegalStateException("Database not initialized");
        }
        return adapter.getMigrationRepository();
    }

    /**
     * Snapshot of live connection pool and fairness-queue health, or {@code null} if the
     * database isn't initialized.
     */
    public ConnectionHandler.PoolStats getPoolStats()
    {
        if (!initialized || connectionHandler == null)
        {
            return null;
        }
        return connectionHandler.getPoolStats();
    }

    /**
     * Get the database type.
     */
    public DatabaseType getDatabaseType()
    {
        if (connectionHandler == null)
        {
            return DatabaseType.SQLITE; // Default
        }
        return connectionHandler.getSqlProperties().getDatabaseType();
    }

    private void fireReady()
    {
        readyFired = true;
        readyCallbacks.forEach(callback -> FTask.run("FreedomDatabase/readyCallback", callback));
        readyCallbacks.clear();
    }
}
