package me.totalfreedom.totalfreedommod.banning;

import me.totalfreedom.totalfreedommod.bridge.WorldEditBridge;

import me.totalfreedom.api.FreedomAPI;
import me.totalfreedom.api.banning.IBanManager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.player.PlayerData;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.api.sql.adapter.BanRepository;
import me.totalfreedom.totalfreedommod.sql.PunishmentHistoryEntry;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.reflect.TypeToken;

public class BanManager extends FreedomService implements IBanManager
{
    private static final Type BAN_LIST_TYPE = new TypeToken<List<Ban>>() {}.getType();
    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;

    private final Set<Ban> bans = Sets.newHashSet();
    private final Map<String, Ban> ipBans = Maps.newHashMap();
    private final Map<String, Ban> nameBans = Maps.newHashMap();
    private final List<String> unbannableUsernames = Lists.newArrayList();
    private final File configFile;
    private final Object lock = new Object();
    private final PersistenceQueue writes = new PersistenceQueue("ban");

    private boolean usingSql = false;

    public BanManager(FreedomAPI plugin)
    {
        super(plugin);
        this.configFile = new File(plugin.getDataFolder(), "bans.json");
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onStart()
    {
        load();
        plugin.database().whenReady(this::load);

        unbannableUsernames.clear();
        unbannableUsernames.addAll((Collection<? extends String>) ConfigEntry.FAMOUS_PLAYERS.getList());
        FLog.info("Loaded {} unbannable usernames.", unbannableUsernames.size());
    }

    /**
     * Populate the ban list from SQL where it is available and the JSON snapshot otherwise.
     * Never blocks: the SQL read runs off-thread and is applied back on the main thread.
     */
    public void load()
    {
        if (plugin.database() != null && plugin.database().isInitialized())
        {
            loadFromSqlAsync();
            return;
        }

        loadFromJson();
    }

    private void loadFromSqlAsync()
    {
        final BanRepository repo = plugin.database().getBanRepository();
        plugin.database().readAsync("BanManager/loadFromSql", repo.findAll(),
                loaded -> applyLoadedBans(repo, loaded),
                this::loadFromJson);
    }

    private void applyLoadedBans(final BanRepository repo, final List<Ban> loaded)
    {
        synchronized (lock)
        {
            bans.clear();
            bans.addAll(loaded);
            usingSql = true;
            updateViews();
            FLog.info("Loaded {} IP bans and {} username bans from SQL database.",
                    ipBans.size(), nameBans.size());
        }

        reconcileFromJsonIfNewer(repo);
    }

    /**
     * If bans.json was written more recently than the database's last update, merge it into SQL.
     * The comparison and the merge both ride the write queue off the main thread.
     * <p>
     * The merge only adds and updates; it never deletes a row the file lacks. While the database
     * is still starting (on first boot that includes the YAML import) bans run against the JSON
     * snapshot alone, so the file can hold just the few bans made in that window and still carry a
     * newer timestamp than every row in SQL. Treating it as the full ban list would wipe the rest.
     * Lifting a ban goes straight to SQL, so a stale row cannot outlive an unban in normal running.
     * An empty or unreadable file is ignored.
     */
    private void reconcileFromJsonIfNewer(final BanRepository repo)
    {
        if (!configFile.exists())
        {
            // Nothing to reconcile against, but SQL now has rows that no snapshot covers.
            enqueue(writeJsonAsync(new ArrayList<>(bans)));
            return;
        }

        final List<Ban> jsonBans;
        try
        {
            jsonBans = readJsonBans();
        }
        catch (IOException ex)
        {
            FLog.warn("Failed to read bans.json", ex);
            return;
        }

        if (jsonBans.isEmpty())
        {
            enqueue(writeJsonAsync(new ArrayList<>(bans)));
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
                    FLog.info("bans.json is newer than the database; merging the file's {} ban(s) into it.",
                            jsonBans.size());
                    return upsertToSql(repo, jsonBans).then(plugin.database().syncMono("BanManager/applyReconciled", () -> mergeReconciledBans(jsonBans)));
                })
                .onErrorResume(ex ->
                {
                    FLog.warn("Failed to reconcile bans.json into the database", ex);
                    return Mono.empty();
                })
                .then());
    }

    /**
    * Whether a row in the database is still described by the snapshot. A ban with
    * no uuid is an IP ban, so it is matched on its addresses instead. A row with
    * neither is left alone, since there is no key by which to remove it safely.
    */
    private static boolean isKept(final Ban existing, final Set<UUID> keepUuids, final Set<String> keepIps)
    {
        if (existing.getUuid() != null)
            return keepUuids.contains(existing.getUuid());

        if (existing.getIps().isEmpty())
            return true;

        return existing.getIps().stream().anyMatch(keepIps::contains);
    }

    /**
     * Make the database match {@code desired}: write every record, then delete
     * only the rows it no longer describes. The table is never emptied first,
     * so a failed write leaves the existing rows untouched. A ban with no uuid
     * is an IP ban, so it is matched and removed by address.
     */
    private Mono<Void> syncToSql(final BanRepository repo, final Collection<Ban> desired)
    {
        final Set<UUID> keepUuids = desired.stream()
                                           .map(Ban::getUuid)
                                           .filter(Objects::nonNull)
                                           .collect(Collectors.toSet());
        final Set<String> keepIps = desired.stream()
                                           .flatMap(ban -> ban.getIps().stream())
                                           .collect(Collectors.toSet());

        return repo.loadAllAsync()
                   .flatMapMany(existing -> upsertToSql(repo, desired)
                           .thenMany(Flux.fromIterable(existing)
                                         .filter(row -> !isKept(row, keepUuids, keepIps))
                                         .concatMap(stale -> stale.getUuid() != null
                                                             ? repo.deleteAsync(stale.getUuid())
                                                             : repo.deleteByIpAsync(stale.getIps().get(0)))))
                   .then();
    }

    /**
     * Write every valid ban in {@code bans} to SQL, adding or updating and deleting nothing.
     */
    private Mono<Void> upsertToSql(final BanRepository repo, final Collection<Ban> bans)
    {
        return Flux.fromIterable(bans)
                   .filter(Ban::isValid)
                   .concatMap(repo::save)
                   .then();
    }

    /**
     * Fold the snapshot's bans into memory without dropping anything SQL already supplied. A ban in
     * both is replaced by the snapshot's copy, which is the newer of the two.
     */
    private void mergeReconciledBans(final List<Ban> jsonBans)
    {
        synchronized (lock)
        {
            jsonBans.forEach(ban ->
            {
                bans.remove(ban);
                bans.add(ban);
            });
            updateViews();
        }
    }

    private List<Ban> readJsonBans() throws IOException
    {
        try (FileReader reader = new FileReader(configFile))
        {
            List<Ban> loaded = JsonUtil.GSON.fromJson(reader, BAN_LIST_TYPE);
            return loaded != null ? loaded : new ArrayList<>();
        }
    }

    /**
     * Load bans from the JSON file (fallback).
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
                FLog.error("Could not create bans.json");
            }
        }

        synchronized (lock)
        {
            bans.clear();
            try
            {
                for (Ban ban : readJsonBans())
                {
                    if (!ban.isValid())
                    {
                        FLog.warn("Not adding username ban: {}. Missing information.", ban.getUsername());
                        continue;
                    }
                    bans.add(ban);
                }
            }
            catch (IOException ex)
            {
                FLog.error("Could not read bans.json", ex);
            }

            usingSql = false;
            updateViews();
            FLog.info("Loaded {} IP bans and {} username bans from JSON.", ipBans.size(), nameBans.size());
        }
    }

    @Override
    public void onStop()
    {
        awaitPendingWrites(SHUTDOWN_FLUSH_TIMEOUT_MS);
        saveAll();
        FLog.info("Saved {} player bans", bans.size());
    }

    public void awaitPendingWrites(long timeoutMs)
    {
        writes.await(timeoutMs);
    }

    private void enqueue(Mono<Void> work)
    {
        writes.enqueue(work);
    }

    public Set<Ban> getAllBans()
    {
        return Collections.unmodifiableSet(bans);
    }

    public Collection<Ban> getIpBans()
    {
        return Collections.unmodifiableCollection(ipBans.values());
    }

    public Collection<Ban> getUsernameBans()
    {
        return Collections.unmodifiableCollection(nameBans.values());
    }

    /**
     * Blocking write of every ban record. Startup/shutdown only. Single-entry changes belong
     * on {@link #saveBanToSqlAsync(Ban)}; whole-list changes reachable from a command or event
     * handler belong on {@link #saveAllAsync()}.
     */
    public void saveAll()
    {
        final boolean sql;
        final List<Ban> snapshot;
        synchronized (lock)
        {
            updateViews();
            sql = usingSql;
            snapshot = new ArrayList<>(bans);
        }

        if (sql)
        {
            writeAllToSql(snapshot);
        }
        else
        {
            writeAllToJson(snapshot);
        }
    }

    public void saveAllAsync()
    {
        final boolean sql;
        final List<Ban> snapshot;
        synchronized (lock)
        {
            updateViews();
            sql = usingSql;
            snapshot = new ArrayList<>(bans);
        }

        if (!sql)
        {
            if (!plugin.isEnabled())
            {
                writeAllToJson(snapshot);
                return;
            }
            enqueue(writeJsonAsync(snapshot));
            return;
        }

        if (plugin.database() == null || !plugin.database().isInitialized())
        {
            FLog.warn("SQL not available, falling back to JSON save");
            enqueue(writeJsonAsync(snapshot));
            return;
        }

        final BanRepository repo = plugin.database().getBanRepository();

        enqueue(syncToSql(repo, snapshot)
                .onErrorResume(ex ->
                {
                    FLog.warn("Failed to write bans to SQL", ex);
                    return Mono.empty();
                })
                .then(writeJsonAsync(snapshot)));
    }

    /**
     * Queue a single ban for an off-thread SQL write, followed by a refresh of the JSON
     * snapshot. Safe to call from commands and event handlers.
     */
    private void saveBanToSqlAsync(Ban ban)
    {
        if (plugin.database() == null || !plugin.database().isInitialized())
        {
            FLog.warn("SQL not available; ban change was not saved");
            return;
        }

        final BanRepository repo = plugin.database().getBanRepository();
        final List<Ban> snapshot = currentBansSnapshot();

        enqueue(repo.save(ban)
                .onErrorResume(ex ->
                {
                    FLog.warn("Failed to save ban to SQL", ex);
                    return Mono.<Integer>empty();
                })
                .then(writeJsonAsync(snapshot)));
    }

    /**
     * Queue a single ban removal for an off-thread SQL write, followed by a refresh of the
     * JSON snapshot. Safe to call from commands and event handlers.
     */
    private void removeBanFromSqlAsync(Ban ban)
    {
        if (plugin.database() == null || !plugin.database().isInitialized())
        {
            FLog.warn("SQL not available; ban removal was not saved");
            return;
        }

        final BanRepository repo = plugin.database().getBanRepository();
        final List<Ban> snapshot = currentBansSnapshot();

        final Mono<Boolean> delete;
        if (ban.getUuid() != null)
        {
            delete = repo.deleteByUuid(ban.getUuid());
        }
        else if (ban.hasUsername())
        {
            delete = Mono.fromCallable(() -> repo.deleteByUsername(ban.getUsername()))
                    .subscribeOn(Schedulers.boundedElastic());
        }
        else if (ban.hasIps())
        {
            delete = repo.deleteByIpAsync(ban.getIps().get(0));
        }
        else
        {
            delete = Mono.just(Boolean.FALSE);
        }

        enqueue(delete
                .onErrorResume(ex ->
                {
                    FLog.warn("Failed to remove ban from SQL", ex);
                    return Mono.<Boolean>empty();
                })
                .then(writeJsonAsync(snapshot)));
    }

    private void writeAllToSql(List<Ban> snapshot)
    {
        if (plugin.database() == null || !plugin.database().isInitialized())
        {
            FLog.warn("SQL not available, falling back to JSON save");
            writeAllToJson(snapshot);
            return;
        }

        try
        {
            BanRepository repo = plugin.database().getBanRepository();
            syncToSql(repo, snapshot).block();
            FLog.debug("Saved {} bans to SQL database", snapshot.size());
            writeAllToJson(snapshot);
        }
        catch (Exception ex)
        {
            FLog.warn("Failed to save bans to SQL", ex);
        }
    }

    /**
     * Write the given snapshot of bans to the JSON file (fallback, and the write-through snapshot when using SQL).
     */
    private void writeAllToJson(List<Ban> snapshot)
    {
        try (FileWriter writer = new FileWriter(configFile))
        {
            JsonUtil.GSON.toJson(snapshot, BAN_LIST_TYPE, writer);
        }
        catch (IOException ex)
        {
            FLog.error("Could not save bans.json");
        }
    }

    private Mono<Void> writeJsonAsync(List<Ban> snapshot)
    {
        return Mono.<Void>fromRunnable(() -> writeAllToJson(snapshot))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Ban getByIp(String ip)
    {
        synchronized (lock)
        {
            final Ban directBan = ipBans.get(ip);
            if (directBan != null && !directBan.isExpired())
            {
                return directBan;
            }

            // Match fuzzy IP
            for (Ban loopBan : ipBans.values())
            {
                if (loopBan.isExpired())
                {
                    continue;
                }

                for (String loopIp : loopBan.getIps())
                {
                    if (!loopIp.contains("*"))
                    {
                        continue;
                    }

                    if (FUtil.fuzzyIpMatch(ip, loopIp, 4))
                    {
                        return loopBan;
                    }
                }
            }

            return null;
        }
    }

    public Ban getByUsername(String username)
    {
        synchronized (lock)
        {
            username = username.toLowerCase();
            final Ban directBan = nameBans.get(username);

            if (directBan != null && !directBan.isExpired())
            {
                return directBan;
            }

            return null;
        }
    }

    public Ban unbanIp(String ip)
    {
        final Ban ban;
        final boolean sql;
        synchronized (lock)
        {
            ban = getByIp(ip);
            if (ban != null)
            {
                bans.remove(ban);
                updateViews();
            }
            sql = usingSql;
        }

        if (ban != null)
        {
            if (sql)
            {
                removeBanFromSqlAsync(ban);
            }
            else
            {
                saveAllAsync();
            }
        }

        return ban;
    }

    public Ban unbanUsername(String username)
    {
        final Ban ban;
        final boolean sql;
        synchronized (lock)
        {
            ban = getByUsername(username);
            if (ban != null)
            {
                bans.remove(ban);
                updateViews();
            }
            sql = usingSql;
        }

        if (ban != null)
        {
            if (sql)
            {
                removeBanFromSqlAsync(ban);
            }
            else
            {
                saveAllAsync();
            }
        }

        return ban;
    }

    public boolean isIpBanned(String ip)
    {
        return getByIp(ip) != null;
    }

    public boolean isUsernameBanned(String username)
    {
        return getByUsername(username) != null;
    }

    public boolean addBan(Ban ban)
    {
        cancelWorldEditFor(ban);

        final boolean added;
        final boolean sql;
        synchronized (lock)
        {
            added = bans.add(ban);
            if (added)
            {
                updateViews();
            }
            sql = usingSql;
        }

        if (added)
        {
            recordPunishmentHistory(ban);

            if (sql)
            {
                saveBanToSqlAsync(ban);
            }
            else
            {
                saveAllAsync();
            }
        }

        return added;
    }

    private void recordPunishmentHistory(Ban ban)
    {
        if (!plugin.database().isInitialized())
        {
            FLog.warn("Punishment history was not recorded because the SQL database is unavailable.");
            return;
        }

        final Player target = ban.getUuid() != null
                ? server.getPlayer(ban.getUuid())
                : ban.hasUsername() ? server.getPlayerExact(ban.getUsername()) : null;
        final Player actor = ban.getBy() == null ? null : server.getPlayerExact(ban.getBy());
        final String targetUuid = ban.getUuid() != null
                ? ban.getUuid().toString()
                : target == null ? null : target.getUniqueId().toString();
        final String actorUuid = actor == null ? null : actor.getUniqueId().toString();
        final List<String> ips = ban.getIps()
                .stream()
                .filter(Objects::nonNull)
                .filter(ip -> !ip.isBlank() && !ip.contains("*"))
                .distinct()
                .toList();
        final PunishmentHistoryEntry historyEntry = new PunishmentHistoryEntry(
                0L,
                targetUuid,
                ban.getUsername(),
                actorUuid,
                ban.getBy(),
                ban.hasExpiry() && !ban.isGtfo() ? "TEMPBAN" : "BAN",
                ban.getReason(),
                System.currentTimeMillis(),
                ban.hasExpiry() ? ban.getExpiryDate().getTime() : null,
                ban.getSource(),
                ips,
                null,
                null);
        plugin.database().recordPunishmentAsync(historyEntry);
    }

    private void cancelWorldEditFor(Ban ban)
    {
        if (plugin.bridges().require(WorldEditBridge.class) == null)
        {
            return;
        }
        Player player = null;
        if (ban.getUuid() != null)
        {
            player = server.getPlayer(ban.getUuid());
        }
        if (player == null && ban.getUsername() != null)
        {
            player = server.getPlayerExact(ban.getUsername());
        }
        if (player != null)
        {
            plugin.bridges().require(WorldEditBridge.class).cancel(player);
        }
    }

    public boolean removeBan(Ban ban)
    {
        final boolean removed;
        final boolean sql;
        synchronized (lock)
        {
            removed = bans.remove(ban);
            if (removed)
            {
                updateViews();
            }
            sql = usingSql;
        }

        if (removed)
        {
            if (sql)
            {
                removeBanFromSqlAsync(ban);
            }
            else
            {
                saveAllAsync();
            }
        }

        return removed;
    }

    public int purge()
    {
        final int size;
        synchronized (lock)
        {
            size = bans.size();
            bans.clear();
            updateViews();
        }

        saveAllAsync();

        return size;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event)
    {
        final String username = event.getName();
        final String ip = event.getAddress().getHostAddress().trim();

        // Regular ban
        Ban ban = getByUsername(username);
        if (ban == null)
        {
            ban = getByIp(ip);
        }

        if (ban != null && !ban.isExpired())
        {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ban.bakeKickMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        final PlayerData data = plugin.players().getData(player);

        if (!plugin.ranks().isAdmin(player))
        {
            return;
        }

        // Unban admins
        data.getTrustedIps().forEach(trustedIp ->
        {
            unbanIp(trustedIp);
            unbanIp(FUtil.getFuzzyIp(trustedIp));
        });

        unbanUsername(player.getName());
        player.setOp(true);
    }

    private List<Ban> currentBansSnapshot()
    {
        synchronized (lock)
        {
            return new ArrayList<>(bans);
        }
    }

    // Must be called while holding 'lock'.
    private void updateViews()
    {
        // Remove expired bans
        for (Iterator<Ban> it = bans.iterator(); it.hasNext();)
        {
            if (it.next().isExpired())
            {
                it.remove();
            }
        }

        nameBans.clear();
        ipBans.clear();
        for (Ban ban : bans)
        {
            if (ban.hasUsername())
            {
                nameBans.put(ban.getUsername().toLowerCase(), ban);
            }

            if (ban.hasIps())
            {
                for (String ip : ban.getIps())
                {
                    ipBans.put(ip, ban);
                }
            }
        }
    }

}
