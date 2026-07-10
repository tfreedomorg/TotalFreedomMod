package me.totalfreedom.totalfreedommod.admin;

import com.google.common.base.Function;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.event.admin.AdminManagementEvent;
import me.totalfreedom.totalfreedommod.rank.Rank;
import java.nio.charset.StandardCharsets;
import me.totalfreedom.totalfreedommod.sql.adapter.AdminRepository;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FUtil;
import java.io.File;
import java.io.IOException;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;

public class AdminList extends FreedomService
{

    public static final String CONFIG_FILENAME = "admins.yml";

    private static final long LAST_LOGIN_DEBOUNCE_MS = 5L * 60L * 1000L;

    @Getter
    private final Map<String, Admin> allAdmins = Maps.newHashMap(); // Includes disabled admins
    // Only active admins below
    @Getter
    private final Set<Admin> activeAdmins = Sets.newHashSet();
    
    // UUID-based lookup table
    private final Map<UUID, Admin> uuidTable = Maps.newHashMap();
    private final Map<String, Admin> nameTable = Maps.newHashMap();
    private final Map<String, Admin> ipTable = Maps.newHashMap();
    private final Set<Player> onlineAdminPlayers = Sets.newHashSet();
    //
    private final File configFile;
    private YamlConfiguration config;
    
    // Flag to track if SQL is available
    private boolean usingSql = false;
    private final Object persistenceLock = new Object();
    private CompletableFuture<Void> persistenceChain = CompletableFuture.completedFuture(null);

    public AdminList(TotalFreedomMod plugin)
    {
        super(plugin);

        this.configFile = new File(plugin.getDataFolder(), CONFIG_FILENAME);
        this.config = YamlConfiguration.loadConfiguration(configFile);
    }

    @Override
    protected void onStart()
    {
        load();

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
        save();
    }

    public void load()
    {
        // Try to load from SQL database first
        if (plugin.dm != null && plugin.dm.isInitialized())
        {
            loadFromSql();
        }
        else
        {
            loadFromYaml();
        }

        if (ConfigEntry.ADMINLIST_USE_UUID_ONLY.getBoolean())
        {
            getMissingUuids();
        }
    }

    /**
     * Best-effort UUID backfill for admin records loaded without a stored UUID.
     */
    private void getMissingUuids()
    {
        int resolved = 0;
        int offlineDerived = 0;
        boolean mojangLookup = ConfigEntry.ADMINLIST_MOJANG_UUID_LOOKUP.getBoolean();

        for (Admin admin : allAdmins.values())
        {
            if (admin.getUuid() != null)
            {
                continue;
            }

            UUID uuid = FUtil.usernameToUuid(admin.getName());
            if (uuid != null)
            {
                resolved++;
            }
            else
            {
                uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + admin.getName().toLowerCase()).getBytes(StandardCharsets.UTF_8));
                offlineDerived++;
            }
            admin.setUuid(uuid);
        }

        if (resolved + offlineDerived == 0)
        {
            return;
        }

        FLog.info("UUID backfill: " + resolved + " resolved via Mojang, " + offlineDerived + " offline-derived");
        if (offlineDerived > 0 && !mojangLookup)
        {
            FLog.warning("use_uuid_only is enabled but mojang_uuid_lookup is disabled; "
                    + offlineDerived + " admin record(s) fell back to offline-derived UUIDs and "
                    + "will not match premium accounts on login");
        }

        updateTables();
        saveAsync();
    }
    
    /**
     * Load admins from SQL database.
     */
    private void loadFromSql()
    {
        try
        {
            AdminRepository repo = plugin.dm.getAdminRepository();
            List<Admin> admins = repo.findAll().join();
            
            allAdmins.clear();
            for (Admin admin : admins)
            {
                String key = admin.getName().toLowerCase();
                admin = fixConfigKey(admin, key);
                allAdmins.put(key, admin);
            }
            
            usingSql = true;
            updateTables();
            FLog.info("Loaded " + allAdmins.size() + " admins from SQL database (" + nameTable.size() + " active, " + ipTable.size() + " IPs)");
        }
        catch (Exception ex)
        {
            FLog.warning("Failed to load admins from SQL, falling back to YAML: " + ex.getMessage());
            loadFromYaml();
        }
    }
    
    /**
     * Fix the config key on an admin (needed when loading from SQL).
     */
    private Admin fixConfigKey(Admin admin, String key)
    {
        // Use reflection or create new admin to set configKey
        // Since configKey is private with no setter, we need to recreate
        if (admin.getConfigKey() == null || !admin.getConfigKey().equals(key))
        {
            Admin fixed = new Admin(key);
            fixed.setUuid(admin.getUuid());
            fixed.setName(admin.getName());
            fixed.setRank(admin.getRank());
            fixed.setActive(admin.isActive());
            fixed.setLastLogin(admin.getLastLogin());
            fixed.setLoginMessage(admin.getLoginMessage());
            fixed.setCustomRankId(admin.getCustomRankId());
            fixed.addIps(admin.getIps());
            return fixed;
        }
        return admin;
    }
    
    /**
     * Load admins from YAML file (fallback).
     */
    private void loadFromYaml()
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
        config = YamlConfiguration.loadConfiguration(configFile);

        allAdmins.clear();
        for (String key : config.getKeys(false))
        {
            ConfigurationSection section = config.getConfigurationSection(key);
            if (section == null)
            {
                FLog.warning("Invalid admin list format: " + key);
                continue;
            }

            Admin admin = new Admin(key);
            admin.loadFrom(section);

            if (!admin.isValid())
            {
                FLog.warning("Could not load admin: " + key + ". Missing details!");
                continue;
            }

            allAdmins.put(key, admin);
        }

        usingSql = false;
        updateTables();
        FLog.info("Loaded " + allAdmins.size() + " admins from YAML (" + nameTable.size() + " active, " + ipTable.size() + " IPs)");
    }

    public synchronized void save()
    {
        if (usingSql)
        {
            saveToSql();
        }
        else
        {
            saveToYaml();
        }
    }

    /**
     * Persist admin records on a worker thread. Use this on the hot login path
     */
    public void saveAsync()
    {
        if (usingSql)
        {
            for (Admin admin : List.copyOf(allAdmins.values()))
            {
                saveAdminAsync(admin);
            }
            return;
        }

        if (!plugin.isEnabled())
        {
            saveToYaml();
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
        {
            synchronized (AdminList.this)
            {
                saveToYaml();
            }
        });
    }

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

        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning("SQL not available; admin change was not saved for " + admin.getName());
            return;
        }

        Admin snapshot = copyAdmin(admin);
        UUID uuid = snapshot.getUuid();

        if (uuid == null)
        {
            uuid = FUtil.usernameToUuid(snapshot.getName());
            if (uuid == null)
            {
                uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + snapshot.getName().toLowerCase()).getBytes(StandardCharsets.UTF_8));
            }
            snapshot.setUuid(uuid);
            admin.setUuid(uuid);
        }

        UUID finalUuid = uuid;
        synchronized (persistenceLock)
        {
            persistenceChain = persistenceChain
                    .handle((ignored, throwable) -> null)
                    .thenCompose(ignored -> plugin.dm.getAdminRepository().save(finalUuid, snapshot).thenAccept(id ->
                    {
                    }))
                    .exceptionally(ex ->
                    {
                        FLog.warning("Failed to save admin " + snapshot.getName() + " to SQL: " + ex.getMessage());
                        return null;
                    });
        }
    }

    private Admin copyAdmin(Admin admin)
    {
        Admin copy = new Admin(admin.getConfigKey());
        copy.setUuid(admin.getUuid());
        copy.setName(admin.getName());
        copy.setRank(admin.getRank());
        copy.setActive(admin.isActive());
        copy.setLastLogin(admin.getLastLogin() == null ? null : new Date(admin.getLastLogin().getTime()));
        copy.setLoginMessage(admin.getLoginMessage());
        copy.setCustomRankId(admin.getCustomRankId());
        copy.addIps(new ArrayList<>(admin.getIps()));
        return copy;
    }
    
    /**
     * Save all admins to SQL database.
     */
    private void saveToSql()
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            FLog.warning("SQL not available, falling back to YAML save");
            saveToYaml();
            return;
        }
        
        try
        {
            AdminRepository repo = plugin.dm.getAdminRepository();
            for (Admin admin : allAdmins.values())
            {
                UUID uuid = admin.getUuid();
                if (uuid == null)
                {
                    // Generate UUID if not present
                    uuid = FUtil.usernameToUuid(admin.getName());
                    if (uuid == null)
                    {
                        uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + admin.getName().toLowerCase()).getBytes());
                    }
                    admin.setUuid(uuid);
                }
                repo.save(uuid, admin).join();
            }
            FLog.debug("Saved " + allAdmins.size() + " admins to SQL database");
        }
        catch (Exception ex)
        {
            FLog.warning("Failed to save admins to SQL: " + ex.getMessage());
            // Don't fall back to YAML here - we don't want to create conflicting data
        }
    }
    
    /**
     * Save all admins to YAML file (fallback).
     */
    private void saveToYaml()
    {
        // Clear the config
        for (String key : config.getKeys(false))
        {
            config.set(key, null);
        }

        for (Admin admin : allAdmins.values())
        {
            ConfigurationSection section = config.createSection(admin.getConfigKey());
            admin.saveTo(section);
        }

        try
        {
            config.save(configFile);
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save " + CONFIG_FILENAME);
        }
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

    public boolean isSeniorAdmin(CommandSender sender)
    {
        Admin admin = getAdmin(sender);
        if (admin == null)
        {
            return false;
        }

        return admin.getRank().ordinal() >= Rank.SENIOR_ADMIN.ordinal();
    }

    public Admin getAdmin(CommandSender sender)
    {
        if (sender instanceof Player)
        {
            return getAdmin((Player) sender);
        }

        return getEntryByName(sender.getName());
    }

    public Admin getAdmin(Player player)
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
                String oldKey = uuidAdmin.getName().toLowerCase();
                uuidAdmin.setName(player.getName());
                String newKey = uuidAdmin.getName().toLowerCase();
                if (!oldKey.equals(newKey))
                {
                    nameTable.remove(oldKey);
                    nameTable.put(newKey, uuidAdmin);
                }
                saveAsync();
            }
            return uuidAdmin;
        }

        // Find admin
        String ip = player.getAddress().getAddress().getHostAddress();
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
                    saveAsync();
                }
                return admin;
            }

            // Impostor
        }

        // Admin by ip
        admin = getEntryByIp(ip);
        if (admin != null)
        {
            // Set the new username
            String oldKey = admin.getName().toLowerCase();
            admin.setName(player.getName());
            String newKey = admin.getName().toLowerCase();
            if (!oldKey.equals(newKey))
            {
                nameTable.remove(oldKey);
                if (admin.isActive())
                {
                    nameTable.put(newKey, admin);
                }
            }
            saveAsync();
        }

        return null;
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

        for (String ip : ipTable.keySet())
        {
            if (FUtil.fuzzyIpMatch(needleIp, ip, 3))
            {
                return ipTable.get(ip);
            }
        }

        return null;
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
            saveAsync();
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
        return admin == null ? false : admin.getName().equalsIgnoreCase(player.getName());
    }

    public boolean addAdmin(Admin admin)
    {
        if (!admin.isValid())
        {
            FLog.warning("Could not add admin: " + admin.getConfigKey() + " Admin is missing details!");
            return false;
        }

        final String key = admin.getConfigKey();

        // Store admin, update views
        allAdmins.put(key, admin);
        updateTables();

        // Save admin
        if (usingSql)
        {
            saveAdminToSql(admin);
        }
        else
        {
            admin.saveTo(config.createSection(key));
            try
            {
                config.save(configFile);
            }
            catch (IOException ex)
            {
                FLog.severe("Could not save " + CONFIG_FILENAME);
            }
        }

        refreshWorldEditBypassForAdmin(admin);
        return true;
    }

    /**
     * Save a single admin to SQL database.
     */
    private void saveAdminToSql(Admin admin)
    {
        saveAdminAsync(admin);
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
            config.set(admin.getConfigKey(), null);
            try
            {
                config.save(configFile);
            }
            catch (IOException ex)
            {
                FLog.severe("Could not save " + CONFIG_FILENAME);
            }
        }

        refreshWorldEditBypassForAdmin(admin);
        return true;
    }

    private void refreshWorldEditBypassForAdmin(Admin admin)
    {
        if (plugin.web == null)
        {
            return;
        }
        try
        {
            org.bukkit.entity.Player online = null;
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
            FLog.warning("Failed to refresh WorldEdit bypass negation: " + t.getMessage());
        }
    }

    /**
     * Remove admin from SQL database.
     */
    private void removeAdminFromSql(Admin admin)
    {
        if (plugin.dm == null || !plugin.dm.isInitialized())
        {
            return;
        }

        UUID uuid = admin.getUuid();
        String name = admin.getName();

        synchronized (persistenceLock)
        {
            persistenceChain = persistenceChain
                    .handle((ignored, throwable) -> null)
                    .thenCompose(ignored ->
                    {
                        if (uuid != null)
                        {
                            return plugin.dm.getAdminRepository().deleteByUuid(uuid).thenAccept(deleted ->
                            {
                            });
                        }

                        return CompletableFuture.runAsync(() ->
                        {
                            try
                            {
                                plugin.dm.getAdminRepository().deleteByUsername(name);
                            }
                            catch (Exception ex)
                            {
                                throw new RuntimeException(ex);
                            }
                        });
                    })
                    .exceptionally(ex ->
                    {
                        FLog.warning("Failed to remove admin " + name + " from SQL: " + ex.getMessage());
                        return null;
                    });
        }
    }

    public void updateTables()
    {
        activeAdmins.clear();
        nameTable.clear();
        ipTable.clear();
        uuidTable.clear();
        onlineAdminPlayers.clear();

        for (Admin admin : allAdmins.values())
        {
            // Always populate UUID table
            if (admin.getUuid() != null)
            {
                uuidTable.put(admin.getUuid(), admin);
            }
            
            if (!admin.isActive())
            {
                continue;
            }

            activeAdmins.add(admin);
            nameTable.put(admin.getName().toLowerCase(), admin);

            for (String ip : admin.getIps())
            {
                ipTable.put(ip, admin);
            }

        }

        // Re-populate online-admin cache from currently-online players.
        for (Player online : Bukkit.getOnlinePlayers())
        {
            if (isAdmin(online))
            {
                onlineAdminPlayers.add(online);
            }
        }

        plugin.wm.adminworld.wipeAccessCache();

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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdminManagement(AdminManagementEvent event)
    {
        plugin.al.updateTables();
        plugin.al.saveAdminAsync(event.getAdmin());
    }

    public void deactivateOldEntries(boolean verbose)
    {
        for (Admin admin : allAdmins.values())
        {
            if (!admin.isActive() || admin.getRank().isAtLeast(Rank.SENIOR_ADMIN))
            {
                continue;
            }

            final Date lastLogin = admin.getLastLogin();
            final long lastLoginHours = TimeUnit.HOURS.convert(new Date().getTime() - lastLogin.getTime(), TimeUnit.MILLISECONDS);

            if (lastLoginHours < ConfigEntry.ADMINLIST_CLEAN_THESHOLD_HOURS.getInteger())
            {
                continue;
            }

            if (verbose)
            {
                FUtil.adminAction("TotalFreedomMod", "Deactivating superadmin " + admin.getName() + ", inactive for " + lastLoginHours + " hours", true);
            }

            admin.setActive(false);
            saveAdminAsync(admin);
        }

        updateTables();
    }
}
