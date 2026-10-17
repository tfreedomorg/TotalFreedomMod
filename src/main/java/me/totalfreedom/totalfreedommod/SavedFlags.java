package me.totalfreedom.totalfreedommod;

import java.io.*;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.SavedFlagRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.JsonUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.gson.reflect.TypeToken;

public class SavedFlags extends FreedomService
{

    public static final String DATA_FILENAME = "savedflags.json";
    public static final String LEGACY_YAML_FILENAME = "savedflags.yml";
    public static final String LEGACY_DATA_FILENAME = "savedflags.dat";

    private static final Type FLAGS_MAP_TYPE = new TypeToken<Map<String, Boolean>>() {}.getType();
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    private final PersistenceQueue writes = new PersistenceQueue("saved flag");

    /**
     * Authoritative in-memory view. Kept current so reads never touch JDBC on the calling thread.
     */
    private final Map<String, Boolean> flags = new HashMap<>();

    private boolean usingSql = false;

    public SavedFlags(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    @Override
    protected void onStart()
    {
        final File dataFile = new File(plugin.getDataFolder(), DATA_FILENAME);
        final File legacyFile = new File(plugin.getDataFolder(), LEGACY_DATA_FILENAME);

        if (legacyFile.exists() && !dataFile.exists())
        {
            migrateLegacyData(legacyFile, dataFile);
        }

        flags.putAll(readJsonFlags(dataFile));
        plugin.dm.whenReady(this::load);
    }

    @Override
    protected void onStop()
    {
        writes.await(SHUTDOWN_FLUSH_TIMEOUT_MS);
    }

    /**
     * Refresh the in-memory view from SQL, then reconcile the JSON snapshot back into it if the
     * file is the newer of the two. Never blocks.
     */
    public void load()
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
            return;

        final SavedFlagRepository repo = plugin.dm.getSavedFlagRepository();
        plugin.dm.readAsync("SavedFlags/loadFromSql", repo.loadAllAsync(),
                loaded ->
                {
                    usingSql = true;
                    flags.clear();
                    flags.putAll(loaded);
                    reconcileFromJsonIfNewer(repo);
                },
                () -> usingSql = false);
    }

    @SuppressWarnings("unchecked")
    private void migrateLegacyData(File legacyFile, File dataFile)
    {
        FLog.info("Migrating saved flags from legacy .dat format...");
        try (FileInputStream fis = new FileInputStream(legacyFile);
             ObjectInputStream ois = new ObjectInputStream(fis))
        {
            final HashMap<String, Boolean> legacyFlags = (HashMap<String, Boolean>) ois.readObject();

            // Writing the snapshot is enough: the database is still coming up, and the
            // reconcile pass on ready folds this file into SQL because it is the newer of the two.
            saveToJson(legacyFlags);

            File oldFile = new File(legacyFile.getParent(), LEGACY_DATA_FILENAME + ".old");
            if (legacyFile.renameTo(oldFile))
            {
                FLog.info("Migration complete. Legacy file renamed to " + LEGACY_DATA_FILENAME + ".old");
            }
            else
            {
                FLog.warning("Migration complete but could not rename legacy file.");
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to migrate legacy saved flags data: " + ex.getMessage());
            FLog.severe(ex);
        }
    }

    /**
     * Reads the pre-JSON {@code savedflags.yml} format. Retained only for the one-time
     * legacy-install migration path (not called during normal startup).
     */
    private Map<String, Boolean> loadLegacyYaml(File file)
    {
        Map<String, Boolean> flags = new HashMap<>();
        if (!file.exists())
        {
            return flags;
        }

        try
        {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection flagsSection = config.getConfigurationSection("flags");

            if (flagsSection != null)
            {
                for (String key : flagsSection.getKeys(false))
                {
                    flags.put(key, flagsSection.getBoolean(key));
                }
            }
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to load legacy saved flags: " + ex.getMessage());
            FLog.severe(ex);
        }

        return flags;
    }

    /**
     * If savedflags.json was written more recently than the database's last update, re-import it
     * into SQL. The comparison and the re-import both ride the write queue off the main thread.
     */
    private void reconcileFromJsonIfNewer(final SavedFlagRepository repo)
    {
        final File dataFile = new File(plugin.getDataFolder(), DATA_FILENAME);
        if (!dataFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            writes.enqueue(writeJsonAsync());
            return;
        }

        final Map<String, Boolean> jsonFlags = readJsonFlags(dataFile);
        if (jsonFlags.isEmpty())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        final long fileModified = dataFile.lastModified();

        writes.enqueue(Mono.fromCallable(() ->
              {
                  final Long sqlUpdatedAt = repo.getMaxUpdatedAt();
                  return FUtil.isSnapshotNewer(fileModified, sqlUpdatedAt);
              })
              .subscribeOn(Schedulers.boundedElastic())
              .filter(Boolean::booleanValue)
              .flatMap(ignored ->
              {
                FLog.info(String.format("%s is newer than the database; rebuilding it from the file's %d flag(s).",
                                        DATA_FILENAME, jsonFlags.size()));
                return repo.loadAllAsync()
                           .flatMapMany(existing -> Flux.fromIterable(jsonFlags.entrySet())
                                                        .concatMap(entry -> repo.upsertAsync(entry.getKey(), entry.getValue()))
                                                        .thenMany(Flux.fromIterable(existing.keySet())
                                                        .filter(flag -> !jsonFlags.containsKey(flag))
                                                        .concatMap(repo::deleteAsync)))
                           .then(Mono.<Void>fromRunnable(() -> plugin.dm.sync("SavedFlags/applyReconciled",
                                                         () -> 
                                                         {
                                                            flags.clear();
                                                            flags.putAll(jsonFlags);
                                                         })));
              })
              .onErrorResume(ex ->
              {
                  FLog.warning(String.format("Failed to reconcile %s into the database: %s",
                                             DATA_FILENAME, ex.getMessage()));
                  return Mono.empty();
              })
              .then());
    }

    private Map<String, Boolean> readJsonFlags(File file)
    {
        Map<String, Boolean> flags = new HashMap<>();
        if (!file.exists())
            return flags;

        try (FileReader reader = new FileReader(file))
        {
            Map<String, Boolean> loaded = JsonUtil.GSON.fromJson(reader, FLAGS_MAP_TYPE);
            if (loaded != null)
                flags.putAll(loaded);
        }
        catch (Exception ex)
        {
            FLog.severe("Failed to load saved flags: " + ex.getMessage());
            FLog.severe(ex);
        }

        return flags;
    }

    private void saveToJson(final Map<String, Boolean> snapshot)
    {
        final File file = new File(plugin.getDataFolder(), DATA_FILENAME);
        try (FileWriter writer = new FileWriter(file))
        {
            JsonUtil.GSON.toJson(snapshot, FLAGS_MAP_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.severe(String.format("Failed to save saved flags: %s", ex.getMessage()));
            FLog.severe(ex);
        }
    }

    private Mono<Void> writeJsonAsync()
    {
        final Map<String, Boolean> snapshot = new HashMap<>(flags);
        return Mono.<Void>fromRunnable(() -> saveToJson(snapshot))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * The current flags, served from memory. Never touches JDBC on the calling thread.
     */
    public Map<String, Boolean> getSavedFlags()
    {
        return Collections.unmodifiableMap(flags);
    }

    public boolean getSavedFlag(String flag) throws Exception
    {
        final Boolean flagValue = flags.get(flag);
        if (flagValue == null)
            throw new Exception();

        return flagValue;
    }

    /**
     * Set a flag in memory and queue the SQL write plus a refresh of the JSON snapshot. Safe
     * from a command handler: the round trip runs off the main thread.
     */
    public void setSavedFlag(String flag, boolean value)
    {
        flags.put(flag, value);

        if (!usingSql || plugin.dm == null || !plugin.dm.isInitialized())
        {
            writes.enqueue(writeJsonAsync());
            return;
        }

        writes.enqueue(plugin.dm.getSavedFlagRepository().upsertAsync(flag, value)
              .onErrorResume(ex ->
              {
                  FLog.severe(String.format("Could not save flag '%s' to SQL: %s", flag, ex.getMessage()));
                  return Mono.empty();
              })
              .then(writeJsonAsync()));
    }

}
