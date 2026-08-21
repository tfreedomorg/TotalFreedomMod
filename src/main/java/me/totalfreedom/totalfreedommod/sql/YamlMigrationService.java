package me.totalfreedom.totalfreedommod.sql;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion;
import me.totalfreedom.totalfreedommod.ProtectArea.ProtectedRegion.CantFindWorldException;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.banning.Ban;
import me.totalfreedom.totalfreedommod.banning.PermBan;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.sql.adapter.*;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;

/**
 * Service for migrating data from YAML files to SQL database.
 * Handles one-time migration of admins.yml, bans.yml, and permbans.yml.
 * 
 * Migration process:
 * 1. Check if migration has already been completed (migration flag in database)
 * 2. Read data from YAML files
 * 3. Insert data into SQL database
 * 4. Mark migration as complete
 * 5. Optionally rename/backup old YAML files
 */
public class YamlMigrationService
{
    private final TotalFreedomMod plugin;
    private final FreedomDatabase databaseManager;

    private static final String ADMINS_FILE = "admins.yml";
    private static final String BANS_FILE = "bans.yml";
    private static final String PERMBANS_FILE = "permbans.yml";
    private static final String RANKS_FILE = "ranks.yml";
    private static final String PROTECTED_AREAS_FILE = "protectedareas.yml";
    private static final String SAVED_FLAGS_FILE = "savedflags.yml";
    private static final String PLAYERS_DIR = "players";

    /**
     * Ledger keys, one per domain. Recorded in the {@code migrations} table once that domain's
     * YAML import has run, whether or not it found anything to import.
     */
    private static final String V_ADMINS = "yaml-import:admins";
    private static final String V_BANS = "yaml-import:bans";
    private static final String V_PERMBANS = "yaml-import:permbans";
    private static final String V_RANKS = "yaml-import:ranks";
    private static final String V_PROTECTED_AREAS = "yaml-import:protected_areas";
    private static final String V_SAVED_FLAGS = "yaml-import:saved_flags";
    private static final String V_PLAYERS = "yaml-import:players";

    private static final Set<String> ALL_VERSIONS = Set.of(
            V_ADMINS, V_BANS, V_PERMBANS, V_RANKS, V_PROTECTED_AREAS, V_SAVED_FLAGS, V_PLAYERS);

    public YamlMigrationService(TotalFreedomMod plugin, FreedomDatabase databaseManager)
    {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /**
     * Run all migrations if they haven't been completed yet.
     * @return Mono that completes when all migrations are done
     */
    public Mono<Void> runMigrations()
    {
        return Mono.<Void>fromRunnable(() -> {
            try
            {
                FLog.info("Checking for YAML data migrations...");

                if (!databaseManager.isInitialized())
                {
                    FLog.warning("Database not initialized, skipping YAML migrations");
                    return;
                }

                runAllMigrations();

                FLog.info("YAML data migration check complete");
            }
            catch (Exception ex)
            {
                FLog.severe("Error during YAML migrations: " + ex.getMessage());
                ex.printStackTrace();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Run every domain's one-time YAML import, in the order their tables reference each other,
     * skipping any already recorded in the ledger.
     */
    private void runAllMigrations() throws SQLException
    {
        final MigrationRepository ledger = databaseManager.getMigrationRepository();
        final Set<String> applied = ledger.findApplied();

        if (applied.containsAll(ALL_VERSIONS))
        {
            FLog.info("YAML imports already applied to this database; nothing to migrate.");
            return;
        }

        runOnce(ledger, applied, V_ADMINS, this::migrateAdmins);
        runOnce(ledger, applied, V_BANS, this::migrateBans);
        runOnce(ledger, applied, V_PERMBANS, this::migratePermbans);
        runOnce(ledger, applied, V_RANKS, this::migrateRanks);
        runOnce(ledger, applied, V_PROTECTED_AREAS, this::migrateProtectedAreas);
        runOnce(ledger, applied, V_SAVED_FLAGS, this::migrateSavedFlags);
        runOnce(ledger, applied, V_PLAYERS, this::migratePlayers);
    }

    /**
     * Run {@code body} unless {@code version} is already in the ledger, then record it.
     * <p>
     * The version is recorded even when the domain had no YAML to import, so an install that
     * never had the file stops re-checking for it. A {@code body} that throws is left
     * unrecorded and retried on the next start; the remaining domains still run, since one
     * unimportable file should not strand every other domain's data.
     */
    private void runOnce(final MigrationRepository ledger, final Set<String> applied,
            final String version, final Runnable body) throws SQLException
    {
        if (applied.contains(version))
            return;

        try
        {
            body.run();
        }
        catch (RuntimeException ex)
        {
            FLog.warning(String.format("YAML import '%s' did not complete, will retry on next start: %s",
                    version, ex.getMessage()));
            return;
        }

        ledger.markApplied(version);
    }

    /**
     * Migrate admins from admins.yml to database.
     */
    private void migrateAdmins()
    {
        File adminsFile = new File(plugin.getDataFolder(), ADMINS_FILE);
        if (!adminsFile.exists())
        {
            FLog.info("No admins.yml found, skipping admin migration");
            return;
        }

        AdminRepository repo = databaseManager.getAdminRepository();

        // Check if we already have admins in the database
        try
        {
            List<Admin> existingAdmins = repo.findAll().block();
            if (!existingAdmins.isEmpty())
            {
                FLog.info("Database already contains " + existingAdmins.size() + " admins, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing admins: " + ex.getMessage());
        }

        FLog.info("Migrating admins from " + ADMINS_FILE + "...");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(adminsFile);
        AtomicInteger migrated = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (String key : config.getKeys(false))
        {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null)
            {
                FLog.warning("Invalid admin entry: " + key);
                failed.incrementAndGet();
                continue;
            }

            try
            {
                Admin admin = new Admin(key);
                admin.loadFrom(section);

                if (!admin.isValid())
                {
                    FLog.warning("Invalid admin data for: " + key);
                    failed.incrementAndGet();
                    continue;
                }

                // Generate UUID if not present (legacy data)
                UUID uuid = generateUuidForAdmin(admin);

                repo.save(uuid, admin).block();
                migrated.incrementAndGet();
            }
            catch (Exception ex)
            {
                FLog.warning("Failed to migrate admin " + key + ": " + ex.getMessage());
                failed.incrementAndGet();
            }
        }

        FLog.info("Admin migration complete: " + migrated.get() + " migrated, " + failed.get() + " failed");

        finishMigration(failed.get(), adminsFile);
    }

    /**
     * Migrate bans from bans.yml to database.
     */
    private void migrateBans()
    {
        File bansFile = new File(plugin.getDataFolder(), BANS_FILE);
        if (!bansFile.exists())
        {
            FLog.info("No bans.yml found, skipping ban migration");
            return;
        }

        BanRepository repo = databaseManager.getBanRepository();

        // Check if we already have bans in the database
        try
        {
            List<Ban> existingBans = repo.findAll().block();
            if (!existingBans.isEmpty())
            {
                FLog.info("Database already contains " + existingBans.size() + " bans, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing bans: " + ex.getMessage());
        }

        FLog.info("Migrating bans from " + BANS_FILE + "...");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(bansFile);
        AtomicInteger migrated = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (String key : config.getKeys(false))
        {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null)
            {
                FLog.warning("Invalid ban entry: " + key);
                failed.incrementAndGet();
                continue;
            }

            try
            {
                Ban ban = new Ban();
                ban.loadFrom(section);

                if (!ban.isValid())
                {
                    FLog.warning("Invalid ban data for: " + key);
                    failed.incrementAndGet();
                    continue;
                }

                repo.save(ban).block();
                migrated.incrementAndGet();
            }
            catch (Exception ex)
            {
                FLog.warning("Failed to migrate ban " + key + ": " + ex.getMessage());
                failed.incrementAndGet();
            }
        }

        FLog.info("Ban migration complete: " + migrated.get() + " migrated, " + failed.get() + " failed");

        finishMigration(failed.get(), bansFile);
    }

    /**
     * Migrate permbans from permbans.yml to database.
     */
    private void migratePermbans()
    {
        File permbansFile = new File(plugin.getDataFolder(), PERMBANS_FILE);
        if (!permbansFile.exists())
        {
            FLog.info("No permbans.yml found, skipping permban migration");
            return;
        }

        PermbanRepository repo = databaseManager.getPermbanRepository();

        // Check if we already have permbans in the database
        try
        {
            List<PermBan> existingPermbans = repo.findAll().block();
            if (!existingPermbans.isEmpty())
            {
                FLog.info("Database already contains " + existingPermbans.size() + " permbans, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing permbans: " + ex.getMessage());
        }

        FLog.info("Migrating permbans from " + PERMBANS_FILE + "...");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(permbansFile);
        AtomicInteger migrated = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (String name : config.getKeys(false))
        {
            try
            {
                List<String> ips = config.getStringList(name);

                PermBan permban = new PermBan();
                permban.setUsername(name.toLowerCase().trim());
                permban.setIps(ips);
                permban.setReason("Migrated from permbans.yml");

                // Generate UUID for name
                permban.setUuid(FUtil.usernameToUuid(name));

                repo.save(permban).block();
                migrated.incrementAndGet();
            }
            catch (Exception ex)
            {
                FLog.warning("Failed to migrate permban " + name + ": " + ex.getMessage());
                failed.incrementAndGet();
            }
        }

        FLog.info("Permban migration complete: " + migrated.get() + " migrated, " + failed.get() + " failed");

        finishMigration(failed.get(), permbansFile);
    }

    /**
     * Migrate custom ranks from ranks.yml to database.
     */
    private void migrateRanks()
    {
        File ranksFile = new File(plugin.getDataFolder(), RANKS_FILE);
        if (!ranksFile.exists())
        {
            FLog.info("No ranks.yml found, skipping rank migration");
            return;
        }

        RankRepository repo = databaseManager.getRankRepository();

        try
        {
            if (!repo.loadAll().isEmpty())
            {
                FLog.info("Database already contains ranks, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing ranks: " + ex.getMessage());
        }

        FLog.info("Migrating ranks from " + RANKS_FILE + "...");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(ranksFile);
        AtomicInteger migrated = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (String key : config.getKeys(false))
        {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null)
            {
                FLog.warning("Invalid rank entry: " + key);
                failed.incrementAndGet();
                continue;
            }

            try
            {
                CustomRank rank = new CustomRank(key);
                rank.loadFrom(section);
                repo.saveOrUpdate(rank);
                migrated.incrementAndGet();
            }
            catch (Exception ex)
            {
                FLog.warning("Failed to migrate rank " + key + ": " + ex.getMessage());
                failed.incrementAndGet();
            }
        }

        FLog.info("Rank migration complete: " + migrated.get() + " migrated, " + failed.get() + " failed");

        finishMigration(failed.get(), ranksFile);
    }

    /**
     * Migrate protected areas from protectedareas.yml to database.
     */
    private void migrateProtectedAreas()
    {
        File areasFile = new File(plugin.getDataFolder(), PROTECTED_AREAS_FILE);
        if (!areasFile.exists())
        {
            FLog.info("No protectedareas.yml found, skipping protected area migration");
            return;
        }

        ProtectedAreaRepository repo = databaseManager.getProtectedAreaRepository();

        try
        {
            if (!repo.loadAll().isEmpty())
            {
                FLog.info("Database already contains protected areas, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing protected areas: " + ex.getMessage());
        }

        FLog.info("Migrating protected areas from " + PROTECTED_AREAS_FILE + "...");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(areasFile);
        ConfigurationSection areasSection = config.getConfigurationSection("areas");
        AtomicInteger migrated = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        if (areasSection != null)
        {
            for (String id : areasSection.getKeys(false))
            {
                ConfigurationSection areaSection = areasSection.getConfigurationSection(id);
                if (areaSection == null)
                {
                    FLog.warning("Invalid protected area entry: " + id);
                    failed.incrementAndGet();
                    continue;
                }

                try
                {
                    UUID uuid = UUID.fromString(id);
                    String name = areaSection.getString("name");
                    int minX = areaSection.getInt("min_x");
                    int minY = areaSection.getInt("min_y");
                    int minZ = areaSection.getInt("min_z");
                    int maxX = areaSection.getInt("max_x");
                    int maxY = areaSection.getInt("max_y");
                    int maxZ = areaSection.getInt("max_z");
                    String worldUUID = areaSection.getString("world");

                    ProtectedRegion region = new ProtectedRegion(uuid, name, minX, minY, minZ, maxX, maxY, maxZ, worldUUID);
                    repo.saveOrUpdate(region);
                    migrated.incrementAndGet();
                }
                catch (CantFindWorldException | IllegalArgumentException ex)
                {
                    FLog.warning("Failed to migrate protected area " + id + ": " + ex.getMessage());
                    failed.incrementAndGet();
                }
                catch (Exception ex)
                {
                    FLog.warning("Failed to migrate protected area " + id + ": " + ex.getMessage());
                    failed.incrementAndGet();
                }
            }
        }

        FLog.info("Protected area migration complete: " + migrated.get() + " migrated, " + failed.get() + " failed");

        finishMigration(failed.get(), areasFile);
    }

    /**
     * Migrate saved flags from savedflags.yml to database.
     */
    private void migrateSavedFlags()
    {
        File flagsFile = new File(plugin.getDataFolder(), SAVED_FLAGS_FILE);
        if (!flagsFile.exists())
        {
            FLog.info("No savedflags.yml found, skipping saved flag migration");
            return;
        }

        SavedFlagRepository repo = databaseManager.getSavedFlagRepository();

        try
        {
            if (!repo.loadAll().isEmpty())
            {
                FLog.info("Database already contains saved flags, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing saved flags: " + ex.getMessage());
        }

        FLog.info("Migrating saved flags from " + SAVED_FLAGS_FILE + "...");

        YamlConfiguration config = YamlConfiguration.loadConfiguration(flagsFile);
        ConfigurationSection flagsSection = config.getConfigurationSection("flags");
        AtomicInteger migrated = new AtomicInteger(0);

        if (flagsSection != null)
        {
            for (String key : flagsSection.getKeys(false))
            {
                try
                {
                    repo.upsert(key, flagsSection.getBoolean(key));
                    migrated.incrementAndGet();
                }
                catch (Exception ex)
                {
                    FLog.warning("Failed to migrate saved flag " + key + ": " + ex.getMessage());
                }
            }
        }

        FLog.info("Saved flag migration complete: " + migrated.get() + " migrated");

        backupFile(flagsFile);
    }

    /**
     * Migrate per-player data from players/*.yml to database.
     */
    private void migratePlayers()
    {
        File playersDir = new File(plugin.getDataFolder(), PLAYERS_DIR);
        File[] files = playersDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0)
        {
            FLog.info("No players/*.yml found, skipping player data migration");
            return;
        }

        PlayerRepository repo = databaseManager.getPlayerRepository();

        try
        {
            if (!repo.loadAll().isEmpty())
            {
                FLog.info("Database already contains player data, skipping YAML migration");
                return;
            }
        }
        catch (Exception ex)
        {
            FLog.warning("Could not check existing player data: " + ex.getMessage());
        }

        FLog.info("Migrating player data from players/*.yml...");

        AtomicInteger migrated = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        for (File file : files)
        {
            String username = file.getName().substring(0, file.getName().length() - ".yml".length());

            try
            {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                PlayerData data = new PlayerData(username);
                data.loadFrom(config);

                if (!data.isValid())
                {
                    FLog.warning("Invalid player data for: " + username);
                    failed.incrementAndGet();
                    continue;
                }

                repo.saveOrUpdate(data);
                migrated.incrementAndGet();
            }
            catch (Exception ex)
            {
                FLog.warning("Failed to migrate player data for " + username + ": " + ex.getMessage());
                failed.incrementAndGet();
            }
        }

        FLog.info("Player data migration complete: " + migrated.get() + " migrated, " + failed.get() + " failed");

        finishMigration(failed.get(), files);
    }

    /**
     * Generate or retrieve UUID for an admin.
     */
    private UUID generateUuidForAdmin(Admin admin)
    {
        // Try to get UUID by name from Mojang/cache
        UUID uuid = FUtil.usernameToUuid(admin.getName());
        if (uuid != null)
        {
            return uuid;
        }

        // Fallback: generate offline-mode UUID
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + admin.getName().toLowerCase()).getBytes());
    }

    /**
     * Retire the imported YAML now that every entry landed in the database.
     * <p>
     * A domain that lost even one entry keeps its file under the original name and aborts, so
     * {@link #runOnce} leaves the version unrecorded and the whole domain is retried on the next
     * start rather than silently stranding the data in a {@code .migrated} file.
     */
    private void finishMigration(final int failed, final File... sources)
    {
        if (failed > 0)
            throw new IllegalStateException(String.format("%d entr%s could not be imported",
                    failed, failed == 1 ? "y" : "ies"));

        Stream.of(sources)
              .forEach(this::backupFile);
    }

    /**
     * Backup a file by renaming it with .migrated extension.
     */
    private void backupFile(File file)
    {
        if (!file.exists())
        {
            return;
        }

        File backupFile = new File(file.getParentFile(), file.getName() + ".migrated");
        
        // If backup already exists, add timestamp
        if (backupFile.exists())
        {
            backupFile = new File(file.getParentFile(), 
                file.getName() + ".migrated." + System.currentTimeMillis());
        }

        if (file.renameTo(backupFile))
        {
            FLog.info("Backed up " + file.getName() + " to " + backupFile.getName());
        }
        else
        {
            FLog.warning("Could not backup " + file.getName() + " - please manually remove or rename it");
        }
    }

    /**
     * Force re-migration of all YAML data.
     * WARNING: This will clear existing database data and re-import from YAML.
     */
    public Mono<Void> forceMigration()
    {
        return Mono.<Void>fromRunnable(() -> {
            FLog.warning("Force migration requested - this will overwrite database data!");

            try
            {
                // Clear existing data
                databaseManager.getAdminRepository().deleteAll().block();
                databaseManager.getBanRepository().deleteAll().block();
                databaseManager.getPermbanRepository().deleteAll().block();
                databaseManager.getStrikeRepository().deleteAll().block();
                databaseManager.getRankRepository().deleteAll().block();
                databaseManager.getProtectedAreaRepository().deleteAll().block();
                databaseManager.getSavedFlagRepository().deleteAll().block();
                databaseManager.getPlayerRepository().deleteAll().block();

                // Forget the ledger, otherwise every import below is skipped as already applied
                databaseManager.getMigrationRepository().clear();
            }
            catch (Exception ex)
            {
                FLog.severe("Failed to clear existing data: " + ex.getMessage());
                return;
            }

            // Restore backup files if they exist
            restoreBackupFiles();

            try
            {
                runAllMigrations();
            }
            catch (SQLException ex)
            {
                FLog.severe(String.format("Force migration failed: %s", ex.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Restore .migrated backup files to their original names.
     */
    private void restoreBackupFiles()
    {
        final File dataFolder = plugin.getDataFolder();

        Stream.of(ADMINS_FILE, BANS_FILE, PERMBANS_FILE, RANKS_FILE, PROTECTED_AREAS_FILE, SAVED_FLAGS_FILE)
              .forEach(name -> restoreBackupFile(new File(dataFolder, name + ".migrated"), new File(dataFolder, name)));
    }

    private void restoreBackupFile(File backup, File original)
    {
        if (backup.exists() && !original.exists())
        {
            if (backup.renameTo(original))
            {
                FLog.info("Restored " + original.getName() + " from backup");
            }
        }
    }
}
