package me.totalfreedom.totalfreedommod.player;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

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

import com.google.common.collect.Maps;

public class PlayerList extends FreedomService
{

    public static final long AUTO_PURGE_TICKS = 20L * 60L * 5L;

    private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 10L * 1000L;
    //
    public final Map<String, FPlayer> playerMap = Maps.newHashMap(); // key: lowercase username
    public final Map<String, PlayerData> dataMap = Maps.newHashMap(); // key: lowercase username
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
        FPlayer tPlayer = playerMap.get(player.getName().toLowerCase());
        if (tPlayer != null)
        {
            return tPlayer;
        }

        tPlayer = new FPlayer(plugin, player);
        final PlayerData data = getData(player);
        tPlayer.setCommandSpyMode(data.getCommandSpyMode());
        tPlayer.setCommandsBlocked(data.isCommandsBlocked());
        tPlayer.setJoinLeaveMessagesEnabled(data.isJoinLeaveMessagesEnabled());
        playerMap.put(player.getName().toLowerCase(), tPlayer);

        return tPlayer;
    }

    // May not return null
    public PlayerData getData(Player player)
    {
        // Check already loaded
        PlayerData data = dataMap.get(player.getName().toLowerCase());
        if (data != null)
        {
            return data;
        }

        // Load data
        data = getData(player.getName());

        // Create data if nonexistent
        if (data == null)
        {
            FLog.info("Creating new player data entry for " + player.getName());

            // Create new player
            final long unix = FUtil.getUnixTime();
            data = new PlayerData(player);
            data.setFirstJoinUnix(unix);
            data.setLastJoinUnix(unix);
            data.addIp(player.getAddress().getAddress().getHostAddress());

            // Store player
            dataMap.put(player.getName().toLowerCase(), data);

            // Save player
            saveOne(data);
        }

        return data;
    }

    // May return null
    public PlayerData getData(String username)
    {
        username = username.toLowerCase();

        if (usingSql())
        {
            PlayerData data = loadFromSql(username);
            if (data != null)
            {
                return data;
            }
        }

        return loadFromJson(username);
    }

    private PlayerData loadFromSql(String username)
    {
        try
        {
            PlayerRepository repo = plugin.dm.getPlayerRepository();
            reconcileFromJsonIfNewer(username, repo);

            return repo.findByUsername(username)
                       .map(data ->
                       {
                           if (Bukkit.getPlayerExact(data.getUsername()) != null)
                           {
                               dataMap.put(data.getUsername().toLowerCase(), data);
                           }
                           return data;
                       })
                       .orElse(null);
        }
        catch (Exception ex)
        {
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

        // Only store data if the player is online
        if (Bukkit.getPlayerExact(data.getUsername()) != null)
        {
            dataMap.put(data.getUsername().toLowerCase(), data);
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

        return Arrays.stream(configFolder.listFiles())
            .filter(file -> file != null)
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

        final PlayerData data = getData(event.getName());
        if (data != null)
            dataMap.put(data.getUsername().toLowerCase(), data);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        final String username = event.getPlayer().getName().toLowerCase();
        playerMap.remove(username);
        final PlayerData data = dataMap.remove(username);

        if (data != null && plugin.isEnabled())
        {
            saveOne(data);
        }
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
        int deleted = 0;
        for (File file : getConfigFolder().listFiles())
        {
            deleted += file.delete() ? 1 : 0;
        }

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

    protected File getConfigFile(String name)
    {
        return new File(getConfigFolder(), name + ".json");
    }
}
