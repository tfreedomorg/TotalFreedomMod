package me.totalfreedom.totalfreedommod.admin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.rank.CustomRank;
import me.totalfreedom.totalfreedommod.rank.RankRole;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.AdminRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

import com.google.common.base.Function;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.reflect.TypeToken;

public class AdminList extends FreedomService
{

    public static final String CONFIG_FILENAME = "admins.json";

    /**
     * The node that marks a rank as senior. Senior standing is a capability granted by
     * {@code ranks.json} rather than a fixed tier, so a defined rank can hold it and a
     * rename or re-tier of the shipped ranks does not strand this check.
     */
    public static final String SENIOR_STATUS_NODE = "tfm.admin.senior.status";

    private static final Type ADMIN_MAP_TYPE = new TypeToken<Map<String, Admin>>() {}.getType();

    private static final long LAST_LOGIN_DEBOUNCE_MS = 5L * 60L * 1000L;

    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    private final Map<String, Admin> allAdmins = Maps.newHashMap(); // Includes disabled admins
    // Only active admins below
    private final Set<Admin> activeAdmins = Sets.newHashSet();

    // UUID-based lookup table
    private final Map<UUID, Admin> uuidTable = Maps.newHashMap();
    private final Map<String, Admin> nameTable = Maps.newHashMap();
    private final Map<String, Admin> ipTable = Maps.newHashMap();
    private final Set<Player> onlineAdminPlayers = Sets.newHashSet();
    //
    private final File configFile;

    // Serialises every queued write so a stale snapshot can never land after a newer one.
    private final PersistenceQueue writes = new PersistenceQueue("admin");

    // Flag to track if SQL is available
    private boolean usingSql = false;

    public AdminList(TotalFreedomMod plugin)
    {
        super(plugin);

        this.configFile = new File(plugin.getDataFolder(), CONFIG_FILENAME);
    }

    @Override
    protected void onStart()
    {
        load();
        plugin.dm.whenReady(this::load);

        server.getServicesManager().register(Function.class, new Function<Player, Boolean>()
        {
            @Override
            public Boolean apply(Player player)
            {
                return isAdmin(player);
            }
        }, plugin, ServicePriority.Normal);

        deactivateOldEntries(false);
    }

    @Override
    protected void onStop()
    {
        // Let the queue drain first, then flush everything. Ordering matters:
        // a queued write landing after the flush would restore a stale snapshot.
        awaitPendingWrites(SHUTDOWN_FLUSH_TIMEOUT_MS);
        save();
    }

    /**
     * Populate the list from SQL where it is available and the JSON snapshot otherwise. Never
     * blocks: the SQL read runs off-thread and its result is applied back on the main thread,
     * so this is safe from a command handler as well as from startup.
     */
    public void load()
    {
        if (plugin.dm != null && plugin.dm.isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJson();
        backfillUuidsIfEnabled();
    }

    /**
     * Blocking write of every admin record. This is the shutdown flush which is
     * called from {@link #onStop()} so nothing is lost when the server stops.
     * <p>
     * Do <b>not</b> call this from a command or event handler: under SQL it is a
     * serial round-trip per admin on the calling thread, which stalls the main
     * thread for as long as the whole list takes to write. Single-entry changes
     * belong on {@link #saveAdminAsync(Admin)}.
     */
    public synchronized void save()
    {
        if (usingSql)
        {
            saveToSql();
        }
        else
        {
            saveToJson();
        }
    }

    /**
     * Wait for queued {@link #saveAdminAsync(Admin)} writes to land, up to {@code timeoutMs}.
     */
    public void awaitPendingWrites(long timeoutMs)
    {
        writes.await(timeoutMs);
    }

    /**
     * Persist <i>every</i> admin record off the main thread. Under SQL the whole
     * list goes out as one queued batch, written serially so the connection pool
     * is not swamped.
     * <p>
     * Only call this when the whole list is genuinely dirty. If only a single entry
     * changed, call {@link #saveAdminAsync(Admin)} instead, which queues just that row.
     */
    public void saveAsync()
    {
        if (usingSql)
        {
            queueSqlWrites(allAdmins.values()
                    .stream()
                    .map(this::pendingWrite)
                    .toList());
            return;
        }

        // Render on this thread while we still own the maps, then hand the
        // finished text to the worker. Serialising inside the async task would
        // read allAdmins off-thread while the main thread is free to mutate it.
        final String json = serialiseAdmins();

        if (!plugin.isEnabled())
        {
            writeJson(json);
            return;
        }

        enqueue(writeJsonAsync(json));
    }

    /**
     * Queue a single admin row for an off-thread write, followed by a refresh of
     * the JSON snapshot. Safe to call from commands and event handlers.
     */
    public void saveAdminAsync(Admin admin)
    {
        if (admin == null)
        {
            return;
        }

        if (!usingSql)
        {
            saveAsync();
            return;
        }

        queueSqlWrites(List.of(pendingWrite(admin)));
    }

    public synchronized boolean isAdminSync(CommandSender sender)
    {
        return isAdmin(sender);
    }

    public boolean isAdmin(CommandSender sender)
    {
        if (!(sender instanceof Player))
        {
            return true;
        }

        Admin admin = getAdmin((Player) sender);

        return admin != null && admin.isActive();
    }

    /**
     * Whether {@code sender} counts as a senior admin.
     * <p>
     * Asked as a capability rather than as a rank comparison, because no rank is named in code any
     * more: whichever ranks {@code ranks.json} grants {@link #SENIOR_STATUS_NODE} to are the senior
     * ones, including any custom definitions.
     */
    public boolean isSeniorAdmin(CommandSender sender)
    {
        return isAdmin(sender) && plugin.rm.hasPermission(sender, SENIOR_STATUS_NODE);
    }

    /**
     * The same test applied to a stored profile rather than to a live sender, for the cleanup pass
     * that runs over admins who are not online to be asked.
     */
    public boolean grantsSeniorStatus(Admin admin)
    {
        return plugin.rm.getRegistry()
                        .byId(admin.getRankId())
                        .map(rank -> plugin.rm.getRegistry().satisfies(rank, SENIOR_STATUS_NODE))
                        .orElse(false);
    }

    public Admin getAdmin(CommandSender sender)
    {
        if (sender instanceof Player player) // this instead of two separate methods.
        {
            if (ConfigEntry.ADMINLIST_USE_UUID_ONLY.getBoolean())
            {
                Admin uuidAdmin = uuidTable.get(player.getUniqueId());
                if (uuidAdmin == null || !uuidAdmin.isActive())
                {
                    return null;
                }
                // Rewrite the stored display name if Mojang has changed it.
                if (!uuidAdmin.getName().equalsIgnoreCase(player.getName()))
                {
                    final String oldKey = uuidAdmin.getName().toLowerCase();
                    uuidAdmin.setName(player.getName());
                    final String newKey = uuidAdmin.getName().toLowerCase();
                    if (!oldKey.equals(newKey))
                    {
                        nameTable.remove(oldKey);
                        nameTable.put(newKey, uuidAdmin);
                    }
                    saveAdminAsync(uuidAdmin);
                }
                return uuidAdmin;
            }

            // Find admin
            final String ip = player.getAddress().getAddress().getHostAddress();
            Admin admin = getEntryByName(player.getName());

            // Admin by name
            if (admin != null)
            {
                // Check if we're in online mode,
                // Or the players IP is in the admin entry
                if (Bukkit.getOnlineMode() || admin.getIps().contains(ip))
                {
                    if (!admin.getIps().contains(ip))
                    {
                        // Add the new IP if we have to
                        admin.addIp(ip);
                        ipTable.put(ip, admin);
                        saveAdminAsync(admin);
                    }
                    return admin;
                }

                // Impostor: the name is ours but the IP is not. Fall through to
                // the IP lookup, which will not match this entry.
            }

            // Admin by ip
            admin = getEntryByIp(ip);
            if (admin != null)
            {
                // Set the new username
                final String oldKey = admin.getName().toLowerCase();
                admin.setName(player.getName());
                final String newKey = admin.getName().toLowerCase();
                if (!oldKey.equals(newKey))
                {
                    nameTable.remove(oldKey);
                    if (admin.isActive())
                    {
                        nameTable.put(newKey, admin);
                    }
                }
                saveAdminAsync(admin);
            }

            return admin;
        }

        return getEntryByName(sender.getName());
    }

    public Admin getEntryByName(String name)
    {
        return nameTable.get(name.toLowerCase());
    }

    public Admin getEntryByIp(String ip)
    {
        return ipTable.get(ip);
    }

    public Admin getEntryByIpFuzzy(String needleIp)
    {
        final Admin directAdmin = getEntryByIp(needleIp);
        if (directAdmin != null)
        {
            return directAdmin;
        }

        return ipTable.entrySet()
                .stream()
                .filter(entry -> FUtil.fuzzyIpMatch(needleIp, entry.getKey(), 3))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public void updateLastLogin(Player player)
    {
        final Admin admin = getAdmin(player);
        if (admin == null)
        {
            return;
        }

        final Date now = new Date();
        final Date then = admin.getLastLogin();
        final boolean debounce = then != null
                && (now.getTime() - then.getTime()) < LAST_LOGIN_DEBOUNCE_MS;

        admin.setLastLogin(now);
        admin.setName(player.getName());

        if (!debounce)
        {
            saveAdminAsync(admin);
        }
    }

    public boolean isAdminImpostor(Player player)
    {
        return getEntryByName(player.getName()) != null && !isAdmin(player);
    }

    public boolean isIdentityMatched(Player player)
    {
        if (Bukkit.getOnlineMode())
        {
            return true;
        }

        Admin admin = getAdmin(player);
        return admin != null && admin.getName().equalsIgnoreCase(player.getName());
    }

    public boolean addAdmin(Admin admin)
    {
        if (!admin.isValid())
        {
            FLog.warning(String.format("Could not add admin: %s Admin is missing details!", admin.getConfigKey()));
            return false;
        }

        final String key = admin.getConfigKey();

        // Store admin, update views
        allAdmins.put(key, admin);
        updateTables();

        // Save admin
        if (usingSql)
        {
            saveAdminAsync(admin);
        }
        else
        {
            saveAsync();
        }

        refreshWorldEditBypassForAdmin(admin);
        return true;
    }

    public boolean removeAdmin(Admin admin)
    {
        // Remove admin, update views
        if (allAdmins.remove(admin.getConfigKey()) == null)
        {
            return false;
        }
        updateTables();

        // Remove from storage
        if (usingSql)
        {
            removeAdminFromSql(admin);
        }
        else
        {
            saveAsync();
        }

        refreshWorldEditBypassForAdmin(admin);
        return true;
    }

    /**
     * Refresh the IP lookup table for a single admin. Use this instead of
     * {@link #updateTables()} when only one entry's IP list has changed.
     */
    public void refreshIps(Admin admin)
    {
        if (admin == null)
        {
            return;
        }

        ipTable.values().removeIf(entry -> entry == admin);

        if (!admin.isActive())
        {
            return;
        }

        admin.getIps().forEach(ip -> ipTable.put(ip, admin));
    }

    public void updateTables()
    {
        activeAdmins.clear();
        nameTable.clear();
        ipTable.clear();
        uuidTable.clear();
        onlineAdminPlayers.clear();

        allAdmins.values().forEach(admin ->
        {
            // Always populate UUID table
            if (admin.getUuid() != null)
            {
                uuidTable.put(admin.getUuid(), admin);
            }

            if (!admin.isActive())
            {
                return;
            }

            activeAdmins.add(admin);
            nameTable.put(admin.getName().toLowerCase(), admin);
            admin.getIps().forEach(ip -> ipTable.put(ip, admin));
        });

        // Re-populate online-admin cache from currently-online players.
        Bukkit.getOnlinePlayers()
                .stream()
                .filter(this::isAdmin)
                .forEach(onlineAdminPlayers::add);

        if (plugin.wm != null && plugin.wm.adminworld != null)
        {
            plugin.wm.adminworld.wipeAccessCache();
        }
    }

    public Map<String, Admin> getAllAdmins()
    {
        return allAdmins;
    }

    public Set<Admin> getActiveAdmins()
    {
        return activeAdmins;
    }

    /**
     * Get admin by UUID.
     */
    public Admin getAdminByUuid(UUID uuid)
    {
        return uuidTable.get(uuid);
    }

    public Set<String> getAdminNames()
    {
        return nameTable.keySet();
    }

    public Set<String> getAdminIps()
    {
        return ipTable.keySet();
    }

    public Set<Player> getOnlineAdmins()
    {
        return onlineAdminPlayers;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        if (isAdmin(player))
        {
            onlineAdminPlayers.add(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        onlineAdminPlayers.remove(event.getPlayer());
    }

    public void deactivateOldEntries(boolean verbose)
    {
        final long threshold = ConfigEntry.ADMINLIST_CLEAN_THESHOLD_HOURS.getInteger();

        allAdmins.values()
                .stream()
                .filter(Admin::isActive)
                .filter(admin -> !grantsSeniorStatus(admin))
                // A record with no recorded login has nothing to age out against.
                .filter(admin -> admin.getLastLogin() != null)
                .filter(admin -> inactiveHours(admin) >= threshold)
                .forEach(admin ->
                {
                    if (verbose)
                    {
                        FUtil.adminAction("TotalFreedomMod", String.format(
                                "Deactivating superadmin %s, inactive for %d hours",
                                admin.getName(), inactiveHours(admin)), true);
                    }

                    admin.setActive(false);
                    saveAdminAsync(admin);
                });

        updateTables();
    }

    /**
     * Best-effort UUID backfill for admin records loaded without a stored UUID.
     * Runs at startup only: {@link FUtil#usernameToUuid} may make a blocking
     * Mojang request, which must never happen during play.
     */
    private void getMissingUuids()
    {
        final List<UuidBackfill> backfilled = allAdmins.values()
                .stream()
                .filter(admin -> admin.getUuid() == null)
                .map(AdminList::backfillUuid)
                .toList();

        if (backfilled.isEmpty())
        {
            return;
        }

        final long resolved = backfilled.stream()
                .filter(UuidBackfill::fromLookup)
                .count();
        final long offlineDerived = backfilled.size() - resolved;

        FLog.info(String.format("UUID backfill: %d resolved via lookup, %d offline-derived", resolved, offlineDerived));

        if (offlineDerived > 0 && !ConfigEntry.ADMINLIST_MOJANG_UUID_LOOKUP.getBoolean())
        {
            FLog.warning(String.format("use_uuid_only is enabled but mojang_uuid_lookup is disabled; "
                    + "%d admin record(s) fell back to offline-derived UUIDs and "
                    + "will not match premium accounts on login", offlineDerived));
        }

        updateTables();
        saveAsync();
    }

    /**
     * Read every admin off-thread and apply the result on the main thread, dropping back to
     * the JSON snapshot if the read fails.
     */
    private void loadFromSqlAsync()
    {
        final AdminRepository repo = plugin.dm.getAdminRepository();
        plugin.dm.readAsync("AdminList/loadFromSql", repo.findAll(),
                admins -> applyLoadedAdmins(repo, admins),
                () ->
                {
                    loadFromJson();
                    backfillUuidsIfEnabled();
                });
    }

    private void applyLoadedAdmins(final AdminRepository repo, final List<Admin> admins)
    {
        allAdmins.clear();
        admins.forEach(admin ->
        {
            final String key = admin.getName().toLowerCase();
            allAdmins.put(key, fixConfigKey(admin, key));
        });

        usingSql = true;
        updateTables();
        FLog.info(String.format("Loaded %d admins from SQL database (%d active, %d IPs)",
                allAdmins.size(), nameTable.size(), ipTable.size()));

        reconcileFromJsonIfNewer(repo);
        backfillUuidsIfEnabled();
    }

    private void backfillUuidsIfEnabled()
    {
        if (ConfigEntry.ADMINLIST_USE_UUID_ONLY.getBoolean())
            getMissingUuids();
    }

    /**
     * If admins.json was written more recently than the database's last update (e.g. edited
     * by hand, or restored from backup while SQL was unavailable), re-import it into SQL.
     * The comparison and the re-import both ride the write queue off the main thread.
     * <p>
     * The import replaces the table rather than merging into it, so an entry removed from the file
     * by hand is removed from SQL too instead of reappearing on the next start. An empty or
     * unreadable file is ignored, so a truncated snapshot cannot empty the table.
     */
    private void reconcileFromJsonIfNewer(final AdminRepository repo)
    {
        if (!configFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            enqueue(writeJsonAsync(serialiseAdmins()));
            return;
        }

        final Map<String, Admin> jsonAdmins;
        try
        {
            jsonAdmins = readJsonAdmins();
        }
        catch (IOException ex)
        {
            FLog.warning(String.format("Failed to read %s: %s", CONFIG_FILENAME, ex.getMessage()));
            return;
        }

        if (jsonAdmins.isEmpty())
        {
            // An empty snapshot described nothing, but SQL may hold rows it should be covering.
            // Refresh it rather than leaving the fallback with no admins.
            enqueue(writeJsonAsync(serialiseAdmins()));
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
                    FLog.info(String.format("%s is newer than the database; rebuilding it from the file's %d admin(s).",
                                            CONFIG_FILENAME, jsonAdmins.size()));
                    return Flux.fromIterable(jsonAdmins.values())
                               .filter(Admin::isValid)
                               .concatMap(admin -> repo.save(resolveUuidFor(admin), copyAdmin(admin)))
                               .then(repo.findAll())
                               .flatMapMany(Flux::fromIterable)
                               .filter(existing -> existing.getUuid() != null
                                                   && !jsonAdmins.containsKey(existing.getName().toLowerCase()))
                               .concatMap(stale -> repo.deleteByUuid(stale.getUuid()))
                               .then(Mono.<Void>fromRunnable(() -> plugin.dm.sync("AdminList/applyReconciled",
                                                             () -> applyReconciledAdmins(jsonAdmins))));
                })
                .onErrorResume(ex ->
                {
                    FLog.warning(String.format("Failed to reconcile %s into the database: %s",
                            CONFIG_FILENAME, ex.getMessage()));
                    return Mono.empty();
                })
                .then());
    }

    private void applyReconciledAdmins(final Map<String, Admin> jsonAdmins)
    {
        allAdmins.clear();
        allAdmins.putAll(jsonAdmins);
        updateTables();
    }

    private Map<String, Admin> readJsonAdmins() throws IOException
    {
        try (FileReader reader = new FileReader(configFile))
        {
            Map<String, Admin> admins = JsonUtil.GSON.fromJson(reader, ADMIN_MAP_TYPE);
            return admins != null ? admins : Maps.newHashMap();
        }
    }

    /**
     * Fix the config key on an admin (needed when loading from SQL).
     */
    private Admin fixConfigKey(Admin admin, String key)
    {
        if (admin.getConfigKey() == null || !admin.getConfigKey().equals(key))
        {
            Admin fixed = new Admin(key);
            fixed.setUuid(admin.getUuid());
            fixed.setName(admin.getName());
            fixed.setRankId(admin.getRankId());
            fixed.setActive(admin.isActive());
            fixed.setLastLogin(admin.getLastLogin());
            fixed.setLoginMessage(admin.getLoginMessage());
            fixed.addIps(admin.getIps());
            return fixed;
        }
        return admin;
    }

    /**
     * Load admins from JSON file (fallback).
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
                FLog.severe(String.format("Could not create %s", CONFIG_FILENAME));
            }
        }

        allAdmins.clear();
        try
        {
            readJsonAdmins().forEach((key, admin) ->
            {
                if (admin == null || !admin.isValid())
                {
                    FLog.warning(String.format("Could not load admin: %s. Missing details!", key));
                    return;
                }
                allAdmins.put(key, admin);
            });
        }
        catch (IOException ex)
        {
            FLog.severe(String.format("Could not read %s: %s", CONFIG_FILENAME, ex.getMessage()));
        }

        usingSql = false;
        updateTables();
        FLog.info(String.format("Loaded %d admins from JSON (%d active, %d IPs)",
                allAdmins.size(), nameTable.size(), ipTable.size()));
    }

    private void enqueue(Mono<Void> work)
    {
        writes.enqueue(work);
    }

    /**
     * Queue a batch of admin rows for an off-thread SQL write, followed by one
     * refresh of the JSON snapshot.
     */
    private void queueSqlWrites(List<PendingWrite> batch)
    {
        if (batch.isEmpty())
        {
            return;
        }

        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning(String.format("SQL not available; %d admin change(s) were not saved", batch.size()));
            return;
        }

        final AdminRepository repo = plugin.dm.getAdminRepository();

        // Render the snapshot here, while we still own the maps on the calling
        // thread. Serialising inside the queued task would read allAdmins
        // off-thread while the main thread is free to mutate it.
        final String json = serialiseAdmins();

        enqueue(Flux.fromIterable(batch)
                .concatMap(pending -> resolveUuidAsync(pending.live(), pending.snapshot())
                        .flatMap(uuid -> repo.save(uuid, pending.snapshot()))
                        .onErrorResume(ex ->
                        {
                            FLog.warning(String.format("Failed to save admin %s to SQL: %s",
                                    pending.snapshot().getName(), ex.getMessage()));
                            return Mono.<Integer>empty();
                        }))
                .then(writeJsonAsync(json)));
    }

    /**
     * Remove admin from SQL database.
     */
    private void removeAdminFromSql(Admin admin)
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning(String.format("SQL not available; removal of admin %s was not saved", admin.getName()));
            return;
        }

        final AdminRepository repo = plugin.dm.getAdminRepository();
        final UUID uuid = admin.getUuid();
        final String name = admin.getName();
        final String json = serialiseAdmins();

        final Mono<Void> delete = uuid != null
                ? repo.deleteByUuid(uuid).then()
                : Mono.<Void>fromRunnable(() ->
                {
                    try
                    {
                        repo.deleteByUsername(name);
                    }
                    catch (SQLException ex)
                    {
                        throw new IllegalStateException(ex);
                    }
                }).subscribeOn(Schedulers.boundedElastic());

        enqueue(delete
                .onErrorResume(ex ->
                {
                    FLog.warning(String.format("Failed to remove admin %s from SQL: %s", name, ex.getMessage()));
                    return Mono.empty();
                })
                .then(writeJsonAsync(json)));
    }

    /**
     * Blocking write of every admin record to SQL. Startup/shutdown only.
     */
    private void saveToSql()
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning("SQL not available, falling back to the JSON snapshot");
            saveToJson();
            return;
        }

        final AdminRepository repo = plugin.dm.getAdminRepository();

        final Long failed = Flux.fromIterable(List.copyOf(allAdmins.values()))
                .concatMap(admin -> Mono.fromCallable(() -> resolveUuidFor(admin))
                        .flatMap(uuid -> repo.save(uuid, admin))
                        .thenReturn(Boolean.TRUE)
                        .onErrorResume(ex ->
                        {
                            FLog.warning(String.format("Failed to save admin %s to SQL: %s",
                                    admin.getName(), ex.getMessage()));
                            return Mono.just(Boolean.FALSE);
                        }))
                .filter(Boolean.FALSE::equals)
                .count()
                .block();

        FLog.debug(String.format("Flushed %d admin(s) to SQL (%d failed)",
                allAdmins.size(), failed == null ? 0L : failed));

        // Write the snapshot either way, so a later SQL-less start has something to read.
        saveToJson();
    }

    /**
     * Blocking write of the JSON snapshot. Startup/shutdown and main-thread
     * fallbacks only; queued writes go through {@link #writeJsonAsync(String)}.
     */
    private void saveToJson()
    {
        writeJson(serialiseAdmins());
    }

    private String serialiseAdmins()
    {
        return JsonUtil.GSON.toJson(allAdmins, ADMIN_MAP_TYPE);
    }

    private Mono<Void> writeJsonAsync(String json)
    {
        return Mono.<Void>fromRunnable(() -> writeJson(json))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void writeJson(String json)
    {
        try (FileWriter writer = new FileWriter(configFile))
        {
            writer.write(json);
        }
        catch (IOException ex)
        {
            FLog.severe(String.format("Could not save %s: %s", CONFIG_FILENAME, ex.getMessage()));
        }
    }

    private PendingWrite pendingWrite(Admin live)
    {
        return new PendingWrite(live, copyAdmin(live));
    }

    private Admin copyAdmin(Admin admin)
    {
        Admin copy = new Admin(admin.getConfigKey());
        copy.setUuid(admin.getUuid());
        copy.setName(admin.getName());
        copy.setRankId(effectiveRankId(admin));
        copy.setActive(admin.isActive());
        copy.setLastLogin(admin.getLastLogin() == null ? null : new Date(admin.getLastLogin().getTime()));
        copy.setLoginMessage(admin.getLoginMessage());
        copy.addIps(new ArrayList<>(admin.getIps()));
        return copy;
    }

    /**
     * The rank id to store for an admin. An unset id means "whatever fills the default admin role",
     * which a NOT NULL column cannot express, so the role is resolved to a concrete id at write time.
     */
    private String effectiveRankId(final Admin admin)
    {
        if (admin.getRankId() != null)
            return admin.getRankId();

        return plugin.rm.getRegistry()
                        .byRole(RankRole.ADMIN_DEFAULT)
                        .map(CustomRank::getId)
                        .orElse(null);
    }

    /**
     * Resolve the UUID for a queued write. The lookup runs on the persistence
     * chain rather than the caller, because {@link FUtil#usernameToUuid} can make
     * a blocking Mojang request with a 5s connect and 5s read timeout. The resolved value is handed
     * back to the live entry on the main thread, which owns the lookup tables.
     */
    private Mono<UUID> resolveUuidAsync(Admin live, Admin snapshot)
    {
        if (snapshot.getUuid() != null)
        {
            return Mono.just(snapshot.getUuid());
        }

        return Mono.fromCallable(() -> resolveUuidFor(snapshot))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(resolved -> syncResolvedUuid(live, resolved));
    }

    /**
     * Hand a UUID resolved off-thread back to the live entry and the lookup
     * table, both of which belong to the main thread.
     */
    private void syncResolvedUuid(Admin live, UUID resolved)
    {
        if (!plugin.isEnabled())
        {
            return;
        }

        try
        {
            plugin.getServer().getScheduler().runTask(plugin, () ->
            {
                if (live.getUuid() == null)
                {
                    live.setUuid(resolved);
                }
                uuidTable.putIfAbsent(resolved, live);
            });
        }
        catch (RuntimeException ex)
        {
            // Plugin disabled between the check and the schedule; Bukkit answers
            // that with IllegalPluginAccessException. The snapshot already holds
            // the UUID, so the write itself is unaffected.
            FLog.debug(String.format("Could not sync resolved UUID back to %s; server is stopping", live.getName()));
        }
    }

    /**
     * Resolve and store a UUID on {@code admin}: Mojang lookup by name, falling
     * back to an offline-derived UUID. Blocking - never call this on the main
     * thread outside startup or shutdown.
     */
    private UUID resolveUuidFor(Admin admin)
    {
        if (admin.getUuid() != null)
        {
            return admin.getUuid();
        }

        final UUID looked = FUtil.usernameToUuid(admin.getName());
        final UUID resolved = looked != null ? looked : offlineUuid(admin.getName());
        admin.setUuid(resolved);
        return resolved;
    }

    private void refreshWorldEditBypassForAdmin(Admin admin)
    {
        if (plugin.web == null)
        {
            return;
        }
        try
        {
            Player online = null;
            final UUID uuid = admin.getUuid();
            if (uuid != null)
            {
                online = plugin.getServer().getPlayer(uuid);
            }
            if (online == null && admin.getName() != null)
            {
                online = plugin.getServer().getPlayerExact(admin.getName());
            }
            if (online != null)
            {
                plugin.web.refreshBypassNegation(online);
            }
        }
        catch (Throwable t)
        {
            FLog.warning(String.format("Failed to refresh WorldEdit bypass negation: %s", t.getMessage()));
        }
    }

    private long inactiveHours(Admin admin)
    {
        return TimeUnit.HOURS.convert(new Date().getTime() - admin.getLastLogin().getTime(), TimeUnit.MILLISECONDS);
    }

    private static UuidBackfill backfillUuid(Admin admin)
    {
        final UUID looked = FUtil.usernameToUuid(admin.getName());
        admin.setUuid(looked != null ? looked : offlineUuid(admin.getName()));
        return new UuidBackfill(admin, looked != null);
    }

    private static UUID offlineUuid(String name)
    {
        return UUID.nameUUIDFromBytes(String.format("OfflinePlayer:%s", name.toLowerCase())
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A live entry paired with the immutable snapshot that will actually be
     * written, so a mutation on the main thread cannot change a row mid-write.
     */
    private record PendingWrite(Admin live, Admin snapshot) {}

    private record UuidBackfill(Admin admin, boolean fromLookup) {}
}
