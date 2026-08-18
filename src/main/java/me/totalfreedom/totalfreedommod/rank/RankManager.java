package me.totalfreedom.totalfreedommod.rank;

import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import me.totalfreedom.totalfreedommod.FreedomService;
import me.totalfreedom.totalfreedommod.TotalFreedomMod;
import me.totalfreedom.totalfreedommod.admin.Admin;
import me.totalfreedom.totalfreedommod.config.ConfigEntry;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchContext;
import me.totalfreedom.totalfreedommod.dispatch.RemoteDispatchSession;
import me.totalfreedom.totalfreedommod.player.FPlayer;
import me.totalfreedom.totalfreedommod.util.AdventureUtil;
import me.totalfreedom.totalfreedommod.util.FLog;
import me.totalfreedom.totalfreedommod.util.FTask;
import me.totalfreedom.totalfreedommod.util.FUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.GameMode;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

public class RankManager extends FreedomService
{
    private static final int CURRENT_RANKS_SCHEMA_VERSION = 2;
    private static final Set<String> ADMIN_BLOCK_PERMISSIONS = Set.of(
            "tfm.admin.block.inspect",
            "tfm.admin.block.modify");
    private static final String PLAYER_BLOCK_PERMISSION = "tfm.player.block";
    private static final String RANKS_SCHEMA_VERSION_KEY = "schema_version";
    private static final Set<String> PLAYER_BLOCK_DEFAULT_RANKS = Set.of(
            "non_op",
            "op",
            "super_admin",
            "senior_admin",
            "senior_console",
            "developer",
            "owner",
            "executive");
    private static final Set<String> ADMIN_BLOCK_DEFAULT_RANKS = Set.of(
            "super_admin",
            "senior_admin",
            "senior_console",
            "developer",
            "owner",
            "executive");

    public static final String RANKS_FILENAME = "ranks.yml";

    /**
     * All custom ranks, keyed by ID.
     */
    private final Map<String, CustomRank> customRanks = Maps.newLinkedHashMap();

    /**
     * File for storing custom ranks.
     */
    private File ranksFile;

    /**
     * YAML configuration for ranks.
     */
    private YamlConfiguration ranksConfig;

    private int ranksSchemaVersion;

    /**
     * Chat input handler for interactive menus.
     */
    private final ChatInputHandler chatInputHandler = new ChatInputHandler();

    public RankManager(TotalFreedomMod plugin)
    {
        super(plugin);
    }

    private BukkitRunnable persistentMonitorTask = null;

    @Override
    protected void onStart()
    {
        // Load custom ranks
        loadRanks();

        // The console registry is built during onEnable, before any service starts, so its first
        // read happened with no ranks in memory and every binding naming a custom rank was thrown
        // away as unknown. Re-read it now that the ranks exist, which is also what lets the
        // host-channel floor compare against real rank levels.
        if (plugin.csr != null)
        {
            plugin.csr.load();
        }

        server.getScheduler().runTask(plugin, this::updateAllPlayerTeams);

        // Start persistent monitor if enabled
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && ConfigEntry.AUTO_OP_PERSISTENT_MONITOR.getBoolean())
        {
            startPersistentMonitor();
        }
    }

    @Override
    protected void onStop()
    {
        // Save ranks before shutdown
        saveRanks();

        // Stop persistent monitor
        if (persistentMonitorTask != null)
        {
            persistentMonitorTask.cancel();
            persistentMonitorTask = null;
        }

        // Clear chat input handlers
        chatInputHandler.clearAll();
    }

    /**
     * Load custom ranks from ranks.yml.
     */
    public void loadRanks()
    {
        ranksFile = new File(plugin.getDataFolder(), RANKS_FILENAME);

        if (!ranksFile.exists())
        {
            createDefaultRanks();
            migrateConfigRanks();
            return;
        }

        ranksConfig = YamlConfiguration.loadConfiguration(ranksFile);
        ranksSchemaVersion = ranksConfig.getInt(RANKS_SCHEMA_VERSION_KEY, 0);
        customRanks.clear();

        for (String key : ranksConfig.getKeys(false))
        {
            ConfigurationSection section = ranksConfig.getConfigurationSection(key);
            if (section == null) continue;

            CustomRank rank = new CustomRank(key);
            rank.loadFrom(section);
            customRanks.put(key.toLowerCase(), rank);
        }

        validateEssentialRanks();
        migrateRanksSchema();
        resolveInheritance();
        updateAllPlayerTeams();
        FLog.info("Loaded " + customRanks.size() + " custom ranks.");

    }

    private static final String[] ESSENTIAL_RANKS = {
            "non_op", "op", "super_admin", "senior_admin"
    };

    private void validateEssentialRanks()
    {
        boolean modified = false;
        for (String rankId : ESSENTIAL_RANKS)
        {
            if (!customRanks.containsKey(rankId))
            {
                FLog.warning("Essential rank '" + rankId + "' missing from ranks.yml, recreating...");
                Rank legacyRank = Rank.findRank(rankId);
                CustomRank custom = createDefaultRank(legacyRank);
                customRanks.put(rankId, custom);
                modified = true;
            }
        }
        if (modified)
        {
            saveRanks();
            FLog.info("Repaired ranks.yml with missing essential ranks.");
        }
    }

    /**
     * Create default ranks from the legacy Rank enum.
     */
    private void createDefaultRanks()
    {
        customRanks.clear();
        ranksSchemaVersion = CURRENT_RANKS_SCHEMA_VERSION;

        for (Rank legacyRank : Rank.values())
        {
            CustomRank custom = createDefaultRank(legacyRank);
            customRanks.put(custom.getId(), custom);
        }

        resolveInheritance();
        saveRanks();
        FLog.info("Created default ranks configuration.");
    }

    private CustomRank createDefaultRank(final Rank legacyRank)
    {
        final CustomRank custom = CustomRank.fromLegacyRank(legacyRank);

        switch (legacyRank)
        {
            case SENIOR_ADMIN, SENIOR_CONSOLE ->
            {
                custom.addPermission("tfm.manage.ranks");
                custom.addPermission("tfm.admin.senior");
                custom.addPermission("tfm.admin.telnet");
                custom.addPermission("tfm.admin.ban.perm");
                addSuperAdminPermissions(custom);
            }
            case SUPER_ADMIN -> addSuperAdminPermissions(custom);
            case OP -> custom.addPermission("tfm.player.op");
            case NON_OP -> custom.addPermission(PLAYER_BLOCK_PERMISSION);
            default -> {}
        }

        custom.setInheritFrom(switch (legacyRank)
        {
            case OP -> "non_op";
            case SUPER_ADMIN -> "op";
            case SENIOR_ADMIN -> "super_admin";
            case SENIOR_CONSOLE -> "senior_admin";
            default -> null;
        });
        return custom;
    }

    private void addSuperAdminPermissions(final CustomRank rank)
    {
        Stream.of(
                "tfm.admin.ban",
                "tfm.admin.kick",
                "tfm.admin.mute",
                "tfm.admin.freeze",
                "tfm.admin.cage",
                "tfm.admin.block.inspect",
                "tfm.admin.block.modify",
                "tfm.fun.smite",
                "tfm.fun.doom",
                "tfm.world.gamerule")
                .forEach(rank::addPermission);
    }

    private void migrateRanksSchema()
    {
        if (ranksSchemaVersion >= CURRENT_RANKS_SCHEMA_VERSION)
            return;

        if (ranksSchemaVersion < 1)
        {
            addMissingDefaultPermissions(
                    PLAYER_BLOCK_DEFAULT_RANKS,
                    Set.of(PLAYER_BLOCK_PERMISSION));
        }

        if (ranksSchemaVersion < 2)
        {
            addMissingDefaultPermissions(
                    ADMIN_BLOCK_DEFAULT_RANKS,
                    ADMIN_BLOCK_PERMISSIONS);
        }

        ranksSchemaVersion = CURRENT_RANKS_SCHEMA_VERSION;
        saveRanks();
        FLog.info("Migrated ranks.yml to schema version " + CURRENT_RANKS_SCHEMA_VERSION + ".");
    }

    private void addMissingDefaultPermissions(
            final Set<String> rankIds,
            final Set<String> permissions)
    {
        rankIds.stream()
                .map(customRanks::get)
                .filter(java.util.Objects::nonNull)
                .filter(rank -> !rank.getPermissions().contains("*"))
                .forEach(rank -> permissions.stream()
                        .filter(permission -> !rank.getPermissions().contains(permission))
                        .forEach(rank::addPermission));
    }

    private void migrateConfigRanks()
    {
        applyConfigPrefix("impostor", ConfigEntry.VAULT_PREFIX_IMPOSTOR);
        applyConfigPrefix("non_op", ConfigEntry.VAULT_PREFIX_NON_OP);
        applyConfigPrefix("op", ConfigEntry.VAULT_PREFIX_OP);
        applyConfigPrefix("super_admin", ConfigEntry.VAULT_PREFIX_SUPER_ADMIN);
        applyConfigPrefix("senior_admin", ConfigEntry.VAULT_PREFIX_SENIOR_ADMIN);
        applyConfigPrefix("senior_console", ConfigEntry.VAULT_PREFIX_SENIOR_CONSOLE);
        applyConfigPrefix("developer", ConfigEntry.VAULT_PREFIX_DEVELOPER);
        applyConfigPrefix("owner", ConfigEntry.VAULT_PREFIX_OWNER);

        List<String> owners = ConfigEntry.SERVER_OWNERS.getStringList();
        if (owners != null && !owners.isEmpty())
        {
            int found = 0;
            for (String ownerName : owners)
            {
                if (ownerName != null && !ownerName.trim().isEmpty())
                {
                    if (plugin.al.getEntryByName(ownerName.trim()) != null)
                    {
                        found++;
                    }
                }
            }
            if (found > 0)
            {
                FLog.info("Found " + found + " owner(s) from config.yml. They will display with the owner rank.");
            }
        }

        saveRanks();
        removeConfigRanks();
        FLog.info("Migrated rank configuration from config.yml to ranks.yml.");
    }

    private void applyConfigPrefix(String rankId, ConfigEntry entry)
    {
        String prefix = entry.getString();
        if (prefix != null && !prefix.isEmpty())
        {
            CustomRank rank = getCustomRank(rankId);
            if (rank != null)
            {
                rank.setPrefix(prefix);
            }
        }
    }

    private void removeConfigRanks()
    {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists())
        {
            return;
        }

        try
        {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
            boolean modified = false;

            if (config.contains("server.owners"))
            {
                config.set("server.owners", null);
                modified = true;
            }

            String[] prefixKeys = {
                    "chat.prefix.impostor", "chat.prefix.non_op", "chat.prefix.op",
                    "chat.prefix.super_admin", "chat.prefix.senior_admin",
                    "chat.prefix.senior_console",
                    "chat.prefix.developer", "chat.prefix.owner"
            };

            for (String key : prefixKeys)
            {
                if (config.contains(key))
                {
                    config.set(key, null);
                    modified = true;
                }
            }

            ConfigurationSection prefixSection = config.getConfigurationSection("chat.prefix");
            if (prefixSection != null && prefixSection.getKeys(false).isEmpty())
            {
                config.set("chat.prefix", null);
            }

            if (modified)
            {
                config.save(configFile);
            }
        }
        catch (IOException ex)
        {
            FLog.warning("Could not update config.yml: " + ex.getMessage());
        }
    }

    /**
     * Save custom ranks to ranks.yml.
     */
    public void saveRanks()
    {
        if (ranksFile == null)
        {
            ranksFile = new File(plugin.getDataFolder(), RANKS_FILENAME);
        }

        ranksConfig = new YamlConfiguration();
        ranksConfig.set(RANKS_SCHEMA_VERSION_KEY, ranksSchemaVersion);

        for (CustomRank rank : customRanks.values())
        {
            ConfigurationSection section = ranksConfig.createSection(rank.getId());
            rank.saveTo(section);
        }

        try
        {
            ranksConfig.save(ranksFile);
        }
        catch (IOException ex)
        {
            FLog.severe("Could not save " + RANKS_FILENAME + ": " + ex.getMessage());
        }

    }

    private void resolveInheritance()
    {
        for (CustomRank rank : customRanks.values())
        {
            Set<String> resolved = collectPermissions(rank, new HashSet<>());
            rank.setResolvedPermissions(resolved);
        }
    }

    private Set<String> collectPermissions(CustomRank rank, Set<String> visited)
    {
        if (rank == null) return Set.of();

        if (visited.contains(rank.getId()))
        {
            FLog.warning("Circular inheritance detected for rank: " + rank.getId());
            return Set.of();
        }
        visited.add(rank.getId());

        Set<String> perms = new HashSet<>(rank.getPermissions());

        if (rank.getInheritFrom() != null)
        {
            CustomRank parent = customRanks.get(rank.getInheritFrom().toLowerCase());
            if (parent == null)
            {
                FLog.warning("Rank '" + rank.getId() + "' inherits from non-existent rank: " + rank.getInheritFrom());
            }
            else
            {
                perms.addAll(collectPermissions(parent, visited));
            }
        }

        return perms;
    }

    public CustomRank getCustomRank(String id)
    {
        if (id == null)
        {
            return null;
        }
        return customRanks.get(id.toLowerCase());
    }


    private CustomRank getAssignedAdminRank(Player player)
    {
        if (plugin.al.isAdminImpostor(player))
        {
            return null;
        }

        Admin admin = plugin.al.getAdmin(player);

        if (admin == null || !admin.isActive())
        {
            return null;
        }

        if (admin.getCustomRankId() != null)
        {
            CustomRank customRank = getCustomRank(admin.getCustomRankId());

            if (customRank != null)
            {
                return customRank;
            }
        }

        return getCustomRankForLegacy(admin.getRank());
    }

    public void updatePlayerTeam(Player player)
    {
        ScoreboardManager manager = server.getScoreboardManager();

        if (manager == null)
        {
            return;
        }

        Scoreboard scoreboard = manager.getMainScoreboard();
        Team currentTeam = scoreboard.getEntryTeam(player.getName());
        CustomRank rank = getAssignedAdminRank(player);
        final boolean admin = rank != null && rank.isAdmin();

        if (rank == null)
        {
            rank = CustomRank.fromLegacyRank(Rank.OP); // potential NPE, averting by setting to OP, should be an optional but that's outside of the scope. Rank system will get it's own dedicated branch scope. 
        }

        final String teamName = admin ? createTeamName(rank) : DEFAULT_TEAM_NAME;

        if (currentTeam != null && !currentTeam.getName().equals(teamName))
        {
            currentTeam.removeEntry(player.getName());
        }

        Team team = scoreboard.getTeam(teamName);

        if (team == null)
        {
            team = scoreboard.registerNewTeam(teamName);
        }

        team.color(admin ? rank.getColor() : NamedTextColor.WHITE);
        team.prefix(Component.empty());
        team.addEntry(player.getName());
    }

    private static final String DEFAULT_TEAM_NAME = "zz_default";

    private String createTeamName(CustomRank rank)
    {
        final int level = Math.max(0, Math.min(99, rank.getLevel()));
        String name = String.format("%02d_%s", 99 - level,
                rank.getId().replaceAll("[^A-Za-z0-9_\\-]", "_"));

        if (name.length() > 16)
        {
            name = name.substring(0, 16);
        }

        return name;
    }

    public void updateAllPlayerTeams()
    {
        for (Player player : server.getOnlinePlayers())
        {
            updatePlayerTeam(player);
        }
    }

    /**
     * Get all custom ranks.
     */
    public Map<String, CustomRank> getCustomRanks()
    {
        return customRanks;
    }

    /**
     * Get custom ranks sorted by level.
     */
    public List<CustomRank> getCustomRanksSorted()
    {
        List<CustomRank> sorted = new ArrayList<>(customRanks.values());
        sorted.sort(Comparator.comparingInt(CustomRank::getLevel));
        return sorted;
    }

    public void setCustomRank(CustomRank rank)
    {
        customRanks.put(rank.getId(), rank);
        resolveInheritance();
        saveRanks();
        updateAllPlayerTeams();
    }

    /**
     * Remove a custom rank.
     */
    public boolean removeCustomRank(String id)
    {
        CustomRank removed = customRanks.remove(id.toLowerCase());
        if (removed != null)
        {
            saveRanks();
            updateAllPlayerTeams();
            return true;
        }
        return false;
    }

    /**
     * Check if a custom rank exists.
     */
    public boolean hasCustomRank(String id)
    {
        return customRanks.containsKey(id.toLowerCase());
    }

    // ========================================================================
    // Permission System (Internal, NOT Bukkit-based)
    // ========================================================================

    /**
     * Check if a sender has a specific TFM permission.
     * This does NOT use Bukkit permission nodes - it's purely internal.
     *
     * @param sender The command sender
     * @param permission The TFM permission string (e.g., "tfm.admin.ban")
     * @return true if the sender has the permission
     */
    public boolean hasPermission(CommandSender sender, String permission)
    {
        if (!(sender instanceof Player))
        {
            if (sender instanceof BlockCommandSender || sender instanceof CommandMinecart)
            {
                return false;
            }

            // getEffectiveRank already knows how this sender earned its rank. An identified SSH or
            // Discord session resolves to that admin's own profile; a host channel resolves to its
            // binding. That covers what the dispatch-excluded lookup here used to miss, namely
            // that a remote user's custom rank was invisible to the permission check.
            CustomRank effective = getEffectiveRank(sender);
            if (effective != null && hasCustomRankPermission(effective, permission))
            {
                return true;
            }

            Rank rank = getRank(sender);
            CustomRank customRank = getCustomRankForLegacy(rank);
            if (customRank != null && hasCustomRankPermission(customRank, permission))
            {
                return true;
            }
            return checkLegacyPermission(rank, permission);
        }

        Player player = (Player) sender;

        // Check if admin
        Admin admin = plugin.al.getAdmin(player);
        if (admin != null && admin.isActive())
        {
            // Try custom rank ID assigned to the admin first
            if (admin.getCustomRankId() != null)
            {
                CustomRank custom = getCustomRank(admin.getCustomRankId());
                if (custom != null)
                {
                    if (hasCustomRankPermission(custom, permission))
                    {
                        return true;
                    }
                }
            }

            // Fallback to custom rank derived from legacy rank
            CustomRank customRank = getCustomRankForLegacy(admin.getRank());
            if (customRank != null)
            {
                if (hasCustomRankPermission(customRank, permission))
                {
                    return true;
                }
            }

            // Legacy fallback: check rank level
            return checkLegacyPermission(admin.getRank(), permission);
        }

        // Resolve ordinary players through the same identity-aware legacy rank path used by
        // the rest of the rank system, so admin impostors cannot inherit player permissions.
        final CustomRank playerRank = getCustomRankForLegacy(getRank(player));
        return playerRank != null && hasCustomRankPermission(playerRank, permission);
    }

    private boolean hasCustomRankPermission(CustomRank rank, String permission)
    {
        if (rank.hasPermission(permission))
        {
            return true;
        }

        String[] parts = permission.split("\\.");
        StringBuilder wildcard = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++)
        {
            wildcard.append(parts[i]).append(".");
            if (rank.hasPermission(wildcard + "*"))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Check permission based on legacy rank level.
     */
    private boolean checkLegacyPermission(Rank rank, String permission)
    {
        // Map common permissions to rank levels
        if (permission.startsWith("tfm.manage."))
        {
            return rank.isAtLeast(Rank.SENIOR_ADMIN);
        }
        if (permission.startsWith("tfm.admin.senior") || permission.equals("tfm.admin.ban.perm"))
        {
            return rank.isAtLeast(Rank.SENIOR_ADMIN);
        }
        if (permission.startsWith("tfm.admin.telnet"))
        {
            return rank.isAtLeast(Rank.SENIOR_ADMIN);
        }
        if (permission.startsWith("tfm.admin."))
        {
            return rank.isAtLeast(Rank.SUPER_ADMIN);
        }
        if (permission.startsWith("tfm.fun."))
        {
            return rank.isAtLeast(Rank.SUPER_ADMIN);
        }
        return false;
    }

    /**
     * Get the custom rank that corresponds to a legacy Rank enum.
     */
    public CustomRank getCustomRankForLegacy(Rank legacyRank)
    {
        return getCustomRank(legacyRank.name().toLowerCase());
    }

    /**
     * Check if sender has permission to manage ranks.
     */
    public boolean canManageRanks(CommandSender sender)
    {
        return hasPermission(sender, "tfm.manage.ranks");
    }

    // ========================================================================
    // Chat Input Handler (Inner Class)
    // ========================================================================

    /**
     * Get the chat input handler for interactive menus.
     */
    public ChatInputHandler getChatInputHandler()
    {
        return chatInputHandler;
    }

    /**
     * Inner class that handles chat input for interactive configuration menus.
     * Players can be registered to have their next chat message captured.
     */
    public class ChatInputHandler
    {
        /**
         * Map of player UUIDs to their pending input handlers.
         */
        private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();

        /**
         * Register a player to capture their next chat message.
         *
         * @param player The player
         * @param prompt The prompt to show the player
         * @param callback The callback to invoke with the input
         * @param timeoutSeconds How long to wait before expiring (0 = no timeout)
         */
        public void awaitInput(Player player, Component prompt, Consumer<String> callback, int timeoutSeconds)
        {
            UUID uuid = player.getUniqueId();

            // Cancel any existing pending input
            cancelInput(player);

            // Send prompt
            player.sendMessage(Component.empty());
            player.sendMessage(prompt);
            player.sendMessage(Component.text("Type your response in chat, or type 'cancel' to abort.")
                    .color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC));

            // Register pending input
            PendingInput pending = new PendingInput(callback, System.currentTimeMillis());
            pendingInputs.put(uuid, pending);

            // Schedule timeout if specified
            if (timeoutSeconds > 0)
            {
                new BukkitRunnable()
                {
                    @Override
                    public void run()
                    {
                        PendingInput current = pendingInputs.get(uuid);
                        if (current == pending)
                        {
                            pendingInputs.remove(uuid);
                            Player p = server.getPlayer(uuid);
                            if (p != null && p.isOnline())
                            {
                                p.sendMessage(Component.text("Input timed out.").color(NamedTextColor.RED));
                            }
                        }
                    }
                }.runTaskLater(plugin, timeoutSeconds * 20L);
            }
        }

        /**
         * Cancel pending input for a player.
         */
        public void cancelInput(Player player)
        {
            pendingInputs.remove(player.getUniqueId());
        }

        /**
         * Check if a player has pending input.
         */
        public boolean hasPendingInput(Player player)
        {
            return pendingInputs.containsKey(player.getUniqueId());
        }

        /**
         * Process a chat message from a player.
         *
         * @param player The player
         * @param message The chat message
         * @return true if the message was consumed (pending input), false otherwise
         */
        public boolean processChat(Player player, String message)
        {
            UUID uuid = player.getUniqueId();
            PendingInput pending = pendingInputs.remove(uuid);

            if (pending == null)
            {
                return false;
            }

            // Check for cancel
            if (message.equalsIgnoreCase("cancel"))
            {
                player.sendMessage(Component.text("Input cancelled.").color(NamedTextColor.YELLOW));
                return true;
            }

            // Invoke callback
            try
            {
                pending.callback().accept(message);
            }
            catch (Exception ex)
            {
                player.sendMessage(Component.text("Error processing input: " + ex.getMessage()).color(NamedTextColor.RED));
                FLog.warning("Error in chat input callback: " + ex.getMessage());
            }

            return true;
        }

        /**
         * Clear all pending inputs.
         */
        public void clearAll()
        {
            pendingInputs.clear();
        }

        /**
         * Inner class representing pending input.
         */
        private record PendingInput(Consumer<String> callback, long timestamp)
        {
        }
    }

    // ========================================================================
    // Chat Event Handler (for input capture)
    // ========================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event)
    {
        Player player = event.getPlayer();

        // Check if this player has pending input
        if (chatInputHandler.hasPendingInput(player))
        {
            // Extract plain text from the Component message
            final String message = PlainTextComponentSerializer.plainText().serialize(event.message());

            // Process on main thread to avoid async issues
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    chatInputHandler.processChat(player, message);
                }
            }.runTask(plugin);

            // Cancel the chat event so the message isn't broadcast
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        // Clean up pending inputs when player leaves
        chatInputHandler.cancelInput(event.getPlayer());

        ScoreboardManager manager = server.getScoreboardManager();

        if (manager != null)
        {
            Team team = manager.getMainScoreboard().getEntryTeam(event.getPlayer().getName());

            if (team != null)
            {
                team.removeEntry(event.getPlayer().getName());
            }
        }
    }

    // ========================================================================
    // Interactive Menu Builder (for /rankconfig)
    // ========================================================================

    /**
     * Build the main rank configuration menu.
     */
    public Component buildMainMenu()
    {
        Component builder = Component.empty();

        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.GOLD));
        builder.append(Component.text("\n"));
        builder.append(Component.text("         RANK CONFIGURATION").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.GOLD));
        builder.append(Component.text("\n\n"));

        // List ranks with edit buttons
        builder.append(Component.text("Ranks (sorted by level):").color(NamedTextColor.YELLOW));
        builder.append(Component.text("\n"));

        for (CustomRank rank : getCustomRanksSorted())
        {
            builder.append(Component.text("  • ").color(NamedTextColor.GRAY));
            builder.append(rank.getColoredTag());
            builder.append(Component.text(" ").append(rank.getColoredName()));
            builder.append(Component.text(" (Level " + rank.getLevel() + ")").color(NamedTextColor.DARK_GRAY));
            builder.append(Component.text(" "));

            // Edit button
            builder.append(Component.text("[Edit]")
                    .color(NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/rankconfig edit " + rank.getId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to edit " + rank.getName()))));

            builder.append(Component.text(" "));

            // Delete button
            builder.append(Component.text("[Delete]")
                    .color(NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/rankconfig delete " + rank.getId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to delete " + rank.getName()))));

            builder.append(Component.text("\n"));
        }

        builder.append(Component.text("\n"));

        // Actions
        builder.append(Component.text("[+ Create New Rank]")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/rankconfig create"))
                .hoverEvent(HoverEvent.showText(Component.text("Click to create a new rank"))));

        builder.append(Component.text("  "));

        builder.append(Component.text("[Reload]")
                .color(NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand("/rankconfig reload"))
                .hoverEvent(HoverEvent.showText(Component.text("Reload ranks from file"))));

        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.GOLD));
        builder.append(Component.text("\n"));

        return builder;
    }

    /**
     * Build the rank edit menu.
     */
    public Component buildEditMenu(CustomRank rank)
    {
        Component builder = Component.empty();

        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.AQUA));
        builder.append(Component.text("\n"));
        builder.append(Component.text("  Editing: ").color(NamedTextColor.WHITE));
        builder.append(rank.getColoredTag());
        builder.append(Component.text(" ").append(rank.getColoredName()));
        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.AQUA));
        builder.append(Component.text("\n\n"));

        builder.append(buildEditableProperty("Name", rank.getName(), "/rankconfig set " + rank.getId() + " name"));
        builder.append(buildEditableProperty("Abbreviation", rank.getAbbreviation(), "/rankconfig set " + rank.getId() + " abbreviation"));
        builder.append(buildEditableProperty("Prefix", rank.getPrefix() != null ? rank.getPrefix() : "(none)", "/rankconfig set " + rank.getId() + " prefix"));
        builder.append(buildEditableProperty("Level", String.valueOf(rank.getLevel()), "/rankconfig set " + rank.getId() + " level"));
        builder.append(buildEditableProperty("Color", rank.getColor().toString(), "/rankconfig set " + rank.getId() + " color"));
        builder.append(buildEditableProperty("Determiner", rank.getDeterminer(), "/rankconfig set " + rank.getId() + " determiner"));
        builder.append(buildEditableProperty("Is Admin", String.valueOf(rank.isAdmin()), "/rankconfig set " + rank.getId() + " admin"));
        builder.append(buildEditableProperty("Console Only", String.valueOf(rank.isConsoleOnly()), "/rankconfig set " + rank.getId() + " console"));
        builder.append(buildEditableProperty("Inherit From", rank.getInheritFrom() != null ? rank.getInheritFrom() : "(none)", "/rankconfig set " + rank.getId() + " inherit"));

        builder.append(Component.text("\n"));

        // Permissions section
        builder.append(Component.text("Permissions:").color(NamedTextColor.YELLOW));
        builder.append(Component.text(" "));
        builder.append(Component.text("[+ Add]")
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/rankconfig set " + rank.getId() + " addperm"))
                .hoverEvent(HoverEvent.showText(Component.text("Add a permission"))));
        builder.append(Component.text("\n"));

        if (rank.getPermissions().isEmpty())
        {
            builder.append(Component.text("  (none)").color(NamedTextColor.DARK_GRAY).decorate(TextDecoration.ITALIC));
            builder.append(Component.text("\n"));
        }
        else
        {
            for (String perm : rank.getPermissions())
            {
                builder.append(Component.text("  • ").color(NamedTextColor.GRAY));
                builder.append(Component.text(perm).color(NamedTextColor.WHITE));
                builder.append(Component.text(" "));
                builder.append(Component.text("[X]")
                        .color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/rankconfig set " + rank.getId() + " remperm " + perm))
                        .hoverEvent(HoverEvent.showText(Component.text("Remove this permission"))));
                builder.append(Component.text("\n"));
            }
        }

        builder.append(Component.text("\n"));
        builder.append(Component.text("[← Back to List]")
                .color(NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/rankconfig"))
                .hoverEvent(HoverEvent.showText(Component.text("Return to rank list"))));
        builder.append(Component.text("  "));
        builder.append(Component.text("[Save & Close]")
                .color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/rankconfig save"))
                .hoverEvent(HoverEvent.showText(Component.text("Save changes"))));
        builder.append(Component.text("\n"));
        builder.append(Component.text("═══════════════════════════════════════").color(NamedTextColor.AQUA));
        builder.append(Component.text("\n"));

        return builder;
    }

    /**
     * Build an editable property line.
     */
    private Component buildEditableProperty(String label, String value, String command)
    {
        return Component.text("  " + label + ": ").color(NamedTextColor.GRAY)
                .append(FUtil.colorizeWithLinks(value, NamedTextColor.WHITE))
                .append(Component.text(" "))
                .append(Component.text("[Edit]")
                        .color(NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to change " + label.toLowerCase()))))
                .append(Component.text("\n"));
    }

    // ========================================================================
    // Original RankManager Methods (preserved)
    // ========================================================================

    private void startPersistentMonitor()
    {
        final int interval = ConfigEntry.AUTO_OP_MONITOR_INTERVAL.getInteger();
        if (interval <= 0)
        {
            return;
        }

        persistentMonitorTask = new BukkitRunnable()
        {
            @Override
            public void run()
            {
                if (!ConfigEntry.AUTO_OP_ENABLED.getBoolean())
                {
                    cancel();
                    return;
                }

                FTask.run("RankManager/autoOpMonitor", () ->
                {
                    for (Player player : server.getOnlinePlayers())
                    {
                        // Skip admins and players who should not be OP
                        if (plugin.al.isAdmin(player) || plugin.al.isAdminImpostor(player))
                        {
                            continue;
                        }

                        // Re-OP players who lost OP status
                        if (!player.isOp())
                        {
                            ensureOp(player);
                        }
                    }
                });
            }
        };
        persistentMonitorTask.runTaskTimer(plugin, interval, interval);
    }

    /**
     * Ensures a player has OP status and aggressively refreshes permissions.
     * Used for auto-OP system to maintain OP status reliably.
     */
    private void ensureOp(Player player)
    {
        if (player == null || !player.isOnline())
        {
            return;
        }

        // Skip admins and impostors
        if (plugin.al.isAdmin(player) || plugin.al.isAdminImpostor(player))
        {
            return;
        }

        // Only ensure OP if auto-OP is enabled
        if (!ConfigEntry.AUTO_OP_ENABLED.getBoolean())
        {
            return;
        }

        // Set OP if not already set
        if (!player.isOp())
        {
            player.setOp(true);
        }

        // Aggressively refresh permissions immediately
        try
        {
            player.recalculatePermissions();
        }
        catch (Exception ex)
        {
            // Ignore - some plugins may throw exceptions
        }

        // Schedule multiple delayed recalculations to catch plugins that cache late
        // This ensures WorldEdit, Essentials, etc. pick up the OP status
        for (long delay : new long[]{2L, 5L, 10L, 20L}) // 100ms, 250ms, 500ms, 1s
        {
            new BukkitRunnable()
            {
                @Override
                public void run()
                {
                    if (player.isOnline() && !plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
                    {
                        try
                        {
                            player.recalculatePermissions();
                        }
                        catch (Exception ex)
                        {
                            // Ignore
                        }
                    }
                }
            }.runTaskLater(plugin, delay);
        }
    }

    public Displayable getDisplay(CommandSender sender)
    {
        if (!(sender instanceof Player))
        {
            Rank rank = getRank(sender);
            CustomRank custom = getCustomRankForLegacy(rank);
            return custom != null ? custom : rank;
        }

        final Player player = (Player) sender;

        if (plugin.al.isAdminImpostor(player))
        {
            CustomRank impostorRank = getCustomRank("impostor");
            return impostorRank != null ? impostorRank : Rank.IMPOSTOR;
        }

        if (FUtil.DEVELOPERS.contains(player.getName()))
        {
            CustomRank devRank = getCustomRank("developer");
            if (devRank != null) return devRank;
        }

        final Rank rank = getRank(player);

        if (ConfigEntry.SERVER_OWNERS.getList().contains(player.getName()))
        {
            CustomRank ownerRank = getCustomRank("owner");
            if (ownerRank != null) return ownerRank;
        }

        Admin admin = plugin.al.getAdmin(player);
        if (admin != null && admin.isActive() && admin.getCustomRankId() != null)
        {
            CustomRank custom = getCustomRank(admin.getCustomRankId());
            if (custom != null)
            {
                return custom;
            }
        }

        CustomRank customRank = getCustomRankForLegacy(rank);
        return customRank != null ? customRank : rank;
    }

    /**
     * Resolves the rank a sender actually acts at, as a {@link CustomRank}.
     * <p>
     * This is the identity-aware view the permission gate tests against, and it is the only place
     * that knows how a sender earns its rank:
     * <ul>
     *   <li>SSH and Discord carry a proven identity (an SSH public key, or a {@code discord_links}
     *       row), so an identified session resolves to that admin's own profile rank, custom rank
     *       included. A session that proved nothing, meaning password-only SSH, falls back to the
     *       channel's {@code host_senders:} binding, which grants no identity and so no profile.</li>
     *   <li>Host channels (RCON, RemoteBukkit, console) carry no identity at all and resolve to
     *       their binding, which {@link ConsoleSenderRegistry} floors at senior admin.</li>
     * </ul>
     * Returning a {@link CustomRank} rather than a {@link Rank} matters: custom ranks carry
     * operator-defined levels that need not line up with {@link Rank#ordinal()}, and an admin
     * holding {@code executive} or {@code owner} would otherwise be demoted to their legacy tier
     * the moment they acted through a console channel.
     *
     * @return the sender's effective rank, or {@code null} when no rank could be resolved (the
     *         caller should then fall back to {@link #getRank(CommandSender)} on the legacy scale)
     */
    public CustomRank getEffectiveRank(CommandSender sender)
    {
        if (sender instanceof Player player)
        {
            CustomRank assigned = getAssignedAdminRank(player);
            if (assigned != null)
            {
                return assigned;
            }
            return getCustomRankForLegacy(getRank(player));
        }

        if (sender instanceof BlockCommandSender || sender instanceof CommandMinecart)
        {
            return getCustomRankForLegacy(Rank.NON_OP);
        }

        RemoteDispatchSession dispatch = RemoteDispatchContext.getActiveSession();
        if (dispatch != null)
        {
            CustomRank identity = resolveDispatchIdentity(dispatch);
            if (identity != null)
            {
                return identity;
            }

            String channel = dispatch.getChannel() == RemoteDispatchSession.Channel.DISCORD ? "discord" : "ssh";
            return getBoundRank(channel);
        }

        Admin admin = plugin.al.getEntryByName(sender.getName());
        if (admin != null && admin.isActive())
        {
            return rankOf(admin);
        }

        CustomRank bound = getBoundRank(sender.getName());
        return bound != null ? bound : getCustomRankForLegacy(Rank.NON_OP);
    }

    /**
     * The admin behind an identified dispatch session, as a {@link CustomRank}, or {@code null}
     * when the session proved no identity or the name no longer maps to an active admin.
     * <p>
     * SSH additionally honours {@code ssh.inherit_rank}: with it off, even a public-key session is
     * held to the flat {@code host_senders:} tier.
     */
    private CustomRank resolveDispatchIdentity(RemoteDispatchSession dispatch)
    {
        if (!dispatch.isIdentified())
        {
            return null;
        }

        if (dispatch.getChannel() == RemoteDispatchSession.Channel.SSH
            && !ConfigEntry.SSH_INHERIT_RANK.getBoolean())
        {
            return null;
        }

        Admin admin = plugin.al.getEntryByName(dispatch.getUsername());
        return admin != null && admin.isActive() ? rankOf(admin) : null;
    }

    /**
     * An admin's rank, preferring the custom rank pinned to their profile over their legacy tier.
     */
    private CustomRank rankOf(Admin admin)
    {
        if (admin.getCustomRankId() != null)
        {
            CustomRank custom = getCustomRank(admin.getCustomRankId());
            if (custom != null)
            {
                return custom;
            }
        }
        return getCustomRankForLegacy(admin.getRank());
    }

    /**
     * The custom rank bound to a sender name by {@code host_senders:}, resolving a legacy rank id
     * through the registry so both naming styles work.
     */
    private CustomRank getBoundRank(String senderName)
    {
        String boundRankId = plugin.csr.getRankIdForSender(senderName);
        if (boundRankId == null)
        {
            return null;
        }

        CustomRank bound = getCustomRank(boundRankId);
        if (bound != null)
        {
            return bound;
        }

        Rank legacy = plugin.csr.getRankForSender(senderName);
        return legacy != null ? getCustomRankForLegacy(legacy) : null;
    }

    /**
     * Places a custom rank on the legacy ladder by level, so callers that still speak {@link Rank}
     * get a sane answer for operator-defined ranks. Compared on the registry's own scale, so it
     * holds whatever numbering the operator chose.
     */
    public Rank toLegacyRank(CustomRank custom)
    {
        if (custom == null)
        {
            return Rank.NON_OP;
        }

        return Stream.of(Rank.values())
            .filter(candidate -> !candidate.isConsole())
            .filter(candidate ->
            {
                CustomRank equivalent = getCustomRankForLegacy(candidate);
                return equivalent != null && custom.getLevel() >= equivalent.getLevel();
            })
            .max(Comparator.comparingInt(Rank::getLevel))
            .orElse(Rank.NON_OP);
    }

    public Rank getRank(CommandSender sender)
    {
        if (sender instanceof Player player)
        {
            if (plugin.al.isAdminImpostor(player))
            {
                return Rank.IMPOSTOR;
            }
    
            final Admin entry = plugin.al.getAdmin(player);
            if (entry != null)
            {
                return entry.getRank();
            }
    
            return player.isOp() ? Rank.OP : Rank.NON_OP;
        }

        if (sender instanceof BlockCommandSender || sender instanceof CommandMinecart)
        {
            return Rank.NON_OP;
        }

        RemoteDispatchSession dispatch = RemoteDispatchContext.getActiveSession();
        if (dispatch != null)
        {
            CustomRank identity = resolveDispatchIdentity(dispatch);
            if (identity != null)
            {
                return toLegacyRank(identity);
            }

            String channel = dispatch.getChannel() == RemoteDispatchSession.Channel.DISCORD ? "discord" : "ssh";
            Rank fallback = plugin.csr.getRankForSender(channel);
            if (fallback != null)
            {
                return fallback;
            }

            CustomRank bound = getBoundRank(channel);
            return bound != null ? toLegacyRank(bound) : Rank.NON_OP;
        }

        Admin admin = plugin.al.getEntryByName(sender.getName());
        if (admin != null)
        {
            return admin.getRank();
        }

        Rank rank = plugin.csr.getRankForSender(sender.getName());
        if (rank != null)
        {
            return rank;
        }

        // A host channel may be bound to a custom rank with no legacy equivalent; place it on the
        // ladder by level rather than assuming a tier, which used to hand every such sender
        // SUPER_ADMIN regardless of what it was actually bound to.
        CustomRank bound = getBoundRank(sender.getName());
        return bound != null ? toLegacyRank(bound) : Rank.NON_OP;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoinAutoOp(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        final boolean isAdmin = plugin.al.isAdmin(player);

        // Skip admins and impostors
        if (isAdmin || plugin.al.isAdminImpostor(player))
        {
            return;
        }

        // Verify and ensure OP status with aggressive permission refresh
        // This runs at LOWEST priority to execute before other plugins
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean())
        {
            ensureOp(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();
        //plugin.pl.getData(player);
        final FPlayer fPlayer = plugin.pl.getPlayer(player);

        // Unban admins
        boolean isAdmin = plugin.al.isAdmin(player);
        if (isAdmin)
        {
            // Verify strict IP match
            if (!plugin.al.isIdentityMatched(player))
            {
                Component warningMsg = Component.text("Warning: " + player.getName() + " is an admin, but is using an account not registered to one of their ip-list.")
                        .color(NamedTextColor.RED);
                FUtil.bcastMsg(warningMsg);
                fPlayer.setSuperadminIdVerified(false);
            }
            else
            {
                fPlayer.setSuperadminIdVerified(true);
                plugin.al.updateLastLogin(player);
            }
        }

        updatePlayerTeam(player);

        // Handle impostors
        if (plugin.al.isAdminImpostor(player))
        {
            Component impostorMsg = Component.text(player.getName() + " is ")
                    .color(NamedTextColor.AQUA)
                    .append(Rank.IMPOSTOR.getColoredLoginMessage());
            FUtil.bcastMsg(impostorMsg);
            if (plugin.db != null)
            {
                plugin.db.relayLoginMessage(impostorMsg);
            }

            Component warningMsg = Component.text("Warning: " + player.getName() + " has been flagged as an impostor and has been frozen!")
                    .color(NamedTextColor.RED);
            FUtil.bcastMsg(warningMsg);

            player.getInventory().clear();
            player.setOp(false);
            player.setGameMode(GameMode.SURVIVAL);
            plugin.pl.getPlayer(player).getFreezeData().setFrozen(true);

            Component playerMsg = Component.text("You are marked as an impostor, please verify yourself!")
                    .color(NamedTextColor.RED);
            player.sendMessage(playerMsg);
            return;
        }

        // Set display
        if (isAdmin || FUtil.DEVELOPERS.contains(player.getName()))
        {
            final Displayable display = getDisplay(player);
            Component loginMsg = formatLoginMessage(player);
            FUtil.bcastMsg(loginMsg);
            if (plugin.db != null)
            {
                plugin.db.relayLoginMessage(loginMsg);
            }

            // Skip rank tag when the player has a saved custom tag.
            final String savedTag = plugin.pl.getData(player).getSavedTag();
            if (savedTag == null)
            {
                String tagLegacy = AdventureUtil.componentToLegacySection(display.getColoredTag());
                plugin.pl.getPlayer(player).setTag(tagLegacy);
            }

        }
    }

    public Component formatLoginMessage(Player player)
    {
        final Displayable display = getDisplay(player);
        final boolean isAdmin = plugin.al.isAdmin(player);
        Component loginMsg = Component.text(player.getName() + " is ")
                .color(NamedTextColor.AQUA)
                .append(display.getColoredLoginMessage());

        if (isAdmin)
        {
            Admin admin = plugin.al.getAdmin(player);
            if (admin.hasLoginMessage())
            {
                // Temporary measure to convert old tags to the preferred MiniMessage system and update database
                String loginMessage = admin.getLoginMessage();
                if (loginMessage.contains("%name%") || loginMessage.contains("%rank%") || loginMessage.contains("%coloredrank%"))
                {
                    loginMessage = loginMessage
                            .replace("%name%", "<name>")
                            .replace("%rank%", "<rank>")
                            .replace("%coloredrank%", "<colored_rank>");

                    admin.setLoginMessage(loginMessage);
                    plugin.al.save();
                    plugin.al.updateTables();
                }

                loginMsg = AdventureUtil.addLinks(
                        AdventureUtil.formatWithPlaceholders(
                                loginMessage,
                                Placeholder.unparsed("name", player.getName()),
                                Placeholder.unparsed("rank", admin.getRank().getName()),
                                Placeholder.component("colored_rank", display.getColoredName())
                        ));
            }
        }

        return loginMsg;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        final Player player = event.getPlayer();
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && !plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
        {
            // Re-verify OP after respawn
            ensureOp(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event)
    {
        final Player player = event.getPlayer();
        if (ConfigEntry.AUTO_OP_ENABLED.getBoolean() && !plugin.al.isAdmin(player) && !plugin.al.isAdminImpostor(player))
        {
            // Re-verify OP after world change
            ensureOp(player);
        }
    }
}
