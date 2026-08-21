package me.totalfreedom.totalfreedommod.banning;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.StrikeRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.JsonUtil;
import me.totalfreedom.totalfreedommod.util.FUtil;

import com.google.common.collect.Maps;
import com.google.gson.reflect.TypeToken;

public class StrikeList extends FreedomService
{
    private static final Type STRIKE_MAP_TYPE = new TypeToken<Map<String, StrikeRecord>>() {}.getType();
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    private final Map<String, StrikeRecord> strikes = Maps.newHashMap();
    private final File configFile;
    private final PersistenceQueue writes = new PersistenceQueue("strike");

    private boolean usingSql = false;
    private boolean persistEnabled = true;

    public StrikeList(TotalFreedomMod plugin)
    {
        super(plugin);
        this.configFile = new File(plugin.getDataFolder(), "strikes.json");
    }

    @Override
    protected void onStart()
    {
        strikes.clear();
        usingSql = false;

        final Boolean persist = ConfigEntry.AUTOEJECT_PERSIST.getBoolean();
        persistEnabled = persist == null || persist;

        if (!persistEnabled)
        {
            FLog.info("Strike persistence disabled; running in-memory.");
            return;
        }

        load();
        plugin.dm.whenReady(this::load);
    }

    /**
     * Populate the strike map from SQL where it is available and the JSON snapshot otherwise.
     * Never blocks: the SQL read runs off-thread and is applied back on the main thread.
     */
    public void load()
    {
        if (!persistEnabled)
            return;

        if (plugin.dm != null && plugin.dm.isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJson();
        finishLoad();
    }

    @Override
    protected void onStop()
    {
        if (!persistEnabled) 
            return;

        awaitPendingWrites(SHUTDOWN_FLUSH_TIMEOUT_MS);

        if (!usingSql) 
            saveToJson();
        
    }

    /**
     * Wait for queued async writes to land, up to {@code timeoutMs}.
     */
    public void awaitPendingWrites(long timeoutMs)
    {
        writes.await(timeoutMs);
    }

    private void enqueue(Mono<Void> work)
    {
        writes.enqueue(work);
    }

    private Mono<Void> writeJsonAsync()
    {
        return Mono.<Void>fromRunnable(this::saveToJson)
                   .subscribeOn(Schedulers.boundedElastic());
    }

    private void loadFromSqlAsync()
    {
        final StrikeRepository repo = plugin.dm.getStrikeRepository();
        plugin.dm.readAsync("StrikeList/loadFromSql", repo.loadAllAsync(),
                loaded -> applyLoadedStrikes(repo, loaded),
                () ->
                {
                    loadFromJson();
                    finishLoad();
                });
    }

    private void applyLoadedStrikes(final StrikeRepository repo, final Map<String, StrikeRecord> loaded)
    {
        strikes.clear();
        strikes.putAll(loaded);
        usingSql = true;

        reconcileFromJsonIfNewer(repo);
        finishLoad();
    }

    private void finishLoad()
    {
        pruneDecayed();
        FLog.info(String.format("Loaded %d strike records.", strikes.size()));
    }

    /**
     * If strikes.json was written more recently than the database's last update, re-import it
     * into SQL. The comparison and the re-import both ride the write queue off the main thread.
     */
    private void reconcileFromJsonIfNewer(final StrikeRepository repo)
    {
        if (!configFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            enqueue(writeJsonAsync());
            return;
        }

        final Map<String, StrikeRecord> jsonStrikes;
        try
        {
            jsonStrikes = readJsonStrikes();
        }
        catch (IOException ex)
        {
            FLog.warning(String.format("Failed to read strikes.json: %s", ex.getMessage()));
            return;
        }

        if (jsonStrikes.isEmpty())
        {
            enqueue(writeJsonAsync());
            return;
        }

        final long fileModified = configFile.lastModified();

        enqueue(Mono.fromCallable(() ->
                    {
                        final Long sqlUpdatedAt = repo.getMaxUpdatedAt();
                        return FUtil.isSnapshotNewer(fileModified, sqlUpdatedAt);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .filter(Boolean::booleanValue)
                    .flatMap(ignored ->
                    {
                        FLog.info(String.format("strikes.json is newer than the database; rebuilding it from the file's %d strike record(s).",
                                                jsonStrikes.size()));
                        return repo.loadAllAsync()
                                   .flatMapMany(existing -> Flux.fromIterable(jsonStrikes.values())
                                                                .concatMap(repo::upsertAsync)
                                                                .thenMany(Flux.fromIterable(existing.keySet())
                                                                              .filter(ip -> !jsonStrikes.containsKey(ip))
                                                                              .concatMap(repo::deleteByIpAsync)))
                                   .then(Mono.<Void>fromRunnable(() -> plugin.dm.sync("StrikeList/applyReconciled",
                                                                 () ->
                                                                 {
                                                                    strikes.clear();
                                                                    strikes.putAll(jsonStrikes);
                                                                 })));
                    })
                    .onErrorResume(ex ->
                    {
                        FLog.warning(String.format("Failed to reconcile strikes.json into the database: %s",
                                                   ex.getMessage()));
                        return Mono.empty();
                    })
                    .then());
    }

    private Map<String, StrikeRecord> readJsonStrikes() throws IOException
    {
        try (FileReader reader = new FileReader(configFile))
        {
            Map<String, StrikeRecord> loaded = JsonUtil.GSON.fromJson(reader, STRIKE_MAP_TYPE);
            return loaded != null ? loaded : Maps.newHashMap();
        }
    }

    private void loadFromJson()
    {
        if (!configFile.exists())
        {
            try
            {
                configFile.getParentFile().mkdirs();
                configFile.createNewFile();
            }
            catch (IOException ex)
            {
                FLog.severe("Could not create strikes.json");
            }
        }

        try
        {
            strikes.putAll(readJsonStrikes());
        }
        catch (IOException ex)
        {
            FLog.severe("Could not read strikes.json: " + ex.getMessage());
        }
        usingSql = false;
    }

    private void pruneDecayed()
    {
        final int decayHours = decayHours();
        if (decayHours <= 0)
            return;

        final List<String> prunedIps = new ArrayList<>();
        for (Iterator<Map.Entry<String, StrikeRecord>> it = strikes.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<String, StrikeRecord> e = it.next();
            if (e.getValue().effectiveCount(decayHours) == 0)
            {
                it.remove();
                prunedIps.add(e.getKey());
            }
        }

        if (prunedIps.isEmpty())
            return;

        if (usingSql && plugin.dm != null && plugin.dm.isInitialized())
        {
            final StrikeRepository repo = plugin.dm.getStrikeRepository();
            enqueue(Flux.fromIterable(prunedIps)
                        .concatMap(ip -> repo.deleteByIpAsync(ip)
                                             .onErrorResume(ex ->
                                             {
                                                 FLog.warning(String.format(
                                                                            "Failed to prune decayed strike for %s: %s", 
                                                                            ip, 
                                                                            ex.getMessage()
                                                                            )
                                                                );
                                                 return Mono.<Boolean>empty();
                                             })
                                    )
                        .then(writeJsonAsync()));
        }
        else
        {
            enqueue(writeJsonAsync());
        }

        FLog.info("Pruned " + prunedIps.size() + " decayed strike record(s).");
    }

    private int decayHours()
    {
        final Integer h = ConfigEntry.AUTOEJECT_TIME_WINDOW.getInteger();
        return h == null ? 0 : h;
    }

    public synchronized int recordStrikeAndGet(String ip, String username)
    {
        StrikeRecord r = strikes.get(ip);
        final int decay = decayHours();
        int base = 0;
        if (r != null)
            base = r.effectiveCount(decay);
        
        if (r == null)
        {
            r = new StrikeRecord(ip);
            strikes.put(ip, r);
        }

        r.setCount(base + 1);
        r.setLastStrikeUnix(System.currentTimeMillis() / 1000L);
        if (username != null)
            r.setLastUsername(username);

        if (persistEnabled)
            persist(r);

        return r.getCount();
    }

    public synchronized int peek(String ip)
    {
        StrikeRecord r = strikes.get(ip);
        if (r == null)
            return 0;

        return r.effectiveCount(decayHours());
    }

    public synchronized boolean clear(String ip)
    {
        StrikeRecord removed = strikes.remove(ip);
        if (removed == null)
            return false;

        if (!persistEnabled)
            return true;

        if (usingSql && plugin.dm != null && plugin.dm.isInitialized())
        {
            final StrikeRepository repo = plugin.dm.getStrikeRepository();
            enqueue(repo.deleteByIpAsync(ip)
                        .onErrorResume(ex ->
                        {
                            FLog.warning("Failed to clear strike from SQL: " + ex.getMessage());
                            return Mono.<Boolean>empty();
                        })
                        .then(writeJsonAsync()));
        }
        else enqueue(writeJsonAsync()); // else on same line maybe??? that is nice tbh

        return true;
    }

    public synchronized Map<String, StrikeRecord> snapshot()
    {
        return Collections.unmodifiableMap(new HashMap<>(strikes));
    }

    private void persist(StrikeRecord r)
    {
        if (usingSql && plugin.dm != null && plugin.dm.isInitialized())
        {
            final StrikeRepository repo = plugin.dm.getStrikeRepository();
            enqueue(repo.upsertAsync(r)
                        .onErrorResume(ex ->
                        {
                            FLog.warning("Failed to persist strike to SQL: " + ex.getMessage());
                            return Mono.empty();
                        })
                        .then(writeJsonAsync()));
        }
        else enqueue(writeJsonAsync());
    }

    private synchronized void saveToJson()
    {
        try (FileWriter writer = new FileWriter(configFile))
        {
            JsonUtil.GSON.toJson(strikes, STRIKE_MAP_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save strikes.json");
        }
    }
}
