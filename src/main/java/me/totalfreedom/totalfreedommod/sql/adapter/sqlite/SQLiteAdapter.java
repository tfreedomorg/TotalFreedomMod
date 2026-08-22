package me.totalfreedom.totalfreedommod.sql.adapter.sqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.*;
import me.totalfreedom.totalfreedommod.sql.adapter.generic.*;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * SQLite-specific database adapter.
 * Uses SQLite-specific SQL features like:
 * - INTEGER PRIMARY KEY AUTOINCREMENT for primary keys
 * - INSERT OR IGNORE for upsert operations
 * - TEXT for all string/timestamp columns
 * - LOWER() for case-insensitive comparisons
 */
public class SQLiteAdapter extends DatabaseAdapter
{
    /**
     * Constant stand-in for CURRENT_TIMESTAMP, which SQLite will not accept as the default of a
     * column added by ALTER TABLE. Rows carrying it are backfilled immediately after the column
     * is added.
     */
    private static final String EPOCH_TIMESTAMP = "1970-01-01 00:00:00";

    private AdminRepository adminRepository;
    private BanRepository banRepository;
    private PermbanRepository permbanRepository;
    private StrikeRepository strikeRepository;
    private DiscordLinkRepository discordLinkRepository;
    private RankRepository rankRepository;
    private TitleRepository titleRepository;
    private ProtectedAreaRepository protectedAreaRepository;
    private SavedFlagRepository savedFlagRepository;
    private PlayerRepository playerRepository;
    private MigrationRepository migrationRepository;

    public SQLiteAdapter(TotalFreedomMod plugin, ConnectionHandler connectionHandler, StatementHandler statementHandler)
    {
        super(plugin, connectionHandler, statementHandler);
    }

    // ============================================
    // SQL Dialect Methods
    // ============================================

    @Override
    public String autoIncrementSyntax()
    {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public String primaryKeySyntax()
    {
        return ""; // Already included in autoIncrementSyntax for SQLite
    }

    @Override
    public String textType()
    {
        return "TEXT";
    }

    @Override
    public String timestampType()
    {
        return "TEXT"; // SQLite stores timestamps as TEXT (ISO format)
    }

    @Override
    public String booleanType()
    {
        return "INTEGER"; // SQLite uses 0/1 for boolean
    }

    @Override
    public String jsonType()
    {
        return "TEXT"; // No native JSON type; JSON1 extension functions operate on TEXT
    }

    @Override
    public String jsonParamPlaceholder()
    {
        return "?";
    }

    @Override
    public String insertIgnoreSyntax()
    {
        return "INSERT OR IGNORE";
    }

    @Override
    public String insertIgnoreSuffix()
    {
        return "";
    }

    @Override
    public String quoteIdentifier(String identifier)
    {
        return identifier; // SQLite doesn't require identifier quoting for simple names
    }

    @Override
    public String currentTimestamp()
    {
        return "CURRENT_TIMESTAMP";
    }

    /**
     * SQLite writes CURRENT_TIMESTAMP as UTC text with no zone, which the driver
     * would otherwise read in the JVM's timezone and place hours away from when
     * the row was actually written.
     */
    @Override
    public Long readTimestamp(final ResultSet rs, final int index) throws SQLException
    {
        final Timestamp ts = rs.getTimestamp(index, Calendar.getInstance(TimeZone.getTimeZone("UTC")));

        return ts != null ? ts.getTime() : null;
    }


    @Override
    public String timestampParamPlaceholder()
    {
        return "?";
    }

    @Override
    public String caseInsensitiveEquals(String columnRef, String paramPlaceholder)
    {
        return String.format("LOWER(%s) = LOWER(%s)", columnRef, paramPlaceholder);
    }

    @Override
    public String compareToNow(String columnRef, String operator)
    {
        // expire_at is stored as formatted TEXT; datetime() normalizes both sides for comparison.
        return String.format("datetime(%s) %s datetime('now')", columnRef, operator);
    }

    @Override
    public String upsertClause(String conflictColumn, String... updateColumns)
    {
        String assignments = Stream.of(updateColumns)
                .map(col -> String.format("%s = EXCLUDED.%s", col, col))
                .collect(Collectors.joining(", "));
        return String.format("ON CONFLICT(%s) DO UPDATE SET %s", conflictColumn, assignments);
    }

    // ============================================
    // Migration Methods
    // ============================================

    @Override
    public void runMigrations() throws SQLException
    {
        FLog.info("[SQLite] Running database migrations...");

        // Enable foreign keys for SQLite
        statementHandler.executeUpdate("PRAGMA foreign_keys = ON");

        createMigrationTable();
        createAdminsTable();
        createAdminIpsTable();
        createBansTable();
        createBanIpsTable();
        createPermbansTable();
        createPermbanIpsTable();
        createStrikesTable();
        createDiscordLinksTable();
        createRanksTable();
        createRankPermissionsTable();
        createTitlesTable();
        createTitlePermissionsTable();
        createProtectedAreasTable();
        createSavedFlagsTable();
        createPlayersTable();
        createPlayerIpsTable();

        FLog.info("[SQLite] Database migrations complete.");
    }

    private void createMigrationTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS migrations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version TEXT NOT NULL UNIQUE,
                applied_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createAdminsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS admins (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                username TEXT NOT NULL,
                rank TEXT NOT NULL,
                active INTEGER DEFAULT 1,
                last_login TEXT,
                login_message TEXT,
                custom_rank TEXT,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);

        // Migration for tables created before custom_rank/updated_at existed.
        addColumnIfMissing("admins", "custom_rank", "TEXT");
        addTimestampColumnIfMissing("admins", "updated_at");

        // Create indexes
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admins_username ON admins(username)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admins_active ON admins(active)");
    }

    private void createAdminIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS admin_ips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                admin_id INTEGER NOT NULL,
                ip TEXT NOT NULL,
                UNIQUE (admin_id, ip),
                FOREIGN KEY (admin_id) REFERENCES admins(id) ON DELETE CASCADE
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admin_ips_ip ON admin_ips(ip)");
    }

    private void createBansTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS bans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT,
                username TEXT,
                banned_by TEXT,
                banned_by_uuid TEXT,
                reason TEXT,
                expire_at TEXT,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("bans", "updated_at");

        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_uuid ON bans(uuid)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_username ON bans(username)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_expire ON bans(expire_at)");
    }

    private void createBanIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS ban_ips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ban_id INTEGER NOT NULL,
                ip TEXT NOT NULL,
                UNIQUE (ban_id, ip),
                FOREIGN KEY (ban_id) REFERENCES bans(id) ON DELETE CASCADE
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ban_ips_ip ON ban_ips(ip)");
    }

    /**
     * A permban is always keyed by username, ensure it's not null.
     */
    private void createPermbansTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS permbans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT,
                username TEXT NOT NULL,
                reason TEXT,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("permbans", "updated_at");

        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_permbans_uuid ON permbans(uuid)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_permbans_username ON permbans(username)");
    }

    private void createPermbanIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS permban_ips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                permban_id INTEGER NOT NULL,
                ip TEXT NOT NULL,
                UNIQUE (permban_id, ip),
                FOREIGN KEY (permban_id) REFERENCES permbans(id) ON DELETE CASCADE
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_permban_ips_ip ON permban_ips(ip)");
    }

    private void createStrikesTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS strikes (
                ip TEXT PRIMARY KEY,
                strike_count INTEGER NOT NULL DEFAULT 0,
                last_strike_unix INTEGER NOT NULL DEFAULT 0,
                last_username TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("strikes", "updated_at");
    }

    private void createDiscordLinksTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS discord_links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                admin_uuid TEXT NOT NULL UNIQUE,
                discord_user_id TEXT NOT NULL UNIQUE,
                linked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("discord_links", "updated_at");
    }

    private void createRanksTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS ranks (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                determiner TEXT NOT NULL DEFAULT 'a',
                abbreviation TEXT,
                level INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT 'white',
                admin INTEGER NOT NULL DEFAULT 0,
                prefix TEXT,
                inherit_from TEXT,
                roles TEXT,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("ranks", "updated_at");
        addColumnIfMissing("ranks", "roles", "TEXT");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ranks_level ON ranks(level)");
    }

    private void createRankPermissionsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS rank_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                rank_id TEXT NOT NULL,
                permission TEXT NOT NULL,
                UNIQUE (rank_id, permission),
                FOREIGN KEY (rank_id) REFERENCES ranks(id) ON DELETE CASCADE
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createTitlesTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS titles (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                determiner TEXT NOT NULL DEFAULT 'a',
                abbreviation TEXT,
                color TEXT NOT NULL DEFAULT 'white',
                prefix TEXT,
                weight INTEGER NOT NULL DEFAULT 0,
                announce INTEGER NOT NULL DEFAULT 1,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createTitlePermissionsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS title_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title_id TEXT NOT NULL,
                permission TEXT NOT NULL,
                UNIQUE (title_id, permission),
                FOREIGN KEY (title_id) REFERENCES titles(id) ON DELETE CASCADE
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createProtectedAreasTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS protected_areas (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                min_x INTEGER NOT NULL,
                min_y INTEGER NOT NULL,
                min_z INTEGER NOT NULL,
                max_x INTEGER NOT NULL,
                max_y INTEGER NOT NULL,
                max_z INTEGER NOT NULL,
                world_uuid TEXT NOT NULL,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("protected_areas", "updated_at");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_protected_areas_name ON protected_areas(name)");
    }

    private void createSavedFlagsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS saved_flags (
                flag_name TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("saved_flags", "updated_at");
    }

    private void createPlayersTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS players (
                username TEXT PRIMARY KEY,
                first_join_unix INTEGER NOT NULL DEFAULT 0,
                last_join_unix INTEGER NOT NULL DEFAULT 0,
                potion_spy_mode TEXT NOT NULL DEFAULT 'off',
                command_spy_mode TEXT NOT NULL DEFAULT 'off',
                sign_spy_mode TEXT NOT NULL DEFAULT 'off',
                book_spy_mode TEXT NOT NULL DEFAULT 'off',
                muted INTEGER NOT NULL DEFAULT 0,
                frozen INTEGER NOT NULL DEFAULT 0,
                commands_blocked INTEGER NOT NULL DEFAULT 0,
                join_leave_messages INTEGER NOT NULL DEFAULT 1,
                strikes INTEGER NOT NULL DEFAULT 0,
                saved_tag TEXT,
                nickname TEXT,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        addTimestampColumnIfMissing("players", "updated_at");
        addColumnIfMissing("players", "titles", "TEXT");
        addColumnIfMissing("players", "potion_spy_mode", "TEXT NOT NULL DEFAULT 'off'");
        addColumnIfMissing("players", "sign_spy_mode", "TEXT NOT NULL DEFAULT 'off'");
        addColumnIfMissing("players", "book_spy_mode", "TEXT NOT NULL DEFAULT 'off'");
        addColumnIfMissing("players", "join_leave_messages", "INTEGER NOT NULL DEFAULT 1");
    }

    private void createPlayerIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS player_ips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                ip TEXT NOT NULL,
                UNIQUE (username, ip),
                FOREIGN KEY (username) REFERENCES players(username) ON DELETE CASCADE
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    /**
     * Add a column to a table created before that column existed. SQLite has no
     * ADD COLUMN IF NOT EXISTS, so presence is checked up front rather than by
     * swallowing the resulting error.
     */
    private void addColumnIfMissing(final String table, final String column, final String definition) throws SQLException
    {
        if (columnExists(table, column))
            return;

        statementHandler.executeUpdate(String.format("ALTER TABLE %s ADD COLUMN %s %s", table, column, definition));
    }

    /**
     * Add a missing timestamp column to a table created before that column existed.
     * SQLite rejects a non-constant default such as CURRENT_TIMESTAMP in ALTER TABLE ADD COLUMN,
     * so the column is added with a constant default and existing rows are then backfilled with
     * the current time.
     */
    private void addTimestampColumnIfMissing(final String table, final String column) throws SQLException
    {
        if (columnExists(table, column))
            return;

        statementHandler.executeUpdate(String.format("ALTER TABLE %s ADD COLUMN %s TEXT NOT NULL DEFAULT '%s'",
                table, column, EPOCH_TIMESTAMP));
        statementHandler.executeUpdate(String.format("UPDATE %s SET %s = CURRENT_TIMESTAMP", table, column));
    }

    private boolean columnExists(final String table, final String column) throws SQLException
    {
        try (ResultSet columns = statementHandler.executeQuery(String.format("PRAGMA table_info(%s)", table)))
        {
            while (columns.next())
            {
                if (column.equalsIgnoreCase(columns.getString("name")))
                    return true;
            }
        }
        return false;
    }

    // ============================================
    // Repository Getters
    // ============================================

    @Override
    public AdminRepository getAdminRepository()
    {
        if (adminRepository == null)
        {
            adminRepository = new GenericAdminRepository(statementHandler, this);
        }
        return adminRepository;
    }

    @Override
    public BanRepository getBanRepository()
    {
        if (banRepository == null)
        {
            banRepository = new GenericBanRepository(statementHandler, this);
        }
        return banRepository;
    }

    @Override
    public PermbanRepository getPermbanRepository()
    {
        if (permbanRepository == null)
        {
            permbanRepository = new GenericPermbanRepository(statementHandler, this);
        }
        return permbanRepository;
    }

    @Override
    public StrikeRepository getStrikeRepository()
    {
        if (strikeRepository == null)
        {
            strikeRepository = new GenericStrikeRepository(statementHandler, this);
        }
        return strikeRepository;
    }

    @Override
    public DiscordLinkRepository getDiscordLinkRepository()
    {
        if (discordLinkRepository == null)
        {
            discordLinkRepository = new GenericDiscordLinkRepository(statementHandler, this);
        }
        return discordLinkRepository;
    }

    @Override
    public TitleRepository getTitleRepository()
    {
        if (titleRepository == null)
        {
            titleRepository = new GenericTitleRepository(statementHandler, this);
        }
        return titleRepository;
    }

    @Override
    public RankRepository getRankRepository()
    {
        if (rankRepository == null)
        {
            rankRepository = new GenericRankRepository(statementHandler, this);
        }
        return rankRepository;
    }

    @Override
    public ProtectedAreaRepository getProtectedAreaRepository()
    {
        if (protectedAreaRepository == null)
        {
            protectedAreaRepository = new GenericProtectedAreaRepository(statementHandler, this);
        }
        return protectedAreaRepository;
    }

    @Override
    public SavedFlagRepository getSavedFlagRepository()
    {
        if (savedFlagRepository == null)
        {
            savedFlagRepository = new GenericSavedFlagRepository(statementHandler, this);
        }
        return savedFlagRepository;
    }

    @Override
    public PlayerRepository getPlayerRepository()
    {
        if (playerRepository == null)
        {
            playerRepository = new GenericPlayerRepository(statementHandler, this);
        }
        return playerRepository;
    }

    @Override
    public MigrationRepository getMigrationRepository()
    {
        if (migrationRepository == null)
        {
            migrationRepository = new GenericMigrationRepository(statementHandler, this);
        }
        return migrationRepository;
    }
}
