package me.totalfreedom.totalfreedommod.banning;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.PermbanRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.reflect.TypeToken;

public class PermbanList extends FreedomService
{

    public static final String CONFIG_FILENAME = "permbans.json";
    private static final Type PERMBAN_MAP_TYPE = new TypeToken<Map<String, PermBan>>() {}.getType();
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    private final Set<String> permbannedNames = Sets.newHashSet();
    private final Set<String> permbannedIps = Sets.newHashSet();
    private final Map<String, PermBan> permbansByName = Maps.newHashMap();
    private final File configFile;
    private final Object lock = new Object();
    private final PersistenceQueue writes = new PersistenceQueue("permban");

    private boolean usingSql = false;

    public PermbanList(TotalFreedomMod plugin)
    {
        super(plugin);
        this.configFile = new File(plugin.getDataFolder(), CONFIG_FILENAME);
    }

    @Override
    protected void onStart()
    {
        load();
        plugin.dm.whenReady(this::load);
    }

    /**
     * Populate the permban list from SQL where it is available and the JSON snapshot otherwise.
     * Never blocks: the SQL read runs off-thread and is applied back on the main thread.
     */
    public void load()
    {
        if (plugin.dm != null && plugin.dm.isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJson();
    }

    private void loadFromSqlAsync()
    {
        final PermbanRepository repo = plugin.dm.getPermbanRepository();
        plugin.dm.readAsync("PermbanList/loadFromSql", repo.findAll(),
                loaded -> applyLoadedPermbans(repo, loaded),
                this::loadFromJson);
    }

    private void applyLoadedPermbans(final PermbanRepository repo, final List<PermBan> loaded)
    {
        synchronized (lock)
        {
            replaceViews(loaded);
            usingSql = true;
            FLog.info(String.format("Loaded %d perm IP bans and %d perm username bans from SQL database.",
                    permbannedIps.size(), permbannedNames.size()));
        }

        reconcileFromJsonIfNewer(repo);
    }

    /**
     * If permbans.json was written more recently than the database's last update, re-import it
     * into SQL. The comparison and the re-import both ride the write queue off the main thread.
     */
    private void reconcileFromJsonIfNewer(final PermbanRepository repo)
    {
        if (!configFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            enqueue(writeJsonAsync());
            return;
        }

        final Map<String, PermBan> jsonPermbans;
        try
        {
            jsonPermbans = readJsonPermbans();
        }
        catch (IOException ex)
        {
            FLog.warning(String.format("Failed to read %s: %s", CONFIG_FILENAME, ex.getMessage()));
            return;
        }

        if (jsonPermbans.isEmpty())
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
                        FLog.info(String.format("%s is newer than the database; rebuilding it from the file's %d permban(s).",
                                                CONFIG_FILENAME, jsonPermbans.size()));
                        final Set<UUID> keepUuids = jsonPermbans.values().stream()
                                                                .map(PermBan::getUuid)
                                                                .filter(Objects::nonNull)
                                                                .collect(Collectors.toSet());
                        final Set<String> keepIps = jsonPermbans.values().stream()
                                                                .flatMap(permban -> permban.getIps().stream())
                                                                .collect(Collectors.toSet());
                        return Flux.fromIterable(jsonPermbans.values())
                                   .concatMap(repo::save)
                                   .then(repo.loadAllAsync())
                                   .flatMapMany(Flux::fromIterable)
                                   .filter(existing -> !isKept(existing, keepUuids, keepIps))
                                   .concatMap(stale -> stale.getUuid() != null
                                                       ? repo.deleteAsync(stale.getUuid())
                                                       : repo.deleteByIpAsync(stale.getIps().get(0)))
                                   .then(Mono.<Void>fromRunnable(() -> plugin.dm.sync("PermbanList/applyReconciled",
                                                                 () -> applyReconciledPermbans(jsonPermbans.values()))));
                    })
                    .onErrorResume(ex ->
                    {
                        FLog.warning(String.format("Failed to reconcile %s into the database: %s",
                                                   CONFIG_FILENAME, ex.getMessage()));
                        return Mono.empty();
                    })
                    .then());
    }

    /**
    * Whether a row in the database is still described by the snapshot. A permban with
    * no uuid is an IP permban, so it is matched on its addresses instead. A row with
    * neither is left alone, since there is no key to remove it safely.
    */
    private static boolean isKept(final PermBan existing, final Set<UUID> keepUuids, final Set<String> keepIps)
    {
        if (existing.getUuid() != null)
            return keepUuids.contains(existing.getUuid());

        if (existing.getIps().isEmpty())
            return true;

        return existing.getIps().stream().anyMatch(keepIps::contains);
    }

    private void applyReconciledPermbans(final Collection<PermBan> jsonPermbans)
    {
        synchronized (lock)
        {
            replaceViews(jsonPermbans);
        }
    }

    /**
     * Rebuild the name/IP lookup views from {@code source}. Callers hold {@link #lock}.
     */
    private void replaceViews(final Collection<PermBan> source)
    {
        permbannedNames.clear();
        permbannedIps.clear();
        permbansByName.clear();

        source.forEach(permban ->
        {
            final String name = permban.hasUsername() ? permban.getUsername().toLowerCase().trim()
                                                      : null;
            if (name != null)
            {
                permbannedNames.add(name);
                permbansByName.put(name, permban);
            }
            permbannedIps.addAll(permban.getIps());
        });
    }

    private Map<String, PermBan> readJsonPermbans() throws IOException
    {
        try (FileReader reader = new FileReader(configFile))
        {
            Map<String, PermBan> loaded = JsonUtil.GSON.fromJson(reader, PERMBAN_MAP_TYPE);
            return loaded != null ? loaded : Maps.newHashMap();
        }
    }

    /**
     * Load permbans from the JSON file (fallback).
     */
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
                FLog.severe("Could not create " + CONFIG_FILENAME);
            }
        }

        synchronized (lock)
        {
            permbannedNames.clear();
            permbannedIps.clear();
            permbansByName.clear();

            try
            {
                for (Map.Entry<String, PermBan> entry : readJsonPermbans().entrySet())
                {
                    PermBan permban = entry.getValue();
                    permbannedNames.add(entry.getKey());
                    permbannedIps.addAll(permban.getIps());
                    permbansByName.put(entry.getKey(), permban);
                }
            }
            catch (IOException ex)
            {
                FLog.severe("Could not read " + CONFIG_FILENAME + ": " + ex.getMessage());
            }

            usingSql = false;
            FLog.info("Loaded " + permbannedIps.size() + " perm IP bans and " + permbannedNames.size() + " perm username bans from JSON.");
        }
    }

    private void saveToJson()
    {
        final Map<String, PermBan> snapshot;
        synchronized (lock)
        {
            snapshot = Maps.newHashMap(permbansByName);
        }

        try (FileWriter writer = new FileWriter(configFile))
        {
            JsonUtil.GSON.toJson(snapshot, PERMBAN_MAP_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save " + CONFIG_FILENAME);
        }
    }

    @Override
    protected void onStop()
    {
        awaitPendingWrites(SHUTDOWN_FLUSH_TIMEOUT_MS);

        if (usingSql)
            saveAllToSql();
        else
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

    /**
     * Blocking write of every permban record to SQL. Startup/shutdown only.
     */
    private void saveAllToSql()
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
            return;

        final List<PermBan> snapshot;
        synchronized (lock)
        {
            snapshot = new ArrayList<>(permbansByName.values());
        }

        try
        {
            PermbanRepository repo = plugin.dm.getPermbanRepository();
            for (PermBan permban : snapshot)
                repo.save(permban).block();

            FLog.debug("Saved " + snapshot.size() + " permbans to SQL database");
            saveToJson();
        }
        catch (Exception ex)
        {
            FLog.warning("Failed to save permbans to SQL: " + ex.getMessage());
        }
    }

    /**
     * Flush pending changes and re-read the list. Safe from a command handler: the flush is
     * queued rather than awaited, and {@link #load()} applies its result asynchronously.
     */
    public void reload()
    {
        saveAsync();
        load();
    }

    /**
     * Queue a write of every permban record, followed by a refresh of the JSON snapshot.
     */
    public void saveAsync()
    {
        if (!usingSql || plugin.dm == null || !plugin.dm.isInitialized())
        {
            enqueue(writeJsonAsync());
            return;
        }

        final List<PermBan> snapshot;
        synchronized (lock)
        {
            snapshot = new ArrayList<>(permbansByName.values());
        }

        final PermbanRepository repo = plugin.dm.getPermbanRepository();
        enqueue(Flux.fromIterable(snapshot)
                .concatMap(permban -> repo.save(permban)
                        .onErrorResume(ex ->
                        {
                            FLog.warning(String.format("Failed to save permban %s to SQL: %s",
                                    permban.getUsername(), ex.getMessage()));
                            return Mono.empty();
                        }))
                .then(writeJsonAsync()));
    }


    /**
     * Add a permban.
     */
    public void addPermban(PermBan permban)
    {
        final boolean sql;
        synchronized (lock)
        {
            String name = permban.getUsername().toLowerCase().trim();
            permbannedNames.add(name);
            permbannedIps.addAll(permban.getIps());
            permbansByName.put(name, permban);
            sql = usingSql;
        }

        if (sql)
            savePermbanToSqlAsync(permban);
        else
            saveToJson();
    }

    /**
     * Remove a permban by username.
     */
    public boolean removePermban(String username)
    {
        final String name = username.toLowerCase().trim();
        final PermBan permban;
        final boolean sql;
        synchronized (lock)
        {
            permban = permbansByName.remove(name);
            if (permban == null)
                return false;

            permbannedNames.remove(name);
            permbannedIps.removeAll(permban.getIps());
            sql = usingSql;
        }

        if (sql)
            removePermbanFromSqlAsync(name);
        else
            saveToJson();

        return true;
    }

    /**
     * Remove every permban that has an IP matching the given address or range.
     */
    public List<String> removePermbansByIp(String ip)
    {
        final List<String> removedNames = new ArrayList<>();
        final boolean sql;
        synchronized (lock)
        {
            for (PermBan permban : new ArrayList<>(permbansByName.values()))
            {
                boolean matches = false;
                for (String banIp : permban.getIps())
                {
                    if (FUtil.fuzzyIpMatch(ip, banIp, 4))
                    {
                        matches = true;
                        break;
                    }
                }

                if (!matches)
                    continue;

                final String name = permban.getUsername().toLowerCase().trim();
                permbansByName.remove(name);
                permbannedNames.remove(name);
                removedNames.add(permban.getUsername());
            }

            // Rebuild the IP view so IPs shared with surviving permbans are retained.
            permbannedIps.clear();
            for (PermBan permban : permbansByName.values())
                permbannedIps.addAll(permban.getIps());

            sql = usingSql;
        }

        if (sql)
            for (String name : removedNames)
                removePermbanFromSqlAsync(name.toLowerCase().trim()); // why does this look so nice without brackets lmao

        else if (!removedNames.isEmpty())
            saveToJson();

        return removedNames;
    }

    /**
     * Get a permban by username.
     */
    public PermBan getPermban(String username)
    {
        synchronized (lock)
        {
            return permbansByName.get(username.toLowerCase().trim());
        }
    }

    /**
     * Returns a snapshot of the permbanned usernames.
     */
    public Set<String> getPermbannedNames()
    {
        synchronized (lock)
        {
            return new HashSet<>(permbannedNames);
        }
    }

    /**
     * Returns a snapshot of the permbanned IPs.
     */
    public Set<String> getPermbannedIps()
    {
        synchronized (lock)
        {
            return new HashSet<>(permbannedIps);
        }
    }

    /**
     * Queue a single permban for an off-thread SQL write, followed by a refresh of the JSON
     * snapshot. Safe to call from commands and event handlers.
     */
    private void savePermbanToSqlAsync(PermBan permban)
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning("SQL not available; permban change was not saved");
            return;
        }

        final PermbanRepository repo = plugin.dm.getPermbanRepository();

        enqueue(repo.save(permban)
                .onErrorResume(ex ->
                {
                    FLog.warning("Failed to save permban to SQL: " + ex.getMessage());
                    return Mono.<Integer>empty();
                })
                .then(writeJsonAsync()));
    }

    /**
     * Queue a single permban removal for an off-thread SQL write, followed by a refresh of
     * the JSON snapshot. Safe to call from commands and event handlers.
     */
    private void removePermbanFromSqlAsync(String name)
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning("SQL not available; permban removal was not saved");
            return;
        }

        final PermbanRepository repo = plugin.dm.getPermbanRepository();

        enqueue(Mono.fromCallable(() -> repo.deleteByUsername(name))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex ->
                {
                    FLog.warning("Failed to remove permban from SQL: " + ex.getMessage());
                    return Mono.<Boolean>empty();
                })
                .then(writeJsonAsync()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event)
    {
        final String username = event.getName();
        final String ip = event.getAddress().getHostAddress().trim();

        // Permbanned IPs
        for (String testIp : getPermbannedIps())
        {
            if (FUtil.fuzzyIpMatch(testIp, ip, 4))
            {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Your IP address is permanently banned from this server.\n"
                                + "Release procedures are available at\n", NamedTextColor.RED)
                                .append(Component.text(ConfigEntry.SERVER_PERMBAN_URL.getString(), NamedTextColor.GOLD)));
                return;
            }
        }

        // Permbanned usernames
        for (String testPlayer : getPermbannedNames())
        {
            if (testPlayer.equalsIgnoreCase(username))
            {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Your username is permanently banned from this server.\n"
                                + "Release procedures are available at\n", NamedTextColor.RED)
                                .append(Component.text(ConfigEntry.SERVER_PERMBAN_URL.getString(), NamedTextColor.GOLD)));
                return;
            }
        }

    }

}
