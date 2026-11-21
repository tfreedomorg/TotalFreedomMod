package me.totalfreedom.totalfreedommod.player;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import com.destroystokyo.paper.profile.PlayerProfile;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.sql.PersistenceQueue;
import me.totalfreedom.totalfreedommod.sql.adapter.PlayerRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import me.totalfreedom.totalfreedommod.util.JsonUtil;

public class PlayerList extends FreedomService
{

    public static final long AUTO_PURGE_TICKS = 20L * 60L * 5L;

    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;
    //
    public final Map<String, FPlayer> playerMap = new ConcurrentHashMap<>(); // key: lowercase username
    public final Map<String, PlayerData> dataMap = new ConcurrentHashMap<>(); // key: lowercase username
    private final Set<String> unverifiedUsernames = ConcurrentHashMap.newKeySet();
    private final File configFolder;
    private final PersistenceQueue writes = new PersistenceQueue("player data");

    public Map<String, FPlayer> getPlayerMap()
    {
        return playerMap;
    }

    public Map<String, PlayerData> getDataMap()
    {
        return dataMap;
    }

    public File getConfigFolder()
    {
        if (!configFolder.exists())
        {
            configFolder.mkdirs();
        }

        return configFolder;
    }

    public PlayerList(TotalFreedomMod plugin)
    {
        super(plugin);

        this.configFolder = new File(plugin.getDataFolder(), "players");
    }

    private boolean usingSql()
    {
        return plugin.dm != null && plugin.dm.isInitialized();
    }

    @Override
    protected void onStart()
    {
        playerMap.clear();
        dataMap.clear();

        // Preload online players
        for (Player player : server.getOnlinePlayers())
        {
            getPlayer(player);
        }
    }

    @Override
    protected void onStop()
    {
        save();
        writes.await(SHUTDOWN_FLUSH_TIMEOUT_MS);
    }

    /**
     * Queue a write of every loaded player record.
     */
    public void save()
    {
        List.copyOf(dataMap.values()).forEach(this::saveOne);
    }

    public void saveAsync()
    {
        save();
    }

    /**
     * Queue one player's SQL write plus its JSON snapshot refresh. Safe from an event handler:
     * the round trip runs off the main thread.
     */
    private void saveOne(final PlayerData data)
    {
        if (unverifiedUsernames.contains(data.getUsername().toLowerCase()))
        {
            FLog.warning(String.format("Not saving player data for %s: its SQL lookup failed, so the record is unverified.",
                    data.getUsername()));
            return;
        }

        if (!usingSql())
        {
            writes.enqueue(writeJsonAsync(data));
            return;
        }

        writes.enqueue(plugin.dm.getPlayerRepository().save(data)
                .onErrorResume(ex ->
                {
                    FLog.severe(String.format("Could not save player data for %s to SQL: %s",
                            data.getUsername(), ex.getMessage()));
                    FLog.severe(ex);
                    return Mono.empty();
                })
                .then(writeJsonAsync(data)));
    }

    private Mono<Void> writeJsonAsync(final PlayerData data)
    {
        return Mono.<Void>fromRunnable(() -> saveToJson(data))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void saveToJson(PlayerData data)
    {
        final File configFile = getConfigFile(data.getUsername().toLowerCase());
        try (FileWriter writer = new FileWriter(configFile))
        {
            JsonUtil.GSON.toJson(data, PlayerData.class, writer);
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save player data for " + data.getUsername());
            FLog.severe(ex);
        }
    }

    public void saveData(PlayerData data)
    {
        saveOne(data);
    }

    public void clearSavedTag(Player player)
    {
        final PlayerData data = getData(player);
        if (data.getSavedTag() != null)
        {
            data.setSavedTag(null);
            saveData(data);
        }
    }

    public boolean saveCurrentTag(Player player)
    {
        final String tag = getPlayer(player).getInternalTag();
        if (tag == null || tag.isEmpty())
        {
            return false;
        }

        final PlayerData data = getData(player);
        data.setSavedTag(tag);
        saveData(data);
        return true;
    }

    public FPlayer getPlayerSync(Player player)
    {
        synchronized (playerMap)
        {
            return getPlayer(player);
        }
    }

    public String getIp(OfflinePlayer player)
    {
        if (player.isOnline())
        {
            return player.getPlayer().getAddress().getAddress().getHostAddress();
        }

        final PlayerData entry = getData(player.getName());

        return (entry == null ? null : entry.getIps().iterator().next());
    }

    // May not return null
    public FPlayer getPlayer(Player player)
    {
        return playerMap.computeIfAbsent(player.getName().toLowerCase(), key ->
        {
            final FPlayer created = new FPlayer(plugin, player);
            final PlayerData data = getData(player);
            created.setCommandSpyMode(data.getCommandSpyMode());
            created.setCommandsBlocked(data.isCommandsBlocked());
            created.setJoinLeaveMessagesEnabled(data.isJoinLeaveMessagesEnabled());
            return created;
        });
    }

    // May not return null
    public PlayerData getData(Player player)
    {
        final String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        return resolve(player.getName(), ip);
    }

    // May return null
    public PlayerData getData(String username)
    {
        final PlayerData cached = dataMap.get(username.toLowerCase());
        return cached != null ? cached : load(username.toLowerCase());
    }

    private PlayerData resolve(final String username, final String ip)
    {
        return dataMap.computeIfAbsent(username.toLowerCase(), key ->
        {
            final PlayerData loaded = load(key);
            if (loaded != null)
                return loaded;

            FLog.info("Creating new player data entry for " + username);
            final long unix = FUtil.getUnixTime();
            final PlayerData created = new PlayerData(username);
            created.setFirstJoinUnix(unix);
            created.setLastJoinUnix(unix);
            if (ip != null)
                created.addIp(ip);
            return created;
        });
    }

    private PlayerData load(final String username)
    {
        if (usingSql())
        {
            final PlayerData data = loadFromSql(username);
            if (data != null)
            {
                return data;
            }
            // A failed lookup must fall through to JSON, but is not the same as "no such player".
        }

        return loadFromJson(username);
    }

    private PlayerData loadFromSql(String username)
    {
        try
        {
            PlayerRepository repo = plugin.dm.getPlayerRepository();
            reconcileFromJsonIfNewer(username, repo);

            final PlayerData found = repo.findByUsername(username).orElse(null);
            unverifiedUsernames.remove(username);
            return found;
        }
        catch (Exception ex)
        {
            unverifiedUsernames.add(username);
            FLog.warning("Failed to load player data for " + username + " from SQL, falling back to JSON: " + ex.getMessage());
            return null;
        }
    }

    /**
     * If this player's JSON snapshot was written more recently than the database's last update
     * for them, re-import it into SQL.
     */
    private void reconcileFromJsonIfNewer(String username, PlayerRepository repo)
    {
        final File jsonFile = getConfigFile(username);
        if (!jsonFile.exists())
        {
            return;
        }

        try
        {
            Long sqlUpdatedAt = repo.getUpdatedAt(username);
            if (!FUtil.isSnapshotNewer(jsonFile.lastModified(), sqlUpdatedAt))
                return;

            PlayerData jsonData = readJsonPlayer(username);
            if (jsonData == null || !jsonData.isValid())
            {
                return;
            }

            FLog.info(jsonFile.getName() + " is newer than the database; re-importing player data for " + username + " from it.");
            repo.saveOrUpdate(jsonData);
        }
        catch (Exception ex)
        {
            FLog.warning("Failed to reconcile player data for " + username + " into the database: " + ex.getMessage());
        }
    }

    private PlayerData loadFromJson(String username)
    {
        final File configFile = getConfigFile(username);
        if (!configFile.exists())
        {
            return null;
        }

        final PlayerData data = readJsonPlayer(username);

        if (data == null || !data.isValid())
        {
            FLog.warning("Could not load player data entry: " + username + ". Entry is not valid!");
            configFile.delete();
            return null;
        }

        return data;
    }

    private PlayerData readJsonPlayer(String username)
    {
        final File configFile = getConfigFile(username);
        try (FileReader reader = new FileReader(configFile))
        {
            return JsonUtil.GSON.fromJson(reader, PlayerData.class);
        }
        catch (Exception ex)
        {
            FLog.severe("Could not read player data for " + username + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Reads the pre-JSON per-player {@code players/<name>.yml} format. Retained only for the
     * one-time legacy-install migration path (not called during normal startup).
     */
    private PlayerData loadLegacyYaml(String username)
    {
        final File legacyFile = new File(getConfigFolder(), username + ".yml");
        if (!legacyFile.exists())
        {
            return null;
        }

        final YamlConfiguration config = YamlConfiguration.loadConfiguration(legacyFile);
        final PlayerData data = new PlayerData(username);
        data.loadFrom(config);
        return data;
    }

    public Collection<PlayerData> getAllData()
    {
        if (usingSql())
        {
            try
            {
                return plugin.dm.getPlayerRepository().loadAll().values();
            }
            catch (Exception ex)
            {
                FLog.warning("Failed to load all player data from SQL, falling back to JSON: " + ex.getMessage());
            }
        }

        return listConfigFiles()
            .stream()
            .map(File::getName)
            .filter(name -> name.endsWith(".json"))
            .map(name -> getData(name.substring(0, name.length() - ".json".length()))).toList();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        final PlayerData data = getData(player);
        final FPlayer fPlayer = getPlayer(player);

        if (data.isMuted())
        {
            fPlayer.setMuted(true);
            player.sendMessage(Component.text("You are still muted.", NamedTextColor.RED));
        }

        if (data.isFrozen())
        {
            fPlayer.getFreezeData().setFrozen(true);
            player.sendMessage(Component.text("You are still frozen.", NamedTextColor.AQUA));
        }

        final String savedTag = data.getSavedTag();
        if (savedTag != null)
        {
            fPlayer.setTag(savedTag);
        }

        if (data.hasCustomNickname())
        {
            player.displayName(data.hasCustomNickname() ?
                    data.getDisplayedNickname() :
                    Component.text(player.getName(), player.isOp() ? NamedTextColor.RED : NamedTextColor.WHITE));
        }
        data.setLastJoinUnix(FUtil.getUnixTime());
        if (player.getAddress() != null)
        {
            data.addIp(player.getAddress().getAddress().getHostAddress());
        }
        if (plugin.isEnabled())
        {
            saveOne(data);
        }
    }

    /**
     * Warm {@link #dataMap} before the player reaches the main thread. This event already runs
     * off it, so the lookup that {@code onPlayerJoin} would otherwise make against the database
     * happens here instead and finds a cached entry on join.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event)
    {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
            return;

        resolve(event.getName(), event.getAddress().getHostAddress());
    }

    /**
     * A login refused after pre-login never produces a quit, so drop the record pre-login cached.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event)
    {
        if (event.isAllowed() || !(event.getConnection() instanceof PlayerLoginConnection login))
            return;

        // The profile is null until authentication finishes, and a refusal can come before that.
        Optional.ofNullable(login.getAuthenticatedProfile())
                .map(PlayerProfile::getName)
                .map(String::toLowerCase)
                .ifPresent(this::releasePlayer);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        final String username = event.getPlayer().getName().toLowerCase();

        if (!plugin.isEnabled())
        {
            playerMap.remove(username);
            dataMap.remove(username);
            return;
        }

        server.getScheduler().runTask(plugin, () -> releasePlayer(username));
    }

    private void releasePlayer(final String username)
    {
        if (server.getPlayerExact(username) != null)
            return;

        playerMap.remove(username);
        final PlayerData data = dataMap.remove(username);

        if (data != null)
        {
            saveOne(data);
        }

        unverifiedUsernames.remove(username);
    }

    public Collection<FPlayer> getLoadedPlayers()
    {
        return playerMap.values();
    }

    public Collection<PlayerData> getLoadedData()
    {
        return dataMap.values();
    }

    public int purgeAllData()
    {
        final int deleted = (int) listConfigFiles()
                .stream()
                .filter(File::delete)
                .count();

        if (usingSql())
        {
            writes.enqueue(plugin.dm.getPlayerRepository().deleteAll()
                    .onErrorResume(ex ->
                    {
                        FLog.severe(String.format("Could not purge player data from SQL: %s", ex.getMessage()));
                        return Mono.empty();
                    }));
        }

        dataMap.clear();
        return deleted;
    }

    private List<File> listConfigFiles()
    {
        return Optional.ofNullable(getConfigFolder().listFiles())
                .map(Arrays::asList)
                .orElseGet(List::of);
    }

    protected File getConfigFile(String name)
    {
        return new File(getConfigFolder(), name + ".json");
    }
}
