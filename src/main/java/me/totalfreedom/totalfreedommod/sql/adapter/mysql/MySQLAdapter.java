package me.totalfreedom.totalfreedommod.sql.adapter.mysql;

import java.sql.SQLException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.totalfreedommod.sql.ConnectionHandler;
import me.totalfreedom.totalfreedommod.sql.StatementHandler;
import me.totalfreedom.api.sql.adapter.*;
import me.totalfreedom.totalfreedommod.sql.adapter.*;
import me.totalfreedom.totalfreedommod.sql.adapter.generic.*;
import me.totalfreedom.totalfreedommod.util.FLog;

/**
 * MySQL-specific database adapter.
 * Works with both MySQL and MariaDB as they share SQL syntax.
 * Uses MySQL-specific SQL features like:
 * - INT AUTO_INCREMENT for primary keys
 * - INSERT IGNORE for upsert operations
 * - Backtick (`) for identifier quoting
 * - DATETIME for timestamp columns
 * - LOWER() for case-insensitive comparisons (collation may also handle this)
 */
public class MySQLAdapter extends DatabaseAdapter
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
    private EconomyRepository economyRepository;

    public MySQLAdapter(FreedomAPI plugin, ConnectionHandler connectionHandler, StatementHandler statementHandler)
    {
        super(plugin, connectionHandler, statementHandler);
    }

    // ============================================
    // SQL Dialect Methods
    // ============================================

    @Override
    public String autoIncrementSyntax()
    {
        return "INT AUTO_INCREMENT";
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
        return "DATETIME";
    }

    @Override
    public String booleanType()
    {
        return "TINYINT(1)";
    }

    @Override
    public String jsonType()
    {
        return "JSON";
    }

    @Override
    public String jsonParamPlaceholder()
    {
        return "?";
    }

    @Override
    public String insertIgnoreSyntax()
    {
        return "INSERT IGNORE";
    }

    @Override
    public String insertIgnoreSuffix()
    {
        return "";
    }

    @Override
    public String quoteIdentifier(String identifier)
    {
        return "`" + identifier + "`";
    }

    @Override
    public String currentTimestamp()
    {
        return "NOW()";
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
        return String.format("%s %s NOW()", columnRef, operator);
    }

    // MySQL's ON DUPLICATE KEY UPDATE infers the conflicting row from whatever key was violated.
    @Override
    public String upsertClause(String conflictColumn, String... updateColumns)
    {
        String assignments = Stream.of(updateColumns)
                                   .map(col -> String.format("%s = VALUES(%s)", col, col))
                                   .collect(Collectors.joining(", "));
        return String.format("ON DUPLICATE KEY UPDATE %s", assignments);
    }

    // ============================================
    // Migration Methods
    // ============================================

    @Override
    public void runMigrations() throws SQLException
    {
        FLog.info("[MySQL] Running database migrations...");

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
        createEconomyPlayersTable();
        createEconomyBankTable();

        FLog.info("[MySQL] Database migrations complete.");
    }

    private void createMigrationTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `migrations` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `version` VARCHAR(50) NOT NULL UNIQUE,
                `applied_at` DATETIME DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createAdminsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `admins` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `uuid` VARCHAR(36) NOT NULL UNIQUE,
                `username` VARCHAR(16) NOT NULL,
                `rank` VARCHAR(32) NOT NULL,
                `active` TINYINT(1) DEFAULT 1,
                `last_login` DATETIME,
                `login_message` TEXT,
                `custom_rank` VARCHAR(64),
                `updated_at` DATETIME NOT NULL DEFAULT NOW(),
                INDEX `idx_admins_username` (`username`),
                INDEX `idx_admins_active` (`active`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);

        // Migration for tables created before custom_rank/updated_at existed.
        addColumnIfMissing("admins", "custom_rank", "VARCHAR(64)");
        addColumnIfMissing("admins", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createAdminIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `admin_ips` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `admin_id` INT NOT NULL,
                `ip` VARCHAR(45) NOT NULL,
                UNIQUE KEY `uk_admin_ip` (`admin_id`, `ip`),
                INDEX `idx_admin_ips_ip` (`ip`),
                FOREIGN KEY (`admin_id`) REFERENCES `admins`(`id`) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createBansTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `bans` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `uuid` VARCHAR(36),
                `username` VARCHAR(16),
                `banned_by` VARCHAR(16),
                `banned_by_uuid` VARCHAR(36),
                `reason` TEXT,
                `expire_at` DATETIME,
                `updated_at` DATETIME NOT NULL DEFAULT NOW(),
                INDEX `idx_bans_uuid` (`uuid`),
                INDEX `idx_bans_username` (`username`),
                INDEX `idx_bans_expire` (`expire_at`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("bans", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createBanIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `ban_ips` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `ban_id` INT NOT NULL,
                `ip` VARCHAR(45) NOT NULL,
                UNIQUE KEY `uk_ban_ip` (`ban_id`, `ip`),
                INDEX `idx_ban_ips_ip` (`ip`),
                FOREIGN KEY (`ban_id`) REFERENCES `bans`(`id`) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createPermbansTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `permbans` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `uuid` VARCHAR(36),
                `username` VARCHAR(16) NOT NULL,
                `reason` TEXT,
                `updated_at` DATETIME NOT NULL DEFAULT NOW(),
                INDEX `idx_permbans_uuid` (`uuid`),
                INDEX `idx_permbans_username` (`username`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("permbans", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createPermbanIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `permban_ips` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `permban_id` INT NOT NULL,
                `ip` VARCHAR(45) NOT NULL,
                UNIQUE KEY `uk_permban_ip` (`permban_id`, `ip`),
                INDEX `idx_permban_ips_ip` (`ip`),
                FOREIGN KEY (`permban_id`) REFERENCES `permbans`(`id`) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createStrikesTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `strikes` (
                `ip` VARCHAR(45) PRIMARY KEY,
                `strike_count` INT NOT NULL DEFAULT 0,
                `last_strike_unix` BIGINT NOT NULL DEFAULT 0,
                `last_username` VARCHAR(16),
                `created_at` DATETIME DEFAULT NOW(),
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("strikes", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createDiscordLinksTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `discord_links` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `admin_uuid` VARCHAR(36) NOT NULL UNIQUE,
                `discord_user_id` VARCHAR(32) NOT NULL UNIQUE,
                `linked_at` DATETIME NOT NULL DEFAULT NOW(),
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("discord_links", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createRanksTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `ranks` (
                `id` VARCHAR(64) PRIMARY KEY,
                `name` VARCHAR(64) NOT NULL,
                `determiner` VARCHAR(8) NOT NULL DEFAULT 'a',
                `abbreviation` VARCHAR(16),
                `level` INT NOT NULL DEFAULT 0,
                `color` VARCHAR(32) NOT NULL DEFAULT 'white',
                `admin` TINYINT(1) NOT NULL DEFAULT 0,
                `prefix` VARCHAR(64),
                `inherit_from` VARCHAR(64),
                `roles` VARCHAR(255),
                `updated_at` DATETIME NOT NULL DEFAULT NOW(),
                INDEX `idx_ranks_level` (`level`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("ranks", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
        addColumnIfMissing("ranks", "roles", "VARCHAR(255)");
    }

    private void createRankPermissionsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `rank_permissions` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `rank_id` VARCHAR(64) NOT NULL,
                `permission` VARCHAR(128) NOT NULL,
                UNIQUE KEY `uk_rank_permission` (`rank_id`, `permission`),
                FOREIGN KEY (`rank_id`) REFERENCES `ranks`(`id`) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createTitlesTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `titles` (
                `id` VARCHAR(64) PRIMARY KEY,
                `name` VARCHAR(64) NOT NULL,
                `determiner` VARCHAR(8) NOT NULL DEFAULT 'a',
                `abbreviation` VARCHAR(16),
                `color` VARCHAR(32) NOT NULL DEFAULT 'white',
                `prefix` VARCHAR(64),
                `weight` INT NOT NULL DEFAULT 0,
                `announce` TINYINT(1) NOT NULL DEFAULT 1,
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createTitlePermissionsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `title_permissions` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `title_id` VARCHAR(64) NOT NULL,
                `permission` VARCHAR(128) NOT NULL,
                UNIQUE KEY `uk_title_permission` (`title_id`, `permission`),
                FOREIGN KEY (`title_id`) REFERENCES `titles`(`id`) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createProtectedAreasTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `protected_areas` (
                `uuid` VARCHAR(36) PRIMARY KEY,
                `name` VARCHAR(64) NOT NULL,
                `min_x` INT NOT NULL,
                `min_y` INT NOT NULL,
                `min_z` INT NOT NULL,
                `max_x` INT NOT NULL,
                `max_y` INT NOT NULL,
                `max_z` INT NOT NULL,
                `world_uuid` VARCHAR(36) NOT NULL,
                `updated_at` DATETIME NOT NULL DEFAULT NOW(),
                INDEX `idx_protected_areas_name` (`name`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("protected_areas", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createSavedFlagsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `saved_flags` (
                `flag_name` VARCHAR(64) PRIMARY KEY,
                `enabled` TINYINT(1) NOT NULL DEFAULT 0,
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("saved_flags", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
    }

    private void createPlayersTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `players` (
                `username` VARCHAR(16) PRIMARY KEY,
                `first_join_unix` BIGINT NOT NULL DEFAULT 0,
                `last_join_unix` BIGINT NOT NULL DEFAULT 0,
                `potion_spy_mode` VARCHAR(16) NOT NULL DEFAULT 'off',
                `command_spy_mode` VARCHAR(16) NOT NULL DEFAULT 'off',
                `command_spy_alert_sound` VARCHAR(128) NOT NULL DEFAULT 'entity.ender_dragon.growl',
                `command_spy_alert_regex` TEXT,
                `sign_spy_mode` VARCHAR(16) NOT NULL DEFAULT 'off',
                `book_spy_mode` VARCHAR(16) NOT NULL DEFAULT 'off',
                `muted` TINYINT(1) NOT NULL DEFAULT 0,
                `frozen` TINYINT(1) NOT NULL DEFAULT 0,
                `commands_blocked` TINYINT(1) NOT NULL DEFAULT 0,
                `join_leave_messages` TINYINT(1) NOT NULL DEFAULT 1,
                `strikes` INT NOT NULL DEFAULT 0,
                `saved_tag` TEXT,
                `nickname` TEXT,
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
        addColumnIfMissing("players", "updated_at", "DATETIME NOT NULL DEFAULT NOW()");
        addColumnIfMissing("players", "titles", "TEXT");
        addColumnIfMissing("players", "potion_spy_mode", "VARCHAR(16) NOT NULL DEFAULT 'off'");
        addColumnIfMissing("players", "command_spy_alert_sound", "VARCHAR(128) NOT NULL DEFAULT 'entity.ender_dragon.growl'");
        addColumnIfMissing("players", "command_spy_alert_regex", "TEXT");
        addColumnIfMissing("players", "sign_spy_mode", "VARCHAR(16) NOT NULL DEFAULT 'off'");
        addColumnIfMissing("players", "book_spy_mode", "VARCHAR(16) NOT NULL DEFAULT 'off'");
        addColumnIfMissing("players", "join_leave_messages", "TINYINT(1) NOT NULL DEFAULT 1");
    }

    private void createPlayerIpsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `player_ips` (
                `id` INT AUTO_INCREMENT PRIMARY KEY,
                `username` VARCHAR(16) NOT NULL,
                `ip` VARCHAR(45) NOT NULL,
                UNIQUE KEY `uk_player_ip` (`username`, `ip`),
                FOREIGN KEY (`username`) REFERENCES `players`(`username`) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createEconomyPlayersTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `economy_players` (
                `username` VARCHAR(16) PRIMARY KEY,
                `uuid` VARCHAR(36) NOT NULL,
                `wallet_balance` INT NOT NULL DEFAULT 0,
                `checking_balance` INT NOT NULL DEFAULT 0,
                `savings_balance` INT NOT NULL DEFAULT 0,
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    private void createEconomyBankTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS `economy_bank` (
                `id` VARCHAR(16) PRIMARY KEY,
                `checking_balance` INT NOT NULL DEFAULT 0,
                `savings_balance` INT NOT NULL DEFAULT 0,
                `updated_at` DATETIME NOT NULL DEFAULT NOW()
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
        statementHandler.executeUpdate(sql);
    }

    /**
     * Add a column to a table created before that column existed. Ignores the error
     * when the column is already present (MySQL has no ADD COLUMN IF NOT EXISTS
     * before 8.0.29, so this stays a try/catch like the rest of this file's migrations).
     */
    private void addColumnIfMissing(String table, String column, String definition)
    {
        try
        {
            statementHandler.executeUpdate(String.format("ALTER TABLE `%s` ADD COLUMN `%s` %s", table, column, definition));
        }
        catch (SQLException ignored)
        {
            // Column already exists.
        }
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

    @Override
    public EconomyRepository getEconomyRepository()
    {
        if (economyRepository == null)
        {
            economyRepository = new GenericEconomyRepository(statementHandler, this);
        }
        return economyRepository;
    }
}
