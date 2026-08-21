package me.totalfreedom.totalfreedommod.sql.adapter;

import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Timestamp;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;

/**
 * Abstract base class for database adapters.
 * Each database type (SQLite, MySQL, PostgreSQL, etc.) has its own implementation
 * with database-specific SQL syntax and initialization.
 * 
 * Database-specific implementations handle:
 * - Table creation and migrations
 * - SQL dialect differences (INSERT IGNORE vs ON CONFLICT, etc.)
 * - Repository creation with database-specific queries
 */
public abstract class DatabaseAdapter
{
    protected final TotalFreedomMod plugin;
    protected final ConnectionHandler connectionHandler;
    protected final StatementHandler statementHandler;

    protected DatabaseAdapter(TotalFreedomMod plugin, ConnectionHandler connectionHandler, StatementHandler statementHandler)
    {
        this.plugin = plugin;
        this.connectionHandler = connectionHandler;
        this.statementHandler = statementHandler;
    }

    // ============================================
    // Lifecycle Methods
    // ============================================

    /**
     * Run database migrations (create tables, indexes, etc.)
     * Called during initialization.
     */
    public abstract void runMigrations() throws SQLException;

    /**
     * Initialize the database. Runs migrations.
     */
    public void initialize() throws SQLException
    {
        runMigrations();
    }

    /**
     * Close database connections and cleanup resources.
     */
    public void shutdown()
    {
        connectionHandler.shutdown();
    }

    // ============================================
    // Repository Getters
    // ============================================

    /**
     * Get the admin repository for this database type.
     */
    public abstract AdminRepository getAdminRepository();

    /**
     * Get the ban repository for this database type.
     */
    public abstract BanRepository getBanRepository();

    /**
     * Get the permban repository for this database type.
     */
    public abstract PermbanRepository getPermbanRepository();

    /**
     * Get the strike repository for this database type.
     */
    public abstract StrikeRepository getStrikeRepository();

    /**
     * Get the Discord link repository for this database type.
     */
    public abstract DiscordLinkRepository getDiscordLinkRepository();

    /**
     * Get the rank repository for this database type.
     */
    public abstract RankRepository getRankRepository();

    /**
     * Repository for titles, the flat capability grants that sit alongside ranks.
     */
    public abstract TitleRepository getTitleRepository();

    /**
     * Get the protected area repository for this database type.
     */
    public abstract ProtectedAreaRepository getProtectedAreaRepository();

    /**
     * Get the saved flag repository for this database type.
     */
    public abstract SavedFlagRepository getSavedFlagRepository();

    /**
     * Get the per-player data repository for this database type.
     */
    public abstract PlayerRepository getPlayerRepository();

    /**
     * Get the applied-migration ledger for this database type.
     */
    public abstract MigrationRepository getMigrationRepository();

    // ============================================
    // SQL Dialect Methods (override for differences)
    // ============================================

    /**
     * Get the auto-increment syntax for primary keys.
     * SQLite: INTEGER PRIMARY KEY AUTOINCREMENT
     * MySQL: INT AUTO_INCREMENT
     * PostgreSQL: SERIAL
     */
    public abstract String autoIncrementSyntax();

    /**
     * Get the primary key syntax.
     */
    public abstract String primaryKeySyntax();

    /**
     * Get the text/string type for this database.
     * SQLite: TEXT
     * MySQL: TEXT or VARCHAR
     * PostgreSQL: TEXT
     */
    public abstract String textType();

    /**
     * Get the timestamp/datetime type for this database.
     * SQLite: TEXT (stored as ISO string)
     * MySQL: DATETIME
     * PostgreSQL: TIMESTAMP
     */
    public abstract String timestampType();

    /**
     * Get the boolean type for this database.
     * SQLite: INTEGER (0/1)
     * MySQL: TINYINT(1)
     * PostgreSQL: BOOLEAN
     */
    public abstract String booleanType();

    /**
     * Get the JSON column type for this database.
     * SQLite: TEXT (no native JSON type; validate/query with the JSON1 extension's
     * json_valid()/json_extract() if needed)
     * MySQL: JSON
     * PostgreSQL: JSONB
     */
    public abstract String jsonType();

    /**
     * Get the bind-parameter placeholder for a value being written into a native
     * JSON column. PostgreSQL requires an explicit cast since it won't implicitly
     * convert a bound String to jsonb.
     * SQLite/MySQL: "?"
     * PostgreSQL: "?::jsonb"
     */
    public abstract String jsonParamPlaceholder();

    /**
     * Get the INSERT IGNORE / INSERT OR IGNORE syntax prefix.
     * SQLite: INSERT OR IGNORE
     * MySQL: INSERT IGNORE
     * PostgreSQL: INSERT (use insertIgnoreSuffix() for ON CONFLICT DO NOTHING)
     */
    public abstract String insertIgnoreSyntax();

    /**
     * Get the trailing clause (if any) needed to make an INSERT a no-op on conflict.
     * SQLite/MySQL: "" (handled entirely by the insertIgnoreSyntax() prefix)
     * PostgreSQL: " ON CONFLICT DO NOTHING"
     */
    public abstract String insertIgnoreSuffix();

    /**
     * Quote an identifier (table name, column name) for this database.
     * SQLite: No quoting or double quotes
     * MySQL: `identifier` (backticks)
     * PostgreSQL: "identifier" (double quotes)
     */
    public abstract String quoteIdentifier(String identifier);

    /**
     * Get the current timestamp function, for use as a literal insert value.
     * SQLite: CURRENT_TIMESTAMP
     * MySQL: NOW()
     * PostgreSQL: CURRENT_TIMESTAMP
     */
    public abstract String currentTimestamp();

    /**
     * Reads a timestamp column as epoch millis. Overridden where the dialect's
     * stored value is not in the JVM's own timezone.
     */
    public Long readTimestamp(final ResultSet rs, final int index) throws SQLException
    {
        final Timestamp ts = rs.getTimestamp(index);

        return ts != null ? ts.getTime() : null;
    }

    /**
     * Get the bind-parameter placeholder for a value being written into a native
     * timestamp column. PostgreSQL requires an explicit cast since it won't
     * implicitly convert a bound String to timestamp.
     * SQLite/MySQL: "?"
     * PostgreSQL: "?::timestamp"
     */
    public abstract String timestampParamPlaceholder();

    /**
     * Build a case-insensitive equality comparison between a (already-quoted) column
     * reference and a bind-parameter placeholder.
     * SQLite/MySQL: LOWER(columnRef) = LOWER(paramPlaceholder)
     * PostgreSQL: columnRef ILIKE paramPlaceholder
     */
    public abstract String caseInsensitiveEquals(String columnRef, String paramPlaceholder);

    /**
     * Build a boolean expression comparing a stored (already-quoted) timestamp column
     * against the current time. SQLite stores timestamps as formatted TEXT, so both
     * sides need datetime() normalization; MySQL/PostgreSQL compare native
     * DATETIME/TIMESTAMP columns directly.
     *
     * @param columnRef the already-quoted column reference
     * @param operator  e.g. ">", "<="
     */
    public abstract String compareToNow(String columnRef, String operator);

    /**
     * Build the upsert clause appended after an INSERT ... VALUES (...) to update the
     * given columns from the incoming row on a conflict against conflictColumn.
     * MySQL: ON DUPLICATE KEY UPDATE col = VALUES(col), ...
     * SQLite/PostgreSQL: ON CONFLICT(conflictColumn) DO UPDATE SET col = EXCLUDED.col, ...
     */
    public abstract String upsertClause(String conflictColumn, String... updateColumns);
}
