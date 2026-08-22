package me.totalfreedom.totalfreedommod.sql.adapter.postgresql;

import java.sql.SQLException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.totalfreedommod.sql.adapter.*;
import me.totalfreedom.totalfreedommod.sql.adapter.generic.*;
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

    public PostgreSQLAdapter(TotalFreedomMod plugin, ConnectionHandler connectionHandler, StatementHandler statementHandler)
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

    private void createAdminsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "admins" (
                "id" SERIAL PRIMARY KEY,
                "uuid" VARCHAR(36) NOT NULL UNIQUE,
                "username" VARCHAR(16) NOT NULL,
                "rank" VARCHAR(32) NOT NULL,
                "active" BOOLEAN DEFAULT TRUE,
                "last_login" TIMESTAMP,
                "login_message" TEXT,
                "custom_rank" VARCHAR(64),
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);

        // Migration for tables created before custom_rank/updated_at existed.
        statementHandler.executeUpdate("ALTER TABLE \"admins\" ADD COLUMN IF NOT EXISTS \"custom_rank\" VARCHAR(64)");
        statementHandler.executeUpdate("ALTER TABLE \"admins\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");

        // Create indexes separately (PostgreSQL style)
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admins_username ON \"admins\"(\"username\")");
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admins_active ON \"admins\"(\"active\")");
    }

    private void createAdminIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "admin_ips" (
                "id" SERIAL PRIMARY KEY,
                "admin_id" INTEGER NOT NULL REFERENCES "admins"("id") ON DELETE CASCADE,
                "ip" VARCHAR(45) NOT NULL,
                UNIQUE ("admin_id", "ip")
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("CREATE INDEX IF NOT EXISTS idx_admin_ips_ip ON \"admin_ips\"(\"ip\")");
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

    private void createDiscordLinksTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "discord_links" (
                "id" SERIAL PRIMARY KEY,
                "admin_uuid" VARCHAR(36) NOT NULL UNIQUE,
                "discord_user_id" VARCHAR(32) NOT NULL UNIQUE,
                "linked_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"discord_links\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
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

    private void createPlayersTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "players" (
                "username" VARCHAR(16) PRIMARY KEY,
                "first_join_unix" BIGINT NOT NULL DEFAULT 0,
                "last_join_unix" BIGINT NOT NULL DEFAULT 0,
                "potion_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "command_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "sign_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "book_spy_mode" VARCHAR(16) NOT NULL DEFAULT 'off',
                "muted" BOOLEAN NOT NULL DEFAULT FALSE,
                "frozen" BOOLEAN NOT NULL DEFAULT FALSE,
                "commands_blocked" BOOLEAN NOT NULL DEFAULT FALSE,
                "join_leave_messages" BOOLEAN NOT NULL DEFAULT TRUE,
                "strikes" INTEGER NOT NULL DEFAULT 0,
                "saved_tag" TEXT,
                "nickname" TEXT,
                "updated_at" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;
        statementHandler.executeUpdate(sql);
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"updated_at\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"titles\" TEXT");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"potion_spy_mode\" VARCHAR(16) NOT NULL DEFAULT 'off'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"sign_spy_mode\" VARCHAR(16) NOT NULL DEFAULT 'off'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"book_spy_mode\" VARCHAR(16) NOT NULL DEFAULT 'off'");
        statementHandler.executeUpdate("ALTER TABLE \"players\" ADD COLUMN IF NOT EXISTS \"join_leave_messages\" BOOLEAN NOT NULL DEFAULT TRUE");
    }

    private void createPlayerIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS "player_ips" (
                "id" SERIAL PRIMARY KEY,
                "username" VARCHAR(16) NOT NULL REFERENCES "players"("username") ON DELETE CASCADE,
                "ip" VARCHAR(45) NOT NULL,
                UNIQUE ("username", "ip")
            )
            """;
        statementHandler.executeUpdate(sql);
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
