package me.totalfreedom.totalfreedommod.sql.adapter.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Bukkit;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.*;
import me.totalfreedom.totalfreedommod.sql.adapter.generic.*;
import me.totalfreedom.totalfreedommod.sql.adapter.sqlite.SQLitePunishmentHistoryRepository;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * PostgreSQL-specific database adapter.
 * Uses PostgreSQL-specific SQL features like:
 * - SERIAL for auto-incrementing primary keys
 * - INSERT ... ON CONFLICT DO NOTHING for upsert operations
 * - Double quotes (") for identifier quoting
 * - TIMESTAMP for timestamp columns
 * - ILIKE for case-insensitive LIKE
 */
public class PostgreSQLAdapter extends DatabaseAdapter
{
    private BanRepository banRepository;
    private PermbanRepository permbanRepository;
    private StrikeRepository strikeRepository;
    private RankRepository rankRepository;
    private TitleRepository titleRepository;
    private ProtectedAreaRepository protectedAreaRepository;
    private SavedFlagRepository savedFlagRepository;
    private PlayerRepository playerRepository;
    private MigrationRepository migrationRepository;
    private EconomyRepository economyRepository;
    private PunishmentHistoryRepository punishmentHistoryRepository;

    public PostgreSQLAdapter(FreedomAPI plugin, ConnectionHandler connectionHandler, StatementHandler statementHandler)
    {
        super(plugin, connectionHandler, statementHandler);
    }

    // ============================================
    // SQL Dialect Methods
    // ============================================

    @Override
    public String autoIncrementSyntax()
    {
        return "SERIAL";
    }

    @Override
    public String primaryKeySyntax()
    {
        return "PRIMARY KEY";
    }

    @Override
    public String textType()
    {
        return "TEXT";
    }

    @Override
    public String timestampType()
    {
        return "TIMESTAMP";
    }

    @Override
    public String booleanType()
    {
        return "BOOLEAN";
    }

    @Override
    public String jsonType()
    {
        return "JSONB";
    }

    @Override
    public String jsonParamPlaceholder()
    {
        return "?::jsonb";
    }

    @Override
    public String insertIgnoreSyntax()
    {
        // PostgreSQL uses ON CONFLICT DO NOTHING instead of INSERT IGNORE
        return "INSERT";  // Base INSERT, ON CONFLICT added per-statement
    }

    @Override
    public String insertIgnoreSuffix()
    {
        return " ON CONFLICT DO NOTHING";
    }

    @Override
    public String quoteIdentifier(String identifier)
    {
        return "\"" + identifier + "\"";
    }

    @Override
    public String currentTimestamp()
    {
        return "CURRENT_TIMESTAMP";
    }

    @Override
    public String timestampParamPlaceholder()
    {
        // PostgreSQL won't implicitly convert a bound String to timestamp.
        return "?::timestamp";
    }

    @Override
    public String caseInsensitiveEquals(String columnRef, String paramPlaceholder)
    {
        return String.format("LOWER(%s) = LOWER(%s)", columnRef, paramPlaceholder);
    }

    @Override
    public String compareToNow(String columnRef, String operator)
    {
        return String.format("%s %s CURRENT_TIMESTAMP", columnRef, operator);
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
        FLog.info("[PostgreSQL] Running database migrations...");

        createMigrationTable();
        createBansTable();
        createBanIpsTable();
        createPermbansTable();
        createPermbanIpsTable();
        createStrikesTable();
        createRanksTable();
        createRankPermissionsTable();
        createTitlesTable();
        createTitlePermissionsTable();
        createProtectedAreasTable();
        createSavedFlagsTable();
        createPlayerDataTable();
        migrateLegacyPlayersTable();
        migrateLegacyStaffTables();
        createEconomyPlayersTable();
        createEconomyBankTable();
        createPunishmentHistoryTable();
        createPunishmentHistoryIpsTable();

        FLog.info("[PostgreSQL] Database migrations complete.");
    }

    private void createMigrationTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "migrations" (
                "id" SERIAL PRIMARY KEY,
                "version" VARCHAR(50) NOT NULL UNIQUE,
                "applied_at" TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createBansTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "bans" (
                "id" SERIAL PRIMARY KEY,
                "uuid" VARCHAR(36),
                "username" VARCHAR(16),
                "banned_by" VARCHAR(16),
                "banned_by_uuid" VARCHAR(36),
                "reason" TEXT,
                "expire_at" TIMESTAMP,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"bans\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");

        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_uuid ON \"bans\"(\"uuid\")");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_username ON \"bans\"(\"username\")");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_expire ON \"bans\"(\"expire_at\")");
    }

    private void createBanIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "ban_ips" (
                "id" SERIAL PRIMARY KEY,
                "ban_id" INTEGER NOT NULL REFERENCES "bans"("id") ON DELETE CASCADE,
                "ip" VARCHAR(45) NOT NULL,
                UNIQUE ("ban_id", "ip")
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ban_ips_ip ON \"ban_ips\"(\"ip\")");
    }

    private void createPermbansTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "permbans" (
                "id" SERIAL PRIMARY KEY,
                "uuid" VARCHAR(36),
                "username" VARCHAR(16) NOT NULL,
                "reason" TEXT,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"permbans\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");

        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_permbans_uuid ON \"permbans\"(\"uuid\")");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_permbans_username ON \"permbans\"(\"username\")");
    }

    private void createPermbanIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "permban_ips" (
                "id" SERIAL PRIMARY KEY,
                "permban_id" INTEGER NOT NULL REFERENCES "permbans"("id") ON DELETE CASCADE,
                "ip" VARCHAR(45) NOT NULL,
                UNIQUE ("permban_id", "ip")
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_permban_ips_ip ON \"permban_ips\"(\"ip\")");
    }

    private void createStrikesTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "strikes" (
                "ip" VARCHAR(45) PRIMARY KEY,
                "strike_count" INTEGER NOT NULL DEFAULT 0,
                "last_strike_unix" BIGINT NOT NULL DEFAULT 0,
                "last_username" VARCHAR(16),
                "created_at" TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"strikes\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
    }

    private void createRanksTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "ranks" (
                "id" VARCHAR(64) PRIMARY KEY,
                "name" VARCHAR(64) NOT NULL,
                "determiner" VARCHAR(8) NOT NULL DEFAULT 'a',
                "abbreviation" VARCHAR(16),
                "level" INTEGER NOT NULL DEFAULT 0,
                "color" VARCHAR(32) NOT NULL DEFAULT 'white',
                "admin" BOOLEAN NOT NULL DEFAULT FALSE,
                "prefix" VARCHAR(64),
                "inherit_from" VARCHAR(64),
                "roles" VARCHAR(255),
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"ranks\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        statementHandler.executeUpdate("ALTER TABLE \"ranks\" ADD COLUMN IF NOT EXISTS \"roles\" VARCHAR(255)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ranks_level ON \"ranks\"(\"level\")");
    }

    private void createRankPermissionsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "rank_permissions" (
                "id" SERIAL PRIMARY KEY,
                "rank_id" VARCHAR(64) NOT NULL REFERENCES "ranks"("id") ON DELETE CASCADE,
                "permission" VARCHAR(128) NOT NULL,
                UNIQUE ("rank_id", "permission")
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createTitlesTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "titles" (
                "id" VARCHAR(64) PRIMARY KEY,
                "name" VARCHAR(64) NOT NULL,
                "determiner" VARCHAR(8) NOT NULL DEFAULT 'a',
                "abbreviation" VARCHAR(16),
                "color" VARCHAR(32) NOT NULL DEFAULT 'white',
                "prefix" VARCHAR(64),
                "weight" INTEGER NOT NULL DEFAULT 0,
                "announce" BOOLEAN NOT NULL DEFAULT TRUE,
                "updated_at" TIMESTAMP NOT NULL DEFAULT NOW()
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createTitlePermissionsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "title_permissions" (
                "id" SERIAL PRIMARY KEY,
                "title_id" VARCHAR(64) NOT NULL REFERENCES "titles"("id") ON DELETE CASCADE,
                "permission" VARCHAR(128) NOT NULL,
                UNIQUE ("title_id", "permission")
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createProtectedAreasTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "protected_areas" (
                "uuid" VARCHAR(36) PRIMARY KEY,
                "name" VARCHAR(64) NOT NULL,
                "min_x" INTEGER NOT NULL,
                "min_y" INTEGER NOT NULL,
                "min_z" INTEGER NOT NULL,
                "max_x" INTEGER NOT NULL,
                "max_y" INTEGER NOT NULL,
                "max_z" INTEGER NOT NULL,
                "world_uuid" VARCHAR(36) NOT NULL,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"protected_areas\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_protected_areas_name ON \"protected_areas\"(\"name\")");
    }

    private void createSavedFlagsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "saved_flags" (
                "flag_name" VARCHAR(64) PRIMARY KEY,
                "enabled" BOOLEAN NOT NULL DEFAULT FALSE,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"saved_flags\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
    }

    private void createPlayerDataTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "player_data" (
                "uuid" CHAR(36) PRIMARY KEY,
                "username" VARCHAR(16) NOT NULL,
                "first_join_unix" BIGINT NOT NULL DEFAULT 0,
                "last_join_unix" BIGINT NOT NULL DEFAULT 0,
                "potion_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "command_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "command_spy_alert_sound" VARCHAR(128) NOT NULL DEFAULT 'entity.ender_dragon.growl',
                "command_spy_alert_regex" TEXT,
                "sign_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "book_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "muted" BOOLEAN NOT NULL DEFAULT FALSE,
                "frozen" BOOLEAN NOT NULL DEFAULT FALSE,
                "commands_blocked" BOOLEAN NOT NULL DEFAULT FALSE,
                "join_leave_messages" BOOLEAN NOT NULL DEFAULT TRUE,
                "strikes" INTEGER NOT NULL DEFAULT 0,
                "saved_tag" TEXT,
                "nickname" TEXT,
                "ip" VARCHAR(45),
                "rank_id" VARCHAR(64),
                "admin_active" BOOLEAN NOT NULL DEFAULT TRUE,
                "login_message" TEXT,
                "discord_id" VARCHAR(32),
                "discord_identifier" VARCHAR(8),
                "trusted_ips" TEXT,
                "titles" TEXT,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"player_data\" ADD COLUMN IF NOT EXISTS \"trusted_ips\" TEXT");
    }

    /**
     * Copies the username-keyed {@code players} table, if this database still has one, into
     * {@code player_data}. The old table is patched up to the last column layout first so it reads
     * the same whichever version created it.
     */
    private void migrateLegacyPlayersTable() throws SQLException
    {
        if (!tableExists("players"))
            return;

        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"titles\" TEXT");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"potion_spy_mode\" VARCHAR(16) NOT NULL DEFAULT 'off'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"command_spy_alert_sound\" VARCHAR(128) NOT NULL DEFAULT 'entity.ender_dragon.growl'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"command_spy_alert_regex\" TEXT");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"sign_spy_mode\" VARCHAR(16) NOT NULL DEFAULT 'off'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"book_spy_mode\" VARCHAR(16) NOT NULL DEFAULT 'off'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"join_leave_messages\" BOOLEAN NOT NULL DEFAULT TRUE");

        new GenericPlayerRepository(statementHandler, this).migrateLegacyTables();
    }

    /**
     * Moves the retired admins tables, if this database still has them, onto {@code player_data}.
     * The old table is patched up to the last column layout first so it reads the same whichever
     * version created it.
     */
    private void migrateLegacyStaffTables() throws SQLException
    {
        if (!tableExists("admins"))
            return;

        statementHandler.executeUpdate("ALTER TABLE \"admins\" ADD COLUMN IF NOT EXISTS \"custom_rank\" VARCHAR(64)");
        statementHandler.executeUpdate("ALTER TABLE \"admins\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");

        new GenericPlayerRepository(statementHandler, this).migrateLegacyStaff(tableExists("discord_links"), Bukkit.getServer().getServerConfig().isProxyOnlineMode());
    }

    @Override
    public boolean tableExists(final String table) throws SQLException
    {
        try (ResultSet rs = statementHandler.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?", table))
        {
            return rs.next();
        }
    }

    private void createEconomyPlayersTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "economy_players" (
                "username" VARCHAR(16) PRIMARY KEY,
                "uuid" VARCHAR(36) NOT NULL,
                "wallet_balance" INTEGER NOT NULL DEFAULT 0,
                "checking_balance" INTEGER NOT NULL DEFAULT 0,
                "savings_balance" INTEGER NOT NULL DEFAULT 0,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createEconomyBankTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "economy_bank" (
                "id" VARCHAR(16) PRIMARY KEY,
                "checking_balance" INTEGER NOT NULL DEFAULT 0,
                "savings_balance" INTEGER NOT NULL DEFAULT 0,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createPunishmentHistoryTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "punishment_history" (
                "id" BIGSERIAL PRIMARY KEY,
                "target_uuid" VARCHAR(36),
                "target_name" VARCHAR(255),
                "actor_uuid" VARCHAR(36),
                "actor_name" VARCHAR(255),
                "punishment_type" VARCHAR(64) NOT NULL,
                "reason" TEXT,
                "applied_at" BIGINT NOT NULL,
                "expires_at" BIGINT,
                "source" VARCHAR(64)
            )
            """;
        statementHandler.executeUpdate(sql);
        // Appeals revoke an entry rather than deleting it, so the record of what happened stays.
        statementHandler.executeUpdate("ALTER TABLE \"punishment_history\" ADD COLUMN IF NOT EXISTS \"revoked_at\" BIGINT");
        statementHandler.executeUpdate("ALTER TABLE \"punishment_history\" ADD COLUMN IF NOT EXISTS \"revoked_by\" VARCHAR(64)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishment_history_target_uuid ON \"punishment_history\"(\"target_uuid\", \"applied_at\" DESC, \"id\" DESC)");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishment_history_target_name ON \"punishment_history\"(\"target_name\")");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishment_history_applied_at ON \"punishment_history\"(\"applied_at\" DESC, \"id\" DESC)");
    }

    private void createPunishmentHistoryIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "punishment_history_ips" (
                "id" BIGSERIAL PRIMARY KEY,
                "history_id" BIGINT NOT NULL REFERENCES "punishment_history"("id") ON DELETE CASCADE,
                "ip" VARCHAR(45) NOT NULL,
                UNIQUE ("history_id", "ip")
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_punishment_history_ips_ip ON \"punishment_history_ips\"(\"ip\")");
    }

    // ============================================
    // Repository Getters
    // ============================================

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

    @Override
    public EconomyRepository getEconomyRepository()
    {
        if (economyRepository == null)
        {
            economyRepository = new GenericEconomyRepository(statementHandler, this);
        }
        return economyRepository;
    }

    @Override
    public PunishmentHistoryRepository getPunishmentHistoryRepository()
    {
        if (punishmentHistoryRepository == null)
        {
            punishmentHistoryRepository = new SQLitePunishmentHistoryRepository(statementHandler);
        }
        return punishmentHistoryRepository;
    }
}
